"""A4 acceptance using synthetic strings and offline providers only."""

import json
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from time import perf_counter
from unittest.mock import patch

from test_a1_clean_names import ENV, settings
from test_core import document, experience, personal, response
from test_o5_lines import line_personal, numbered
from test_pull import TOKEN, Backend, backend_server, claim, config

from cv_compass_llm.bench import run_bench
from cv_compass_llm.cli import CLI_ERRORS, parser, run_batch
from cv_compass_llm.config import (
    BENCH_ENV_KEYS,
    ENV_KEYS,
    PullConfigError,
    load_bench_config,
    load_config,
    load_pull_config,
)
from cv_compass_llm.names import trim_company, trim_title
from cv_compass_llm.pipeline import extract
from cv_compass_llm.prompts import prompt_version
from cv_compass_llm.providers import FakeProvider
from cv_compass_llm.pull import run_pull
from cv_compass_llm.pull_payload import compact_result
from cv_compass_llm.segmenter import Block, Span
from cv_compass_llm.validate import (
    validate_experiences,
    validate_line_experiences,
    validate_whole,
)

# Each numbered rule has a positive and a must-not-cut case.
COMPANIES = (
    (1, 'Company: Aster Works', 'Aster Works'),
    (1, 'Công ty: Aster Works', 'Aster Works'),
    (1, 'Company Aster Works', 'Company Aster Works'),
    (2, 'ACME JSC | Cloud tools for retail', 'ACME JSC'),
    (2, 'Aster|Works', 'Aster|Works'),
    (3, 'Aster (Incl: tools)', 'Aster'),
    (3, 'Aster (including tools)', 'Aster'),
    (3, 'Aster (bao gồm tools)', 'Aster'),
    (3, 'Aster (Incl: tools', 'Aster (Incl: tools'),
    (3, 'Aster (a (b c d e f g h))', 'Aster (a (b c d e f g h))'),
    (3, 'Aster (one two three four five six seven)', 'Aster'),
    (3, 'Aster (one two three four five six)', 'Aster (one two three four five six)'),
    (3, 'Brant & Moyle Vietnam Limited (BM)', 'Brant & Moyle Vietnam Limited (BM)'),
    (3, 'Aster (QUELBRO)', 'Aster (QUELBRO)'),
    (3, 'Aster (VIETNAM)', 'Aster (VIETNAM)'),
    (3, 'Aster (ZORANA VIETNAM)', 'Aster (ZORANA VIETNAM)'),
    (
        3,
        'Aster (TAN KHOI GEMSTONE JOINT STOCK COMPANY)',
        'Aster (TAN KHOI GEMSTONE JOINT STOCK COMPANY)',
    ),
    (4, 'Viet Nam, Aster Works', 'Aster Works'),
    (4, 'USA, Aster Works', 'Aster Works'),
    (4, '  USA,\tAster Works', 'Aster Works'),
    (4, 'Vietnam Aster Works', 'Vietnam Aster Works'),
    (
        5,
        'NORTHWIND TRADING CO., LTD, HELIX GROUP AG, SWITZERLAND',
        'NORTHWIND TRADING CO., LTD',
    ),
    (5, 'ASTER GROUP COMPANY, HELIX AG', 'ASTER GROUP COMPANY'),
    (5, 'ASTER CO.,LTD - HELIX', 'ASTER CO.,LTD'),
    (5, 'ASTER CO. LTD–HELIX', 'ASTER CO. LTD'),
    (5, 'ASTER LTD- HELIX', 'ASTER LTD'),
    (5, 'ASTER JSC HELIX', 'ASTER JSC'),
    (5, 'ASTER CORP. (VN), HELIX', 'ASTER CORP. (VN)'),
    (5, 'ASTER LTD .', 'ASTER LTD .'),
    (5, 'Aster Co., Ltd', 'Aster Co., Ltd'),
    (5, 'ACME JSC Hanoi', 'ACME JSC Hanoi'),
    (5, 'KIM THANH GROUP JOINT STOCK COMPANY', 'KIM THANH GROUP JOINT STOCK COMPANY'),
    (5, 'ABC TRADING COMPANY LIMITED', 'ABC TRADING COMPANY LIMITED'),
    (5, 'ASTER LIMITED, VIETNAM', 'ASTER LIMITED, VIETNAM'),
    (
        5,
        'KIM THANH GROUP JOINT STOCK COMPANY, VIETNAM',
        'KIM THANH GROUP JOINT STOCK COMPANY',
    ),
    (5, 'ASTER GROUP joint stock company, VIETNAM', 'ASTER GROUP joint stock company'),
    (5, 'ASTER GROUP, COMPANY', 'ASTER GROUP'),
    (
        5,
        'Công ty Cổ phần Cơ điện và Xây dựng Vertaxa',
        'Công ty Cổ phần Cơ điện và Xây dựng Vertaxa',
    ),
    (
        5,
        'Công ty TNHH Aster GROUP COMPANY Việt Nam',
        'Công ty TNHH Aster GROUP COMPANY Việt Nam',
    ),
    (
        15,
        'Công ty TNHH Sản xuất - Thương mại Aster',
        'Công ty TNHH Sản xuất - Thương mại Aster',
    ),
    (
        15,
        'Công ty Cổ phần Đầu tư - Xây dựng Vertaxa',
        'Công ty Cổ phần Đầu tư - Xây dựng Vertaxa',
    ),
    (
        15,
        'Công ty TNHH Xuất nhập khẩu - Dịch vụ Lumora',
        'Công ty TNHH Xuất nhập khẩu - Dịch vụ Lumora',
    ),
    (3, 'Công ty TNHH Aster (Incl: tools)', 'Công ty TNHH Aster (Incl: tools)'),
    (
        3,
        'Công ty Cổ phần Aster (one two three four five six seven)',
        'Công ty Cổ phần Aster (one two three four five six seven)',
    ),
    (1, 'Company: Công ty TNHH Aster', 'Công ty TNHH Aster'),
    (2, 'Công ty TNHH Aster | tools', 'Công ty TNHH Aster'),
    (15, 'Công ty TNHH Aster belong to Helix', 'Công ty TNHH Aster'),
    (
        15,
        'Company: Công ty TNHH Sản xuất - Thương mại Aster | tools',
        'Công ty TNHH Sản xuất - Thương mại Aster',
    ),
    (15, 'Aster (belong to Helix)', 'Aster'),
    (15, 'Aster belong to Helix', 'Aster'),
    (15, 'Quorra Holding – Asset holding of a pioneer', 'Quorra Holding'),
    (15, 'Aster - tools for teams', 'Aster'),
    (15, 'ZEFO - QRT CEMENT JOINT STOCK COMPANY', 'ZEFO - QRT CEMENT JOINT STOCK COMPANY'),
    (15, 'QMX – Northgate Marketing Group VN', 'QMX – Northgate Marketing Group VN'),
    (15, 'Aster - IT', 'Aster - IT'),
)
TITLES = (
    (6, 'Position: Engineer', None, 'Engineer'),
    (6, 'Chức vụ: Engineer', None, 'Engineer'),
    (6, 'Vị trí: Engineer', None, 'Engineer'),
    (6, 'Position Engineer', None, 'Position Engineer'),
    (7, 'Work as shift lead in Plant 2, Hai Phong', None, 'shift lead'),
    (7, 'WORKED AS Shift Lead AT Aster', None, 'Shift Lead'),
    (7, 'Work as shift lead', None, 'Work as shift lead'),
    (8, 'Manager for product ranges: tools', None, 'Manager'),
    (8, 'Manager consults for one two three four five six', None, 'Manager'),
    (8, 'Manager cho sản phẩm: tools', None, 'Manager'),
    (8, 'Head of Sales for Asia', None, 'Head of Sales for Asia'),
    (8, 'Manager for one two three four five', None, 'Manager for one two three four five'),
    (9, 'Director (one two three four)', None, 'Director'),
    (9, 'Director (cum Chief Accountant)', None, 'Director (cum Chief Accountant)'),
    (9, 'Director (CGOO)', None, 'Director (CGOO)'),
    (9, 'Director (CE)', None, 'Director (CE)'),
    (9, 'Director (MT channel)', None, 'Director (MT channel)'),
    (10, 'Engineer Aster Works', 'Aster Works', 'Engineer'),
    (10, 'Engineer Aster Works\t', 'Aster Works', 'Engineer'),
    (10, 'Engineer MegAster', 'Aster', 'Engineer MegAster'),
    (10, 'Engineer Aster', 'Other', 'Engineer Aster'),
    (11, 'Area Sales Executive | Management Trainee', None, 'Area Sales Executive'),
    (11, 'Chief Executive Officer / ZE-TOP - VORAN GROUP', None, 'Chief Executive Officer'),
    (11, 'Director/Engineer', None, 'Director/Engineer'),
    (
        12,
        'Manager – Saturnia Foods Vietnam (Kelvra Group)',
        'Saturnia Foods Vietnam (Kelvra Group)',
        'Manager',
    ),
    (12, 'Manager at Aster', 'Aster', 'Manager'),
    (12, 'Manager at Aster\n', 'Aster', 'Manager'),
    (12, 'Manager - Aster', 'Other', 'Manager - Aster'),
    (13, 'Portfolio Manager at Leading Fund Management Company', None, 'Portfolio Manager'),
    (13, 'Head of Sales at', None, 'Head of Sales at'),
    (13, 'Engineer at Aster', None, 'Engineer at Aster'),
    (14, 'Senior Investment Officer, Investment Department', None, 'Senior Investment Officer'),
    (14, 'Engineer, Phòng kỹ thuật', None, 'Engineer'),
    (14, 'Engineer, Team Aster', None, 'Engineer, Team Aster'),
)


