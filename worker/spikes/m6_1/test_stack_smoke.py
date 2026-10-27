"""Safety guards for the diagnostic executable, not production worker tests."""

import os
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import stack_smoke


class SpikeGuardTests(unittest.TestCase):
    def test_production_rejected_before_engines_and_fixtures(self):
        report = {'checks': []}
        with (
            patch.object(stack_smoke, 'native_fixture') as fixture,
            patch.object(stack_smoke.subprocess, 'run') as child,
        ):
            self.assertEqual(stack_smoke.run(SimpleNamespace(profile='production'), report), 3)
            fixture.assert_not_called()
            child.assert_not_called()
        self.assertEqual(report['error'], 'HARD_ISOLATION_REQUIRED')
        self.assertEqual(report['status'], 'blocked')

    def test_child_environment_does_not_inherit_credentials(self):
        with patch.dict(
            os.environ,
            {
                'PATH': 'dummy-tools',
                'SYSTEMROOT': 'dummy-system',
                'DB_PASSWORD': 'synthetic-secret',
                'AWS_SECRET_ACCESS_KEY': 'synthetic-secret',
                'OMP_THREAD_LIMIT': '100',
            },
            clear=True,
        ):
            env = stack_smoke.safe_engine_env()
        self.assertEqual(
            env, {'PATH': 'dummy-tools', 'SYSTEMROOT': 'dummy-system', 'OMP_THREAD_LIMIT': '1'}
        )

    def test_version_parser_accepts_platform_prefix_not_different_versions(self):
        for header in ('tesseract 5.5.3', 'tesseract v5.5.3.20260724'):
            with self.subTest(header=header):
                self.assertEqual(stack_smoke.tesseract_semver(header), (5, 5, 3))
        for header in (
            'tesseract 5.5.30',
            'tesseract v5.5.2',
            'tesseract 5.6.3',
            'tesseract 5.5.3rc1',
            'unrecognized',
        ):
            with self.subTest(header=header):
                self.assertNotEqual(stack_smoke.tesseract_semver(header), (5, 5, 3))


if __name__ == '__main__':
    unittest.main(verbosity=2)
