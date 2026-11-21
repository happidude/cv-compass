"""O10 lines-2 acceptance with invented source text and offline providers."""

import hashlib
import json
import unittest

from test_core import document, experience, personal
from test_o5_lines import line_personal, numbered, position, settings
from test_pull import claim

from cv_compass_llm.pipeline import extract
from cv_compass_llm.prompts import (
    CLEAN_NAME_LINES_INSTRUCTIONS,
    build_experience_prompt,
    build_lines_prompt,
    build_personal_prompt,
    build_whole_prompt,
    prompt_version,
)
from cv_compass_llm.providers import FakeProvider
from cv_compass_llm.pull_payload import compact_result
from cv_compass_llm.schema import Evidence, Experience, FieldValue, Issue
from cv_compass_llm.validate import validate_line_experiences, validate_line_personal

T = 'Synthetic Person\nExperience\nRole-Z at Org-Z\n01/2020 - 02/2021'


def validate_row(source, **changes):
    row = position(period=None)
    row.update(changes)
    return validate_line_experiences(json.dumps({'experiences': [row]}), numbered(source))


def validate_fields(source, **changes):
    return validate_line_personal(json.dumps(line_personal(**changes)), numbered(source))


class SubstringTests(unittest.TestCase):
    def test_title_and_company_evidence_are_only_the_requested_values(self):
        source = 'Position: Data Analyst at Synthetic Works from July 2022 to Oct 2023.'
        rows, issues = validate_row(
            source,
            title={'line': 1, 'value': 'data analyst'},
            company={'line': 1, 'value': 'synthetic works'},
        )
        self.assertFalse(issues)
        self.assertEqual((rows[0].title, rows[0].company), ('Data Analyst', 'Synthetic Works'))
        for key, value in (('title', 'Data Analyst'), ('company', 'Synthetic Works')):
            start = source.index(value)
            self.assertEqual(rows[0].evidence[key], Evidence(1, start, start + len(value), value))

    def test_experience_values_on_adjacent_lines_are_dropped(self):
        source = 'Engineer\nSynthetic Works\nUnrelated'
        rows, issues = validate_row(
            source,
            title={'line': 2, 'value': 'Engineer'},
            company={'line': 3, 'value': 'Synthetic Works'},
        )
        self.assertFalse(rows)
        self.assertEqual(
            issues,
            (
                Issue('QUOTE_NOT_FOUND', 'title'),
                Issue('QUOTE_NOT_FOUND', 'company'),
                Issue('EXPERIENCE_WITHOUT_ROLE'),
            ),
        )

    def test_personal_values_on_adjacent_lines_are_dropped(self):
        source = 'Synthetic Person\nCity North\nOther\n2000-04-03\nUnrelated'
        for key, value, line in (
            ('fullName', 'Synthetic Person', 2),
            ('location', 'City North', 3),
            ('gender', 'OTHER', 4),
            ('dateOfBirth', '2000-04-03', 5),
        ):
            item = {'line': line, 'value': value}
            if key == 'location':
                item['endLine'] = None
            with self.subTest(key=key):
                fields, issues = validate_fields(source, **{key: item})
                self.assertFalse(fields)
                self.assertEqual(issues, (Issue('QUOTE_NOT_FOUND', key),))

    def test_personal_evidence_excludes_labels_and_preserves_source_punctuation(self):
        source = 'Name: Synthetic O’Name\nAddress: City – North\nGender: Other\nDOB: 2000-04-03'
        fields, issues = validate_fields(
            source,
            fullName={'line': 1, 'value': "synthetic o'name"},
            location={'line': 2, 'endLine': None, 'value': 'city - north'},
            gender={'line': 3, 'value': 'OTHER'},
            dateOfBirth={'line': 4, 'value': '2000-04-03'},
        )
        self.assertFalse(issues)
        self.assertEqual(len(fields), 4)
        for field, value in zip(
            fields, ('Synthetic O’Name', 'City – North', 'Other', '2000-04-03'), strict=True
        ):
            start = source.index(value)
            self.assertEqual(field.evidence, Evidence(1, start, start + len(value), value))
        self.assertEqual(fields[2].value, 'OTHER')

    def test_code_does_not_remove_model_supplied_labels(self):
        source = 'Position: Engineer\nCompany: Synthetic Works\nAddress: City North'
        rows, issues = validate_row(
            source,
            title={'line': 1, 'value': 'Position: Engineer'},
            company={'line': 2, 'value': 'Company: Synthetic Works'},
        )
        self.assertFalse(issues)
        self.assertEqual(rows[0].title, 'Position: Engineer')
        self.assertEqual(rows[0].company, 'Company: Synthetic Works')
        fields, issues = validate_fields(
            source, location={'line': 3, 'endLine': None, 'value': 'Address: City North'}
        )
        self.assertFalse(issues)
        self.assertEqual(fields[0].value, 'Address: City North')

    def test_generic_name_heading_is_still_rejected(self):
        fields, issues = validate_fields('Name: Résumé', fullName={'line': 1, 'value': 'résumé'})
        self.assertFalse(fields)
        self.assertEqual(issues, (Issue('QUOTE_NOT_FOUND', 'fullName'),))

    def test_birth_date_uses_only_the_matched_value(self):
        source = 'DOB: 03/04/2000; other text'
        fields, issues = validate_fields(source, dateOfBirth={'line': 1, 'value': '03/04/2000'})
        self.assertEqual(fields[0].value, '2000-04-03')
        self.assertEqual(issues, (Issue('DOB_DAY_FIRST_ASSUMED', 'dateOfBirth'),))
        self.assertEqual(fields[0].evidence, Evidence(1, 5, 15, '03/04/2000'))

    def test_birth_date_rejects_multiple_source_tokens_even_when_one_value_matches(self):
        for source, value in (
            ('DOB: 2000-04-03 / 2001-05-04', '2000-04-03'),
            ('DOB: 03/04/2000; other date 2001-05-04', '03/04/2000'),
            ('DOB: 2000-04-03 or 2000-04-03', '2000-04-03'),
        ):
            with self.subTest(source=source):
                fields, issues = validate_fields(source, dateOfBirth={'line': 1, 'value': value})
                self.assertFalse(fields)
                self.assertEqual(issues, (Issue('QUOTE_NOT_FOUND', 'dateOfBirth'),))


