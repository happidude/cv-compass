"""O5 line evidence acceptance using synthetic, offline fixtures only."""

import json
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path

from test_core import personal
from test_pull import config

from cv_compass_llm.config import load_bench_config, load_config, load_pull_config
from cv_compass_llm.lines import block_lines, number_lines
from cv_compass_llm.ocr_input import Document, Page
from cv_compass_llm.pipeline import extract
from cv_compass_llm.prompts import BEGIN, END, build_lines_prompt, prompt_version
from cv_compass_llm.providers import FakeProvider, ProviderError
from cv_compass_llm.pull_payload import PERSONAL, compact_result
from cv_compass_llm.schema import LINES_EXPERIENCE_SCHEMA, LINES_PERSONAL_SCHEMA, Issue
from cv_compass_llm.segmenter import Block, Span, build_header, split_lines
from cv_compass_llm.validate import (
    parse_response,
    validate_line_experiences,
    validate_line_personal,
)


def numbered(*texts):
    pages = tuple(Page(i, 'ok', text) for i, text in enumerate(texts, 1))
    return number_lines(split_lines(pages))[1]


def position(title=1, company=2, period=3, ongoing=False, *, period_text='2020 - 2022'):
    return {
        'title': {'line': title, 'value': 'engineer'},
        'company': {'line': company, 'value': 'synthetic works'},
        'period': {'line': period, 'text': period_text if period is not None else None},
        'ongoing': ongoing,
    }


def line_personal(**items):
    return {
        'personal': {
            'fullName': {'line': None, 'value': None},
            'location': {'line': None, 'endLine': None, 'value': None},
            'gender': {'line': None, 'value': None},
            'dateOfBirth': {'line': None, 'value': None},
            'summary': [],
            **items,
        }
    }


def settings(**options):
    options.setdefault('evidence', 'lines')
    return replace(config('http://localhost').llm, **options)


class NumberTests(unittest.TestCase):
    def test_global_numbers_and_literal_offsets(self):
        pages = (Page(1, 'ok', '  Engineer\r\n\r\n🧪 Works\n'), Page(2, 'ok', 'Second'))
        text, table = number_lines(split_lines(pages))
        self.assertEqual(
            text, '=== page 1 ===\n[L1] Engineer\n[L2] 🧪 Works\n=== page 2 ===\n[L3] Second'
        )
        self.assertEqual(table[1].text, '  Engineer\r\n')
        for span in table.values():
            self.assertEqual(span.raw_text[span.start : span.end], span.text)
        block = Block((table[2], table[3]))
        rendered, selected = block_lines(block, table)
        self.assertIn('[L2]', rendered)
        self.assertNotIn('[L1]', rendered)
        self.assertEqual(list(selected), [2, 3])

    def test_header_never_includes_partial_or_outside_lines(self):
        pages = (Page(1, 'ok', 'Name\n' + 'x' * 8100), Page(2, 'ok', 'Outside'))
        _, table = number_lines(split_lines(pages))
        rendered, selected = block_lines(build_header(pages), table)
        self.assertEqual(list(selected), [1])
        self.assertNotIn('Outside', rendered)

    def test_source_markers_are_escaped_only_in_rendered_content(self):
        raw = 'Engineer [L999] === page 42 ===\nSynthetic Works'
        text, table = number_lines(split_lines((Page(1, 'ok', raw),)))
        expected = '=== page 1 ===\n[L1] Engineer [ L999] = = = page 42 ===\n[L2] Synthetic Works'
        self.assertEqual(text, expected)
        self.assertEqual(block_lines(Block(tuple(table.values())), table)[0], expected)
        item = position(period=None)
        item['title']['value'] = 'Engineer [L999] === page 42 ==='
        rows, issues = validate_line_experiences(json.dumps({'experiences': [item]}), table)
        self.assertFalse(issues)
        self.assertEqual(rows[0].title, raw.splitlines()[0])
        evidence = rows[0].evidence['title']
        self.assertEqual(raw[evidence.start : evidence.end], evidence.text)
        self.assertEqual(table[1].text, raw.splitlines(keepends=True)[0])


