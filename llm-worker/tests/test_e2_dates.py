"""E2 acceptance coverage using synthetic text only."""

import json
import unittest

from test_core import block, document, experience, personal, response

from cv_compass_llm.dates import find_dates, has_present, parse_date
from cv_compass_llm.evidence import locate
from cv_compass_llm.prompts import PROMPT_VERSION, build_experience_prompt
from cv_compass_llm.segmenter import Block, Span, segment
from cv_compass_llm.validate import supported, validate_experiences, validate_personal


class DateTests(unittest.TestCase):
    def test_formats_and_quote_tokens(self) -> None:
        cases = {'2020': '2020', '1950': '1950', '2100': '2100'}
        for text in (
            '5/2020', '05/2020', '05-2020', '05.2020', '2020-05', '2020/05',
            '2020.05', 'T5/2020', 'T05-2020', 'Tháng 5/2020', 'tháng 05 năm 2020',
            '15/05/2020', '15-05-2020', '15.05.2020', '2020-05-15',
        ):
            cases[text] = '2020-05'
        cases.update({'Jan 2021': '2021-01', 'JANUARY 2021': '2021-01',
                      'Sept. 2021': '2021-09', 'Jan, 2021': '2021-01'})
        for text, expected in cases.items():
            with self.subTest(text=text):
                self.assertEqual(parse_date(text), expected)
                self.assertEqual(find_dates('From ' + text + ' onward'), [expected])
        self.assertEqual(find_dates('Jan 2021; 05.2020; 2020'),
                         ['2021-01', '2020-05', '2020'])

    def test_all_english_months(self) -> None:
        for month, names in enumerate((
            ('January', 'Jan'), ('February', 'Feb'), ('March', 'Mar'), ('April', 'Apr'),
            ('May',), ('June', 'Jun'), ('July', 'Jul'), ('August', 'Aug'),
            ('September', 'Sep', 'Sept'), ('October', 'Oct'), ('November', 'Nov'),
            ('December', 'Dec'),
        ), 1):
            for name in names:
                for variant in (name, name.upper() + '.', name.lower() + ','):
                    text = variant + ' 2021'
                    with self.subTest(text=text):
                        self.assertEqual(parse_date(text), f'2021-{month:02d}')
                        self.assertEqual(find_dates(text), [f'2021-{month:02d}'])

    def test_invalid_tokens_do_not_leak_partial_dates(self) -> None:
        for text in (
            '05/20', "Jan '21", '13/2020', '2020-00', '0/2020', '1949', '2101',
            '31/02/2020', '2020-02-31', 'Tháng 13/2020', 'T0/2020',
            '2020-13', '2020/13', '32-05-2020', '05/2020/15',
        ):
            with self.subTest(text=text):
                self.assertIsNone(parse_date(text))
                self.assertEqual(find_dates(text), [])

    def test_numbers_are_not_dates(self) -> None:
        for text in (
            '2,000+', 'USD 150M', '0902020000', '+84 90 202 0000', '090-2020-000',
            '090.2020.000', '(202) 555-2020', 'ISO/IEC 27001:2013',
            'ISO 9001:2015', 'ID2020', '2020M', '2,020+', '9' * 4000 + 'M',
        ):
            with self.subTest(text=text):
                self.assertEqual(find_dates(text), [])

    def test_present_boundaries(self) -> None:
        for text in ('present', 'NOW', 'Current', 'today', 'to date', 'HIỆN TẠI',
                     'hiện nay', 'nay', 'đến nay'):
            self.assertTrue(has_present('2020 – ' + text))
        for text in ('presentation', 'nowhere', 'currently', 'todayish', 'naybe'):
            self.assertFalse(has_present(text))

    def test_period_normalizes_model_and_evidence_independently(self) -> None:
        for period, start, end, expected in (
            ('Jan 2021 – Present', 'JANUARY 2021', 'Present', ('2021-01', None, True)),
            ('T5/2020 - nay', '05.2020', 'nay', ('2020-05', None, True)),
            ('05.2019 – 10.2024', '2019/05', 'October 2024', ('2019-05', '2024-10', False)),
            ('2025 – 2026', '2025', '2026', ('2025', '2026', False)),
            ('2020-05 - 2021-06', 'May 2020', 'Jun 2021', ('2020-05', '2021-06', False)),
            ('15/05/2020 - 2021-06-30', '2020-05-15', '30.06.2021',
             ('2020-05', '2021-06', False)),
        ):
            with self.subTest(period=period):
                values, issues = validate_experiences(response(experience(
                    start=start, end=end, period=period,
                )), block('Role-Z Org-Z ' + period))
                self.assertFalse(issues)
                self.assertEqual((values[0].start, values[0].end, values[0].ongoing), expected)
        for start, end, period, expected in (
            ('05/20', '2021', '05/20 - 2021', ('INVALID_DATE', 'start')),
            ('2020', 'Jan 22', '2020 - Jan 22', ('INVALID_DATE', 'end')),
            ('2020', '2021', '13/2020 - 2021', ('QUOTE_NOT_FOUND', 'start')),
            ('2022-05', '2021', 'May 2022 - 2021', ('PERIOD_ORDER', 'period')),
        ):
            with self.subTest(period=period):
                values, issues = validate_experiences(response(experience(
                    start=start, end=end, period=period,
                )), block('Role-Z Org-Z ' + period))
                self.assertEqual([(i.code, i.field) for i in issues], [expected])
                self.assertEqual((values[0].start, values[0].end, values[0].ongoing),
                                 (None, None, False))

    def test_birth_dates_calendar_precision_and_source_ambiguity(self) -> None:
        for value, quote, expected, codes in (
            ('01/02/1987', '01/02/1987', '1987-02-01', ['DOB_DAY_FIRST_ASSUMED']),
            ('1987-02-01', '01/02/1987', '1987-02-01', ['DOB_DAY_FIRST_ASSUMED']),
            ('31/12/1990', '31/12/1990', '1990-12-31', []),
            ('1-2-1987', '1-2-1987', '1987-02-01', ['DOB_DAY_FIRST_ASSUMED']),
            ('1.2.1987', '1.2.1987', '1987-02-01', ['DOB_DAY_FIRST_ASSUMED']),
            ('1987-02-01', '1987-02-01', '1987-02-01', []),
            ('29/02/2000', '29/02/2000', '2000-02-29', []),
            ('31/02/1990', '31/02/1990', None, ['INVALID_DATE']),
            ('29/02/1990', '29/02/1990', None, ['INVALID_DATE']),
            ('1987-02-01', '02/01/1987', None, ['QUOTE_NOT_FOUND']),
            ('1987-02', '01/02/1987', None, ['QUOTE_NOT_FOUND']),
        ):
            with self.subTest(value=value, quote=quote):
                values, issues = validate_personal(json.dumps(personal(
                    dateOfBirth={'value': value, 'quote': quote},
                )), block(quote))
                self.assertEqual(values[0].value if values else None, expected)
                self.assertEqual([(i.code, i.field) for i in issues],
                                 [(code, 'dateOfBirth') for code in codes])


