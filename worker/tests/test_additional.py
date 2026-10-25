import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from pypdf import PdfReader, PdfWriter
from cv_compass_worker.pdf import preflight
from cv_compass_worker.pipeline import process
from cv_compass_worker.contracts import validate_result
from cv_compass_worker.sandbox import ChildFailure, run_child
from fixtures import native, scan, combine
from test_pipeline import config, request, FONT


class AdditionalBehaviorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='cv-m62-extra-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root/('b'*32+'.pdf')
        native(self.source)
        self.cfg = config()

    def test_preflight_page_cap_50_and_51_real_engine(self):
        combine(self.source, [], blanks=50)
        self.assertEqual(preflight(self.source, 50), 50)
        combine(self.source, [], blanks=51)
        with self.assertRaisesRegex(ValueError, 'PAGE_COUNT_LIMIT'):
            preflight(self.source, 50)

    def test_pipeline_encryption_both_passwords(self):
        for password in ('', 'synthetic-password'):
            with self.subTest(empty=not password):
                writer = PdfWriter()
                writer.add_blank_page(width=100, height=100)
                writer.encrypt(user_password=password, owner_password='synthetic-owner', algorithm='AES-256')
                writer.write(self.source)
                result = process(request(self.source), self.root, self.cfg)
                validate_result(result, self.cfg)
                self.assertEqual(result['status'], 'failed')
                self.assertEqual(result['errors'][0]['code'], 'FILE_ENCRYPTED')
                self.assertEqual(result['pages'], [])
                self.assertFalse((self.root/'.ocr-lock').exists())

    def test_scanned_body_with_native_text_does_not_bypass_ocr(self):
        scanned, text = self.root/'scan.pdf', self.root/'text.pdf'
        scan(scanned, FONT); native(text)
        writer = PdfWriter(clone_from=scanned)
        writer.pages[0].merge_page(PdfReader(text).pages[0])
        writer.write(self.source)
        result = process(request(self.source), self.root, self.cfg)
        validate_result(result, self.cfg)
        self.assertEqual(result['pages'][0]['method'], 'ocr')
        self.assertIn('NATIVE_TEXT_WITH_IMAGES', result['pages'][0]['warnings'])
        self.assertIn('tiếng Việt', result['pages'][0]['raw_text'])

    def test_metadata_rotation_real_engine(self):
        scanned = self.root/'scan.pdf'
        scan(scanned, FONT)
        writer = PdfWriter(clone_from=scanned)
        writer.pages[0].rotate(90)
        writer.write(self.source)
        result = process(request(self.source), self.root, self.cfg)
        validate_result(result, self.cfg)
        self.assertEqual(result['pages'][0]['method'], 'ocr', result['pages'])
        self.assertIn('Synthetic English', result['pages'][0]['raw_text'])

    def test_native_columns_preserve_initial_extraction_without_reordering(self):
        import pdfplumber
        native(self.source, columns=True)
        with pdfplumber.open(self.source) as pdf:
            expected = pdf.pages[0].extract_text(layout=False)
        result = process(request(self.source), self.root, self.cfg)
        validate_result(result, self.cfg)
        self.assertEqual(result['pages'][0]['method'], 'native')
        self.assertEqual(result['pages'][0]['raw_text'], expected)
        self.assertNotIn('text', result['pages'][0])

    def test_cleanup_failure_warns_keeps_success_and_halts_intake(self):
        original = shutil.rmtree
        def fail_final(path, *args, **kwargs):
            if Path(path).name.startswith('.ocr-tmp-'):
                raise PermissionError('synthetic cleanup fault, never exposed')
            return original(path, *args, **kwargs)
        with patch('cv_compass_worker.pipeline.shutil.rmtree', side_effect=fail_final):
            result = process(request(self.source), self.root, self.cfg)
        validate_result(result, self.cfg)
        self.assertEqual(result['status'], 'success')
        self.assertIn('CLEANUP_FAILED', result['warnings'])
        self.assertTrue((self.root/'.ocr-lock').is_file())
        with self.assertRaises(FileExistsError):
            process(request(self.source), self.root, self.cfg)
        # addCleanup owns this ordinary fixture directory; no automatic worker recovery.

    def test_child_crash_maps_to_safe_code(self):
        real_popen = subprocess.Popen
        def crash(*args, **kwargs):
            return real_popen([sys.executable, '-c', 'import os; os._exit(17)'], **kwargs)
        with patch('cv_compass_worker.sandbox.subprocess.Popen', side_effect=crash):
            with self.assertRaises(ChildFailure) as caught:
                run_child({'action':'page'}, self.root/'child', self.cfg, 5)
        self.assertEqual(caught.exception.code, 'ENGINE_CRASH')

    def test_temp_guard_counts_source_snapshot(self):
        result = process(request(self.source), self.root, config(max_temp_bytes=100))
        self.assertEqual(result['errors'][0]['code'], 'TEMP_LIMIT')
        self.assertFalse((self.root/'.ocr-lock').exists())

    def test_cli_minimum_output_exit_three_no_delivery(self):
        from cv_compass_worker.__main__ import main
        cfg = self.root/'config.toml'
        cfg.write_text('mode="diagnostic"\nmax_result_bytes=1\ntesseract='+json.dumps(self.cfg.tesseract)+'\nmodels='+json.dumps(self.cfg.models)+'\n')
        req = self.root/'request.json'; req.write_text(json.dumps(request(self.source)))
        output, report = self.root/'result.json', self.root/'verification.json'
        with patch('sys.stderr') as stderr:
            status = main(['--config',str(cfg),'--workspace',str(self.root),'--request',str(req),
                           '--output',str(output),'--verification-report',str(report),'--synthetic-only'])
        self.assertEqual(status, 3)
        self.assertFalse(output.exists())
        self.assertFalse(report.exists())
        self.assertNotIn('Traceback', str(stderr.write.call_args_list))


    def test_real_tesseract_nonzero_is_safe_ocr_failure(self):
        from PIL import Image
        from cv_compass_worker.ocr import recognize
        target = self.root/'ordinary.png'
        image = Image.new('RGB', (100, 100), 'white')
        try:
            image.save(target, dpi=(300,300))
        finally:
            image.close()
        empty_models = self.root/'empty-models'
        empty_models.mkdir()
        with self.assertRaisesRegex(ValueError, '^OCR_FAILED$'):
            recognize(config(models=str(empty_models)), self.root, target)
        self.assertTrue((self.root/'engine/recognize.stderr.log').is_file())

    def test_config_above_contract_rejected_by_cli_before_pdf(self):
        from cv_compass_worker.__main__ import main
        cfg = self.root/'bad-config.toml'
        cfg.write_text('mode="diagnostic"\nmax_pages=51\ntesseract='+json.dumps(self.cfg.tesseract)+'\nmodels='+json.dumps(self.cfg.models)+'\n')
        req = self.root/'request.json'; req.write_text(json.dumps(request(self.source)))
        output, report = self.root/'result.json', self.root/'verification.json'
        with patch('sys.stderr') as stderr, patch('cv_compass_worker.pipeline.snapshot') as snapshot:
            status = main(['--config',str(cfg),'--workspace',str(self.root),'--request',str(req),
                           '--output',str(output),'--verification-report',str(report),'--synthetic-only'])
        snapshot.assert_not_called()
        self.assertEqual(status, 2)
        self.assertTrue(output.exists())
        envelope = json.loads(output.read_text())
        self.assertEqual(envelope['status'], 'failed')
        self.assertEqual(envelope['errors'][0]['code'], 'INVALID_CONFIGURATION')
        self.assertIn('true', str(stderr.write.call_args_list))


if __name__ == '__main__':
    unittest.main()
