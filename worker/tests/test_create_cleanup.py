"""A failed docker create (container never existed) must not count as cleanup failure."""

import json
import unittest
from unittest.mock import patch
from cv_compass_worker.docker_sandbox import create_scope, prepare_control
from cv_compass_worker.sandbox import ChildFailure
from test_pipeline import config

TARGET = ('unix:///fake', {})


class Recorder:
    """Fake docker CLI: records calls, create fails, ps answers `ps_output`."""

    def __init__(self, ps_output=b'', fail_on='create'):
        self.calls = []
        self.ps_output = ps_output
        self.fail_on = fail_on
        self.ps_args = None

    def __call__(self, args, timeout=20):
        self.calls.append(list(args))
        if args[0] == self.fail_on:
            raise ChildFailure('INTERNAL_ERROR')
        if args[0] == 'ps':
            self.ps_args = list(args)
            return self.ps_output() if callable(self.ps_output) else self.ps_output
        if args[0] == 'inspect':
            return json.dumps([{'State': {'Running': False, 'Status': 'created'}}]).encode()
        return b''

    def verbs(self):
        return [c[0] for c in self.calls]


class CreateCleanupTests(unittest.TestCase):
    def run_with(self, fake, func, *args):
        with (
            patch('cv_compass_worker.docker_sandbox.resolve_target', return_value=TARGET),
            patch('cv_compass_worker.docker_sandbox.command', fake),
        ):
            with self.assertRaises(ChildFailure) as caught:
                func(*args)
        return caught.exception

    def test_failed_create_scope_is_not_cleanup_failure(self):
        fake = Recorder()
        exc = self.run_with(fake, create_scope, config())
        self.assertEqual(fake.ps_args[:4], ['ps', '-a', '--filter', fake.ps_args[3]])
        self.assertTrue(fake.ps_args[3].startswith('name=cvcompass-ocr-'))
        self.assertNotIn('^', fake.ps_args[3])
        self.assertEqual(fake.ps_args[-2:], ['--format', '{{.Names}}'])
        self.assertEqual(exc.code, 'INTERNAL_ERROR')
        self.assertFalse(getattr(exc, 'cleanup_failed', False))
        self.assertNotIn('inspect', fake.verbs())
        self.assertNotIn('rm', fake.verbs())

    def test_existing_container_is_removed_after_failed_create(self):
        names = []

        def ps():
            return names[0].encode() + b'\n'

        fake = Recorder(ps_output=ps)
        original = fake.__call__

        def spy(args, timeout=20):
            if args[0] == 'create':
                names.append(args[args.index('--name') + 1])
            return original(args, timeout)

        exc = self.run_with(spy, create_scope, config())
        self.assertFalse(getattr(exc, 'cleanup_failed', False))
        self.assertIn('inspect', fake.verbs())
        self.assertEqual(fake.calls[-1][:2], ['rm', '-f'])

    def test_ps_failure_stays_conservative(self):
        fake = Recorder(fail_on='ps')
        original = fake.__call__

        def both(args, timeout=20):
            if args[0] == 'create':
                fake.calls.append(list(args))
                raise ChildFailure('INTERNAL_ERROR')
            return original(args, timeout)

        exc = self.run_with(both, create_scope, config())
        self.assertTrue(exc.cleanup_failed)

    def test_prepare_control_failed_writer_create_removes_volume_only(self):
        fake = Recorder()
        exc = self.run_with(fake, prepare_control, {}, None, config())
        self.assertFalse(getattr(exc, 'cleanup_failed', False))
        self.assertIn(['volume', 'rm', fake.calls[0][-1]], fake.calls)
        self.assertNotIn('inspect', fake.verbs())
        self.assertNotIn('rm', fake.verbs())


if __name__ == '__main__':
    unittest.main()