class ValidationTests(unittest.TestCase):
    def experience(self, table, item=None):
        return validate_line_experiences(json.dumps({'experiences': [item or position()]}), table)

    def test_title_company_source_case_spaces_and_exact_lines(self):
        table = numbered('Engineer\nSynthetic   Works\n2020 - 2022\nOther\nWrong')
        rows, issues = self.experience(table)
        self.assertFalse(issues)
        self.assertEqual((rows[0].title, rows[0].company), ('Engineer', 'Synthetic   Works'))
        for title, company in ((2, 1), (1, 3)):
            rows, issues = self.experience(table, position(title, company))
            self.assertIn(Issue('QUOTE_NOT_FOUND', 'company'), issues)
            if title == 2:
                self.assertFalse(rows)
                self.assertIn(Issue('QUOTE_NOT_FOUND', 'title'), issues)
            else:
                self.assertEqual((rows[0].title, rows[0].company), ('Engineer', None))
        for line in (5, 999, 0):
            rows, issues = self.experience(table, position(line, line))
            self.assertFalse(rows)
            self.assertIn(Issue('QUOTE_NOT_FOUND', 'title'), issues)
            self.assertIn(Issue('EXPERIENCE_WITHOUT_ROLE'), issues)

    def test_period_source_drives_dates_and_ongoing(self):
        cases = (
            ('2020 - 2022', '2020', '2022', False),
            ('August 2017 – November\n2018', '2017-08', '2018-11', False),
            ('August 2017 –\nNovember 2018', '2017-08', '2018-11', False),
            ('2020 – Present', '2020', None, True),
            ('2020 –\nPresent', '2020', None, True),
            ('No dates here', None, None, False),
        )
        for source, start, end, ongoing in cases:
            with self.subTest(source=source):
                table = numbered('Engineer\nSynthetic Works\n' + source)
                rows, _ = self.experience(table, position(period_text=source))
                row = rows[0]
                self.assertEqual((row.start, row.end, row.ongoing), (start, end, ongoing))
                if start:
                    evidence = row.evidence['period']
                    self.assertEqual(evidence.text, source)
                    self.assertEqual(table[3].raw_text[evidence.start : evidence.end], source)

    def test_period_order_invalid_and_noncontiguous_spans(self):
        for source, code in (('2022 - 2020', 'PERIOD_ORDER'), ('2020 - 13/2022', 'INVALID_DATE')):
            rows, issues = self.experience(
                numbered('Engineer\nSynthetic Works\n' + source), position(period_text=source)
            )
            self.assertIsNone(rows[0].start)
            self.assertIn(Issue(code, 'period'), issues)
        table = numbered('Engineer\nSynthetic Works\n2020 -\n2022')
        table[4] = Span(1, table[4].start + 1, table[4].end + 1, table[4].text)
        rows, issues = self.experience(table, position(period_text='2020 -\n2022'))
        self.assertEqual((rows[0].start, rows[0].end, rows[0].ongoing), (None, None, False))
        self.assertNotIn('period', rows[0].evidence)
        self.assertIn(Issue('QUOTE_NOT_FOUND', 'period'), issues)
        rows, issues = self.experience(table, position(period_text='2020'))
        self.assertFalse(issues)
        self.assertEqual((rows[0].start, rows[0].end, rows[0].ongoing), ('2020', None, False))
        self.assertEqual(rows[0].evidence['period'].text, '2020')

    def test_period_does_not_consume_dates_in_following_prose_or_after_blank_line(self):
        cases = (
            ('Engineer | 01/2020\nBuilt X in 2021', '2020-01', '01/2020'),
            ('Engineer | 2020-01\nBuilt X in 2021', '2020-01', '2020-01'),
            ('August 2017 –\n\nNovember 2018', '2017-08', 'August 2017'),
            ('August 2017 – November\n\n2018', '2017-08', 'August 2017'),
        )
        for source, start, quote in cases:
            with self.subTest(source=source):
                rows, issues = self.experience(
                    numbered('Engineer\nSynthetic Works\n' + source), position(period_text=quote)
                )
                self.assertFalse(issues)
                self.assertEqual(
                    (rows[0].start, rows[0].end, rows[0].ongoing), (start, None, False)
                )
                self.assertEqual(rows[0].evidence['period'].text, quote)

    def test_period_continuation_signals_on_either_line(self):
        for connector in ('-', '–', '—', 'to', 'đến', '~'):
            with self.subTest(connector=connector):
                source = f'2020 {connector}\nEnded in 2022'
                rows, issues = self.experience(
                    numbered('Engineer\nSynthetic Works\n' + source), position(period_text=source)
                )
                self.assertFalse(issues)
                self.assertEqual((rows[0].start, rows[0].end), ('2020', '2022'))
        for following, end, ongoing in (
            ('2022', '2022', False),
            ('November 2022', '2022-11', False),
            ('Present', None, True),
            ('- 2022', '2022', False),
            ('to 2022', '2022', False),
        ):
            with self.subTest(following=following):
                source = '2020\n' + following
                rows, issues = self.experience(
                    numbered('Engineer\nSynthetic Works\n' + source), position(period_text=source)
                )
                self.assertFalse(issues)
                self.assertEqual(
                    (rows[0].start, rows[0].end, rows[0].ongoing), ('2020', end, ongoing)
                )

    def test_exact_line_matching_never_crosses_pages_for_experience_or_personal(self):
        table = numbered('Engineer\nSynthetic Works', 'Wrong line')
        rows, issues = self.experience(table, position(title=3, company=3, period=None))
        self.assertFalse(rows)
        self.assertIn(Issue('QUOTE_NOT_FOUND', 'company'), issues)
        table = numbered('Wrong line', 'Engineer\nSynthetic Works')
        rows, issues = self.experience(table, position(title=1, company=1, period=None))
        self.assertFalse(rows)
        self.assertIn(Issue('QUOTE_NOT_FOUND', 'title'), issues)
        for texts, line in (
            (('Synthetic Person', 'Wrong line'), 2),
            (('Wrong line', 'Synthetic Person'), 1),
        ):
            for field in ('fullName', 'location'):
                with self.subTest(texts=texts, field=field):
                    fields, issues = validate_line_personal(
                        json.dumps(
                            line_personal(
                                **{
                                    field: {
                                        'line': line,
                                        'value': 'Synthetic Person',
                                        **({'endLine': None} if field == 'location' else {}),
                                    }
                                }
                            )
                        ),
                        numbered(*texts),
                    )
                    self.assertFalse(fields)
                    self.assertIn(Issue('QUOTE_NOT_FOUND', field), issues)

    def test_period_null_invalid_ids_and_marker_without_date(self):
        table = numbered('Engineer\nSynthetic Works\nPresent')
        for period in (0, 999, 3):
            rows, issues = self.experience(table, position(period=period, period_text='Present'))
            self.assertEqual((rows[0].start, rows[0].end, rows[0].ongoing), (None, None, False))
            self.assertIn(Issue('QUOTE_NOT_FOUND', 'period'), issues)
        rows, issues = self.experience(table, position(period=None))
        self.assertFalse(issues)
        self.assertNotIn('period', rows[0].evidence)
        table = numbered('Engineer\nSynthetic Works\n2020 - 2022')
        rows, _ = self.experience(table, position(ongoing=True))
        self.assertEqual((rows[0].start, rows[0].end, rows[0].ongoing), ('2020', '2022', False))

    def test_personal_exact_line_preserves_nfd_and_rejects_wrong_lines(self):
        table = numbered('Synthetic Nu\u031b\u0303\nCity\nUnrelated\nOther')
        for line in (1,):
            fields, issues = validate_line_personal(
                json.dumps(line_personal(fullName={'line': line, 'value': 'Synthetic Nữ'})),
                table,
            )
            self.assertFalse(issues)
            self.assertEqual(fields[0].value, table[1].text.rstrip('\n'))
        for line in (2, 4, 0, 999):
            fields, issues = validate_line_personal(
                json.dumps(line_personal(fullName={'line': line, 'value': 'Synthetic Nữ'})),
                table,
            )
            self.assertFalse(fields)
            self.assertIn(Issue('QUOTE_NOT_FOUND', 'fullName'), issues)

    def test_summary_joins_contiguous_lines_and_splits_source_gaps(self):
        table = numbered('First line\r\nSecond line\r\n\r\nThird line')
        fields, issues = validate_line_personal(
            json.dumps(line_personal(summary=[{'startLine': 1, 'endLine': 3}])), table
        )
        self.assertFalse(issues)
        self.assertEqual(fields[0].value, 'First line\r\nSecond line\nThird line')
        self.assertEqual(len(fields[0].additional_evidence), 1)

    def test_invalid_birth_date_and_outside_line_are_rejected(self):
        for line, code in ((1, 'INVALID_DATE'), (99, 'QUOTE_NOT_FOUND')):
            fields, issues = validate_line_personal(
                json.dumps(line_personal(dateOfBirth={'line': line, 'value': '31/02/2000'})),
                numbered('DOB: 31/02/2000'),
            )
            self.assertFalse(fields)
            self.assertIn(Issue(code, 'dateOfBirth'), issues)

    def test_multiple_birth_dates_on_one_line_are_rejected_even_if_identical(self):
        for source in ('DOB: 2000-04-03 / 2001-05-04', 'DOB: 2000-04-03 or 2000-04-03'):
            fields, issues = validate_line_personal(
                json.dumps(line_personal(dateOfBirth={'line': 1, 'value': source})),
                numbered(source),
            )
            self.assertFalse(fields)
            self.assertEqual(issues, (Issue('QUOTE_NOT_FOUND', 'dateOfBirth'),))

    def test_personal_matcher_birth_gender_and_cross_page_summary(self):
        table = numbered(
            'Synthetic O’Name\nCity – North\nGender: Other\nDOB: 03/04/2000\nFirst paragraph',
            'Continued paragraph',
        )
        response = line_personal(
            fullName={'line': 1, 'value': "synthetic o'name"},
            location={'line': 2, 'endLine': None, 'value': 'city - north'},
            gender={'line': 3, 'value': 'OTHER'},
            dateOfBirth={'line': 4, 'value': '03/04/2000'},
            summary=[{'startLine': 5, 'endLine': 6}],
        )
        fields, issues = validate_line_personal(json.dumps(response), table)
        values = {field.key: field for field in fields}
        self.assertEqual(values['fullName'].value, 'Synthetic O’Name')
        self.assertEqual(values['location'].value, 'City – North')
        self.assertEqual(values['gender'].value, 'OTHER')
        self.assertEqual(values['dateOfBirth'].value, '2000-04-03')
        self.assertIn(Issue('DOB_DAY_FIRST_ASSUMED', 'dateOfBirth'), issues)
        self.assertEqual(values['summary'].value, 'First paragraph\nContinued paragraph')
        self.assertEqual(len(values['summary'].additional_evidence), 1)
        for field in fields:
            for evidence in (field.evidence, *field.additional_evidence):
                span = next(span for span in table.values() if span.page == evidence.page)
                self.assertEqual(span.raw_text[evidence.start : evidence.end], evidence.text)

    def test_gender_prose_generic_name_and_bad_birth_date_are_rejected(self):
        table = numbered('Resume\nFemale engineer\nNo birth date')
        response = line_personal(
            fullName={'line': 1, 'value': 'resume'},
            gender={'line': 2, 'value': 'FEMALE'},
            dateOfBirth={'line': 3, 'value': '2000-04-03'},
        )
        fields, issues = validate_line_personal(json.dumps(response), table)
        self.assertFalse(fields)
        for key in ('fullName', 'gender', 'dateOfBirth'):
            self.assertIn(Issue('QUOTE_NOT_FOUND', key), issues)

    def test_summary_rejects_gaps_order_limits_and_missing_lines(self):
        tables = (
            ({1: Span(1, 0, 1, 'a'), 2: Span(3, 0, 1, 'b')}, [{'startLine': 1, 'endLine': 2}]),
            (numbered('a\nb'), [{'startLine': 2, 'endLine': 1}]),
            (numbered('a\nb'), [{'startLine': 1, 'endLine': 99}]),
            (numbered('x' * 6001), [{'startLine': 1, 'endLine': 1}]),
            (numbered(*('x' for _ in range(9))), [{'startLine': 1, 'endLine': 9}]),
            (numbered('abc'), [{'startLine': 1, 'endLine': 1}] * 2),
        )
        for table, summary in tables:
            fields, issues = validate_line_personal(
                json.dumps(line_personal(summary=summary)), table
            )
            self.assertFalse(fields)
            self.assertTrue(issues)

    def test_schema_rejects_noninteger_lines_extra_keys_and_legacy_quotes(self):
        for value in (True, 1.0, '1'):
            item = position(title=value)
            self.assertIsNone(
                parse_response(json.dumps({'experiences': [item]}), LINES_EXPERIENCE_SCHEMA)
            )
        data = line_personal()
        data['personal']['quote'] = 'invented'
        self.assertIsNone(parse_response(json.dumps(data), LINES_PERSONAL_SCHEMA))
        self.assertIsNone(parse_response('{broken', LINES_PERSONAL_SCHEMA))


