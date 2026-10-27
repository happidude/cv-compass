"""Regression evidence for Reviewer pass 1 findings."""

import copy
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
from cv_compass_worker.contracts import validate_request, validate_result
from cv_compass_worker.pipeline import process
from cv_compass_worker.sandbox import ChildFailure
from fixtures import native
from test_pipeline import config, request, ROOT


class ReviewRegressionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / ('c' * 32 + '.pdf')
        native(self.source)
        self.req = request(self.source)
        example = json.loads(
            (ROOT / 'design/examples-v2/result-success.json').read_text(encoding='utf-8')
        )
        self.engines = example['engines']
        self.good = example['pages'][0]

    def fake_child(self, count, *items):
        return patch(
            'cv_compass_worker.pipeline.run_child',
            side_effect=[{'page_count': count, 'engines': self.engines}, *items],
        )

    def test_document_text_cap_stops_before_next_engine(self):
        first, second = copy.deepcopy(self.good), copy.deepcopy(self.good)
        second['page_number'] = 2
        cap = len(first['raw_text'])
        with self.fake_child(3, first, second) as child:
            result = process(self.req, self.root, config(max_document_text=cap))
        validate_result(result, config(max_document_text=cap))
        self.assertEqual(child.call_count, 3)  # preflight + two pages, no third
        self.assertEqual(result['pages'][0], first)
        self.assertEqual(result['status'], 'partial')
        self.assertEqual([p['error']['code'] for p in result['pages'][1:]], ['OUTPUT_LIMIT'] * 2)

    def test_fatal_temp_error_marks_remaining_with_actual_cause(self):
        first = copy.deepcopy(self.good)
        with self.fake_child(3, first, ChildFailure('TEMP_LIMIT')):
            result = process(self.req, self.root, config())
        validate_result(result, config())
        self.assertEqual(result['status'], 'partial')
        self.assertEqual(result['pages'][0], first)
        self.assertEqual(result['pages'][1]['error']['code'], 'TEMP_LIMIT')
        self.assertEqual(result['pages'][2]['status'], 'not_processed')
        self.assertEqual(result['pages'][2]['error']['code'], 'TEMP_LIMIT')

    def test_intermediate_cleanup_failure_does_not_fail_document(self):
        original = shutil.rmtree

        def fail_page(path, *args, **kwargs):
            if Path(path).name == 'page-1':
                raise PermissionError('synthetic cleanup fault')
            return original(path, *args, **kwargs)

        with patch('cv_compass_worker.pipeline.shutil.rmtree', side_effect=fail_page):
            result = process(self.req, self.root, config())
        validate_result(result, config())
        self.assertEqual(result['status'], 'success')
        self.assertEqual(result['errors'], [])
        self.assertIn('CLEANUP_FAILED', result['warnings'])
        self.assertFalse((self.root / '.ocr-lock').exists())  # final cleanup recovered

    def test_invalid_identifier_or_path_rejected_before_snapshot(self):
        for key, value in (
            ('request_id', 'synthetic\n'),
            ('input_ref', '../input.pdf'),
            ('input_ref', 'a' * 32 + '.pdf\n'),
            ('expected_sha256', 'a' * 64 + '\n'),
        ):
            with self.subTest(key=key), patch('cv_compass_worker.pipeline.snapshot') as snapshot:
                with self.assertRaises(Exception):
                    process(self.req | {key: value}, self.root, config())
                snapshot.assert_not_called()

    def test_malformed_child_page_does_not_poison_next_page(self):
        bad = copy.deepcopy(self.good)
        bad['warnings'] = ['BLANK_PAGE', 'BLANK_PAGE']
        good = copy.deepcopy(self.good)
        good['page_number'] = 2
        with self.fake_child(2, bad, good):
            result = process(self.req, self.root, config())
        validate_result(result, config())
        self.assertEqual(result['pages'][0]['error']['code'], 'ENGINE_CRASH')
        self.assertEqual(result['pages'][1], good)
        self.assertEqual(result['status'], 'partial')


if __name__ == '__main__':
    unittest.main()
