"""A failed docker create (container never existed) must not count as cleanup failure."""

import json
import unittest
from unittest.mock import patch
from cv_compass_worker.docker_sandbox import create_scope, prepare_control
from cv_compass_worker.sandbox import ChildFailure
from test_pipeline import config

TARGET = ('unix:///fake', {})


class Recorder:
    """Fake docker CLI: records calls; `fail` maps a verb to the error it raises."""

    def __init__(self, fail=None, ps_extra=b'', ps_exact=False):
        self.calls = []
        self.fail = {'create': ChildFailure('INTERNAL_ERROR')} if fail is None else fail
        self.ps_extra = ps_extra
        self.ps_exact = ps_exact

    def __call__(self, args, timeout=20):
        self.calls.append(list(args))
        if args[0] in self.fail:
            raise self.fail[args[0]]
        if args[0] == 'ps':
            name = self.created_name()
            return (name.encode() + b'\n' if self.ps_exact else b'') + self.ps_extra
        if args[0] == 'inspect':
            return json.dumps([{'State': {'Running': False, 'Status': 'created'}}]).encode()
        return b''

    def created_name(self):
        create = next(c for c in self.calls if c[0] == 'create')
        return create[create.index('--name') + 1]

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
        self.assertEqual(exc.code, 'INTERNAL_ERROR')
        self.assertFalse(getattr(exc, 'cleanup_failed', False))
        ps = next(c for c in fake.calls if c[0] == 'ps')
        self.assertEqual(
            ps,
            ['ps', '-a', '--filter', 'name=' + fake.created_name(), '--format', '{{.Names}}'],
        )
        self.assertNotIn('inspect', fake.verbs())
        self.assertNotIn('rm', fake.verbs())

    def test_existing_container_is_removed_after_failed_create(self):
        fake = Recorder(ps_exact=True)
        exc = self.run_with(fake, create_scope, config())
        self.assertFalse(getattr(exc, 'cleanup_failed', False))
        self.assertIn('inspect', fake.verbs())
        self.assertEqual(fake.calls[-1][:2], ['rm', '-f'])

    def test_similar_name_is_not_removed(self):
        fake = Recorder()
        fake.ps_extra = b'x\n'

        def ps_similar(args, timeout=20):
            if args[0] == 'ps':
                fake.calls.append(list(args))
                return (fake.created_name() + 'x\n').encode()
            return Recorder.__call__(fake, args, timeout)

        exc = self.run_with(ps_similar, create_scope, config())
        self.assertFalse(getattr(exc, 'cleanup_failed', False))
        self.assertNotIn('rm', fake.verbs())

    def test_ps_failure_stays_conservative(self):
        fake = Recorder(fail={'create': ChildFailure('INTERNAL_ERROR'), 'ps': ChildFailure('X')})
        exc = self.run_with(fake, create_scope, config())
        self.assertTrue(exc.cleanup_failed)

    def test_create_timeout_stays_conservative(self):
        fake = Recorder(
            fail={'create': ChildFailure('DEPENDENCY_MISSING'), 'inspect': ChildFailure('X')}
        )
        exc = self.run_with(fake, create_scope, config())
        self.assertTrue(exc.cleanup_failed)
        self.assertNotIn('ps', fake.verbs())

    def test_prepare_control_failed_writer_create_removes_volume_only(self):
        fake = Recorder()
        exc = self.run_with(fake, prepare_control, {}, None, config())
        self.assertFalse(getattr(exc, 'cleanup_failed', False))
        self.assertIn(['volume', 'rm', fake.calls[0][-1]], fake.calls)
        self.assertNotIn('inspect', fake.verbs())
        self.assertNotIn('rm', fake.verbs())

    def test_prepare_control_writer_create_timeout_stays_conservative(self):
        fake = Recorder(
            fail={'create': ChildFailure('DEPENDENCY_MISSING'), 'inspect': ChildFailure('X')}
        )
        exc = self.run_with(fake, prepare_control, {}, None, config())
        self.assertTrue(exc.cleanup_failed)

    def test_created_writer_is_removed_without_ps(self):
        # create succeeds, a later step fails; cleanup must not depend on ps.
        fake = Recorder(fail={'start': ChildFailure('INTERNAL_ERROR')})
        with patch(
            'cv_compass_worker.docker_sandbox.docker_cli',
            side_effect=ChildFailure('INTERNAL_ERROR'),
        ):
            self.run_with(fake, prepare_control, {}, None, config())
        self.assertNotIn('ps', fake.verbs())
        self.assertIn('inspect', fake.verbs())
        self.assertIn(['rm', '-f', fake.created_name()], fake.calls)


if __name__ == '__main__':
    unittest.main()
