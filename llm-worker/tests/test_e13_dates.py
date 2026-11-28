"""E13 slice A acceptance using invented, offline date evidence only."""

import json
import unittest

from test_a1_clean_names import ENV
from test_core import block, experience, response
from test_o5_lines import numbered, position

from cv_compass_llm.config import BENCH_ENV_KEYS, ENV_KEYS, load_config
from cv_compass_llm.dates import find_birth_dates, find_dates, parse_birth_date, parse_date
from cv_compass_llm.schema import Issue
from cv_compass_llm.validate import validate_experiences, validate_line_experiences

ENGLISH_CASES = (
    ('Decem 2020', '2020-12'),
    ('Septem 2020', '2020-09'),
    ('Febr 2020', '2020-02'),
    ('Novem 2020', '2020-11'),
    ('Arp 2020', '2020-04'),
    ('Mya 2020', '2020-05'),
    ('Jna 2020', '2020-01'),
)
SPACED_CASES = (
    ('Tháng 3/ 2020', '2020-03'),
    ('tháng 12 / 2019', '2019-12'),
    ('T3/ 2020', '2020-03'),
    ('03/ 2020', '2020-03'),
    ('3 - 2020', '2020-03'),
)


def quote_period(period, start, end=None, ongoing=False, *, style='v3'):
    return validate_experiences(
        response(experience(start=start, end=end, ongoing=ongoing, period=period)),
        block('Role-Z Org-Z ' + period),
        dates=style,
    )


class TolerantDateTests(unittest.TestCase):
    def test_new_tokens_are_additive_and_parseable(self):
        for text, expected in ENGLISH_CASES + SPACED_CASES:
            with self.subTest(text=text):
                year = expected[:4]
                self.assertEqual(parse_date(text, style='v3'), expected)
                self.assertEqual(find_dates(text, style='v3'), [expected, year])
                self.assertIsNone(parse_date(text, style='v2'))
                self.assertEqual(find_dates(text, style='v2'), [year])
                self.assertEqual(find_dates(text, style='v1'), [year])
                for value in (expected, year):
                    rows, issues = quote_period(text, value)
                    self.assertFalse(issues)
                    self.assertEqual(rows[0].start, value)

    def test_english_separators_case_and_word_boundaries(self):
        for separator in (' ', '. ', ', ', '., ', '-', ' – ', '—', "'-"):
            text = 'DECEM' + separator + '2020'
            with self.subTest(separator=separator):
                self.assertEqual(parse_date(text, style='v3'), '2020-12')
                self.assertEqual(find_dates(text, style='v3'), ['2020-12', '2020'])
        for text in ('Arm 2020', 'Ram 2020', 'Mayor 2020', 'Academ 2020', 'xDecem 2020'):
            with self.subTest(text=text):
                self.assertIsNone(parse_date(text, style='v3'))
                self.assertEqual(find_dates(text, style='v3'), ['2020'])
        for text in ('Decem', 'Arp', 'Decem 20', 'Decem2020', 'Decem 20200', 'Decem 2020x'):
            with self.subTest(text=text):
                self.assertIsNone(parse_date(text, style='v3'))
                self.assertEqual(find_dates(text, style='v3'), [])

    def test_ranges_keep_source_order_without_nested_numeric_fragments(self):
        for text, expected in (
            ('2018 - 2020', ['2018', '2020']),
            ('03/2019 - 05/2020', ['2019-03', '2020-05']),
            ('08 .2022 - 09. 2023', ['2022-08', '2023-09']),
            ('2020-01 - 2022-02', ['2020-01', '2022-02']),
            ('May 21, 2019', ['2019-05']),
            ('May 21 – Jul 23', ['2021-05', '2023-07']),
            ('Page 12. 2022', []),
            ('12.08. 2022', []),
            ('+84 90 812 2022', []),
        ):
            with self.subTest(text=text):
                self.assertEqual(find_dates(text, style='v2', current_year=2026), expected)
                self.assertEqual(find_dates(text, style='v3', current_year=2026), expected)
        self.assertEqual(
            find_dates('Arp 2019 - Decem 2020', style='v3'),
            ['2019-04', '2019', '2020-12', '2020'],
        )

    def test_no_missing_part_inference_or_wrong_explicit_month(self):
        for period, start in (
            ('Decem', '2020-12'),
            ('Decem 2020', '2019-12'),
            ('Decem 2020', '2020-04'),
            ('Apr 2020', '2020-05'),
            ('2020', '2021-03'),
        ):
            with self.subTest(period=period, start=start):
                rows, issues = quote_period(period, start)
                self.assertIsNone(rows[0].start)
                self.assertIn(Issue('QUOTE_NOT_FOUND', 'start'), issues)
        for period in ('Arm 2020', 'Ram 2020', 'Mayor 2020', '2020'):
            with self.subTest(period=period):
                rows, issues = quote_period(period, '2020-03')
                self.assertFalse(issues)
                self.assertEqual(rows[0].start, '2020')
        rows, issues = quote_period('2020; Apr 2020', '2020-05')
        self.assertFalse(issues)
        self.assertEqual(rows[0].start, '2020')

    def test_birth_date_parser_stays_v1(self):
        for text, _ in ENGLISH_CASES + SPACED_CASES:
            with self.subTest(text=text):
                self.assertEqual(parse_birth_date(text), (None, False))
                self.assertEqual(find_birth_dates(text), [(text[-4:], False)])


