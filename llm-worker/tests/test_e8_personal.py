"""E8 personal-only extraction and frozen experience contracts; synthetic data only."""

import hashlib
import json
import unicodedata
import unittest

from test_core import block, document, experience, personal
from test_pull import claim

from cv_compass_llm.contacts import extract_contacts
from cv_compass_llm.evidence import find_personal, locate
from cv_compass_llm.pipeline import extract
from cv_compass_llm.prompts import (
    PROMPT_VERSION,
    WHOLE_PROMPT_VERSION,
    build_experience_prompt,
    build_personal_prompt,
    build_whole_prompt,
)
from cv_compass_llm.providers import FakeProvider, ProviderError
from cv_compass_llm.pull_payload import compact_result
from cv_compass_llm.schema import (
    EXPERIENCE_SCHEMA,
    PERSONAL_SCHEMA,
    WHOLE_SCHEMA,
    Evidence,
    ExtractionResult,
    FieldValue,
    Issue,
)
from cv_compass_llm.segmenter import build_header
from cv_compass_llm.validate import parse_response, validate_personal, validate_whole


def summary(*anchors):
    return personal(summary=[{'quoteStart': start, 'quoteEnd': end} for start, end in anchors])


class FrozenExperienceTests(unittest.TestCase):
    def test_prompt_and_schema_sha256(self):
        fixtures = (
            (
                build_experience_prompt('Line A\nLine B'),
                '9468bd9b00018d9cde2029e6a9f0deb93bbfceeb56f76c5a48bef20d6978c944',
            ),
            (
                build_whole_prompt('Line A\nLine B'),
                '9a85fdb7c8a5bf27109b42cfd5e7d71832e86face4041e3f3d61d9fe586d33d0',
            ),
            (
                json.dumps(EXPERIENCE_SCHEMA),
                '526bcdbf2404da0aa94a1fc101dc78cd0273d0cda4ea6824371c7b9ffefc7905',
            ),
            (
                json.dumps(WHOLE_SCHEMA),
                '780e9cfb0dc998452fb924b02d67a168fc2100e5355be170c8f5c8798dbaf47b',
            ),
        )
        for value, expected in fixtures:
            with self.subTest(expected=expected):
                self.assertEqual(hashlib.sha256(value.encode()).hexdigest(), expected)


class PersonalPipelineTests(unittest.TestCase):
    def test_whole_ignores_old_personal_values_and_issues_and_calls_header(self):
        doc = document('Full name: Synthetic Person\n', 'Summary\nSynthetic objective ends here.')
        old = personal(
            fullName={'value': 'Full name', 'quote': 'Full name'},
            summary={'value': 'Invented', 'quote': 'Missing'},
        )
        provider = FakeProvider(
            [
                dict(old, experiences=[]),
                personal(fullName={'value': 'Synthetic Person', 'quote': 'Synthetic Person'}),
            ]
        )
        result = extract(doc, provider, mode='whole')
        self.assertEqual(
            provider.prompts[0],
            build_whole_prompt(doc.pages[0].raw_text + '\n' + doc.pages[1].raw_text),
        )
        self.assertEqual(provider.prompts[1], build_personal_prompt(build_header(doc.pages).text))
        self.assertEqual(provider.calls, [(WHOLE_SCHEMA, 8192, 900), (PERSONAL_SCHEMA, None, None)])
        self.assertEqual(
            [(field.key, field.value) for field in result.fields],
            [('fullName', 'Synthetic Person')],
        )
        self.assertEqual(result.issues, ())
        self.assertEqual(result.prompt_version, WHOLE_PROMPT_VERSION)
        self.assertEqual(PROMPT_VERSION, 'llm-extraction-prompt/3')
        self.assertEqual(WHOLE_PROMPT_VERSION, 'llm-extraction-prompt/whole-2')

    def test_header_includes_successor_and_caps_joined_text_at_8000(self):
        doc = document('Cover', 'Personal information\n' + 'x' * 4000, 'y' * 5000)
        header = build_header(doc.pages)
        self.assertEqual(len(header.text), 8000)
        self.assertEqual([span.page for span in header.spans], [2, 3])
        for span in header.spans:
            self.assertEqual(span.start, 0)
            self.assertEqual(span.text, doc.pages[span.page - 1].raw_text[: span.end])

    def test_header_does_not_skip_missing_successor(self):
        doc = document('Personal information', '', 'Not the next page')
        self.assertEqual(build_header((doc.pages[0], doc.pages[2])).text, doc.pages[0].raw_text)

    def test_whole_progress_counts_only_whole_when_header_is_empty(self):
        provider = FakeProvider(dict(personal(), experiences=[]))
        progress = []
        result = extract(
            document(), provider, mode='whole', on_block=lambda *args: progress.append(args)
        )
        self.assertEqual(len(provider.calls), 1)
        self.assertEqual([step[:2] for step in progress], [(1, 1)])
        self.assertEqual(result.issues, ())

    def test_whole_experiences_survive_personal_provider_failure(self):
        text = 'Role-Z\nOrg-Z\n1/2020 - 2021-02'
        whole = dict(personal(), experiences=[experience()])
        _, expected, issues = validate_whole(json.dumps(whole), block(text))
        self.assertEqual(issues, ())
        self.assertEqual(len(expected), 1)
        provider = FakeProvider([whole, ProviderError('SYNTHETIC_FAILURE')])
        result = extract(document(text), provider, mode='whole')
        self.assertEqual(len(provider.calls), 2)
        self.assertEqual(result.experiences, expected)
        self.assertEqual(result.issues, (Issue('PROVIDER_ERROR', 'personal'),))


