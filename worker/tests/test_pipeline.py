import copy
from dataclasses import replace
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from cv_compass_worker.config import Config, LIMITS
from cv_compass_worker.contracts import classify, fit_result, page, validate_result
from cv_compass_worker.pipeline import empty_result, process, publish
from cv_compass_worker.sandbox import ChildFailure, run_child, safe_env
from fixtures import native, scan, combine

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT/'build/m6-1-spike'
TESS = os.environ.get('OCR_TEST_TESSERACT', str(BUILD/'tesseract-portable/tesseract.exe'))
MODELS = os.environ.get('OCR_TEST_MODELS', str(BUILD/'models'))
FONT = os.environ.get('OCR_TEST_FONT', str(BUILD/'dejavu-fonts-ttf-2.37/ttf/DejaVuSans.ttf'))


def config(**values):
    return Config.parse(dict(tesseract=TESS, models=MODELS, mode='diagnostic') | values)


def request(path):
    return dict(type='request', schema_version='cv-compass.ocr/2.0', request_id='synthetic-request',
                document_id='synthetic-document', input_ref=path.name,
                expected_sha256=hashlib.sha256(path.read_bytes()).hexdigest(), profile='baseline-v1')


class PipelineTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='cv-m62-tests-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root/('a'*32+'.pdf')
        native(self.source)
        self.req = request(self.source)
        self.cfg = config()

    def run_pdf(self, cfg=None):
        before = self.source.read_bytes()
        req = request(self.source)
        result = process(req, self.root, cfg or self.cfg)
        validate_result(result, cfg or self.cfg)
        self.assertEqual(before, self.source.read_bytes())
        self.assertFalse((self.root/'.ocr-lock').exists())
        self.assertFalse(list(self.root.glob('.ocr-tmp-*')))
        return result

    def test_native_real_engine(self):
        result = self.run_pdf()
        self.assertEqual(result['status'], 'success', result['errors'])
        self.assertEqual(result['pages'][0]['method'], 'native')
        self.assertIn('Second line', result['pages'][0]['raw_text'])
        self.assertIsNone(result['pages'][0]['confidence'])
        self.assertEqual(len(result['engines']['models']), 3)

    def test_mixed_native_scan_blank_real_engines(self):
        digital, scanned = self.root/'native.pdf', self.root/'scan.pdf'
        native(digital); scan(scanned, FONT)
        combine(self.source, [digital, scanned], blanks=1)
        result = self.run_pdf()
        self.assertEqual(result['status'], 'success', result['errors'])
        self.assertEqual([p['status'] for p in result['pages']], ['ok', 'ok', 'blank'])
        self.assertEqual([p['method'] for p in result['pages']], ['native', 'ocr', 'none'])
        text = result['pages'][1]['raw_text']
        self.assertIn('tiếng Việt', text)
        self.assertIn('kiểm thử', text)
        self.assertIn('contact@example.com', text)
        self.assertGreater(result['pages'][1]['confidence']['sample_count'], 0)

    def test_all_blank_failed(self):
        combine(self.source, [], blanks=1)
        result = self.run_pdf()
        self.assertEqual(result['status'], 'failed')
        self.assertEqual(result['pages'][0]['status'], 'blank')

    def test_field_output_limit_no_truncation(self):
        result = self.run_pdf(config(max_text_code_points_per_field=10))
        self.assertEqual(result['status'], 'failed')
        self.assertEqual(result['pages'][0]['error']['code'], 'OUTPUT_LIMIT')
        self.assertEqual(result['pages'][0]['raw_text'], '')
        self.assertGreater(result['pages'][0]['timings_ms']['native'], 0)
        self.assertGreater(result['pages'][0]['timings_ms']['total'], 0)

    def test_page_failure_continues_real_engines(self):
        # Two ordinary pages; only the second exceeds an operator-reduced
        # field cap. No corrupt/resource-bomb fixture is used on Windows.
        digital, scanned = self.root/'native.pdf', self.root/'scan.pdf'
        native(digital); scan(scanned, FONT)
        combine(self.source, [digital, scanned])
        result = self.run_pdf(config(max_text_code_points_per_field=200))
        self.assertEqual(result['status'], 'partial', result['errors'])
        self.assertEqual(result['pages'][0]['status'], 'ok')
        self.assertEqual(result['pages'][1]['error']['code'], 'OUTPUT_LIMIT')

    def test_missing_model_and_engine(self):
        for cfg, expected in ((config(models=str(self.root/'missing')), 'MODEL_MISSING'),
                              (config(tesseract=str(self.root/'missing.exe')), 'DEPENDENCY_MISSING')):
            with self.subTest(expected=expected):
                result = self.run_pdf(cfg)
                self.assertEqual(result['errors'][0]['code'], expected)
                self.assertIsNone(result['page_count'])

    def test_input_hash_and_byte_limit(self):
        for req, cfg, expected in ((self.req | {'expected_sha256': '0'*64}, self.cfg, 'INPUT_HASH_MISMATCH'),
                                    (self.req, config(max_file_bytes=10), 'FILE_SIZE_LIMIT')):
            with self.subTest(expected=expected):
                result = process(req, self.root, cfg)
                self.assertEqual(result['errors'][0]['code'], expected)

    def test_production_refuses_before_snapshot(self):
        with patch('cv_compass_worker.pipeline.snapshot') as snapshot:
            with self.assertRaisesRegex(ValueError, 'INVALID_CONFIGURATION'):
                process(self.req, self.root, config(mode='production'))
            snapshot.assert_not_called()

    def test_real_child_timeout_cleanup(self):
        child_dir = self.root/'timeout'
        with self.assertRaises(ChildFailure) as caught:
            run_child(dict(action='page', source=str(self.source), number=1), child_dir, self.cfg, .001)
        self.assertEqual(caught.exception.code, 'PAGE_TIMEOUT')
        # process.wait is completed before run_child raises; parent can remove
        # the directory. This is not a proof of Windows descendant containment.
        import shutil
        shutil.rmtree(child_dir)
        self.assertFalse(child_dir.exists())

    def test_no_overwrite_publication(self):
        target = self.root/'out.json'
        publish(target, b'first')
        with self.assertRaises(FileExistsError):
            publish(target, b'second')
        self.assertEqual(target.read_bytes(), b'first')
        self.assertFalse(list(self.root.glob('.ocr-publish-*')))

    def test_child_environment_excludes_credentials(self):
        with patch.dict(os.environ, {'DB_PASSWORD': 'dummy', 'AWS_SECRET_ACCESS_KEY': 'dummy'}):
            env = safe_env()
        self.assertNotIn('DB_PASSWORD', env)
        self.assertNotIn('AWS_SECRET_ACCESS_KEY', env)
        self.assertEqual(env['OMP_THREAD_LIMIT'], '1')

    def test_cli_real_native(self):
        cfg = self.root/'config.toml'
        cfg.write_text('mode = "diagnostic"\ntesseract = '+json.dumps(TESS)+'\nmodels = '+json.dumps(MODELS)+'\n', encoding='utf-8')
        req = self.root/'request.json'
        req.write_text(json.dumps(self.req), encoding='utf-8')
        output, report = self.root/'result.json', self.root/'verification.json'
        command = [sys.executable, str(ROOT/'run_worker.py'), '--config', str(cfg), '--workspace', str(self.root),
                   '--request', str(req), '--output', str(output), '--verification-report', str(report), '--synthetic-only']
        completed = subprocess.run(command, capture_output=True, timeout=30)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(completed.stdout, b'')
        self.assertEqual(json.loads(completed.stderr), {'code':'RESULT_DELIVERED', 'delivered':True, 'status':'success'})
        self.assertEqual(json.loads(output.read_text())['status'], 'success')
        verification = json.loads(report.read_text())
        self.assertEqual(verification['status'], 'not_enforced')
        self.assertFalse(verification['resource_safety_verified'])
        if directory := os.environ.get('OCR_TEST_REPORT_DIR'):
            target = Path(directory)
            target.mkdir(parents=True, exist_ok=True)
            (target/'cli-verification.json').write_text(json.dumps(verification, indent=2), encoding='utf-8')


