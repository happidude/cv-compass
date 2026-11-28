"""A2 opt-in date parsing and evidence boundaries with synthetic, offline data."""

import hashlib
import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from dataclasses import replace
from datetime import date
from pathlib import Path
from unittest.mock import patch

from test_a1_clean_names import ENV, T, settings
from test_core import block, document, experience, personal, response
from test_o5_lines import line_personal, numbered, position
from test_pull import TOKEN

from cv_compass_llm.cli import CLI_ERRORS, main, parser, run_batch
from cv_compass_llm.config import (
    BENCH_ENV_KEYS,
    ENV_KEYS,
    PullConfigError,
    load_bench_config,
    load_config,
    load_pull_config,
)
from cv_compass_llm.dates import MONTHS, find_birth_dates, find_dates, parse_birth_date
from cv_compass_llm.pipeline import extract
from cv_compass_llm.prompts import prompt_version
from cv_compass_llm.providers import FakeProvider
from cv_compass_llm.schema import Issue
from cv_compass_llm.validate import (
    validate_experiences,
    validate_line_experiences,
    validate_whole,
)


def configured(**values):
    return load_config({}, {**ENV, **values}, None, env_file_explicit=False, _file_values={})


class DateTokenTests(unittest.TestCase):
    def test_new_formats_and_explicit_v1_results(self):
        cases = (
            ('May 21 – Jul 23', ['2021-05', '2023-07'], []),
            ('Aug 19', ['2019-08'], []),
            ('Jan 27', ['2027-01'], []),
            ('Jan 28', [], []),
            ('Jan 99', ['1999-01'], []),
            ('Jan 45', [], []),
            ('1932', [], []),
            ("Sept '21", ['2021-09'], []),
            ('Sept ’21', ['2021-09'], []),
            ('Sept. 21', ['2021-09'], []),
            ('08.2022', ['2022-08'], ['2022-08']),
            ('08. 2022', ['2022-08'], ['2022']),
            ('08 .2022', ['2022-08'], ['2022']),
            ('08 . 2022', ['2022-08'], ['2022']),
            ('2019 – 08. 2022', ['2019', '2022-08'], ['2019', '2022']),
            ('Sep’- 2010', ['2010-09'], ['2010']),
            ("Sept'-2010", ['2010-09'], ['2010']),
            ('Apr - 2016', ['2016-04'], ['2016-04']),
            ('Feb – 2017', ['2017-02'], ['2017-02']),
            ('08.2022 -', ['2022-08'], ['2022-08']),
            ('2022 –', ['2022'], ['2022']),
            ('Arp 2006', ['2006'], ['2006']),
            ('Decem 2006', ['2006'], ['2006']),
            ("Decem'-2006", ['2006'], ['2006']),
            ('May 21, 2019', ['2019-05'], ['2019-05']),
            ('May 21 2019', ['2019-05'], ['2019-05']),
        )
        for text, v2, v1 in cases:
            with self.subTest(text=text):
                self.assertEqual(find_dates(text, style='v2', current_year=2026), v2)
                self.assertEqual(find_dates(text, style='v1', current_year=2026), v1)
                self.assertEqual(find_dates(text), v1)

    def test_all_month_names_and_short_year_punctuation(self):
        for month, names in enumerate(MONTHS, 1):
            for name in names:
                for separator in (' ', '. ', " '", ' ’'):
                    text = name.upper() + separator + '21'
                    with self.subTest(text=text):
                        self.assertEqual(
                            find_dates(text, style='v2', current_year=2026),
                            [f'2021-{month:02d}'],
                        )

    def test_page_marker_only_excludes_dotted_month_year(self):
        for text, expected in (
            ('Nha Trang 2019 – 2021', ['2019', '2021']),
            ('Trang 2019 – 2021', ['2019', '2021']),
            ('Page 2019', ['2019']),
        ):
            for style in ('v1', 'v2'):
                with self.subTest(text=text, style=style):
                    self.assertEqual(find_dates(text, style=style, current_year=2026), expected)

    def test_sentence_dot_after_year_does_not_exclude_dates(self):
        for text, expected in (
            ('2019. 2020', ['2019', '2020']),
            ('12/2019. 2020', ['2019-12', '2020']),
            ('2015 – 2019. 2020', ['2015', '2019', '2020']),
        ):
            for style in ('v1', 'v2'):
                with self.subTest(text=text, style=style):
                    self.assertEqual(find_dates(text, style=style, current_year=2026), expected)

    def test_explicit_four_digit_year_prevents_short_year_in_same_text(self):
        text = 'May 21 – Jun 3, 2019'
        self.assertEqual(find_dates(text, style='v1'), ['2019-06'])
        self.assertEqual(find_dates(text, style='v2', current_year=2026), ['2019-06'])
        for year in (1950, 2100):
            with self.subTest(year=year):
                self.assertEqual(
                    find_dates(f'May 21; {year}', style='v2', current_year=2026), [str(year)]
                )

    def test_phone_page_day_and_time_exclusions(self):
        for text in (
            '0908 123 456',
            '+84 90 812 2022',
            'Room 21',
            'Page 2',
            'Page 12. 2022',
            'PAGE 08.2022',
            'p. 08 .2022',
            'trang 08. 2022',
            'TR. 08.2022',
            '12.08. 2022',
            '12 . 08 .2022',
            '12308. 2022',
            '08:20',
            'Mar 21st',
            'Mar 21st position',
            'May 211',
            'May 21:30',
            'May 21/23',
        ):
            with self.subTest(text=text):
                self.assertEqual(find_dates(text, style='v2', current_year=2026), [])

    def test_existing_numeric_dates_and_ranges_survive_phone_exclusion(self):
        for text, expected in (
            ('12.08.2022', ['2022-08']),
            ('2019 - 08. 2022', ['2019', '2022-08']),
            ('08 .2022 - 09. 2023', ['2022-08', '2023-09']),
            ('2020-01 - 2022-02', ['2020-01', '2022-02']),
            ('08.2022, 09.2023', ['2022-08', '2023-09']),
        ):
            with self.subTest(text=text):
                self.assertEqual(find_dates(text, style='v2', current_year=2026), expected)

    def test_birth_date_detection_stays_v1(self):
        for text in ('Aug 19', "Sept '21", '08. 2022', 'Sep’- 2010'):
            with self.subTest(text=text):
                self.assertEqual(parse_birth_date(text), (None, False))
        self.assertEqual(find_birth_dates('Aug 19'), [])
        self.assertEqual(find_birth_dates('08. 2022'), [('2022', False)])
        self.assertEqual(find_birth_dates('May 21, 2019'), [('2019-05-21', False)])


