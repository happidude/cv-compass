"""E6 header, personal validation and job boundary regressions; synthetic data only."""

import json
import unittest

from cv_compass_llm.dates import find_dates
from cv_compass_llm.ocr_input import Document, Page
from cv_compass_llm.schema import PERSONAL_KEYS, Issue
from cv_compass_llm.segmenter import Span, segment, split_lines, split_spans
from cv_compass_llm.validate import validate_personal, validate_whole


class HeaderTests(unittest.TestCase):
    def test_cover_letter_then_personal_page(self) -> None:
        pages = (
            Page(1, 'ok', 'Synthetic cover letter'),
            Page(2, 'ok', 'Personal information\nSynthetic Person\n' + 'x' * 3100),
        )
        header, _, _ = segment(Document(pages))
        self.assertEqual(header.text, pages[1].raw_text)
        self.assertEqual(
            header.spans, (Span(2, 0, len(header.text), header.text, pages[1].raw_text),)
        )

    def test_all_personal_labels_and_contacts_select_second_page(self) -> None:
        for marker in (
            'name',
            'full name',
            'họ tên',
            'họ và tên',
            'date of birth',
            'day of birth',
            'ngày sinh',
            'gender',
            'giới tính',
            'personal information',
            'personal details',
            'thông tin cá nhân',
            'synthetic@example.invalid',
            '090.000.0000',
        ):
            with self.subTest(marker=marker):
                text = 'Prefix\n' + marker.upper() + ': Synthetic value'
                header, _, _ = segment(
                    Document((Page(1, 'ok', 'Cover letter'), Page(2, 'ok', text)))
                )
                self.assertEqual(header.text, text)
                self.assertEqual(header.spans[0].page, 2)

    def test_first_matching_page_wins(self) -> None:
        pages = (
            Page(1, 'ok', 'Cover letter'),
            Page(2, 'ok', 'Gender: Other'),
            Page(3, 'ok', 'Full name: Synthetic Person'),
        )
        header, _, _ = segment(Document(pages))
        self.assertEqual(header.text, pages[1].raw_text + '\n' + pages[2].raw_text)

    def test_scan_counts_only_ok_pages_and_includes_third(self) -> None:
        pages = (
            Page(1, 'failed', 'Full name: Excluded'),
            Page(2, 'ok', 'Cover letter'),
            Page(3, 'blank', ''),
            Page(4, 'ok', 'Introduction'),
            Page(5, 'not_processed', ''),
            Page(6, 'ok', 'Họ và tên: Synthetic Person'),
        )
        header, _, _ = segment(Document(pages))
        self.assertEqual(header.spans[0].page, 6)
        self.assertEqual(header.text, pages[-1].raw_text)

    def test_fourth_ok_page_is_not_scanned(self) -> None:
        pages = tuple(
            Page(i, 'ok', text)
            for i, text in enumerate(
                ('Cover letter', 'Introduction', 'Portfolio', 'Full name: Synthetic Person'),
                1,
            )
        )
        header, _, _ = segment(Document(pages))
        self.assertEqual(header.text, pages[0].raw_text + '\n' + pages[1].raw_text)

    def test_no_markers_or_partial_words_fall_back_to_first_ok_page(self) -> None:
        pages = (
            Page(1, 'failed', 'Name: Excluded'),
            Page(2, 'ok', 'Cover letter'),
            Page(3, 'ok', 'filename surname transgender names\nhttps://example.invalid/synthetic'),
            Page(4, 'ok', 'Introduction'),
        )
        header, _, _ = segment(Document(pages))
        self.assertEqual(header.spans[0].page, 2)
        self.assertEqual(header.text, pages[1].raw_text + '\n' + pages[2].raw_text)

    def test_marker_after_limit_selects_page_but_header_starts_at_zero(self) -> None:
        text = 'x' * 8100 + '\nNgày sinh: 2000-12-31'
        header, _, _ = segment(Document((Page(1, 'ok', 'Cover'), Page(2, 'ok', text))))
        self.assertEqual(header.spans, (Span(2, 0, 8000, text[:8000], text),))