def row(title=None, company=None):
    return experience(title=title, company=company, start=None, end=None, period=None)


class NameRuleTests(unittest.TestCase):
    def test_long_values_finish_quickly_and_return_substrings(self):
        value = 'Aster ' + 'x ' * 2500 + '(Incl: tools)'
        for trim in (trim_company, lambda text: trim_title(text, None)):
            with self.subTest(trim=trim):
                started = perf_counter()
                start, end = trim(value)
                self.assertLess(perf_counter() - started, 1.0)
                self.assertGreaterEqual(start, 0)
                self.assertLessEqual(end, len(value))
                self.assertLess(start, end)
                self.assertIn(value[start:end], value)

    def test_company_rules(self):
        for rule, value, expected in COMPANIES:
            with self.subTest(rule=rule, value=value):
                start, end = trim_company(value)
                self.assertEqual(value[start:end], expected)

    def test_title_rules(self):
        for rule, value, company, expected in TITLES:
            with self.subTest(rule=rule, value=value):
                start, end = trim_title(value, company)
                self.assertEqual(value[start:end], expected)

    def test_each_rule_preserves_source_and_evidence(self):
        cases = [('company', v, None, e) for _, v, e in COMPANIES]
        cases += [('title', v, c, e) for _, v, c, e in TITLES]
        for field, value, company, expected in cases:
            with self.subTest(field=field, value=value):
                item = row(company=value) if field == 'company' else row(value, company)
                raw = 'prefix\n' + value + '\n' + (company or '') + '\nsuffix'
                source = Block((Span(3, 41, 41 + len(raw), raw),))
                rows, issues = validate_experiences(response(item), source, name_trim='on')
                self.assertFalse(issues)
                result = getattr(rows[0], field)
                evidence = rows[0].evidence[field]
                self.assertEqual(result, expected)
                self.assertEqual(evidence.page, 3)
                self.assertEqual(evidence.text, result)
                self.assertEqual(raw[evidence.start - 41 : evidence.end - 41], result)
                self.assertGreaterEqual(evidence.start, 41)
                self.assertLessEqual(evidence.end, 41 + len(raw))

    def test_newlines_match_as_spaces_but_survive_inside_output(self):
        company = 'Company:\n USA,\t Aster\n Works | tools for teams'
        title = 'Position:\tWorked\nas Senior\n Engineer at\tAster\nWorks'
        self.assertEqual(company[slice(*trim_company(company))], 'Aster\n Works')
        self.assertEqual(title[slice(*trim_title(title, 'Aster Works'))], 'Senior\n Engineer')
        source = Block((Span(2, 10, 10 + len(company), company),))
        rows, issues = validate_experiences(response(row(company=company)), source, name_trim='on')
        self.assertFalse(issues)
        evidence = rows[0].evidence['company']
        self.assertEqual(evidence.text, 'Aster\n Works')
        self.assertEqual(company[evidence.start - 10 : evidence.end - 10], evidence.text)

    def test_rule_order_and_cut_edges(self):
        company = 'Company: USA, ASTER GROUP COMPANY (VN), HELIX | tools'
        self.assertEqual(company[slice(*trim_company(company))], 'ASTER GROUP COMPANY (VN)')
        for sep in (' / ', ' – ', ' - ', ' | ', ' at '):
            value = 'Engineer' + sep + 'Aster'
            self.assertEqual(value[slice(*trim_title(value, 'Aster'))], 'Engineer')
        title = 'Position: Work as Lead in Plant / Other'
        self.assertEqual(title[slice(*trim_title(title, None))], 'Lead')

    def test_empty_results_are_never_applied(self):
        for value in ('Company:', '| tools', '(including tools)', 'USA,', 'belong to Aster', ''):
            self.assertEqual(trim_company(value), (0, len(value)))
        for value, company in (
            ('Position:', None),
            ('Aster', 'Aster'),
            ('(one two three four)', None),
            ('', None),
        ):
            self.assertEqual(trim_title(value, company), (0, len(value)))


