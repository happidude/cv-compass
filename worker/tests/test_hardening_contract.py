"""Exit receipts and additional bounded real-engine requirements."""

import contextlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from pypdf import PdfWriter
from cv_compass_worker.__main__ import main
from cv_compass_worker.contracts import validate_result
from cv_compass_worker.pipeline import process
from fixtures import native, scan, combine
from test_pipeline import config, request, FONT, ROOT
from test_docker_enforcement import scope_config


class ExitContractTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / ('e' * 32 + '.pdf')
        native(self.source)
        self.req = self.root / 'request.json'
        self.req.write_text(json.dumps(request(self.source)))
        cfg = config()
        self.cfg = self.root / 'config.toml'
        self.cfg.write_text(
            'mode="diagnostic"\ntesseract='
            + json.dumps(cfg.tesseract)
            + '\nmodels='
            + json.dumps(cfg.models)
            + '\n'
        )
        self.output = self.root / 'result.json'
        self.report = self.root / 'verification.json'

    def invoke(self):
        args = [
            '--config',
            str(self.cfg),
            '--workspace',
            str(self.root),
            '--request',
            str(self.req),
            '--output',
            str(self.output),
            '--verification-report',
            str(self.report),
            '--synthetic-only',
        ]
        run = subprocess.run(
            [sys.executable, str(ROOT / 'run_worker.py'), *args], capture_output=True, timeout=40
        )
        self.assertEqual(run.stdout, b'')
        event = json.loads(run.stderr)
        self.assertNotIn(str(self.root), run.stderr.decode())
        return run.returncode, event

    def test_success_delivered_zero(self):
        code, event = self.invoke()
        self.assertEqual((code, event['status'], event['delivered']), (0, 'success', True))
        self.assertEqual(json.loads(self.output.read_text())['status'], 'success')

    def test_partial_delivered_one(self):
        digital, scanned = self.root / 'native.pdf', self.root / 'scan.pdf'
        native(digital)
        scan(scanned, FONT)
        combine(self.source, [digital, scanned])
        self.req.write_text(json.dumps(request(self.source)))
        with self.cfg.open('a') as stream:
            stream.write('max_text_code_points_per_field=200\n')
        code, event = self.invoke()
        self.assertEqual((code, event['status'], event['delivered']), (1, 'partial', True))
        data = json.loads(self.output.read_text())
        validate_result(data, config(max_text_code_points_per_field=200))

    def test_failed_delivered_two(self):
        combine(self.source, [], blanks=1)
        self.req.write_text(json.dumps(request(self.source)))
        code, event = self.invoke()
        self.assertEqual((code, event['status'], event['delivered']), (2, 'failed', True))
        self.assertEqual(json.loads(self.output.read_text())['status'], 'failed')

    def test_invalid_config_valid_identity_failed_two(self):
        with self.cfg.open('a') as stream:
            stream.write('max_pages=51\n')
        code, event = self.invoke()
        self.assertEqual((code, event['status'], event['delivered']), (2, 'failed', True))
        self.assertEqual(
            json.loads(self.output.read_text())['errors'][0]['code'], 'INVALID_CONFIGURATION'
        )

    def test_invalid_request_valid_identity_failed_two(self):
        req = request(self.source)
        req['input_ref'] = '../unsafe.pdf'
        self.req.write_text(json.dumps(req))
        code, event = self.invoke()
        self.assertEqual((code, event['status'], event['delivered']), (2, 'failed', True))
        self.assertEqual(
            json.loads(self.output.read_text())['errors'][0]['code'], 'INVALID_REQUEST'
        )

    def test_invalid_identity_no_delivery_three(self):
        self.req.write_text('{"schema_version":"unknown","request_id":"unsafe name"}')
        code, event = self.invoke()
        self.assertEqual((code, event['delivered']), (3, False))
        self.assertNotIn('status', event)
        self.assertFalse(self.output.exists())

    def test_malformed_json_no_delivery_three(self):
        self.req.write_text('{broken-json')
        code, event = self.invoke()
        self.assertEqual((code, event['delivered']), (3, False))
        self.assertFalse(self.output.exists())

    def test_publication_no_overwrite_no_delivery_three(self):
        self.output.write_text('existing sentinel')
        code, event = self.invoke()
        self.assertEqual((code, event['delivered']), (3, False))
        self.assertEqual(self.output.read_text(), 'existing sentinel')

    def test_argument_failure_no_delivery_three(self):
        out = io.StringIO()
        with contextlib.redirect_stderr(out), self.assertRaises(SystemExit) as failure:
            main(['--unrecognized-secret-looking-path'])
        self.assertEqual(failure.exception.code, 3)
        self.assertEqual(json.loads(out.getvalue())['delivered'], False)
        self.assertNotIn('secret-looking', out.getvalue())


class AdditionalEngineBoundaries(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / ('f' * 32 + '.pdf')

    def test_ten_mib_exact_and_plus_one_with_isolated_engine(self):
        cap = 10485760
        native(self.source)
        extra = cap - self.source.stat().st_size
        for _ in range(5):
            native(self.source, padding_bytes=extra)
            difference = self.source.stat().st_size - cap
            if not difference:
                break
            extra -= difference
        self.assertEqual(self.source.stat().st_size, cap)
        cfg = scope_config(preflight_timeout=30, page_timeout=30)
        before = request(self.source)['expected_sha256']
        result = process(request(self.source), self.root, cfg)
        validate_result(result, cfg)
        self.assertEqual(result['status'], 'success', result['errors'])
        self.assertEqual(request(self.source)['expected_sha256'], before)
        with self.source.open('ab') as stream:
            stream.write(b' ')
        self.assertEqual(self.source.stat().st_size, cap + 1)
        with patch('cv_compass_worker.pipeline.run_child') as child:
            result = process(request(self.source), self.root, cfg)
        child.assert_not_called()
        self.assertEqual(result['errors'][0]['code'], 'FILE_SIZE_LIMIT')

    def test_rotations_180_and_270_real_ocr(self):
        scanned = self.root / 'scan.pdf'
        scan(scanned, FONT)
        for angle in (180, 270):
            with self.subTest(angle=angle):
                writer = PdfWriter(clone_from=scanned)
                writer.pages[0].rotate(angle)
                writer.write(self.source)
                cfg = config()
                result = process(request(self.source), self.root, cfg)
                validate_result(result, cfg)
                self.assertEqual(result['pages'][0]['method'], 'ocr', result['pages'][0]['error'])
                self.assertIn('Synthetic English', result['pages'][0]['raw_text'])

    def test_nearblank_is_not_silently_blank(self):
        native(self.source, content=b'BT /F1 8 Tf .94 g 50 760 Td (x) Tj ET')
        cfg = config()
        result = process(request(self.source), self.root, cfg)
        validate_result(result, cfg)
        self.assertEqual(result['pages'][0]['status'], 'failed')
        self.assertNotIn('BLANK_PAGE', result['pages'][0]['warnings'])
        self.assertEqual(result['pages'][0]['error']['code'], 'NO_USABLE_TEXT')

    def test_document_timeout_real_container_watchdog(self):
        native(self.source)
        cfg = scope_config(document_timeout=7.02)
        result = process(request(self.source), self.root, cfg)
        validate_result(result, cfg)
        self.assertEqual(result['status'], 'failed')
        self.assertEqual(result['errors'][0]['code'], 'DOCUMENT_TIMEOUT')
        self.assertFalse((self.root / '.ocr-lock').exists())


if __name__ == '__main__':
    unittest.main()