class PersonalNameTests(unittest.TestCase):
    def validate_name(self, value: str, source: str, whole: bool):
        header, _, _ = segment(Document((Page(1, 'ok', source),)))
        data = {'personal': dict.fromkeys(PERSONAL_KEYS)}
        data['personal']['fullName'] = {'value': value, 'quote': source}
        data['personal']['summary'] = {'value': 'Synthetic summary', 'quote': 'Synthetic summary'}
        if whole:
            data['experiences'] = []
            fields, experiences, issues = validate_whole(json.dumps(data), header)
            self.assertFalse(experiences)
        else:
            data['personal']['summary'] = [
                {'quoteStart': 'Synthetic summary', 'quoteEnd': 'Synthetic summary'}
            ]
            fields, issues = validate_personal(json.dumps(data), header)
        return fields, issues

    def test_generic_titles_are_rejected_in_both_modes(self) -> None:
        for title in (
            'CURRICULUM VITAE',
            'CV',
            'Resume',
            'RÉSUMÉ',
            'SƠ YẾU LÝ LỊCH',
            'LÝ LỊCH',
            '  Curriculum \t\n Vitae  ',
        ):
            for whole in (False, True):
                with self.subTest(title=title, whole=whole):
                    fields, issues = self.validate_name(
                        title,
                        title + '\nSynthetic summary',
                        whole,
                    )
                    self.assertEqual([field.key for field in fields], ['summary'])
                    self.assertEqual(issues, (Issue('QUOTE_NOT_FOUND', 'fullName'),))

    def test_supported_names_are_retained_without_substring_rejection(self) -> None:
        for name in ('Synthetic Person', 'Synthetic Resume Person'):
            for whole in (False, True):
                with self.subTest(name=name, whole=whole):
                    source = name + '\nSynthetic summary'
                    fields, issues = self.validate_name(name, source, whole)
                    self.assertFalse(issues)
                    self.assertEqual([field.key for field in fields], ['fullName', 'summary'])
                    self.assertEqual(fields[0].value, name)
                    evidence = fields[0].evidence
                    self.assertEqual(source[evidence.start : evidence.end], evidence.text)


