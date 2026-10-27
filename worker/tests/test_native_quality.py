from pathlib import Path
import tempfile
import unittest
import pdfplumber
from cv_compass_worker.child import process_page
from native_quality import corpus, generate, evaluate
from test_pipeline import config


class NativeQualityTests(unittest.TestCase):
    def test_raw_native_equals_initial_library_output_across_synthetic_layouts(self):
        with tempfile.TemporaryDirectory() as directory:
            for name, (regions, _) in corpus().items():
                with self.subTest(fixture=name):
                    path = Path(directory) / (name + '.pdf')
                    generate(path, name, regions)
                    with pdfplumber.open(path) as pdf:
                        expected = pdf.pages[0].extract_text(layout=False)
                    child = Path(directory) / (name + '-child')
                    child.mkdir()
                    result = process_page(path, 1, config(), child)
                    self.assertNotIn('text', result)
                    if name == 'tight_word_gaps':
                        self.assertEqual(result['method'], 'ocr')
                    else:
                        self.assertEqual(result['method'], 'native')
                        self.assertEqual(result['raw_text'], expected)

    def test_raw_quality_metrics_are_observations_not_corrected_output(self):
        scores = evaluate(lambda page: (page.extract_text(layout=False) or '', False))
        self.assertEqual(set(scores), set(corpus()))
        for quality in scores.values():
            self.assertTrue(0 <= quality['content'] <= 1)
            self.assertTrue(0 <= quality['whitespace'] <= 1)
            self.assertTrue(0 <= quality['readingOrder'] <= 1)

    def test_raw_keeps_initial_library_soft_hyphen_and_unknown_glyph(self):
        with tempfile.TemporaryDirectory() as directory:
            for name in ('bullets_hyphens', 'unknown_font_icon'):
                with self.subTest(fixture=name):
                    path = Path(directory) / (name + '.pdf')
                    generate(path, name, corpus()[name][0])
                    with pdfplumber.open(path) as pdf:
                        raw = pdf.pages[0].extract_text(layout=False)
                    child = Path(directory) / (name + '-child')
                    child.mkdir()
                    result = process_page(path, 1, config(), child)
                    self.assertEqual(result['method'], 'native')
                    self.assertEqual(result['raw_text'], raw)
                    if name == 'unknown_font_icon':
                        self.assertIn('(cid:65535)', result['raw_text'])

    def test_native_usability_stays_independent_of_layout_reconstruction(self):
        from cv_compass_worker.layout import usable

        self.assertTrue(
            usable('Ordinary synthetic long text with enough visible characters for a native page.')
        )
        self.assertFalse(usable('   '))
