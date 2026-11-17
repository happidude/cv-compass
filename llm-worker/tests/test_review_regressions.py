"""Regression coverage for the L1a review using synthetic OCR only."""

import json
import unittest

from test_core import block, document, experience, personal, response

from cv_compass_llm import Document, Page, extract
from cv_compass_llm.evidence import locate
from cv_compass_llm.json_strict import strict_json
from cv_compass_llm.providers import FakeProvider, ProviderError
from cv_compass_llm.segmenter import segment
from cv_compass_llm.validate import validate_experiences, validate_personal


class GenderRegressionTests(unittest.TestCase):
    def test_name_location_and_prose_cannot_supply_gender(self) -> None:
        for text, quote, value in (
            ('Trần Văn Nam', 'Nam', 'MALE'),
            ('Việt Nam', 'Nam', 'MALE'),
            ('miền Nam', 'Nam', 'MALE'),
            ('Phụ nữ', 'nữ', 'FEMALE'),
            ('Phụ Nữ', 'Nữ', 'FEMALE'),
            ('Gender: Male2', 'Male', 'MALE'),
            ('2Male', 'Male', 'MALE'),
            ('Giới tính: Nam sinh mẫu', 'Nam', 'MALE'),
        ):
            with self.subTest(case=quote):
                header, _, _ = segment(document(text))
                fields, issues = validate_personal(
                    json.dumps(
                        personal(
                            gender={'value': value, 'quote': quote},
                        )
                    ),
                    header,
                )
                self.assertFalse(fields)
                self.assertEqual(
                    [(issue.code, issue.field) for issue in issues], [('QUOTE_NOT_FOUND', 'gender')]
                )

    def test_explicit_gender_lines_and_label_separators(self) -> None:
        for line, quote, value in (
            ('Giới tính: Nữ', 'Nữ', 'FEMALE'),
            ('Male', 'Male', 'MALE'),
            ('  GiỚi TíNh - Nữ  ', 'Nữ', 'FEMALE'),
            ('Gender - Female', 'Gender - Female', 'FEMALE'),
            ('Gender:Other', 'Other', 'OTHER'),
            ('Nam', 'Nam', 'MALE'),
        ):
            with self.subTest(case=quote):
                text = 'Ứng viên mẫu Z\n' + line + '\nNơi ở mẫu'
                header, _, _ = segment(document(text))
                fields, issues = validate_personal(
                    json.dumps(
                        personal(
                            gender={'value': value, 'quote': quote},
                        )
                    ),
                    header,
                )
                self.assertFalse(issues)
                self.assertEqual(fields[0].value, value)
                ev = fields[0].evidence
                self.assertEqual(ev.text, text[ev.start : ev.end])

    def test_header_truncation_does_not_hide_gender_line_suffix(self) -> None:
        text = 'x' * 2995 + '\nMale synthetic suffix'
        header, _, _ = segment(document(text))
        self.assertTrue(header.text.endswith('Male'))
        fields, issues = validate_personal(
            json.dumps(
                personal(
                    gender={'value': 'MALE', 'quote': 'Male'},
                )
            ),
            header,
        )
        self.assertFalse(fields)
        self.assertEqual(issues[0].code, 'QUOTE_NOT_FOUND')


class PeriodRegressionTests(unittest.TestCase):
    def test_compact_ranges_and_vietnamese_present(self) -> None:
        for period, start, end, expected_start, expected_end, ongoing in (
            ('2019-2021', '2019', '2021', '2019', '2021', False),
            ('01/2020-03/2021', '2020-01', '2021-03', '2020-01', '2021-03', False),
            ('2019–2021', '2019', '2021', '2019', '2021', False),
            ('03/2020 - Hiện tại', '2020-03', 'Hiện tại', '2020-03', None, True),
        ):
            with self.subTest(period=period):
                text = 'Role-Z Org-Z\n' + period
                values, issues = validate_experiences(
                    response(
                        experience(
                            start=start,
                            end=end,
                            period=period,
                        )
                    ),
                    block(text),
                )
                self.assertFalse(issues)
                value = values[0]
                self.assertEqual(
                    (value.start, value.end, value.ongoing), (expected_start, expected_end, ongoing)
                )
                ev = value.evidence['period']
                self.assertEqual(ev.text, text[ev.start : ev.end])

    def test_day_precision_birth_date_is_retained_without_losing_precision(self) -> None:
        fields, issues = validate_personal(
            json.dumps(
                personal(
                    dateOfBirth={'value': '03/01/2000', 'quote': '03/01/2000'},
                )
            ),
            block('03/01/2000'),
        )
        self.assertEqual(fields[0].value, '2000-01-03')
        self.assertEqual(
            [(i.code, i.field) for i in issues], [('DOB_DAY_FIRST_ASSUMED', 'dateOfBirth')]
        )
        fields, issues = validate_personal(
            json.dumps(
                personal(
                    dateOfBirth={'value': '2000-01', 'quote': '03/01/2000'},
                )
            ),
            block('03/01/2000'),
        )
        self.assertFalse(fields)
        self.assertEqual(issues[0].code, 'QUOTE_NOT_FOUND')

    def test_roleless_experience_always_has_issue(self) -> None:
        values, issues = validate_experiences(
            response(
                experience(
                    title=None,
                    company=None,
                    start=None,
                    end=None,
                    period=None,
                )
            ),
            block('synthetic'),
        )
        self.assertFalse(values)
        self.assertEqual(
            [(issue.code, issue.field) for issue in issues], [('EXPERIENCE_WITHOUT_ROLE', None)]
        )


