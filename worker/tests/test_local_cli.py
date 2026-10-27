"""Local opt-in still uses the real diagnostic CLI and existing synthetic fixture."""

import json
import subprocess
import sys
import unittest

import test_pipeline as support


class LocalCliTests(unittest.TestCase):
    def setUp(self):
        support.PipelineTests.setUp(self)

    def test_local_diagnostic_gate_preserves_native_result(self):
        config = self.root / 'local.toml'
        config.write_text(
            'mode="diagnostic"\ntesseract='
            + json.dumps(support.TESS)
            + '\nmodels='
            + json.dumps(support.MODELS)
            + '\n',
            encoding='utf-8',
        )
        request = self.root / 'request.json'
        request.write_text(json.dumps(self.req), encoding='utf-8')
        output, report = self.root / 'result.json', self.root / 'verification.json'
        completed = subprocess.run(
            [
                sys.executable,
                str(support.ROOT / 'run_worker.py'),
                '--config',
                str(config),
                '--workspace',
                str(self.root),
                '--request',
                str(request),
                '--output',
                str(output),
                '--verification-report',
                str(report),
                '--local-only',
            ],
            capture_output=True,
            timeout=30,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(completed.stdout, b'')
        self.assertEqual(
            json.loads(completed.stderr),
            {'code': 'RESULT_DELIVERED', 'delivered': True, 'status': 'success'},
        )
        self.assertEqual(json.loads(output.read_text())['status'], 'success')
        self.assertFalse(json.loads(report.read_text())['resource_safety_verified'])