class DateValidationTests(unittest.TestCase):
    def setUp(self):
        calendar = patch('cv_compass_llm.dates.date', wraps=date)
        self.addCleanup(calendar.stop)
        calendar.start().today.return_value = date(2026, 11, 21)

    def test_quote_mode_short_years_require_v2(self):
        period = 'May 21 – Jul 23'
        payload = response(experience(start='2021-05', end='2023-07', period=period))
        source = block('Role-Z Org-Z ' + period)
        rows, issues = validate_experiences(payload, source, dates='v2')
        self.assertFalse(issues)
        self.assertEqual((rows[0].start, rows[0].end), ('2021-05', '2023-07'))
        self.assertEqual(rows[0].evidence['period'].text, period)
        rows, issues = validate_experiences(payload, source, dates='v1')
        self.assertIn(Issue('QUOTE_NOT_FOUND', 'start'), issues)
        self.assertIsNone(rows[0].start)

    def test_misspelled_month_only_downgrades_to_explicit_year(self):
        for period in ('Arp 2006', 'Decem 2006', "Decem'-2006"):
            for style, expected in (('v1', None), ('v2', '2006')):
                with self.subTest(period=period, style=style):
                    rows, issues = validate_experiences(
                        response(experience(start='2006-04', end=None, period=period)),
                        block('Role-Z Org-Z ' + period),
                        dates=style,
                    )
                    self.assertEqual(rows[0].start, expected)
                    self.assertEqual(bool(issues), style == 'v1')

    def test_downgrade_does_not_change_explicit_month_or_supply_missing_year(self):
        for period in ('Apr 2006', 'Arp 2007'):
            with self.subTest(period=period):
                rows, issues = validate_experiences(
                    response(experience(start='2006-05', end=None, period=period)),
                    block('Role-Z Org-Z ' + period),
                    dates='v2',
                )
                self.assertIsNone(rows[0].start)
                self.assertIn(Issue('QUOTE_NOT_FOUND', 'start'), issues)

    def test_quote_and_lines_open_ended_ranges_are_not_ongoing(self):
        for period, start in (('08.2022 -', '2022-08'), ('2022 –', '2022')):
            with self.subTest(period=period):
                rows, issues = validate_experiences(
                    response(experience(start=start, end=None, period=period)),
                    block('Role-Z Org-Z ' + period),
                    dates='v2',
                )
                self.assertFalse(issues)
                self.assertEqual(
                    (rows[0].start, rows[0].end, rows[0].ongoing), (start, None, False)
                )
                table = numbered('Engineer\nSynthetic Works\n' + period)
                rows, issues = validate_line_experiences(
                    json.dumps({'experiences': [position(period_text=period)]}), table, dates='v2'
                )
                self.assertFalse(issues)
                self.assertEqual(
                    (rows[0].start, rows[0].end, rows[0].ongoing), (start, None, False)
                )

    def test_lines_short_year_period(self):
        table = numbered('Engineer\nSynthetic Works\nAug 19 – May 21')
        rows, issues = validate_line_experiences(
            json.dumps({'experiences': [position(period_text='Aug 19 – May 21')]}),
            table,
            dates='v2',
        )
        self.assertFalse(issues)
        self.assertEqual((rows[0].start, rows[0].end), ('2019-08', '2021-05'))
        self.assertEqual(rows[0].evidence['period'].text, 'Aug 19 – May 21')

    def test_city_before_year_preserves_quote_and_lines_periods(self):
        for period in ('Nha Trang 2019 – 2021', 'Trang 2019 – 2021'):
            for style in ('v1', 'v2'):
                with self.subTest(period=period, style=style):
                    rows, issues = validate_experiences(
                        response(experience(start='2019', end='2021', period=period)),
                        block('Role-Z Org-Z ' + period),
                        dates=style,
                    )
                    self.assertFalse(issues)
                    self.assertEqual((rows[0].start, rows[0].end), ('2019', '2021'))
                    rows, issues = validate_line_experiences(
                        json.dumps({'experiences': [position(period_text=period)]}),
                        numbered('Engineer\nSynthetic Works\n' + period),
                        dates=style,
                    )
                    self.assertFalse(issues)
                    self.assertEqual((rows[0].start, rows[0].end), ('2019', '2021'))

    def test_whole_validator_threads_date_style(self):
        period = 'May 21 – Jul 23'
        item = experience(start='2021-05', end='2023-07', period=period)
        _, rows, issues = validate_whole(
            json.dumps({**personal(), 'experiences': [item]}),
            block('Role-Z Org-Z ' + period),
            dates='v2',
        )
        self.assertFalse(issues)
        self.assertEqual((rows[0].start, rows[0].end), ('2021-05', '2023-07'))

    def test_quote_period_cannot_borrow_other_items_year(self):
        source = block('Role-Z Org-Z 2019\nRole-Y Org-Y 2022')
        for style in ('v1', 'v2'):
            with self.subTest(style=style):
                payload = response(
                    experience(start='2019', end=None, period='2019'),
                    experience('Role-Y', 'Org-Y', start='2022', end=None, period='2022'),
                )
                rows, issues = validate_experiences(payload, source, dates=style)
                self.assertFalse(issues)
                self.assertEqual((rows[0].start, rows[0].end), ('2019', None))
                self.assertEqual((rows[1].start, rows[1].end), ('2022', None))
                rows, issues = validate_experiences(
                    response(experience(start='2019', end='2022', period='2019')),
                    source,
                    dates=style,
                )
                self.assertIn(Issue('QUOTE_NOT_FOUND', 'end'), issues)
                self.assertIsNone(rows[0].end)

    def test_adjacent_positions_keep_their_own_complete_period_lines(self):
        table = numbered(
            'Engineer\nSynthetic Works\n2018 - 2019\n2020 - 2022\nManager\nSynthetic Labs'
        )
        second = position(5, 6, 4)
        second['title']['value'], second['company']['value'] = 'manager', 'synthetic labs'
        for style in ('v1', 'v2'):
            with self.subTest(style=style):
                rows, issues = validate_line_experiences(
                    json.dumps({'experiences': [position(period_text='2018 - 2019'), second]}),
                    table,
                    dates=style,
                )
                self.assertFalse(issues)
                self.assertEqual((rows[0].start, rows[0].end), ('2018', '2019'))
                self.assertEqual((rows[1].start, rows[1].end), ('2020', '2022'))

    def test_v2_does_not_join_another_items_single_year_or_role_line(self):
        for following, period_line, title_line in (('2022', 4, 5), ('2022 Manager', 5, 4)):
            tail = 'Manager' if title_line == 5 else '2022'
            table = numbered('Engineer\nSynthetic Works\n2019\n' + following + '\n' + tail)
            second = position(title_line, 2, period_line, period_text='2022')
            second['title']['value'] = 'manager'
            payload = json.dumps({'experiences': [position(period_text='2019\n2022'), second]})
            with self.subTest(following=following):
                legacy, _ = validate_line_experiences(payload, table, dates='v1')
                self.assertEqual((legacy[0].start, legacy[0].end), ('2019', '2022'))
                rows, issues = validate_line_experiences(payload, table, dates='v2')
                self.assertEqual(issues, (Issue('QUOTE_NOT_FOUND', 'period'),))
                self.assertEqual((rows[0].start, rows[0].end), (None, None))
                self.assertNotIn('period', rows[0].evidence)
                self.assertEqual(rows[1].start, '2022')
                payload = json.dumps({'experiences': [position(period_text='2019'), second]})
                rows, issues = validate_line_experiences(payload, table, dates='v2')
                self.assertFalse(issues)
                self.assertEqual((rows[0].start, rows[0].end), ('2019', None))
                self.assertEqual(rows[0].evidence['period'].text, '2019')

    def test_v2_continuation_still_joins_split_range(self):
        for source, expected in (
            ('Aug 19 –\nMay 21', ('2019-08', '2021-05')),
            ('August 2017 – November\n2018', ('2017-08', '2018-11')),
        ):
            with self.subTest(source=source):
                rows, issues = validate_line_experiences(
                    json.dumps({'experiences': [position(period_text=source)]}),
                    numbered('Engineer\nSynthetic Works\n' + source),
                    dates='v2',
                )
                self.assertFalse(issues)
                self.assertEqual((rows[0].start, rows[0].end), expected)

    def test_v2_reserves_another_items_explicit_role_line(self):
        table = numbered('Engineer\nSynthetic Works\n2019\n2022 Manager\n2023')
        second = position(4, 2, 5, period_text='2023')
        second['title']['value'] = 'manager'
        rows, issues = validate_line_experiences(
            json.dumps({'experiences': [position(period_text='2019\n2022'), second]}),
            table,
            dates='v2',
        )
        self.assertEqual(issues, (Issue('QUOTE_NOT_FOUND', 'period'),))
        self.assertEqual((rows[0].start, rows[0].end), (None, None))
        self.assertEqual((rows[1].title, rows[1].start), ('Manager', '2023'))