class CaseEvidenceTests(unittest.TestCase):
    def test_locate_and_supported_keep_source_offsets_and_spelling(self) -> None:
        text = 'Ω prefix\nCHIEF \t\n OPERATING OFFICER at ORG-Z suffix'
        source = block(text)
        quote = 'chief operating officer at org-z'
        evidence = locate(quote, source)
        self.assertEqual(evidence.text, 'CHIEF \t\n OPERATING OFFICER at ORG-Z')
        self.assertEqual(text[evidence.start:evidence.end], evidence.text)
        second_page = Block((Span(2, 17, 17 + len(text), text),))
        shifted_evidence = locate(quote, second_page)
        self.assertEqual((shifted_evidence.page, shifted_evidence.start, shifted_evidence.end),
                         (2, 17 + evidence.start, 17 + evidence.end))
        self.assertEqual(shifted_evidence.text, evidence.text)
        for value, expected in (('Chief Operating Officer', 'CHIEF \t\n OPERATING OFFICER'),
                                ('Org-Z', 'ORG-Z')):
            issues = []
            actual, ev = supported(value, quote, source, 'title', issues)
            self.assertEqual(actual, expected)
            self.assertEqual(ev, evidence)
            self.assertFalse(issues)

    def test_exact_then_whitespace_then_casefold_priority(self) -> None:
        text = 'ROLE-Z role \t Z role z'
        self.assertEqual(locate('role z', block(text)).start, text.rindex('role z'))
        self.assertEqual(locate('role  Z', block(text)).text, 'role \t Z')
        self.assertEqual(locate('role-z', block(text)).text, 'ROLE-Z')

    def test_personal_and_experience_values_use_source(self) -> None:
        text = 'SYNTHETIC PERSON\nNƠI MẪU\nSUMMARY SAMPLE\nROLE-Z ORG-Z'
        fields, issues = validate_personal(json.dumps(personal(
            fullName={'value': 'Synthetic Person', 'quote': 'synthetic person'},
            location={'value': 'Nơi Mẫu', 'quote': 'nơi mẫu'},
            summary={'value': 'Summary Sample', 'quote': 'summary sample'},
        )), block(text))
        self.assertFalse(issues)
        self.assertEqual([f.value for f in fields],
                         ['SYNTHETIC PERSON', 'NƠI MẪU', 'SUMMARY SAMPLE'])
        item = experience(title='Role-Z', company='Org-Z', start=None, end=None, period=None)
        values, issues = validate_experiences(response(item), block(text))
        self.assertFalse(issues)
        self.assertEqual((values[0].title, values[0].company), ('ROLE-Z', 'ORG-Z'))

    def test_no_length_changing_casefold_or_accent_removal(self) -> None:
        for quote, text in (('STRASSE', 'Straße'), ('Straße', 'STRASSE'),
                            ('i title', 'İ TITLE'), ('ff title', 'ﬀ TITLE'),
                            ('Nơi mẫu', 'Noi mau'), ('É', 'e\u0301')):
            with self.subTest(quote=quote):
                self.assertIsNone(locate(quote, block(text)))
                issues = []
                self.assertEqual(supported(quote, text, block(text), 'fullName', issues),
                                 (None, None))
                self.assertEqual(issues[0].code, 'QUOTE_NOT_FOUND')
        self.assertEqual(locate('role-z', block('Straße ROLE-Z')).text, 'ROLE-Z')
        self.assertEqual(locate('å role', block('Å ROLE')).text, 'Å ROLE')

    def test_casefold_does_not_relax_gender_quotes(self) -> None:
        values, issues = validate_personal(json.dumps(personal(
            gender={'value': 'MALE', 'quote': 'male'},
        )), block('Male'))
        self.assertFalse(values)
        self.assertEqual(issues[0].code, 'QUOTE_NOT_FOUND')