class SummaryTests(unittest.TestCase):
    def test_all_paragraphs_and_page_crossing_keep_literal_interior_and_payload(self):
        first = 'Build reliable systems with a misspeled “word” in the middle.'
        second = 'Grow technical skills and support careful delivery.'
        crossing = ('Lead thoughtful teams toward', 'shared goals with measurable impact.')
        doc = document(
            'Personal information\nTóm tắt\n'
            + first
            + '\nĐịnh hướng nghề nghiệp\n'
            + second
            + '\nCareer Objective\n'
            + crossing[0],
            crossing[1] + '\nExperience\nExcluded position',
        )
        data = summary(
            ('Build reliable systems', 'in the middle.'),
            ('Grow technical skills', 'careful delivery.'),
            (crossing[0], crossing[1]),
        )
        fields, issues = validate_personal(json.dumps(data), build_header(doc.pages))
        self.assertEqual(issues, ())
        self.assertEqual(len(fields), 1)
        field = fields[0]
        self.assertEqual(field.value, '\n'.join((first, second, *crossing)))
        parts = (field.evidence, *field.additional_evidence)
        self.assertEqual([part.page for part in parts], [1, 1, 1, 2])
        for part in parts:
            self.assertEqual(doc.pages[part.page - 1].raw_text[part.start : part.end], part.text)
        result = ExtractionResult(fields, (), (), PROMPT_VERSION, 'fake')
        payload = json.loads(compact_result(result, claim(), {}))['personal']['summary']
        self.assertEqual(payload['value'], field.value)
        self.assertEqual(
            payload['evidence'],
            [{'page': p.page, 'start': p.start, 'end': p.end, 'text': p.text} for p in parts],
        )
        self.assertEqual(len(result.to_dict()['fields'][0]['additional_evidence']), 3)

    def test_missing_reversed_overlapping_and_too_long_anchors_are_rejected(self):
        source = block('Beginning words ' + 'x' * 6000 + ' Ending words')
        for data in (
            summary(('Missing words', 'Ending words')),
            summary(('Beginning words', 'Missing words')),
            summary(('Ending words', 'Beginning words')),
            summary(('Beginning words', 'Ending words')),
            summary(('Beginning words', 'Beginning words'), ('Beginning words', 'Ending words')),
        ):
            with self.subTest(data=data):
                fields, issues = validate_personal(json.dumps(data), source)
                self.assertEqual(fields, ())
                self.assertEqual(issues, (Issue('QUOTE_NOT_FOUND', 'summary'),))

    def test_summary_limit_includes_join_separators(self):
        first, second = 'a' * 3000, 'b' * 2999
        for tail, accepted in (('', True), ('b', False)):
            end = second + tail
            fields, issues = validate_personal(
                json.dumps(summary((first, first), (end, end))), block(first + '\n' + end)
            )
            self.assertEqual(bool(fields), accepted)
            self.assertEqual(bool(issues), not accepted)

    def test_schema_has_no_legacy_summary_and_bounds_paragraph_count(self):
        for value in ([], [{}], [{'quoteStart': 'a', 'quoteEnd': 'b'}] * 9, {'value': 'a'}):
            self.assertIsNone(parse_response(json.dumps(personal(summary=value)), PERSONAL_SCHEMA))

    def test_more_than_eight_evidence_parts_are_rejected(self):
        source = block(' '.join(f'Paragraph-{i}.' for i in range(8)))
        data = summary(*[(f'Paragraph-{i}.', f'Paragraph-{i}.') for i in range(8)])
        fields, issues = validate_personal(json.dumps(data), source)
        self.assertEqual(len(fields[0].additional_evidence), 7)
        self.assertEqual(issues, ())
        doc = document(source.text + '\nBegin crossing', 'End crossing')
        data['personal']['summary'][-1] = {
            'quoteStart': 'Begin crossing',
            'quoteEnd': 'End crossing',
        }
        fields, issues = validate_personal(json.dumps(data), build_header(doc.pages))
        self.assertEqual(fields, ())
        self.assertEqual(issues, (Issue('QUOTE_NOT_FOUND', 'summary'),))

    def test_repeated_paragraph_anchors_follow_reading_order(self):
        literal = 'Build careful systems.'
        fields, issues = validate_personal(
            json.dumps(summary((literal, literal), (literal, literal))),
            block(literal + '\nProfile\n' + literal),
        )
        self.assertEqual(issues, ())
        self.assertEqual(fields[0].value, literal + '\n' + literal)
        self.assertGreater(fields[0].additional_evidence[0].start, fields[0].evidence.end)

    def test_single_piece_payload_uses_object_and_invalid_parts_are_dropped(self):
        for parts, value, accepted in (
            ((Evidence(1, 4, 9, 'First'),), 'First', True),
            ((Evidence(1, 4, 9, 'First'), Evidence(1, 3, 7, 'Next')), 'First\nNext', False),
            ((Evidence(1, 4, 9, 'First'),), 'Invented', False),
            ((Evidence(1, 0, 6001, 'x' * 6001),), 'x' * 6001, False),
        ):
            field = FieldValue('summary', value, parts[0], parts[1:])
            result = ExtractionResult((field,), (), (), PROMPT_VERSION, 'fake')
            payload = json.loads(compact_result(result, claim(), {}))
            if accepted:
                self.assertIsInstance(payload['personal']['summary']['evidence'], dict)
            else:
                self.assertIsNone(payload['personal']['summary'])
                self.assertIn({'code': 'QUOTE_NOT_FOUND', 'field': 'summary'}, payload['issues'])

    def test_single_piece_payload_narrows_value_within_larger_quote(self):
        quote = 'Summary: Build \tcareful systems. More context.'
        value = 'Build careful systems.'
        source = 'Build \tcareful systems.'
        offset = 12
        field = FieldValue('summary', value, Evidence(2, offset, offset + len(quote), quote))
        result = ExtractionResult((field,), (), (), PROMPT_VERSION, 'fake')
        payload = json.loads(compact_result(result, claim(), {}))
        start = offset + quote.index('Build')
        self.assertEqual(
            payload['personal']['summary'],
            {
                'value': value,
                'evidence': {'page': 2, 'start': start, 'end': start + len(source), 'text': source},
            },
        )
        self.assertEqual(payload['issues'], [])