class DateConfigPipelineTests(unittest.TestCase):
    def test_config_defaults_allowlist_and_invalid_code(self):
        self.assertEqual(configured().dates, 'v1')
        self.assertEqual(configured(CVC_LLM_DATES='v2').dates, 'v2')
        self.assertEqual(ENV_KEYS['dates'], 'CVC_LLM_DATES')
        self.assertIn('CVC_LLM_DATES', BENCH_ENV_KEYS)
        self.assertIn('INVALID_DATES', CLI_ERRORS)
        for value in ('v4', 'V2', 'raw'):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, '^INVALID_DATES$'):
                configured(CVC_LLM_DATES=value)

    def test_cli_run_and_pull_accept_dates_without_echoing_invalid_values(self):
        run = ['run', '--input', 'synthetic-in', '--output', 'synthetic-out']
        for arguments in (run, ['pull']):
            with self.subTest(arguments=arguments):
                self.assertEqual(parser().parse_args([*arguments, '--dates', 'v2']).dates, 'v2')
        with (
            patch.dict('os.environ', ENV, clear=True),
            patch('cv_compass_llm.config._read_env', return_value={}),
            redirect_stdout(io.StringIO()) as output,
        ):
            self.assertEqual(main([*run, '--dates', 'unsupported-private-value']), 2)
        self.assertEqual(output.getvalue().strip(), 'INVALID_DATES')

    def test_env_file_cli_precedence_bench_and_pull_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'synthetic.env'
            path.write_text('CVC_LLM_DATES=v2\n', encoding='utf-8')
            self.assertEqual(load_config({}, ENV, path, env_file_explicit=True).dates, 'v2')
            self.assertEqual(load_bench_config(path, ENV).dates, 'v2')
            self.assertEqual(
                load_config(
                    {'dates': 'v1'}, {**ENV, 'CVC_LLM_DATES': 'v2'}, path, env_file_explicit=True
                ).dates,
                'v1',
            )
            env = {
                **ENV,
                'CVC_LLM_BACKEND_URL': 'http://localhost',
                'CVC_LLM_WORKER_TOKEN': TOKEN,
            }
            self.assertEqual(load_pull_config(env, path, env_file_explicit=True).llm.dates, 'v2')
            with self.assertRaisesRegex(PullConfigError, '^CVC_LLM_DATES INVALID_DATES$'):
                load_pull_config({**env, 'CVC_LLM_DATES': 'invalid'}, path, env_file_explicit=True)

    def test_fake_provider_pipeline_threads_env_flag_in_both_modes_and_evidence_styles(self):
        period = 'Aug 19 – May 21'
        source = document('Synthetic Person\nExperience\nEngineer\nSynthetic Works\n' + period)
        person = personal(fullName={'value': 'Synthetic Person', 'quote': 'Synthetic Person'})
        item = experience('Engineer', 'Synthetic Works', '2019-08', '2021-05', period=period)
        calendar = patch('cv_compass_llm.dates.date', wraps=date)
        with calendar as fixed:
            fixed.today.return_value = date(2026, 11, 21)
            for mode in ('blocks', 'whole'):
                for evidence in ('quote', 'lines'):
                    config = configured(CVC_LLM_DATES='v2', CVC_LLM_EVIDENCE=evidence)
                    if evidence == 'lines':
                        header = line_personal(fullName={'line': 1, 'value': 'Synthetic Person'})
                        payload = {'experiences': [position(3, 4, 5, period_text=period)]}
                    else:
                        header, payload = person, {'experiences': [item]}
                        if mode == 'whole':
                            payload = {**person, **payload}
                    provider = FakeProvider(
                        [payload, header] if mode == 'whole' else [header, payload]
                    )
                    with self.subTest(mode=mode, evidence=evidence):
                        result = extract(source, provider, mode=mode, config=config)
                        self.assertFalse(result.issues)
                        self.assertEqual(len(result.experiences), 1)
                        row = result.experiences[0]
                        self.assertEqual((row.start, row.end), ('2019-08', '2021-05'))
                        self.assertEqual(row.evidence['period'].text, period)
                        self.assertEqual(
                            prompt_version(mode, config),
                            prompt_version(mode, replace(config, dates='v1')),
                        )

    def test_default_and_v1_result_hashes_and_provider_calls_match_existing_fixture(self):
        person = personal(fullName={'value': 'Synthetic Person', 'quote': 'Synthetic Person'})
        item = experience(start='2020-01', end='2021-02', period='01/2020 - 02/2021')
        for mode in ('blocks', 'whole'):
            responses = (
                [{**person, 'experiences': [item]}, person]
                if mode == 'whole'
                else [person, {'experiences': [item]}]
            )
            hashes, providers = [], []
            for config in (None, settings(), settings(dates='v1')):
                provider = FakeProvider(responses)
                result = extract(document(T), provider, mode=mode, config=config)
                self.assertFalse(result.issues)
                serialized = json.dumps(result.to_dict(), sort_keys=True).encode()
                hashes.append(hashlib.sha256(serialized).hexdigest())
                providers.append(provider)
            with self.subTest(mode=mode):
                self.assertEqual(len(set(hashes)), 1)
                for provider in providers[1:]:
                    self.assertEqual(provider.prompts, providers[0].prompts)
                    self.assertEqual(provider.request_options, providers[0].request_options)

    def test_run_summary_records_dates_flag(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'input'
            repo = root / 'repo'
            source.mkdir()
            repo.mkdir()
            (source / 'synthetic.json').write_text(
                json.dumps({'pages': [{'page_number': 1, 'status': 'ok', 'raw_text': ''}]}),
                encoding='utf-8',
            )
            report = run_batch(
                source,
                root / 'output',
                FakeProvider(personal()),
                config=configured(CVC_LLM_DATES='v2'),
                repo_root=repo,
            )
            self.assertEqual(report['dates'], 'v2')