class NameValidationTests(unittest.TestCase):
    def test_quote_larger_than_value_maps_original_value_offset(self):
        value = 'Position: Engineer at Aster'
        quote = '🧪 Before ' + value + ' after'
        item = row(value, 'Aster')
        item['quotes']['title'] = quote
        source = Block((Span(7, 90, 90 + len(quote), quote),))
        rows, issues = validate_experiences(response(item), source, name_trim='on')
        self.assertFalse(issues)
        evidence = rows[0].evidence['title']
        self.assertEqual(rows[0].title, 'Engineer')
        self.assertEqual(evidence.start, 90 + quote.index('Engineer'))
        self.assertEqual(evidence.end, evidence.start + len('Engineer'))
        self.assertEqual(evidence.text, rows[0].title)

    def test_normalized_model_value_uses_literal_source_before_cutting(self):
        quote = '🧪 Before POSITION:\nEngineer at Aster after'
        item = row('position: engineer at aster', 'aster')
        item['quotes'].update(title=quote, company='Aster')
        source = Block((Span(4, 50, 50 + len(quote), quote),))
        rows, issues = validate_experiences(response(item), source, name_trim='on')
        self.assertFalse(issues)
        self.assertEqual(rows[0].title, 'Engineer')
        evidence = rows[0].evidence['title']
        self.assertEqual(evidence.start, 50 + quote.index('Engineer'))
        self.assertEqual(quote[evidence.start - 50 : evidence.end - 50], evidence.text)

    def test_company_is_trimmed_before_title_in_both_validation_paths(self):
        title, company = 'Engineer at Aster', 'Company: Aster | tools'
        source = title + '\n' + company
        quote = response(row(title, company))
        lines = {
            'title': {'line': 1, 'value': title},
            'company': {'line': 2, 'value': company},
            'period': {'line': None, 'text': None},
            'ongoing': False,
        }
        outputs = [
            validate_whole(
                quote,
                Block((Span(1, 0, len(source), source),)),
                whole_personal=False,
                name_trim='on',
            )[1:],
            validate_line_experiences(
                json.dumps({'experiences': [lines]}), numbered(source), name_trim='on'
            ),
        ]
        for rows, issues in outputs:
            self.assertFalse(issues)
            self.assertEqual((rows[0].title, rows[0].company), ('Engineer', 'Aster'))
            for field in ('title', 'company'):
                evidence = rows[0].evidence[field]
                self.assertEqual(evidence.text, getattr(rows[0], field))
                self.assertEqual(source[evidence.start : evidence.end], evidence.text)

    def test_unsupported_values_are_rejected_before_trimming(self):
        source = 'Engineer\nAster'
        item = row('Position: Engineer', 'Company: Aster')
        rows, issues = validate_experiences(
            response(item), Block((Span(1, 0, len(source), source),)), name_trim='on'
        )
        self.assertFalse(rows)
        self.assertEqual([issue.code for issue in issues].count('QUOTE_NOT_FOUND'), 2)

    def test_pipeline_off_matches_full_compact_baseline_and_on_narrows(self):
        title, company = 'Position: Engineer at Aster', 'Company: Aster | tools'
        text = 'Position: Synthetic Person\nExperience\n' + title + '\n' + company
        header = personal(
            fullName={
                'value': 'Position: Synthetic Person',
                'quote': 'Position: Synthetic Person',
            }
        )
        for mode in ('blocks', 'whole'):
            for evidence in ('quote', 'lines'):
                with self.subTest(mode=mode, evidence=evidence):
                    item = row(title, company)
                    named = header
                    if evidence == 'lines':
                        item = {
                            'title': {'line': 3, 'value': title},
                            'company': {'line': 4, 'value': company},
                            'period': {'line': None, 'text': None},
                            'ongoing': False,
                        }
                        named = line_personal(
                            fullName={'line': 1, 'value': 'Position: Synthetic Person'}
                        )
                    xp = {'experiences': [item]}
                    whole = dict(xp, **header) if evidence == 'quote' else xp
                    responses = [named, xp] if mode == 'blocks' else [whole, named]
                    outputs, providers = [], []
                    for flag in (None, 'off', 'on'):
                        provider = FakeProvider(responses)
                        options = settings(evidence=evidence)
                        if flag is not None:
                            options = replace(options, name_trim=flag)
                        config = None if flag is None and evidence == 'quote' else options
                        outputs.append(extract(document(text), provider, mode=mode, config=config))
                        providers.append(provider)
                    baseline, off, on = outputs
                    self.assertEqual(off, baseline)
                    provenance = {
                        'workerVersion': 'test',
                        'model': 'synthetic-model',
                        'promptVersion': prompt_version(mode, settings(evidence=evidence)),
                    }
                    self.assertEqual(
                        compact_result(off, claim(), provenance),
                        compact_result(baseline, claim(), provenance),
                    )
                    self.assertEqual(
                        (off.experiences[0].title, off.experiences[0].company), (title, company)
                    )
                    self.assertEqual(
                        (on.experiences[0].title, on.experiences[0].company), ('Engineer', 'Aster')
                    )
                    self.assertEqual(on.fields, off.fields)
                    self.assertEqual(providers[0].prompts, providers[2].prompts)
                    self.assertEqual(providers[0].request_options, providers[2].request_options)
                    for field in ('title', 'company'):
                        narrowed = on.experiences[0].evidence[field]
                        self.assertEqual(text[narrowed.start : narrowed.end], narrowed.text)
                        self.assertEqual(narrowed.text, getattr(on.experiences[0], field))
                    payload = json.loads(compact_result(on, claim(), provenance))
                    self.assertNotIn('name_trim', payload)
                    self.assertEqual(payload['experiences'][0]['title'], 'Engineer')
                    self.assertEqual(payload['experiences'][0]['company'], 'Aster')
                    self.assertEqual(payload['issues'], [])
                    self.assertEqual(prompt_version(mode, options), provenance['promptVersion'])