class PipelineTests(unittest.TestCase):
    def test_whole_lines_rejects_personal_outside_schema_and_uses_header_personal(self):
        doc = Document((Page(1, 'ok', 'Synthetic Person\nExperience\nEngineer'),))
        provider = FakeProvider(
            [
                {
                    'experiences': [position(3, None, None)],
                    'personal': {'fullName': {'line': 3, 'value': 'Engineer'}},
                },
                line_personal(fullName={'line': 1, 'value': 'Synthetic Person'}),
            ]
        )
        result = extract(doc, provider, mode='whole', config=settings(whole_personal=True))
        self.assertEqual(provider.calls[0][0], LINES_EXPERIENCE_SCHEMA)
        self.assertEqual(provider.calls[1][0], LINES_PERSONAL_SCHEMA)
        self.assertEqual(result.issues, (Issue('LLM_INVALID_JSON'),))
        self.assertFalse(result.experiences)
        self.assertEqual(
            [(field.key, field.value) for field in result.fields],
            [('fullName', 'Synthetic Person')],
        )

    def test_cross_page_block_compact_evidence_always_matches_original_page(self):
        pages = (
            Page(
                1,
                'ok',
                'Synthetic Person\nGender: Other\nDOB: 2000-04-03\nCity – North\n'
                'Summary\nFirst paragraph\nExperience\nEngineer\n',
            ),
            Page(2, 'ok', 'Synthetic Works\n2020 – Present\n'),
        )
        provider = FakeProvider(
            [
                line_personal(
                    fullName={'line': 1, 'value': 'Synthetic Person'},
                    gender={'line': 2, 'value': 'OTHER'},
                    dateOfBirth={'line': 3, 'value': '2000-04-03'},
                    location={'line': 4, 'endLine': None, 'value': 'City – North'},
                    summary=[{'startLine': 6, 'endLine': 6}],
                ),
                {'experiences': [position(8, 9, 10, period_text='2020 – Present')]},
            ]
        )
        result = extract(Document(pages), provider, config=settings())
        self.assertFalse(result.issues)
        self.assertEqual(len(provider.calls), 2)
        self.assertEqual(len(result.experiences), 1)
        self.assertIn('=== page 1 ===\n[L8] Engineer\n=== page 2 ===\n[L9]', provider.prompts[1])
        claim = {
            'inputSha256': 'a' * 64,
            'definition': {
                'fields': {key: {'enabled': True} for key in (*PERSONAL, 'experiences')},
                'other': {'enabled': True, 'maxItems': 2},
            },
        }
        payload = json.loads(compact_result(result, claim, {}))
        evidence = []

        def collect(value):
            if isinstance(value, dict):
                if set(value) == {'page', 'start', 'end', 'text'}:
                    evidence.append(value)
                else:
                    for child in value.values():
                        collect(child)
            elif isinstance(value, list):
                for child in value:
                    collect(child)

        collect(payload)
        self.assertEqual(len(evidence), 8)
        self.assertEqual({part['page'] for part in evidence}, {1, 2})
        for part in evidence:
            self.assertEqual(
                pages[part['page'] - 1].raw_text[part['start'] : part['end']], part['text']
            )

    def test_each_block_keeps_global_ids_and_excludes_failed_pages(self):
        doc = Document(
            (
                Page(1, 'ok', 'Name\nExperience\nEngineer\nSynthetic Works\n2020 - 2022\nSkills'),
                Page(2, 'failed', 'EXCLUDED_MARKER'),
                Page(3, 'ok', 'Experience\nEngineer\nSynthetic Works\n2023 - Present'),
            )
        )
        provider = FakeProvider(
            [
                line_personal(),
                {'experiences': [position(3, 4, 5)]},
                {'experiences': [position(8, 9, 10, period_text='2023 - Present')]},
            ]
        )
        result = extract(doc, provider, config=settings())
        self.assertEqual(result.issues, (Issue('PERSONAL_EMPTY'),))
        self.assertEqual(len(result.experiences), 2)
        self.assertIn('[L3] Engineer', provider.prompts[1])
        self.assertNotIn('[L8]', provider.prompts[1])
        self.assertIn('=== page 3 ===\n[L8] Engineer', provider.prompts[2])
        self.assertTrue(all('EXCLUDED_MARKER' not in prompt for prompt in provider.prompts))

    def test_quote_defaults_explicit_config_and_o4_options_are_unchanged(self):
        doc = Document((Page(1, 'ok', 'Experience\nEngineer'),))
        for mode in ('blocks', 'whole'):
            responses = (
                [personal(), {'experiences': []}]
                if mode == 'blocks'
                else [dict(personal(), experiences=[]), personal()]
            )
            default = FakeProvider(responses)
            explicit = FakeProvider(responses)
            before = extract(doc, default, mode=mode)
            after = extract(doc, explicit, mode=mode, config=settings(evidence='quote'))
            self.assertEqual(before, after)
            self.assertEqual(default.prompts, explicit.prompts)
            self.assertEqual(default.calls, explicit.calls)

    def test_whole_blocks_schemas_numbering_and_compact_payload(self):
        doc = Document(
            (Page(1, 'ok', 'Synthetic Person\nExperience\nEngineer\nSynthetic Works\n2020 - 2022'),)
        )
        response = {'experiences': [position(3, 4, 5)]}
        header = line_personal(fullName={'line': 1, 'value': 'Synthetic Person'})
        claim = {
            'inputSha256': 'a' * 64,
            'definition': {
                'fields': {key: {'enabled': True} for key in (*PERSONAL, 'experiences')},
                'other': {'enabled': True, 'maxItems': 2},
            },
        }
        for mode in ('blocks', 'whole'):
            provider = FakeProvider([header, response] if mode == 'blocks' else [response, header])
            result = extract(doc, provider, mode=mode, config=settings())
            self.assertFalse(result.issues)
            self.assertEqual(len(result.experiences), 1)
            self.assertIn('[L3] Engineer', provider.prompts[mode == 'blocks'])
            self.assertEqual(provider.calls[mode == 'blocks'][0], LINES_EXPERIENCE_SCHEMA)
            payload = json.loads(compact_result(result, claim, {}))
            self.assertEqual(
                set(payload['experiences'][0]),
                {'title', 'company', 'start', 'end', 'ongoing', 'evidence'},
            )
            quote_header = personal()
            quote_header['personal']['fullName'] = {
                'value': 'Synthetic Person',
                'quote': 'Synthetic Person',
            }
            quote_row = {
                'title': 'Engineer',
                'company': 'Synthetic Works',
                'start': '2020',
                'end': '2022',
                'ongoing': False,
                'quotes': {
                    'title': 'Engineer',
                    'company': 'Synthetic Works',
                    'period': '2020 - 2022',
                },
            }
            quoted = FakeProvider([quote_header, {'experiences': [quote_row]}])
            quote_result = extract(doc, quoted)
            self.assertEqual(payload, json.loads(compact_result(quote_result, claim, {})))

    def test_prompt_versions_lean_and_delimiter_escaping(self):
        for mode, version in (('blocks', 'lines-2'), ('whole', 'whole-lines-2')):
            self.assertEqual(prompt_version(mode, settings()), 'llm-extraction-prompt/' + version)
            lean = settings(prompt_schema_text=False, whole_personal=False)
            suffix = '-lean-xp' if mode == 'whole' else '-lean'
            self.assertEqual(
                prompt_version(mode, lean), 'llm-extraction-prompt/' + version + suffix
            )
        prompt = build_lines_prompt(BEGIN + END, schema_text=False)
        self.assertTrue(prompt.startswith('Return only JSON.\n'))
        self.assertEqual(prompt.count(BEGIN), 1)
        self.assertEqual(prompt.count(END), 1)

    def test_provider_error_and_whole_fallback_preserve_version(self):
        provider = FakeProvider([ProviderError('PROVIDER_ERROR'), {'experiences': []}])
        result = extract(
            Document((Page(1, 'ok', 'Experience\nEngineer'),)), provider, config=settings()
        )
        self.assertIn(Issue('PROVIDER_ERROR', 'personal'), result.issues)
        doc = Document((Page(1, 'ok', 'Experience\n' + ('x\n' * 18000)),))
        provider = FakeProvider([line_personal()] + [{'experiences': []}] * 20)
        result = extract(doc, provider, mode='whole', config=settings())
        self.assertIn(Issue('WHOLE_TOO_LONG'), result.issues)
        self.assertEqual(result.prompt_version, 'llm-extraction-prompt/whole-lines-2')