class GenderTests(unittest.TestCase):
    def test_explicit_gender_tokens_are_normalized_with_token_only_evidence(self):
        for source, token, value in (
            ('Giới tính: Nam', 'Nam', 'MALE'),
            ('Nữ', 'Nữ', 'FEMALE'),
            ('Khác', 'Khác', 'OTHER'),
            ('Gender: Female', 'Female', 'FEMALE'),
            ('Gender: Male', 'Male', 'MALE'),
            ('Gender: Other', 'Other', 'OTHER'),
            ('  Giới tính : Nu\u031b\u0303  ', 'Nu\u031b\u0303', 'FEMALE'),
        ):
            with self.subTest(source=source):
                fields, issues = validate_fields(source, gender={'line': 1, 'value': value})
                self.assertFalse(issues)
                self.assertEqual(len(fields), 1)
                start = source.index(token)
                self.assertEqual(
                    fields[0],
                    FieldValue('gender', value, Evidence(1, start, start + len(token), token)),
                )

    def test_female_never_supports_male_and_gender_prose_is_rejected(self):
        for source in ('Female', 'Gender: Female', 'Female engineer'):
            with self.subTest(source=source):
                fields, issues = validate_fields(source, gender={'line': 1, 'value': 'MALE'})
                self.assertFalse(fields)
                self.assertEqual(issues, (Issue('QUOTE_NOT_FOUND', 'gender'),))

    def test_gender_tokens_on_adjacent_lines_are_rejected(self):
        for source, line in (('Giới tính: Nam\nUnrelated', 2), ('Unrelated\nNữ', 1)):
            value = 'MALE' if line == 2 else 'FEMALE'
            with self.subTest(line=line):
                fields, issues = validate_fields(source, gender={'line': line, 'value': value})
                self.assertFalse(fields)
                self.assertEqual(issues, (Issue('QUOTE_NOT_FOUND', 'gender'),))