class NameConfigTests(unittest.TestCase):
    def test_default_precedence_and_bench(self):
        def load(cli=None, environ=None, values=None):
            return load_config(
                cli or {},
                {**ENV, **(environ or {})},
                None,
                env_file_explicit=False,
                _file_values=values or {},
            )

        key = 'CVC_LLM_NAME_TRIM'
        self.assertEqual(ENV_KEYS['name_trim'], key)
        self.assertIn(key, BENCH_ENV_KEYS)
        self.assertEqual(load().name_trim, 'off')
        self.assertEqual(load(values={key: 'on'}).name_trim, 'on')
        self.assertEqual(load(environ={key: 'off'}, values={key: 'on'}).name_trim, 'off')
        self.assertEqual(load({'name_trim': 'on'}, {key: 'off'}).name_trim, 'on')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'synthetic.env'
            path.write_text(key + '=on\n', encoding='utf-8')
            self.assertEqual(load_bench_config(path, ENV).name_trim, 'on')
        for value in ('invalid', 'true', 'ON'):
            with self.assertRaisesRegex(ValueError, '^INVALID_NAME_TRIM$'):
                load(environ={key: value})
        self.assertIn('INVALID_NAME_TRIM', CLI_ERRORS)

    def test_run_pull_cli_and_safe_pull_errors(self):
        for arguments in (
            ['run', '--input', 'synthetic-in', '--output', 'synthetic-out'],
            ['pull'],
        ):
            args = parser().parse_args(arguments + ['--name-trim', 'on'])
            self.assertEqual(args.name_trim, 'on')
        environ = {
            **ENV,
            'CVC_LLM_BACKEND_URL': 'http://localhost',
            'CVC_LLM_WORKER_TOKEN': TOKEN,
        }
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / 'synthetic.env'
            env_file.write_text('', encoding='utf-8')
            loaded = load_pull_config(
                environ, env_file, env_file_explicit=True, cli={'name_trim': 'on'}
            )
            self.assertEqual(loaded.llm.name_trim, 'on')
            with self.assertRaisesRegex(PullConfigError, '^CVC_LLM_NAME_TRIM INVALID_NAME_TRIM$'):
                load_pull_config(
                    {**environ, 'CVC_LLM_NAME_TRIM': 'private-invalid'},
                    env_file,
                    env_file_explicit=True,
                )

    def test_run_summary_records_flag_with_and_without_config(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'input'
            source.mkdir()
            for flag in (None, 'off', 'on'):
                output = Path(directory) / str(flag)
                provider = FakeProvider(personal())
                summary = run_batch(
                    source, output, provider, config=settings(name_trim=flag) if flag else None
                )
                self.assertEqual(summary['name_trim'], flag or 'off')
                saved = json.loads((output / 'run-summary.json').read_text(encoding='utf-8'))
                self.assertEqual(saved['name_trim'], flag or 'off')

    def test_bench_warmup_and_measured_summaries_record_flag(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, gold, repo = (root / name for name in ('input', 'gold', 'repo'))
            for path in (source, gold, repo):
                path.mkdir()
            (source / 'synthetic.json').write_text(
                json.dumps({'pages': [{'page_number': 1, 'status': 'ok', 'raw_text': ''}]}),
                encoding='utf-8',
            )
            env_file = root / 'synthetic.env'
            env_file.write_text('CVC_LLM_NAME_TRIM=on\n', encoding='utf-8')
            providers = []

            def factory(loaded):
                self.assertEqual(loaded.name_trim, 'on')
                provider = FakeProvider(personal())
                providers.append(provider)
                return provider

            run_bench(
                source,
                gold,
                root / 'output',
                [f'trim={env_file}'],
                provider_factory=factory,
                environ=ENV,
                warmup=1,
                repo_root=repo,
            )
            self.assertEqual(len(providers), 1)
            for run in ('warmup', 'r1'):
                summary_file = root / 'output' / 'trim' / run / 'run-summary.json'
                saved = json.loads(summary_file.read_text(encoding='utf-8'))
                self.assertEqual(saved['name_trim'], 'on')

    def test_pull_startup_log_records_only_flag(self):
        backend = Backend()
        with backend_server(backend) as url:
            loaded = replace(config(url), llm=settings(name_trim='on'))
            with patch('cv_compass_llm.pull.event') as event:
                self.assertEqual(run_pull(loaded, FakeProvider(personal()), once=True), 0)
        starts = [call.kwargs for call in event.call_args_list if call.args == ('worker_start',)]
        self.assertEqual(len(starts), 1)
        self.assertEqual(starts[0]['name_trim'], 'on')
