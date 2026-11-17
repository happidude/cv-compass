"""E7 date and payload acceptance using synthetic source text only."""

import json
import unittest
from dataclasses import asdict

from test_core import block, document, experience, personal, response
from test_pull import claim

from cv_compass_llm import extract
from cv_compass_llm.dates import find_birth_dates, find_dates, parse_birth_date, parse_date
from cv_compass_llm.providers import FakeProvider
from cv_compass_llm.pull_payload import compact_result
from cv_compass_llm.schema import Evidence, Experience, ExtractionResult, FieldValue
from cv_compass_llm.validate import validate_experiences, validate_personal


class EnglishDateTests(unittest.TestCase):
    def test_dashed_month_year_tokens_and_period_evidence(self):
        for text, expected in (
            ('Jan-2019', '2019-01'),
            ('Jan -2019', '2019-01'),
            ('Mar- 2016', '2016-03'),
            ('Feb – 2017', '2017-02'),
            ('Apr - 2016', '2016-04'),
            ('Sep-2011', '2011-09'),
            ('Oct—2020', '2020-10'),
            ('Nov — 2020', '2020-11'),
        ):
            with self.subTest(text=text):
                self.assertEqual(parse_date(text), expected)
                self.assertEqual(find_dates('From: ' + text + ' To Now'), [expected])
                period = 'From: ' + text + ' To Now'
                values, issues = validate_experiences(
                    response(
                        experience(
                            start=expected,
                            end='Now',
                            period=period,
                        )
                    ),
                    block('Role-Z Org-Z ' + period),
                )
                self.assertFalse(issues)
                self.assertEqual(
                    (values[0].start, values[0].end, values[0].ongoing), (expected, None, True)
                )
                self.assertEqual(values[0].evidence['period'].text, period)

        period = 'From: Sep-2011 to Feb – 2017'
        self.assertEqual(find_dates(period), ['2011-09', '2017-02'])
        values, issues = validate_experiences(
            response(
                experience(
                    start='2011-09',
                    end='2017-02',
                    period=period,
                )
            ),
            block('Role-Z Org-Z ' + period),
        )
        self.assertFalse(issues)
        self.assertEqual(
            (values[0].start, values[0].end, values[0].ongoing), ('2011-09', '2017-02', False)
        )

    def test_period_dates_keep_month_precision_and_require_matching_evidence(self):
        self.assertEqual(find_dates('Jan 15, 2019'), ['2019-01'])
        values, issues = validate_experiences(
            response(
                experience(
                    start='2011',
                    end=None,
                    period='Sep-2011',
                )
            ),
            block('Role-Z Org-Z Sep-2011'),
        )
        self.assertEqual(
            [(issue.code, issue.field) for issue in issues], [('QUOTE_NOT_FOUND', 'start')]
        )
        self.assertEqual((values[0].start, values[0].end, values[0].ongoing), (None, None, False))
        self.assertNotIn('period', values[0].evidence)

    def test_year_ranges_and_two_digit_years_remain_conservative(self):
        for separator in ('-', '–', '—'):
            text = '2016 ' + separator + ' 2017'
            with self.subTest(text=text):
                self.assertIsNone(parse_date(text))
                self.assertEqual(find_dates(text), ['2016', '2017'])
        for text in (
            'Jan-19',
            'Jan -19',
            'Mar–16',
            'Sep—11',
            'July 28th,82',
            '28th July 82',
            'Jul 28, 82',
            'July 28 82',
        ):
            with self.subTest(text=text):
                self.assertIsNone(parse_date(text))
                self.assertEqual(parse_birth_date(text), (None, False))
                self.assertEqual(find_dates(text), [])
                self.assertEqual(find_birth_dates(text), [])

    def test_english_birth_days_keep_precision_without_day_first_assumption(self):
        for text in (
            'July 28th,1982',
            '28th July 1982',
            'Jul 28, 1982',
            'July 28 1982',
            '28th July, 1982',
            '28th Jul. 1982',
            '28th Jul., 1982',
        ):
            with self.subTest(text=text):
                self.assertEqual(parse_birth_date(text), ('1982-07-28', False))
                self.assertEqual(find_birth_dates('DOB: ' + text), [('1982-07-28', False)])
                for value in (text, '1982-07-28'):
                    fields, issues = validate_personal(
                        json.dumps(
                            personal(
                                dateOfBirth={'value': value, 'quote': text},
                            )
                        ),
                        block('DOB: ' + text),
                    )
                    self.assertFalse(issues)
                    self.assertEqual(fields[0].value, '1982-07-28')
                    self.assertEqual(fields[0].evidence.text, text)

    def test_english_birth_days_require_valid_calendar(self):
        for text, expected in (
            ('February 29th,2000', '2000-02-29'),
            ('29th February 2000', '2000-02-29'),
            ('February 29th,1990', None),
            ('29th February 1990', None),
            ('February 31, 1990', None),
            ('31st April 1982', None),
            ('July 0th,1982', None),
            ('32nd July 1982', None),
        ):
            with self.subTest(text=text):
                self.assertEqual(parse_birth_date(text), (expected, False))
                self.assertEqual(
                    find_birth_dates('DOB: ' + text), [(expected, False)] if expected else []
                )