class LocationTests(unittest.TestCase):
    def test_wrapped_address_evidence_spans_only_address_parts(self):
        source = 'Address: 12 Invented Road,\r\n  Fictional City; phone: 0900000000'
        value = '12 Invented Road, Fictional City'
        fields, issues = validate_fields(source, location={'line': 1, 'endLine': 2, 'value': value})
        expected = '12 Invented Road,\r\n  Fictional City'
        start = source.index('12')
        self.assertFalse(issues)
        self.assertEqual(fields[0].value, expected)
        self.assertEqual(fields[0].evidence, Evidence(1, start, start + len(expected), expected))
        self.assertFalse(fields[0].additional_evidence)

    def test_wrapped_address_without_end_line_is_dropped(self):
        fields, issues = validate_fields(
            'Address: 12 Invented Road,\nFictional City',
            location={'line': 1, 'endLine': None, 'value': '12 Invented Road, Fictional City'},
        )
        self.assertFalse(fields)
        self.assertEqual(issues, (Issue('QUOTE_NOT_FOUND', 'location'),))

    def test_location_ranges_require_contiguous_lines_on_one_page_with_three_line_limit(self):
        for sources, end in (
            (('Address: Road\n\nCity',), 2),
            (('Address: Road', 'City'), 2),
            (('Address: Road\nCity\nRegion\nCountry',), 4),
            (('Address: Road\nCity',), 0),
            (('Address: Road\nCity',), 99),
        ):
            table = numbered(*sources)
            value = ' '.join(span.text.strip() for span in table.values()).removeprefix('Address: ')
            with self.subTest(end=end, pages=len(sources)):
                fields, issues = validate_line_personal(
                    json.dumps(line_personal(location={'line': 1, 'endLine': end, 'value': value})),
                    table,
                )
                self.assertFalse(fields)
                self.assertEqual(issues, (Issue('QUOTE_NOT_FOUND', 'location'),))

    def test_three_line_address_is_supported(self):
        source = 'Address: Road\nCity\nCountry; other text'
        fields, issues = validate_fields(
            source, location={'line': 1, 'endLine': 3, 'value': 'Road City Country'}
        )
        self.assertFalse(issues)
        self.assertEqual(fields[0].evidence, Evidence(1, 9, 26, 'Road\nCity\nCountry'))

    def test_location_cannot_match_only_a_later_line_in_range(self):
        for value in ('City North', '\nCity North'):
            fields, issues = validate_fields(
                'Wrong\nCity North', location={'line': 1, 'endLine': 2, 'value': value}
            )
            self.assertFalse(fields)
            self.assertEqual(issues, (Issue('QUOTE_NOT_FOUND', 'location'),))