class PersonalMatcherTests(unittest.TestCase):
    def test_punctuation_and_nfd_keep_source_values_and_offsets(self):
        name = unicodedata.normalize('NFD', 'Nguyễn Mẫu')
        location = 'City “A” — Ward, District'
        text = '🧪 ' + name + '\n' + location
        fields, issues = validate_personal(
            json.dumps(
                personal(
                    fullName={'value': 'Nguyễn Mẫu', 'quote': 'Nguyễn Mẫu'},
                    location={
                        'value': 'City "A"-Ward ,District',
                        'quote': 'City "A" - Ward,District',
                    },
                )
            ),
            block(text),
        )
        self.assertEqual(issues, ())
        self.assertEqual([field.value for field in fields], [name, location])
        for field in fields:
            ev = field.evidence
            self.assertEqual(text[ev.start : ev.end], ev.text)
        self.assertEqual(fields[0].evidence.start, 2)
        self.assertEqual(fields[0].evidence.end, 2 + len(name))

    def test_summary_anchors_use_flexible_matcher_with_nfd_offsets(self):
        literal = unicodedata.normalize('NFD', 'Tôi muốn xây dựng') + ' “safe” systems — every day.'
        fields, issues = validate_personal(
            json.dumps(summary(('Tôi muốn xây dựng "safe"', 'systems - every day.'))),
            block('Prefix\n' + literal),
        )
        self.assertEqual(issues, ())
        self.assertEqual(fields[0].value, literal)
        self.assertEqual(fields[0].evidence.start, len('Prefix\n'))

    def test_no_letter_digit_accent_or_punctuation_invention(self):
        for quote, source in (
            ('Nguyen', 'Nguyễn'),
            ('City B', 'City A'),
            ('Ward 12', 'Ward 13'),
            ('AlphaBeta', 'Alpha Beta'),
            ('Alpha Beta', 'AlphaBeta'),
            ('City-A', 'City A'),
            ('STRASSE', 'Straße'),
            ('xë', 'xe\u0308\u0301'),
        ):
            with self.subTest(quote=quote):
                self.assertIsNone(find_personal(quote, source))
        self.assertIsNone(locate('Role - Z', block('Role—Z')))

    def test_gender_nfd_and_dash_variants_remain_explicit(self):
        text = unicodedata.normalize('NFD', 'Giới tính — Nữ')
        fields, issues = validate_personal(
            json.dumps(personal(gender={'value': 'FEMALE', 'quote': 'Giới tính - Nữ'})), block(text)
        )
        self.assertEqual(issues, ())
        self.assertEqual(fields[0].value, 'FEMALE')