class BirthDatePayloadTests(unittest.TestCase):
    def test_english_birth_date_validates_and_reaches_compact_payload(self):
        text = 'DOB: July 28th,1982'
        quote = 'July 28th,1982'
        provider = FakeProvider(
            {
                **personal(dateOfBirth={'value': quote, 'quote': quote}),
                'experiences': [],
            }
        )
        result = extract(document(text), provider, mode='whole')
        self.assertFalse(result.issues)
        self.assertEqual(
            [(field.key, field.value) for field in result.fields], [('dateOfBirth', '1982-07-28')]
        )
        payload = json.loads(compact_result(result, claim(), {}))
        evidence = Evidence(1, len('DOB: '), len(text), quote)
        self.assertEqual(
            payload['personal']['dateOfBirth'],
            {'value': '1982-07-28', 'evidence': asdict(evidence)},
        )
        self.assertFalse(payload['issues'])

    def test_compact_birth_date_calendar_and_year_bounds(self):
        for value, valid in (
            ('1975-08-14', True),
            ('1900-01-01', True),
            ('2100-12-31', True),
            ('2000-02-29', True),
            ('1990-02-31', False),
            ('1990-02-29', False),
            ('1900-02-29', False),
            ('2100-02-29', False),
            ('1899-01-01', False),
            ('2101-01-01', False),
            ('2000-00-01', False),
            ('2000-13-01', False),
            ('2000-01-00', False),
            ('2000-1-01', False),
            ('2000-01-1', False),
        ):
            with self.subTest(value=value):
                text = 'DOB: ' + value
                evidence = Evidence(1, 0, len(text), text)
                result = ExtractionResult(
                    (FieldValue('dateOfBirth', value, evidence),), (), (), 'synthetic', 'fake'
                )
                payload = json.loads(compact_result(result, claim(), {}))
                if valid:
                    self.assertEqual(
                        payload['personal']['dateOfBirth'],
                        {'value': value, 'evidence': asdict(evidence)},
                    )
                    self.assertFalse(payload['issues'])
                else:
                    self.assertIsNone(payload['personal']['dateOfBirth'])
                    self.assertEqual(
                        payload['issues'], [{'code': 'INVALID_DATE', 'field': 'dateOfBirth'}]
                    )

    def test_compact_period_still_rejects_full_dates_on_either_endpoint(self):
        for start, end in (('1975-08-14', '2000'), ('1975', '2000-02-29')):
            with self.subTest(start=start, end=end):
                evidence = Evidence(1, 0, 1, 'x')
                item = Experience(
                    'x', None, start, end, False, {'title': evidence, 'period': evidence}
                )
                result = ExtractionResult((), (item,), (), 'synthetic', 'fake')
                payload = json.loads(compact_result(result, claim(), {}))
                row = payload['experiences'][0]
                self.assertEqual((row['start'], row['end'], row['ongoing']), (None, None, False))
                self.assertEqual(row['evidence'], {'title': asdict(evidence)})
                self.assertEqual(
                    payload['issues'], [{'code': 'INVALID_DATE', 'field': 'experiences'}]
                )