class PeriodTests(unittest.TestCase):
    def test_continued_period_matches_only_date_phrase(self):
        source = 'Engineer\nSynthetic Works\nDates: July 2022 –\nOct 2023; other text'
        for dates in ('v1', 'v2'):
            row = position(period_text='July 2022 – Oct 2023')
            rows, issues = validate_line_experiences(
                json.dumps({'experiences': [row]}), numbered(source), dates=dates
            )
            with self.subTest(dates=dates):
                self.assertFalse(issues)
                row = rows[0]
                self.assertEqual((row.start, row.end, row.ongoing), ('2022-07', '2023-10', False))
                value = 'July 2022 –\nOct 2023'
                start = source.index('July')
                self.assertEqual(
                    row.evidence['period'], Evidence(1, start, start + len(value), value)
                )

    def test_v2_rejects_continuation_line_with_multiple_dates_and_v1_is_unchanged(self):
        source = 'Engineer\nSynthetic Works\nDates: July 2022 –\nOct 2023; other text 2024'
        row = position(period_text='July 2022 – Oct 2023')
        payload = json.dumps({'experiences': [row]})
        rows, issues = validate_line_experiences(payload, numbered(source), dates='v1')
        self.assertFalse(issues)
        self.assertEqual((rows[0].start, rows[0].end), ('2022-07', '2023-10'))
        self.assertEqual(rows[0].evidence['period'].text, 'July 2022 –\nOct 2023')
        rows, issues = validate_line_experiences(payload, numbered(source), dates='v2')
        self.assertEqual(issues, (Issue('QUOTE_NOT_FOUND', 'period'),))
        self.assertEqual((rows[0].start, rows[0].end, rows[0].ongoing), (None, None, False))
        self.assertNotIn('period', rows[0].evidence)

    def test_period_on_an_adjacent_line_is_dropped(self):
        source = 'Engineer\nSynthetic Works\nWrong\n2020 - 2022'
        rows, issues = validate_row(source, period={'line': 3, 'text': '2020 - 2022'})
        self.assertEqual(issues, (Issue('QUOTE_NOT_FOUND', 'period'),))
        self.assertEqual((rows[0].start, rows[0].end), (None, None))
        self.assertNotIn('period', rows[0].evidence)

    def test_period_match_cannot_start_on_the_following_line(self):
        source = 'Engineer\nSynthetic Works\n2019 –\n2020 - 2022'
        for text in ('2020 - 2022', '\n2020 - 2022'):
            rows, issues = validate_row(source, period={'line': 3, 'text': text})
            self.assertEqual(issues, (Issue('QUOTE_NOT_FOUND', 'period'),))
            self.assertIsNone(rows[0].start)

    def test_period_continuation_requires_an_allowed_boundary(self):
        source = 'Engineer\nSynthetic Works\n2020\nBuilt in 2022'
        rows, issues = validate_row(source, period={'line': 3, 'text': '2020 Built in 2022'})
        self.assertEqual(issues, (Issue('QUOTE_NOT_FOUND', 'period'),))
        self.assertIsNone(rows[0].start)

    def test_dates_and_present_marker_outside_matched_text_are_not_used(self):
        source = 'Engineer\nSynthetic Works\n2020 - 2022; Present; also 2023'
        rows, issues = validate_row(source, period={'line': 3, 'text': '2020 - 2022'}, ongoing=True)
        self.assertFalse(issues)
        self.assertEqual((rows[0].start, rows[0].end, rows[0].ongoing), ('2020', '2022', False))
        self.assertEqual(rows[0].evidence['period'].text, '2020 - 2022')

    def test_present_marker_in_matched_text_drives_ongoing(self):
        source = 'Engineer\nSynthetic Works\nDates: 2020 - Present; other text'
        rows, issues = validate_row(source, period={'line': 3, 'text': '2020 - Present'})
        self.assertFalse(issues)
        self.assertEqual((rows[0].start, rows[0].end, rows[0].ongoing), ('2020', None, True))
        self.assertEqual(rows[0].evidence['period'].text, '2020 - Present')