class OffsetAndPipelineRegressionTests(unittest.TestCase):
    def test_whitespace_quote_at_nonzero_offset_on_second_page(self) -> None:
        doc = document('header\nExperience\n', 'prefix\nRole-Z \t\n  Org-Z\n')
        _, blocks, issues = segment(doc)
        self.assertFalse(issues)
        ev = locate('Role-Z Org-Z', blocks[0])
        self.assertEqual(ev.page, 2)
        self.assertEqual(ev.start, len('prefix\n'))
        self.assertEqual(ev.text, 'Role-Z \t\n  Org-Z')
        self.assertEqual(ev.text, doc.pages[1].raw_text[ev.start : ev.end])

    def test_extract_preserves_second_page_evidence(self) -> None:
        doc = document('Ứng viên mẫu Z\nExperience\n', 'Ω\nRole-Z Org-Z\n2019-2021')
        provider = FakeProvider(
            [
                personal(),
                {
                    'experiences': [
                        experience(start='2019', end='2021', period='2019-2021'),
                    ]
                },
            ]
        )
        result = extract(doc, provider)
        self.assertFalse(result.issues)
        self.assertEqual(len(result.experiences), 1)
        for ev in result.experiences[0].evidence.values():
            self.assertEqual(ev.page, 2)
            self.assertEqual(ev.text, doc.pages[1].raw_text[ev.start : ev.end])

    def test_personal_provider_error_leaves_experience_extraction_running(self) -> None:
        provider = FakeProvider(
            [
                ProviderError('synthetic failure'),
                {'experiences': [experience()]},
            ]
        )
        result = extract(document('Experience\nRole-Z Org-Z 1/2020 - 2021-02'), provider)
        self.assertEqual(len(provider.prompts), 2)
        self.assertEqual(len(result.experiences), 1)
        self.assertEqual(
            [(issue.code, issue.field) for issue in result.issues], [('PROVIDER_ERROR', 'personal')]
        )

    def test_no_successful_page_means_no_provider_call(self) -> None:
        for doc in (
            Document(()),
            Document((Page(1, 'failed', 'unused'),)),
            Document((Page(1, 'blank', ''), Page(2, 'not_processed', ''))),
        ):
            provider = FakeProvider([])
            result = extract(doc, provider)
            self.assertEqual(provider.prompts, [])
            self.assertFalse(result.fields)
            self.assertFalse(result.experiences)
            self.assertFalse(any(issue.code == 'PROVIDER_ERROR' for issue in result.issues))

    def test_heading_section_over_limit_splits_at_lines(self) -> None:
        section = ('synthetic line ' + 'x' * 985 + '\n') * 13
        text = 'header\nWork Experience\n' + section + 'Skills\nexcluded'
        _, blocks, issues = segment(document(text))
        self.assertFalse(issues)
        self.assertGreater(len(blocks), 1)
        self.assertEqual(''.join(item.text for item in blocks), section)
        for item in blocks:
            self.assertLessEqual(len(item.text), 6000)
            self.assertTrue(item.text.endswith('\n'))
            for span in item.spans:
                self.assertEqual(text[span.start : span.end], span.text)


class SharedJsonRegressionTests(unittest.TestCase):
    def test_shared_parser_rejects_duplicates_and_nonfinite_numbers(self) -> None:
        for text in ('{"key":1,"key":2}', '{"value":NaN}', '{"value":Infinity}'):
            with self.assertRaisesRegex(ValueError, '^INVALID_JSON$'):
                strict_json(text)
        self.assertEqual(strict_json('{"key":1}'), {'key': 1})


if __name__ == '__main__':
    unittest.main()
