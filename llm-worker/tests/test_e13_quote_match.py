"""E13 B acceptance using invented Unicode text and offline providers only."""

import io
import json
import tempfile
import unicodedata
import unittest
from contextlib import redirect_stdout
from dataclasses import replace
from itertools import product
from pathlib import Path
from unittest.mock import patch

from test_a1_clean_names import ENV, settings
from test_core import block, document, experience, personal, response
from test_o5_lines import line_personal, numbered, position
from test_pull import TOKEN, claim, config

from cv_compass_llm.cli import CLI_ERRORS, parser, run_batch
from cv_compass_llm.config import (
    BENCH_ENV_KEYS,
    ENV_KEYS,
    PullConfigError,
    load_bench_config,
    load_config,
    load_pull_config,
)
from cv_compass_llm.evidence import locate, source_value
from cv_compass_llm.pipeline import extract, fallback_reasons
from cv_compass_llm.providers import FakeProvider
from cv_compass_llm.pull import run_pull
from cv_compass_llm.pull_payload import WORKER_ISSUES, compact_result
from cv_compass_llm.schema import Issue
from cv_compass_llm.segmenter import Block, Span
from cv_compass_llm.validate import supported, validate_experiences, validate_line_experiences

TITLE = 'Kỹ sư mẫu'
COMPANY = 'Xưởng Mây'
PERIOD = '2020 - 2022'


def nfd(text):
    return unicodedata.normalize('NFD', text)


def item(**options):
    return experience(TITLE, COMPANY, start='2020', end='2022', period=PERIOD, **options)