class ContractTests(unittest.TestCase):
    def test_quote_prompt_hashes_remain_frozen(self):
        for builder, options, expected in (
            (
                build_experience_prompt,
                {},
                '02006f1cc2543a66b28b9e3f67490160d78dc9e75619a49378142998bb171d5b',
            ),
            (
                build_whole_prompt,
                {},
                '817ebee4c39aa00398b15ef681da2d5c7c6112b2f9946b0e2e0bafd77ab163ed',
            ),
            (
                build_personal_prompt,
                {},
                '004aa5841a83447adc1ed9f822fbfca341405a95078e6fe86623540e77a72521',
            ),
            (
                build_experience_prompt,
                {'name_style': 'clean'},
                'dc04d2445a956c1638cd53a48c0d80fbd5740dc7fd2e468777bddfbbb6a9dd7b',
            ),
            (
                build_whole_prompt,
                {'name_style': 'clean'},
                '701542faedd928238a3bee7478d681ac17b665d5f477382f23f35f24cfa0b430',
            ),
        ):
            with self.subTest(builder=builder.__name__, options=options):
                self.assertEqual(
                    hashlib.sha256(builder(T, **options).encode()).hexdigest(), expected
                )

    def test_lines_versions_and_clean_instructions(self):
        self.assertIn(
            'values must come from the single referenced line.', CLEAN_NAME_LINES_INSTRUCTIONS
        )
        self.assertNotIn('if a name breaks across a line', CLEAN_NAME_LINES_INSTRUCTIONS)
        for mode, version in (('blocks', 'lines-2'), ('whole', 'whole-lines-2')):
            for style in ('raw', 'clean'):
                suffix = '-clean' if style == 'clean' else ''
                expected = 'llm-extraction-prompt/' + version + suffix
                self.assertEqual(prompt_version(mode, settings(name_style=style)), expected)
                prompt = build_lines_prompt('[L1] Position: Engineer', name_style=style)
                self.assertEqual(CLEAN_NAME_LINES_INSTRUCTIONS in prompt, style == 'clean')

    def test_prompts_request_short_values_without_labels_or_contacts(self):
        experience_prompt = build_lines_prompt('[L1] Invented source', schema_text=False)
        personal_prompt = build_lines_prompt(
            '[L1] Invented source', personal=True, schema_text=False
        )
        self.assertIn('{line, value}', experience_prompt)
        self.assertIn('WITHOUT labels such as Position: or Company:', experience_prompt)
        self.assertIn('never the whole sentence or line with other text', experience_prompt)
        self.assertIn('verbatim date phrase', experience_prompt)
        self.assertIn('WITHOUT labels such as Address:', personal_prompt)
        self.assertIn('endLine', personal_prompt)
        self.assertIn('never include phone numbers or emails in location', personal_prompt)
        self.assertIn('Nam/Nữ/Khác/Male/Female/Other', personal_prompt)
        self.assertIn('token on exactly that line', personal_prompt)
        self.assertIn('value normalized to MALE/FEMALE/OTHER from that token', personal_prompt)

    def test_lines_one_shapes_are_rejected(self):
        row = position()
        row['title'] = {'line': 1, 'text': 'Engineer'}
        rows, issues = validate_line_experiences(
            json.dumps({'experiences': [row]}), numbered('Engineer\nSynthetic Works\n2020 - 2022')
        )
        self.assertFalse(rows)
        self.assertEqual(issues, (Issue('LLM_INVALID_JSON'),))
        response = line_personal()
        response['personal']['dateOfBirthLine'] = response['personal'].pop('dateOfBirth')
        fields, issues = validate_line_personal(json.dumps(response), numbered('Synthetic Person'))
        self.assertFalse(fields)
        self.assertEqual(issues, (Issue('LLM_INVALID_JSON'),))

    def test_lines_and_quote_keep_identical_records_and_backend_payload_shape(self):
        source = (
            'Name: Synthetic Person\nExperience\nPosition: Engineer\n'
            'Company: Synthetic Works\nDates: 2020 - 2022'
        )
        header = personal(fullName={'value': 'Synthetic Person', 'quote': 'Synthetic Person'})
        row = experience(
            title='Engineer',
            company='Synthetic Works',
            start='2020',
            end='2022',
            period='2020 - 2022',
        )
        line_header = line_personal(fullName={'line': 1, 'value': 'Synthetic Person'})
        line_row = position(3, 4, 5)
        for mode in ('blocks', 'whole'):
            quoted = {'experiences': [row]}
            if mode == 'whole':
                quoted.update(personal())
            quotes = [header, quoted] if mode == 'blocks' else [quoted, header]
            lines = [line_header, {'experiences': [line_row]}]
            if mode == 'whole':
                lines.reverse()
            quote_result = extract(document(source), FakeProvider(quotes), mode=mode)
            line_result = extract(
                document(source), FakeProvider(lines), mode=mode, config=settings()
            )
            with self.subTest(mode=mode):
                self.assertFalse(quote_result.issues)
                self.assertFalse(line_result.issues)
                self.assertEqual(line_result.fields, quote_result.fields)
                self.assertEqual(line_result.experiences, quote_result.experiences)
                self.assertIsInstance(line_result.fields[0], FieldValue)
                self.assertIsInstance(line_result.experiences[0], Experience)
                self.assertEqual(
                    json.loads(compact_result(line_result, claim(), {})),
                    json.loads(compact_result(quote_result, claim(), {})),
                )