class HeadingPromptTests(unittest.TestCase):
    def test_lettered_job_subsections_do_not_end_experience(self) -> None:
        for label in ('C. Training', 'D. Activities', 'L. Training', 'M. Contact',
                      'c. Training', 'd. Activities', 'XXI. Skills'):
            with self.subTest(label=label):
                section = 'Role-Z\n' + label + '\nDescription-Z\n'
                text = 'II. KINH NGHIỆM\n' + section + 'Education\nexcluded'
                _, blocks, issues = segment(document(text))
                self.assertFalse(issues)
                self.assertEqual(''.join(b.text for b in blocks), section)

    def test_roman_numerals_up_to_twenty_still_delimit_sections(self) -> None:
        for heading in ('IV - Education', 'XII. Skills', 'XX. Skills', 'iv - Education'):
            with self.subTest(heading=heading):
                text = 'II. KINH NGHIỆM\nRole-Z\n' + heading + '\nexcluded'
                _, blocks, issues = segment(document(text))
                self.assertFalse(issues)
                self.assertEqual(''.join(b.text for b in blocks), 'Role-Z\n')

    def test_decorated_and_new_headings(self) -> None:
        for heading in (
            'Work history', 'Career history', 'Employment', 'Experiences',
            'Kinh nghiệm công tác', 'Lịch sử làm việc', 'Lịch sử công tác',
            'KINH NGHIỆM LÀM VIỆC:', '2. Work Experience', 'II. KINH NGHIỆM',
            'I. Work Experience', 'II) Work Experience',
            'IV - Work Experience', '• Work Experience –', '🛠 3) WORK   EXPERIENCE',
            'WORK EXPERIENCE & PROJECTS',
        ):
            with self.subTest(heading=heading):
                _, blocks, issues = segment(document(heading + '\nRole-Z\nSkills\nexcluded'))
                self.assertFalse(issues)
                self.assertEqual(''.join(b.text for b in blocks), 'Role-Z\n')

    def test_compound_stops_and_known_parts_only(self) -> None:
        for heading in (
            'Certificates', 'Certificate', 'Core skills', 'Key skills', 'Personal information',
            'Thông tin cá nhân', 'Contact', 'Liên hệ', 'Objective', 'Career objective', 'Hobbies',
            'Activities', 'Hoạt động', 'Training', 'Đào tạo', 'Memberships', 'Publications',
            'EDUCATION & CERTIFICATIONS', 'Education / Skills', 'Education, Training',
            'Education and Skills', 'Học vấn và Kỹ năng', 'Education & Work Experience',
            'Projects / Work Experience',
        ):
            with self.subTest(heading=heading):
                _, blocks, issues = segment(document('Experience\nRole-Z\n' + heading + '\nX'))
                self.assertFalse(issues)
                self.assertEqual(''.join(b.text for b in blocks), 'Role-Z\n')
        for text in ('Education supervise sample…', 'Experience & Unknown',
                     'Education and Unknown', 'Experience' + ' ' * 2 + 'x' * 61,
                     'Experience &', 'Work Experience & Skills / Unknown',
                     ' / '.join(['Experience'] * 5)):
            with self.subTest(text=text):
                _, blocks, issues = segment(document('Experience\nRole-Z\n' + text))
                self.assertFalse(issues)
                self.assertEqual(''.join(b.text for b in blocks), 'Role-Z\n' + text)

    def test_projects_are_nested_only_inside_experience(self) -> None:
        for heading in ('Projects', 'Dự án:'):
            text = heading + '\nexcluded\nExperience\nRole-Z\n' + heading + '\nProject-Z\nSkills\nX'
            _, blocks, issues = segment(document(text))
            self.assertFalse(issues)
            self.assertEqual(''.join(b.text for b in blocks), 'Role-Z\n' + heading + '\nProject-Z\n')

    def test_prompt_version_and_bilingual_experience_rules(self) -> None:
        self.assertEqual(PROMPT_VERSION, 'llm-extraction-prompt/2')
        prompt = build_experience_prompt('synthetic source')
        for instruction in ('case', 'hoa-thường', 'industry', 'ngành', 'one record',
                            'mỗi vị trí', 'certificates', 'chứng chỉ', 'verbatim date tokens'):
            self.assertIn(instruction, prompt)


if __name__ == '__main__':
    unittest.main()