class ContractRuntimeTests(unittest.TestCase):
    def test_config_caps_and_unknown(self):
        for values in ({'max_pages': 51}, {'max_text_code_points_per_field': 50001}, {'max_pages': True},
                       {'concurrency': 2}, {'unknown': 1}, {'page_timeout': float('nan')}):
            with self.subTest(values=values), self.assertRaises(ValueError):
                config(**values)
        self.assertEqual(config(max_pages=1).max_pages, 1)
        self.assertEqual(config().max_pages, LIMITS['max_pages'])
        self.assertEqual(config().max_text_code_points_per_field, LIMITS['max_text_code_points_per_field'])

    def test_output_keeps_whole_prefix(self):
        source = json.loads((ROOT/'design/examples-v2/result-success.json').read_text(encoding='utf-8'))
        original = copy.deepcopy(source['pages'][0])
        cap = len(original['raw_text'])
        result = json.loads(fit_result(source, config(max_document_text=cap)))
        self.assertEqual(result['pages'][0], original)
        self.assertEqual(result['pages'][1]['error']['code'], 'OUTPUT_LIMIT')
        self.assertEqual(result['status'], 'partial')
        validate_result(result, config())
        with self.assertRaisesRegex(ValueError, 'OUTPUT_LIMIT'):
            fit_result(source, config(max_result_bytes=1))

    def test_all_ok_document_error_partial_and_blank_failed(self):
        self.assertEqual(classify([{'status':'ok'}], [{'code':'OUTPUT_LIMIT'}]), 'partial')
        self.assertEqual(classify([{'status':'blank'}], []), 'failed')


if __name__ == '__main__':
    unittest.main()