class QuoteMatchTests(unittest.TestCase):
    def assert_evidence(self, evidence, pages):
        self.assertIsNotNone(evidence)
        raw = pages[evidence.page - 1]
        self.assertEqual(raw[evidence.start : evidence.end], evidence.text)
        self.assertLess(evidence.start, evidence.end)

    def assert_rows(self, rows, *pages):
        self.assertTrue(rows)
        for row in rows:
            for field, evidence in row.evidence.items():
                self.assert_evidence(evidence, pages)
                if field in ('title', 'company'):
                    self.assertEqual(
                        ' '.join(getattr(row, field).split()), ' '.join(evidence.text.split())
                    )

    def test_nfd_raw_nfc_model_and_safe_casefold_keep_codepoint_offsets(self):
        for model_title in (TITLE, TITLE.upper()):
            raw = '🧪 prefix\n' + nfd(TITLE + '\n' + COMPANY) + '\n' + PERIOD
            model = item()
            model['title'] = model['quotes']['title'] = model_title
            rows, issues = validate_experiences(response(model), block(raw), quote_match='tolerant')
            self.assert_rows(rows, raw)
            self.assertEqual(rows[0].title, nfd(TITLE))
            self.assertEqual(rows[0].company, nfd(COMPANY))
            self.assertEqual(rows[0].evidence['title'].start, raw.index(nfd(TITLE)))
            self.assertEqual(
                issues,
                (Issue('QUOTE_MATCH_RELAXED', 'title'), Issue('QUOTE_MATCH_RELAXED', 'company')),
            )
            strict_rows, strict_issues = validate_experiences(response(model), block(raw))
            self.assertEqual(strict_rows, ())
            for field in ('title', 'company'):
                self.assertIn(Issue('QUOTE_NOT_FOUND', field), strict_issues)

    def test_source_value_relaxes_even_when_quote_itself_matches_strictly(self):
        raw = nfd(TITLE)
        issues = []
        value, evidence = supported(TITLE, raw, block(raw), 'title', issues, quote_match='tolerant')
        self.assert_evidence(evidence, (raw,))
        self.assertEqual(value, evidence.text)
        self.assertEqual(value, raw)
        self.assertEqual(issues, [Issue('QUOTE_MATCH_RELAXED', 'title')])
        self.assertEqual(source_value(TITLE, raw, quote_match='tolerant'), raw)
        self.assertIsNone(source_value(TITLE, raw))

    def test_each_punctuation_equivalence_and_nbsp(self):
        for raw_mark, model_mark in (
            ('‘', "'"),
            ('’', "'"),
            ('“', '"'),
            ('”', '"'),
            ('–', '-'),
            ('—', '-'),
            ('‐', '-'),
            ('\u00a0', ' '),
        ):
            with self.subTest(mark=ord(raw_mark)):
                raw_value = f'Mẫu{raw_mark}Test'
                model_value = f'Mẫu{model_mark}Test'
                raw = '🧪 ' + raw_value
                issues = []
                value, evidence = supported(
                    model_value,
                    model_value,
                    block(raw),
                    'company',
                    issues,
                    quote_match='tolerant',
                )
                self.assert_evidence(evidence, (raw,))
                self.assertEqual(value, evidence.text)
                self.assertEqual(value, raw_value)
                strict_issues = []
                strict = supported(model_value, model_value, block(raw), 'company', strict_issues)
                if raw_mark == '\u00a0':
                    self.assertEqual(issues, [])
                    self.assertEqual(strict, (value, evidence))
                else:
                    self.assertEqual(issues, [Issue('QUOTE_MATCH_RELAXED', 'company')])
                    self.assertEqual(strict, (None, None))
                    self.assertEqual(strict_issues, [Issue('QUOTE_NOT_FOUND', 'company')])

    def test_strict_match_wins_over_earlier_relaxed_match_and_emits_no_issue(self):
        raw = nfd(TITLE) + '\n' + TITLE
        issues = []
        value, evidence = supported(
            TITLE, TITLE, block(raw), 'title', issues, quote_match='tolerant'
        )
        self.assert_evidence(evidence, (raw,))
        self.assertEqual(value, evidence.text)
        self.assertEqual(evidence.start, raw.rindex(TITLE))
        self.assertEqual(issues, [])

    def test_nfc_punctuation_and_whitespace_combination_keeps_raw_value(self):
        model = 'Kỹ sư "Mây" - QA'
        raw_value = nfd('Kỹ\t sư\u00a0“Mây” — QA')
        raw = '🧪\n' + raw_value
        issues = []
        value, evidence = supported(
            model, model, block(raw), 'title', issues, quote_match='tolerant'
        )
        self.assert_evidence(evidence, (raw,))
        self.assertEqual(value, evidence.text)
        self.assertEqual(value, raw_value)
        self.assertEqual(issues, [Issue('QUOTE_MATCH_RELAXED', 'title')])

    def test_period_b1_b2_keeps_raw_and_does_not_use_b3(self):
        for raw_period, quote in (
            (nfd('từ 2020 - 2022'), 'từ 2020 - 2022'),
            ('2020 — 2022', PERIOD),
        ):
            model = item()
            model['quotes']['period'] = quote
            raw = TITLE + '\n' + COMPANY + '\n' + raw_period
            rows, issues = validate_experiences(response(model), block(raw), quote_match='tolerant')
            self.assert_rows(rows, raw)
            self.assertEqual((rows[0].start, rows[0].end), ('2020', '2022'))
            self.assertEqual(rows[0].evidence['period'].text, raw_period)
            self.assertEqual(issues, (Issue('QUOTE_MATCH_RELAXED', 'period'),))
            strict, strict_issues = validate_experiences(response(model), block(raw))
            self.assert_rows(strict, raw)
            self.assertNotIn('period', strict[0].evidence)
            self.assertIn(Issue('QUOTE_NOT_FOUND', 'period'), strict_issues)
        model['quotes']['period'] = 'Missing period quote'
        rows, issues = validate_experiences(response(model), block(raw), quote_match='tolerant')
        self.assert_rows(rows, raw)
        self.assertNotIn('period', rows[0].evidence)
        self.assertIn(Issue('QUOTE_NOT_FOUND', 'period'), issues)

    def test_b3_unique_or_value_outside_quote_and_repeated_near_period(self):
        for quote in ('Invented missing quote', COMPANY):
            for repeated in (False, True):
                model = item()
                model['quotes']['title'] = quote
                earlier = nfd(TITLE) + '\n' + 'x' * 60 + '\n' if repeated else ''
                raw = earlier + nfd(TITLE) + '\n' + COMPANY + '\n' + PERIOD
                rows, issues = validate_experiences(
                    response(model), block(raw), quote_match='tolerant'
                )
                self.assert_rows(rows, raw)
                self.assertEqual(rows[0].evidence['title'].start, raw.rindex(nfd(TITLE)))
                self.assertEqual(issues, (Issue('QUOTE_MATCH_RELAXED', 'title'),))
                strict, strict_issues = validate_experiences(response(model), block(raw))
                self.assert_rows(strict, raw)
                self.assertIsNone(strict[0].title)
                self.assertIn(Issue('QUOTE_NOT_FOUND', 'title'), strict_issues)

    def test_b3_uses_period_quote_even_if_period_validation_fails(self):
        raw = TITLE + '\n' + 'x' * 60 + '\n' + TITLE + '\n' + COMPANY + '\n' + PERIOD
        model = item()
        model['start'] = '2019'
        model['quotes']['title'] = 'Missing title quote'
        rows, issues = validate_experiences(response(model), block(raw), quote_match='tolerant')
        self.assert_rows(rows, raw)
        self.assertEqual(rows[0].evidence['title'].start, raw.rindex(TITLE))
        self.assertNotIn('period', rows[0].evidence)
        self.assertIn(Issue('QUOTE_NOT_FOUND', 'start'), issues)

    def test_b3_nearest_occurrence_may_use_strict_or_nfd_matching(self):
        for earlier, later in ((TITLE, nfd(TITLE)), (nfd(TITLE), TITLE)):
            raw = earlier + '\n' + 'x' * 60 + '\n' + later + '\n' + COMPANY + '\n' + PERIOD
            model = item()
            model['quotes']['title'] = 'Missing title quote'
            rows, issues = validate_experiences(response(model), block(raw), quote_match='tolerant')
            self.assert_rows(rows, raw)
            self.assertEqual(rows[0].evidence['title'].start, raw.rindex(later))
            self.assertEqual(issues, (Issue('QUOTE_MATCH_RELAXED', 'title'),))

    def test_b3_prefers_period_page_and_without_period_uses_first(self):
        pages = (TITLE + '\n', 'x' * 100 + '\n' + TITLE + '\n' + COMPANY + '\n' + PERIOD)
        source = Block(tuple(Span(page, 0, len(raw), raw) for page, raw in enumerate(pages, 1)))
        for period_quote, expected_page in ((PERIOD, 2), (None, 1)):
            model = item()
            model['quotes']['title'] = 'Missing title quote'
            model['quotes']['period'] = period_quote
            rows, issues = validate_experiences(response(model), source, quote_match='tolerant')
            self.assert_rows(rows, *pages)
            self.assertEqual(rows[0].evidence['title'].page, expected_page)
            self.assertIn(Issue('QUOTE_MATCH_RELAXED', 'title'), issues)

    def test_b3_length_and_word_limits_and_absent_values(self):
        for field, value, accepted in (
            ('title', 'QA', False),
            ('company', 'AB', False),
            ('company', ' AB ', False),
            ('title', 'Engineer', False),
            ('company', 'Cloud', True),
            ('title', 'QA Lead', True),
            ('company', 'Missing Company', False),
        ):
            issues = []
            raw = 'QA AB Engineer Cloud QA Lead'
            original, evidence = supported(
                value, 'Missing quote', block(raw), field, issues, quote_match='tolerant'
            )
            if accepted:
                self.assert_evidence(evidence, (raw,))
                self.assertEqual(original, evidence.text)
                self.assertEqual(issues, [Issue('QUOTE_MATCH_RELAXED', field)])
            else:
                self.assertEqual((original, evidence), (None, None))
                self.assertEqual(issues, [Issue('QUOTE_NOT_FOUND', field)])

    def test_b3_counts_nfc_characters_and_words_for_nfd_values(self):
        for field, value in (('company', nfd('Bà')), ('title', nfd('Trưởng'))):
            for style in ('strict', 'tolerant'):
                with self.subTest(field=field, style=style):
                    issues = []
                    self.assertEqual(
                        supported(
                            value, 'Missing quote', block(value), field, issues, quote_match=style
                        ),
                        (None, None),
                    )
                    self.assertEqual(issues, [Issue('QUOTE_NOT_FOUND', field)])

    def test_b3_requires_word_boundaries_and_skips_embedded_occurrences(self):
        for raw in ('Vinamilk Group', 'PreVina', nfd('é') + 'Vina', 'Vina\u0301'):
            issues = []
            self.assertEqual(
                supported(
                    'Vina',
                    'Missing quote',
                    block(raw),
                    'company',
                    issues,
                    quote_match='tolerant',
                ),
                (None, None),
            )
            self.assertEqual(issues, [Issue('QUOTE_NOT_FOUND', 'company')])
        raw = 'Vinamilk Group; PreVina; (Vina)'
        issues = []
        value, evidence = supported(
            'Vina', 'Missing quote', block(raw), 'company', issues, quote_match='tolerant'
        )
        self.assert_evidence(evidence, (raw,))
        self.assertEqual(evidence.start, raw.rindex('Vina'))
        self.assertEqual(value, evidence.text)
        self.assertEqual(issues, [Issue('QUOTE_MATCH_RELAXED', 'company')])

    def test_b3_boundaries_use_raw_neighbors_outside_the_block(self):
        raw = 'PreVina Group'
        source = Block((Span(1, 3, 7, 'Vina', raw),))
        issues = []
        self.assertEqual(
            supported('Vina', 'Missing quote', source, 'company', issues, quote_match='tolerant'),
            (None, None),
        )
        self.assertEqual(issues, [Issue('QUOTE_NOT_FOUND', 'company')])

    def test_b3_boundaries_do_not_change_supported_strict_quotes(self):
        raw = 'Vinamilk Group'
        for style in ('strict', 'tolerant'):
            issues = []
            value, evidence = supported(
                'Vina', 'Vina', block(raw), 'company', issues, quote_match=style
            )
            self.assert_evidence(evidence, (raw,))
            self.assertEqual(value, evidence.text)
            self.assertEqual(value, 'Vina')
            self.assertEqual(issues, [])

    def test_quote_matching_skips_first_partial_cluster_for_later_whole_match(self):
        raw = 'Ax\u0338 Ax'
        issues = []
        value, evidence = supported('Ax', 'Ax', block(raw), 'title', issues, quote_match='tolerant')
        self.assert_evidence(evidence, (raw,))
        self.assertEqual(evidence.start, raw.rindex('Ax'))
        self.assertEqual(value, evidence.text)
        self.assertEqual(issues, [Issue('QUOTE_MATCH_RELAXED', 'title')])
        strict_value, strict_evidence = supported('Ax', 'Ax', block(raw), 'title', [])
        self.assert_evidence(strict_evidence, (raw,))
        self.assertEqual(strict_value, strict_evidence.text)
        self.assertEqual(strict_evidence.start, 0)

    def test_nfd_present_period_validates_without_changing_raw_evidence(self):
        period = '03/2019 – hiện tại'
        raw_period = nfd(period)
        raw = TITLE + '\n' + COMPANY + '\n' + raw_period
        for dates, quote in product(('v1', 'v2', 'v3'), (period, raw_period)):
            with self.subTest(dates=dates, raw_quote=quote == raw_period):
                model = experience(TITLE, COMPANY, '2019-03', None, True, quote)
                line = position(ongoing=True, period_text=quote)
                line['title']['value'], line['company']['value'] = TITLE, COMPANY
                results = (
                    validate_experiences(
                        response(model), block(raw), dates=dates, quote_match='tolerant'
                    ),
                    validate_line_experiences(
                        response(line), numbered(raw), dates=dates, quote_match='tolerant'
                    ),
                )
                for rows, issues in results:
                    self.assert_rows(rows, raw)
                    self.assertEqual(
                        (rows[0].start, rows[0].end, rows[0].ongoing), ('2019-03', None, True)
                    )
                    self.assertEqual(rows[0].evidence['period'].text, raw_period)
                    self.assertTrue(all(issue.code == 'QUOTE_MATCH_RELAXED' for issue in issues))
                strict_results = (
                    validate_experiences(response(model), block(raw), dates=dates),
                    validate_line_experiences(response(line), numbered(raw), dates=dates),
                )
                for rows, _ in strict_results:
                    self.assert_rows(rows, raw)
                    self.assertFalse(rows[0].ongoing)

    def test_lines_nfd_present_on_continuation_line(self):
        period = '03/2019 hiện tại'
        raw = TITLE + '\n' + COMPANY + '\n03/2019\n' + nfd('hiện tại')
        model = position(ongoing=True, period_text=period)
        model['title']['value'], model['company']['value'] = TITLE, COMPANY
        for dates in ('v1', 'v2', 'v3'):
            rows, issues = validate_line_experiences(
                response(model), numbered(raw), dates=dates, quote_match='tolerant'
            )
            self.assert_rows(rows, raw)
            self.assertTrue(rows[0].ongoing)
            self.assertEqual(rows[0].start, '2019-03')
            self.assertEqual(issues, (Issue('QUOTE_MATCH_RELAXED', 'period'),))

    def test_tolerant_v3_open_period_uses_nfc_validation_view(self):
        period = 'Tháng 3/ 2019 –'
        raw = TITLE + '\n' + COMPANY + '\n' + nfd(period)
        model = experience(TITLE, COMPANY, '2019-03', None, True, period)
        rows, issues = validate_experiences(
            response(model), block(raw), dates='v3', quote_match='tolerant'
        )
        self.assert_rows(rows, raw)
        self.assertEqual((rows[0].start, rows[0].end, rows[0].ongoing), ('2019-03', None, True))
        self.assertEqual(issues, (Issue('QUOTE_MATCH_RELAXED', 'period'),))

    def test_never_splits_clusters_or_joins_gaps_or_expands_casefold(self):
        for raw, quote in (
            ('Ax\u0338', 'Ax'),
            (nfd('Kỹ sư mẫu'), 'Kỹ sư mâ'),
            ('Straße – Werk', 'STRASSE - WERK'),
            ('İş — Yer', 'iş - yer'),
            ('A — B', 'A-B'),
        ):
            self.assertIsNone(locate(quote, block(raw), quote_match='tolerant'))
        for source in (
            Block((Span(1, 0, 3, 'Kỹ '), Span(1, 8, 14, 'sư mẫu'))),
            Block((Span(1, 0, 3, 'Kỹ '), Span(2, 0, 6, 'sư mẫu'))),
        ):
            self.assertIsNone(locate(TITLE, source, quote_match='tolerant'))
        raw = nfd(TITLE)
        source = Block((Span(1, 0, 2, raw[:2]), Span(1, 2, len(raw), raw[2:])))
        evidence = locate(TITLE, source, quote_match='tolerant')
        self.assert_evidence(evidence, (raw,))
        self.assertEqual(evidence.text, raw)

    def test_lines_nfd_names_period_punctuation_and_no_b3(self):
        raw = nfd(TITLE + '\n' + COMPANY) + '\n2020 — 2022'
        model = position(period_text=PERIOD)
        model['title']['value'], model['company']['value'] = TITLE, COMPANY
        rows, issues = validate_line_experiences(
            response(model), numbered(raw), quote_match='tolerant'
        )
        self.assert_rows(rows, raw)
        self.assertEqual(
            set(issues),
            {Issue('QUOTE_MATCH_RELAXED', field) for field in ('title', 'company', 'period')},
        )
        strict, strict_issues = validate_line_experiences(response(model), numbered(raw))
        self.assertEqual(strict, ())
        self.assertIn(Issue('QUOTE_NOT_FOUND', 'title'), strict_issues)
        model['title']['line'] = 2
        rows, issues = validate_line_experiences(
            response(model), numbered(raw), quote_match='tolerant'
        )
        self.assert_rows(rows, raw)
        self.assertIsNone(rows[0].title)
        self.assertIn(Issue('QUOTE_NOT_FOUND', 'title'), issues)

    def test_name_trim_with_nfd_still_returns_literal_substrings(self):
        title, company = 'Position: ' + TITLE, 'Company: ' + COMPANY
        raw = '🧪\n' + nfd(title + '\n' + company) + '\n' + PERIOD
        model = experience(title, company, '2020', '2022', period=PERIOD)
        line = position(title=2, company=3, period=4)
        line['title']['value'], line['company']['value'] = title, company
        results = (
            validate_experiences(
                response(model), block(raw), quote_match='tolerant', name_trim='on'
            ),
            validate_line_experiences(
                response(line), numbered(raw), quote_match='tolerant', name_trim='on'
            ),
        )
        for rows, issues in results:
            self.assert_rows(rows, raw)
            self.assertEqual((rows[0].title, rows[0].company), (nfd(TITLE), nfd(COMPANY)))
            self.assertEqual(len(issues), 2)

    def test_all_pipeline_routes_keep_personal_unchanged_and_issues_local(self):
        raw = 'Synthetic Person\nExperience\n' + nfd(TITLE + '\n' + COMPANY) + '\n' + PERIOD
        for mode, evidence_mode in product(('blocks', 'whole'), ('quote', 'lines')):
            with self.subTest(mode=mode, evidence=evidence_mode):
                line = position(title=3, company=4, period=5)
                line['title']['value'], line['company']['value'] = TITLE, COMPANY
                names = (
                    {'line': 1, 'value': 'Synthetic Person'}
                    if evidence_mode == 'lines'
                    else {'quote': 'Synthetic Person', 'value': 'Synthetic Person'}
                )
                header = (
                    line_personal(fullName=names)
                    if evidence_mode == 'lines'
                    else personal(fullName=names)
                )
                body = {'experiences': [line if evidence_mode == 'lines' else item()]}
                whole = body if evidence_mode == 'lines' else dict(header, **body)
                replies = [header, body] if mode == 'blocks' else [whole, header]
                tolerant_provider = FakeProvider(replies)
                result = extract(
                    document(raw),
                    tolerant_provider,
                    mode=mode,
                    config=settings(quote_match='tolerant', evidence=evidence_mode),
                )
                self.assert_rows(result.experiences, raw)
                for field in result.fields:
                    self.assert_evidence(field.evidence, (raw,))
                self.assertEqual(result.issues.count(Issue('QUOTE_MATCH_RELAXED', 'title')), 1)
                self.assertEqual(result.issues.count(Issue('QUOTE_MATCH_RELAXED', 'company')), 1)
                strict_provider = FakeProvider(replies)
                strict = extract(
                    document(raw),
                    strict_provider,
                    mode=mode,
                    config=settings(evidence=evidence_mode),
                )
                self.assertEqual(tolerant_provider.prompts, strict_provider.prompts)
                explicit = extract(
                    document(raw),
                    FakeProvider(replies),
                    mode=mode,
                    config=settings(quote_match='strict', evidence=evidence_mode),
                )
                self.assertEqual(strict, explicit)
                self.assertEqual(result.fields, strict.fields)
                without_relaxed = replace(
                    result,
                    issues=tuple(
                        issue for issue in result.issues if issue.code != 'QUOTE_MATCH_RELAXED'
                    ),
                )
                self.assertEqual(
                    fallback_reasons(result, document(raw)),
                    fallback_reasons(without_relaxed, document(raw)),
                )
                self.assertNotIn('QUOTE_MATCH_RELAXED', WORKER_ISSUES)
                self.assertEqual(
                    compact_result(result, claim(), {}),
                    compact_result(without_relaxed, claim(), {}),
                )
                payload = json.loads(compact_result(result, claim(), {}))
                self.assertNotIn('QUOTE_MATCH_RELAXED', json.dumps(payload))
                self.assertEqual(len(payload['experiences']), 1)
                for field in ('title', 'company'):
                    retained = payload['experiences'][0]
                    self.assertEqual(retained[field], getattr(result.experiences[0], field))
                    self.assertEqual(retained[field], retained['evidence'][field]['text'])


