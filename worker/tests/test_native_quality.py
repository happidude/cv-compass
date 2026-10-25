from pathlib import Path
import tempfile
import unittest
import pdfplumber
from cv_compass_worker.layout import native_order, normalize_native
from native_quality import corpus, generate, evaluate


class NativeQualityTests(unittest.TestCase):
    def test_ground_truth_content_spacing_order_and_uncertainty(self):
        for name, scores in evaluate(native_order).items():
            with self.subTest(fixture=name):
                self.assertTrue(scores['exact'], scores)
                self.assertEqual(scores['content'], 1)
                self.assertEqual(scores['whitespace'], 1)
                self.assertEqual(scores['readingOrder'], 1)
                if 'columns' in name or name == 'unknown_font_icon':
                    self.assertTrue(scores['uncertain'])

    def test_layout_is_not_tied_to_fixture_coordinates_or_font_size(self):
        with tempfile.TemporaryDirectory() as directory:
            for factor in (.75, 1, 1.2):
                for name, (regions, truth) in corpus().items():
                    with self.subTest(scale=factor, fixture=name):
                        transformed = [(t, x*factor+11, y*factor-100, size*factor) for t,x,y,size in regions]
                        path = Path(directory)/'scaled.pdf'
                        generate(path, name, transformed)
                        with pdfplumber.open(path) as pdf:
                            actual, _ = native_order(pdf.pages[0])
                        self.assertEqual(actual, '\n'.join(truth))

    def test_raw_extraction_is_unchanged_and_unknown_glyph_not_deleted(self):
        with tempfile.TemporaryDirectory() as directory:
            for name in ('tight_word_gaps', 'unknown_font_icon', 'bullets_hyphens'):
                path = Path(directory)/'source.pdf'
                generate(path, name, corpus()[name][0])
                with pdfplumber.open(path) as pdf:
                    page = pdf.pages[0]
                    raw = page.extract_text(layout=False)
                    native, _ = native_order(page)
                    self.assertEqual(raw, page.extract_text(layout=False))
                if name == 'unknown_font_icon':
                    self.assertIn('(cid:65535)', native)
                else:
                    self.assertNotEqual(raw, native)

    def test_hard_hyphens_codes_and_technologies_are_preserved(self):
        source = 'scikit-learn C++ C# .NET UTF-8 ISO-9001\ncross-\nplatform'
        self.assertEqual(normalize_native(source), source)
        self.assertEqual(normalize_native('exam\u00ad\nple'), 'example')

    def test_staggered_columns_and_soft_hyphen_at_column_boundary(self):
        regions = [('Left first paragraph', 40, 740, 12), ('Right first paragraph', 350, 730, 12),
                   ('Left final word\u00ad', 40, 700, 12), ('Right second paragraph', 350, 680, 12)]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'staggered.pdf'
            generate(path, 'staggered', regions)
            with pdfplumber.open(path) as pdf:
                text, uncertain = native_order(pdf.pages[0])
            self.assertEqual(text, '\n'.join(regions[i][0] for i in (0, 2, 1, 3)))
            self.assertTrue(uncertain)