class PhoneOcrTests(unittest.TestCase):
    def test_spaced_punctuation_and_country_code_forms_preserve_source(self):
        for phone in ('+ 8 4   903 860 9 50', '0911.345.321', '(+84) 903-860-950'):
            with self.subTest(phone=phone):
                fields = extract_contacts(document('Phone: ' + phone))
                self.assertEqual(len(fields), 1)
                self.assertEqual(fields[0].value, phone)
                self.assertEqual(fields[0].evidence, Evidence(1, 7, 7 + len(phone), phone))
                self.assertEqual(build_header(document('Cover', phone).pages).spans[0].page, 2)

    def test_dates_years_and_overlong_numbers_are_not_phones(self):
        for text in (
            '01.01.2020',
            '01.01.2020 02',
            '2020 - 2021',
            '0 2020 2021 0',
            '01/01/2020',
            '10900000000',
            '09000000000',
            '+840900000000',
        ):
            with self.subTest(text=text):
                self.assertEqual(extract_contacts(document(text)), ())

    def test_account_number_tails_and_unbounded_separators_are_not_phones(self):
        for text in (
            '1903 0123 4567 89',
            'Account: 1903.0123.4567.89',
            '1903-0123-4567-89',
            '1903 \t0123 4567 89',
            '1903    0123 4567 89',
            '0911    345    321',
            '0911 345 321    89',
            '+84    903-860-950',
        ):
            with self.subTest(text=text):
                self.assertEqual(extract_contacts(document(text)), ())

    def test_previous_line_digits_do_not_hide_phone(self):
        phone = '0911.345.321'
        for separator in ('\n', '\r\n', '\r'):
            with self.subTest(separator=separator):
                fields = extract_contacts(document('1903' + separator + phone))
                self.assertEqual([field.value for field in fields], [phone])

    def test_second_phone_after_accepted_phone_on_same_line_is_kept(self):
        fields = extract_contacts(document('090.000.0000 +84 90 000 0000'))
        self.assertEqual([f.value for f in fields], ['090.000.0000', '+84 90 000 0000'])
        self.assertEqual(extract_contacts(document('0911 345 321 1903 0123 4567 89')), ())