class JobBoundaryTests(unittest.TestCase):
    def assert_source_partition(self, blocks, expected_spans, limit=6000) -> None:
        self.assertTrue(all(0 < len(block.text) <= limit for block in blocks))
        actual = [span for block in blocks for span in block.spans]
        self.assertEqual(actual, expected_spans)
        for previous, span in zip(actual, actual[1:]):
            if previous.page == span.page:
                self.assertLessEqual(previous.end, span.start)
        for span in actual:
            if span.raw_text is not None:
                self.assertEqual(span.text, span.raw_text[span.start : span.end])

    def test_date_parser_preserves_month_precision_for_boundary_examples(self) -> None:
        self.assertEqual(find_dates('Sep-2011'), ['2011-09'])
        self.assertEqual(find_dates('Jan 2021'), ['2021-01'])
        self.assertEqual(find_dates('From: Sep-2011 To Mar- 2016 :'), ['2011-09', '2016-03'])

    def test_two_long_jobs_split_before_second_job_in_both_segmentation_paths(self) -> None:
        first = 'From: Jan 2010 To Jan 2011\n' + ('a' * 99 + '\n') * 35
        for boundary in ('From: Sep-2011 To Mar- 2016 :', 'Jan 2021 – Present | Role-B'):
            second = boundary + '\n' + ('b' * 99 + '\n') * 35
            for heading in ('Experience\n', ''):
                with self.subTest(boundary=boundary, heading=heading):
                    page = Page(1, 'ok', heading + first + second)
                    _, blocks, issues = segment(Document((page,)))
                    self.assertEqual([block.text for block in blocks], [first, second])
                    self.assertEqual(
                        issues, () if heading else (Issue('EXPERIENCE_HEADING_NOT_FOUND'),)
                    )
                    lines = split_lines((page,))
                    self.assert_source_partition(blocks, lines[1:] if heading else lines)

    def test_boundary_variants_and_whole_words(self) -> None:
        first = 'Role-A\n' + ('a' * 99 + '\n') * 35
        for boundary in (
            '  fRoM: undated role',
            '  TỪ: undated role',
            'Jan 2021 - Feb 2022',
            'Jan 2021 — Feb 2022',
            'Jan 2021 TO Feb 2022',
            '2021 ĐẾN 2022',
            'Present | Role-B',
            'Hiện tại | Role-B',
        ):
            with self.subTest(boundary=boundary):
                second = boundary + '\n' + ('b' * 99 + '\n') * 35
                page = Page(1, 'ok', first + second)
                lines = split_lines((page,))
                blocks = split_spans(lines)
                self.assertEqual([block.text for block in blocks], [first, second])
                self.assert_source_partition(blocks, lines)

        for line in (
            'Fromage',
            'Từng',
            'representation',
            '2021 together',
            '2021 destination',
            'No dates - Role-B',
            'Jan 2021',
        ):
            with self.subTest(non_boundary=line):
                text = first + line + '\n' + ('b' * 99 + '\n') * 35
                lines = split_lines((Page(1, 'ok', text),))
                blocks = split_spans(lines)
                self.assertIn(line + '\n', blocks[0].text)
                self.assert_source_partition(blocks, lines)

    def test_nearest_prior_boundary_wins(self) -> None:
        jobs = [
            f'{year} - {year + 1}\n' + (char * 99 + '\n') * 25
            for year, char in ((2010, 'a'), (2015, 'b'), (2020, 'c'))
        ]
        lines = split_lines((Page(1, 'ok', ''.join(jobs)),))
        blocks = split_spans(lines)
        self.assertEqual([block.text for block in blocks], [jobs[0] + jobs[1], jobs[2]])
        self.assert_source_partition(blocks, lines)

    def test_single_long_job_falls_back_to_lines_without_empty_blocks(self) -> None:
        text = 'Experience\nFrom: undated role\n' + ('x' * 99 + '\n') * 150
        page = Page(1, 'ok', text)
        _, blocks, issues = segment(Document((page,)))
        self.assertFalse(issues)
        self.assertEqual(len(blocks), 3)
        self.assertEqual(''.join(block.text for block in blocks), text[len('Experience\n') :])
        self.assert_source_partition(blocks, split_lines((page,))[1:])

    def test_carrying_job_can_still_require_line_fallback(self) -> None:
        page = Page(1, 'ok', 'aa\nFrom\nbbb\ncccccccc\n')
        lines = split_lines((page,))
        blocks = split_spans(lines, limit=12)
        self.assertEqual([block.text for block in blocks], ['aa\n', 'From\nbbb\n', 'cccccccc\n'])
        self.assert_source_partition(blocks, lines, limit=12)

    def test_page_and_gap_separators_are_recomputed_after_carry(self) -> None:
        for last in (Span(2, 10, 14, 'cccc'), Span(3, 0, 4, 'cccc')):
            with self.subTest(last=last):
                lines = [Span(1, 0, 4, 'aaaa'), Span(2, 0, 4, 'From'), Span(2, 4, 8, 'bbbb'), last]
                blocks = split_spans(lines, limit=13)
                self.assertEqual([block.text for block in blocks], ['aaaa', 'Frombbbb\ncccc'])
                self.assert_source_partition(blocks, lines, limit=13)

    def test_exact_limit_does_not_split_early_at_job_boundary(self) -> None:
        first = 'a' * 2999 + '\n'
        second = 'From\n' + 'b' * 2994 + '\n'
        lines = split_lines((Page(1, 'ok', first + second),))
        blocks = split_spans(lines)
        self.assertEqual([block.text for block in blocks], [first + second])
        self.assert_source_partition(blocks, lines)


if __name__ == '__main__':
    unittest.main()