class ConfigTests(unittest.TestCase):
    def test_precedence_default_bench_and_fixed_diagnostics(self):
        env = {'CVC_LLM_PROVIDER': 'ollama', 'CVC_LLM_MODEL': 'synthetic'}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'config.env'
            path.write_text('CVC_LLM_EVIDENCE=lines\n', encoding='utf-8')
            loaded = load_config({}, env, Path(directory) / 'absent', env_file_explicit=False)
            self.assertEqual(loaded.evidence, 'quote')
            self.assertEqual(load_config({}, env, path, env_file_explicit=True).evidence, 'lines')
            environ = dict(env, CVC_LLM_EVIDENCE='quote')
            self.assertEqual(
                load_config({}, environ, path, env_file_explicit=True).evidence, 'quote'
            )
            self.assertEqual(
                load_config({'evidence': 'lines'}, environ, path, env_file_explicit=True).evidence,
                'lines',
            )
            self.assertEqual(load_bench_config(path, environ).evidence, 'lines')
            with self.assertRaisesRegex(ValueError, '^INVALID_EVIDENCE$'):
                load_config({'evidence': 'bad-private-value'}, env, path, env_file_explicit=True)
            pull_env = dict(
                env,
                CVC_LLM_BACKEND_URL='http://localhost',
                CVC_LLM_WORKER_TOKEN='a' * 43,
                CVC_LLM_EVIDENCE='bad',
            )
            with self.assertRaisesRegex(ValueError, '^CVC_LLM_EVIDENCE INVALID_EVIDENCE$'):
                load_pull_config(pull_env, path, env_file_explicit=True)
