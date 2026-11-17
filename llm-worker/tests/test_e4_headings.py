"""E4 heading regressions using synthetic source text only."""

import unittest

from cv_compass_llm.ocr_input import Document, Page
from cv_compass_llm.segmenter import normalize_heading, segment


class HeadingTests(unittest.TestCase):
    def test_sentence_fragments_keep_following_experience_lines(self) -> None:
        for fragment in (
            'activities.', 'projects.', 'education.', 'skills',
            'hoạt động.', 'đào tạo', 'kỹ năng', 'dự án.', 'Học vấn.',
            'Activities.', 'Projects.', 'Education.', 'Skills.', 'Experience.',
            '  Education. \t', '2. education', '• skills', 'II. education',
            'ii) skills', 'IV - activities', '• 2) XX. education',
            'C. Training', 'education & Certifications', '', '•', '2.', 'II.',
        ):
            with self.subTest(fragment=fragment):
                section = 'Role-Z\n' + fragment + '\nDescription-Z\n'
                text = 'Experience\n' + section + 'Education\nexcluded'
                _, blocks, issues = segment(Document((Page(1, 'ok', text),)))
                self.assertFalse(issues)
                self.assertEqual(''.join(block.text for block in blocks), section)

    def test_fragments_preserve_experience_across_pages_and_source_offsets(self) -> None:
        contents = (
            'Role-Z\nactivities.\nDescription-A\n',
            'projects.\nDescription-B\neducation.\nDescription-C\n',
            'skills\nDescription-D\n',
        )
        pages = (
            Page(1, 'ok', 'Experience\n' + contents[0]),
            Page(2, 'ok', contents[1]),
            Page(3, 'ok', contents[2] + 'EDUCATION & CERTIFICATIONS\nexcluded'),
        )
        _, blocks, issues = segment(Document(pages))
        self.assertFalse(issues)
        self.assertEqual(len(blocks), 1)
        self.assertEqual(blocks[0].text, '\n'.join(contents))
        self.assertEqual({span.page for span in blocks[0].spans}, {1, 2, 3})
        for span in blocks[0].spans:
            source = pages[span.page - 1].raw_text
            self.assertEqual(span.raw_text, source)
            self.assertEqual(span.text, source[span.start:span.end])

    def test_valid_experience_headings_still_start_sections(self) -> None:
        for heading in (
            'KINH NGHIỆM LÀM VIỆC:', '2. Work Experience', 'II. KINH NGHIỆM',
            'EXPERIENCES:', 'Work History', '• Work Experience –',
            '  2) Work Experience - \t', 'WORK EXPERIENCE & PROJECTS',
        ):
            with self.subTest(heading=heading):
                text = 'excluded\n' + heading + '\nRole-Z\nEducation\nexcluded'
                _, blocks, issues = segment(Document((Page(1, 'ok', text),)))
                self.assertFalse(issues)
                self.assertEqual(''.join(block.text for block in blocks), 'Role-Z\n')

    def test_valid_stop_headings_still_end_sections(self) -> None:
        for heading in (
            'EDUCATION & CERTIFICATIONS', 'Education / Skills', 'Education, Training',
            'Education and Skills', 'Học vấn và Kỹ năng', '• 2) Đào tạo:',
            'IV - Education', 'iv - Education', 'XX) Skills',
        ):
            with self.subTest(heading=heading):
                text = 'Experience\nRole-Z\n' + heading + '\nexcluded'
                _, blocks, issues = segment(Document((Page(1, 'ok', text),)))
                self.assertFalse(issues)
                self.assertEqual(''.join(block.text for block in blocks), 'Role-Z\n')

    def test_rejected_experience_headings_use_full_document_fallback(self) -> None:
        for heading in (
            'experience', 'work history', '2. work experience', 'II. kinh nghiệm',
            'Experience.', '2. Work Experience. \t', '• XX) Work Experience.',
        ):
            with self.subTest(heading=heading):
                text = heading + '\nRole-Z\nEducation\nDescription-Z'
                _, blocks, issues = segment(Document((Page(1, 'ok', text),)))
                self.assertEqual([issue.code for issue in issues],
                                 ['EXPERIENCE_HEADING_NOT_FOUND'])
                self.assertEqual(''.join(block.text for block in blocks), text)

    def test_normalization_keeps_periods_and_removes_other_suffixes(self) -> None:
        for text, expected in (
            ('Education.', 'education.'), ('  2. Work Experience. \t', 'work experience.'),
            ('II. KINH NGHIỆM:', 'kinh nghiệm'), ('• Skills -', 'skills'),
            ('Education –', 'education'),
        ):
            with self.subTest(text=text):
                self.assertEqual(normalize_heading(text), expected)


if __name__ == '__main__':
    unittest.main()