class QuoteMatchConfigTests(unittest.TestCase):
    def test_default_values_precedence_bench_and_pull_diagnostics(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'synthetic.env'
            path.write_text('CVC_LLM_QUOTE_MATCH=tolerant\n', encoding='utf-8')
            self.assertEqual(ENV_KEYS['quote_match'], 'CVC_LLM_QUOTE_MATCH')
            self.assertIn(ENV_KEYS['quote_match'], BENCH_ENV_KEYS)
            self.assertEqual(settings().quote_match, 'strict')
            self.assertEqual(load_bench_config(path, ENV).quote_match, 'tolerant')
            for mode in ('strict', 'tolerant'):
                loaded = load_config(
                    {}, ENV | {'CVC_LLM_QUOTE_MATCH': mode}, path, env_file_explicit=True
                )
                self.assertEqual(loaded.quote_match, mode)
                env = ENV | {'CVC_LLM_QUOTE_MATCH': 'strict' if mode == 'tolerant' else 'tolerant'}
                loaded = load_config({'quote_match': mode}, env, path, env_file_explicit=True)
                self.assertEqual(loaded.quote_match, mode)
                pull_env = ENV | {
                    'CVC_LLM_BACKEND_URL': 'http://localhost',
                    'CVC_LLM_WORKER_TOKEN': TOKEN,
                    'CVC_LLM_QUOTE_MATCH': mode,
                }
                loaded_pull = load_pull_config(pull_env, path, env_file_explicit=True)
                self.assertEqual(loaded_pull.llm.quote_match, mode)
            for invalid in ('other', 'TOLERANT'):
                with self.assertRaisesRegex(ValueError, '^INVALID_QUOTE_MATCH$'):
                    load_config({'quote_match': invalid}, ENV, path, env_file_explicit=True)
                env = ENV | {
                    'CVC_LLM_BACKEND_URL': 'http://localhost',
                    'CVC_LLM_WORKER_TOKEN': TOKEN,
                    'CVC_LLM_QUOTE_MATCH': invalid,
                }
                with self.assertRaisesRegex(
                    PullConfigError, '^CVC_LLM_QUOTE_MATCH INVALID_QUOTE_MATCH$'
                ):
                    load_pull_config(env, path, env_file_explicit=True)
            self.assertIn('INVALID_QUOTE_MATCH', CLI_ERRORS)
            for command in (
                ['run', '--input', 'synthetic-input', '--output', 'synthetic-output'],
                ['pull'],
            ):
                args = parser().parse_args(command + ['--quote-match', 'tolerant'])
                self.assertEqual(args.quote_match, 'tolerant')

    def test_run_summary_counts_relaxed_and_records_flag(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, repo = root / 'input', root / 'repo'
            source.mkdir()
            repo.mkdir()
            raw = 'Synthetic Person\nExperience\n' + nfd(TITLE + '\n' + COMPANY) + '\n' + PERIOD
            (source / 'synthetic.json').write_text(
                json.dumps({'pages': [{'page_number': 1, 'status': 'ok', 'raw_text': raw}]}),
                encoding='utf-8',
            )
            report = run_batch(
                source,
                root / 'output',
                FakeProvider([personal(), {'experiences': [item()]}]),
                config=settings(quote_match='tolerant'),
                repo_root=repo,
            )
            self.assertEqual(report['quote_match'], 'tolerant')
            self.assertEqual(report['issues']['QUOTE_MATCH_RELAXED'], 2)
            saved = json.loads((root / 'output' / 'run-summary.json').read_text(encoding='utf-8'))
            self.assertEqual(saved['quote_match'], 'tolerant')

    def test_worker_start_records_flag_without_network(self):
        pull = config('http://localhost')
        pull = replace(pull, llm=replace(pull.llm, quote_match='tolerant'))
        with (
            patch('cv_compass_llm.pull.BackendClient') as client,
            patch('cv_compass_llm.pull.event') as event,
            redirect_stdout(io.StringIO()),
        ):
            client.return_value.request.return_value = (204, None)
            self.assertEqual(run_pull(pull, FakeProvider(personal()), once=True), 0)
        start = next(call for call in event.call_args_list if call.args == ('worker_start',))
        self.assertEqual(start.kwargs['quote_match'], 'tolerant')


if __name__ == '__main__':
    unittest.main()