class OpenPeriodTests(unittest.TestCase):
    def test_quote_open_period_requires_model_ongoing_and_null_end(self):
        for token, start in (
            ('08.2022', '2022-08'),
            ('03/2021', '2021-03'),
            ('2020', '2020'),
            ('Decem 2020', '2020-12'),
            ('Tháng 3/ 2020', '2020-03'),
        ):
            for separator in ('-', '–', '—', '~', '→'):
                period = token + ' ' + separator + ' \t'
                with self.subTest(period=period):
                    rows, issues = quote_period(period, start, ongoing=True)
                    self.assertFalse(issues)
                    self.assertEqual(
                        (rows[0].start, rows[0].end, rows[0].ongoing), (start, None, True)
                    )
                    self.assertEqual(rows[0].evidence['period'].text, period)
                    rows, issues = quote_period(period, start)
                    self.assertFalse(issues)
                    self.assertFalse(rows[0].ongoing)
                    for end in (start, '2024', 'present'):
                        rows, issues = quote_period(period, start, end, ongoing=True)
                        self.assertIn(Issue('QUOTE_NOT_FOUND', 'end'), issues)
                        self.assertIsNone(rows[0].end)
                        self.assertFalse(rows[0].ongoing)

    def test_nonterminal_or_unattached_separators_are_not_ongoing(self):
        for period in (
            '08.2022 - 2023',
            '08.2022 - unfinished',
            '08.2022 note -',
            '- 08.2022',
            '08.2022 --',
            '08.2022 - 2023 -',
        ):
            with self.subTest(period=period):
                rows, issues = quote_period(period, '2022-08', ongoing=True)
                self.assertIn(Issue('QUOTE_NOT_FOUND', 'ongoing'), issues)
                self.assertFalse(rows[0].ongoing)
        rows, issues = quote_period('08.2022 - 2023', '2022-08', '2023')
        self.assertFalse(issues)
        self.assertEqual((rows[0].start, rows[0].end), ('2022-08', '2023'))

    def test_legacy_quote_modes_and_lines_do_not_infer_ongoing(self):
        for style in ('v1', 'v2'):
            with self.subTest(style=style):
                rows, issues = quote_period('08.2022 -', '2022-08', style=style)
                self.assertFalse(issues)
                self.assertFalse(rows[0].ongoing)
                rows, issues = quote_period('08.2022 -', '2022-08', ongoing=True, style=style)
                self.assertIn(Issue('QUOTE_NOT_FOUND', 'ongoing'), issues)
        for period, start in (('08.2022 -', '2022-08'), ('Decem 2020 →', '2020-12')):
            for ongoing in (False, True):
                with self.subTest(period=period, ongoing=ongoing):
                    rows, issues = validate_line_experiences(
                        json.dumps(
                            {'experiences': [position(period_text=period, ongoing=ongoing)]}
                        ),
                        numbered('Engineer\nSynthetic Works\n' + period),
                        dates='v3',
                    )
                    self.assertFalse(issues)
                    self.assertEqual(
                        (rows[0].start, rows[0].end, rows[0].ongoing), (start, None, False)
                    )

    def test_lines_accept_new_tokens_without_counting_year_fallbacks_as_endpoints(self):
        for period, start, end in (
            ('Arp 2020', '2020-04', None),
            ('tháng 12 / 2019 - T3/ 2020', '2019-12', '2020-03'),
            ('Decem 2020 - present', '2020-12', None),
        ):
            with self.subTest(period=period):
                rows, issues = validate_line_experiences(
                    json.dumps({'experiences': [position(period_text=period)]}),
                    numbered('Engineer\nSynthetic Works\n' + period),
                    dates='v3',
                )
                self.assertFalse(issues)
                self.assertEqual((rows[0].start, rows[0].end), (start, end))
                self.assertEqual(rows[0].ongoing, 'present' in period)


class DateConfigTests(unittest.TestCase):
    def test_v3_is_allowed_and_v4_keeps_invalid_dates_code(self):
        config = load_config(
            {},
            {**ENV, 'CVC_LLM_DATES': 'v3'},
            None,
            env_file_explicit=False,
            _file_values={},
        )
        self.assertEqual(config.dates, 'v3')
        self.assertEqual(ENV_KEYS['dates'], 'CVC_LLM_DATES')
        self.assertIn('CVC_LLM_DATES', BENCH_ENV_KEYS)
        with self.assertRaisesRegex(ValueError, '^INVALID_DATES$'):
            load_config(
                {},
                {**ENV, 'CVC_LLM_DATES': 'v4'},
                None,
                env_file_explicit=False,
                _file_values={},
            )
