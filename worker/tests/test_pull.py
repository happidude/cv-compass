"""Real loopback HTTP stand-in + supervisor unit tests. NOT backend/PG/Docker integration."""

from dataclasses import replace
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
import uuid
from cv_compass_worker.config import Config
from cv_compass_worker.pull_config import PullConfig
from cv_compass_worker.pull_http import (
    BASE,
    PROTOCOL,
    WorkerHttp,
    HttpFailure,
    strict_json,
    json_bytes,
)
from cv_compass_worker.pull import Lease, PullSupervisor, submission, write_once


def config(**values):
    return PullConfig.parse(
        dict(
            origin='http://127.0.0.1:1',
            credential_env='CV_COMPASS_OCR_TEST_SECRET',
            spool_root=str(Path(tempfile.gettempdir()) / 'private'),
            pipeline_config=str(Path(tempfile.gettempdir()) / 'm6.toml'),
            profile_revision='synthetic-v1',
            worker_commit='a' * 40,
            image_id='sha256:' + 'b' * 64,
            config_fingerprint='c' * 64,
            enabled=True,
            retry_backoff_seconds=0.001,
        )
        | values
    )


class ConfigTests(unittest.TestCase):
    def test_local_admission_requires_explicit_exclusive_opt_in(self):
        self.assertTrue(config(synthetic_only=False, local_only=True).local_only)
        for values in (
            {'local_only': True},
            {'synthetic_only': False},
            {'synthetic_only': None, 'local_only': True},
            {'local_only': 'true'},
        ):
            with self.subTest(values=values), self.assertRaises(ValueError):
                config(**values)

    def test_credential_env_cannot_alias_forwarded_runtime_settings(self):
        from cv_compass_worker.sandbox import safe_env
        from cv_compass_worker.docker_sandbox import docker_env

        for name in ('LANG', 'LC_ALL', 'PATH', 'HOME', 'DOCKER_CONFIG'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                config(credential_env=name)
        with patch.dict(os.environ, {'CV_COMPASS_OCR_TEST_SECRET': 'synthetic-secret'}):
            self.assertNotIn('CV_COMPASS_OCR_TEST_SECRET', safe_env())
            self.assertNotIn('CV_COMPASS_OCR_TEST_SECRET', docker_env())

    def test_remote_plain_http_credentials_paths_and_unknown_options_rejected(self):
        for values in (
            {'origin': 'http://192.0.2.1'},
            {'origin': 'https://user:pass@example.test'},
            {'origin': 'https://example.test/path'},
            {'spool_root': 'relative'},
            {'bypass': True},
            {'enabled': False},
            {'synthetic_only': False},
            {'image_id': 'latest'},
            {'http_timeout_seconds': float('nan')},
            {'max_file_bytes': 10485761},
        ):
            with self.subTest(values=values), self.assertRaises((ValueError, TypeError)):
                config(**values)

    def test_limits_can_only_reduce(self):
        self.assertEqual(config(max_file_bytes=10).max_file_bytes, 10)
        for values in (
            {'max_result_bytes': 8388609},
            {'max_envelope_bytes': 8454145},
            {'max_http_attempts': 11},
        ):
            with self.subTest(values=values), self.assertRaises(ValueError):
                config(**values)

    def test_strict_json_duplicate_nonfinite_utf8_depth(self):
        for raw in (
            b'{"a":1,"a":2}',
            b'{"a":NaN}',
            b'{} {}',
            b'"\xff"',
            b'[' * 34 + b'0' + b']' * 34,
        ):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                strict_json(raw)


class HttpTests(unittest.TestCase):
    def setUp(self):
        self.events = []
        self.responses = []
        self.pause_body = False
        self.release_body = threading.Event()
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                self.respond()

            def do_POST(self):
                self.respond()

            def do_PUT(self):
                self.respond()

            def respond(self):
                raw = self.rfile.read(int(self.headers.get('Content-Length', '0')))
                owner.events.append((self.command, self.path, raw, dict(self.headers)))
                status, headers, body = owner.responses.pop(0)
                self.send_response(status)
                for name, value in headers.items():
                    self.send_header(name, value)
                self.end_headers()
                if owner.pause_body:
                    owner.release_body.wait(2)
                try:
                    self.wfile.write(body)
                except BrokenPipeError, ConnectionResetError:
                    pass  # Expected client socket deadline in the timeout negative.

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop)
        self.cfg = config(origin='http://127.0.0.1:' + str(self.server.server_port))
        self.http = WorkerHttp(self.cfg, 's' * 43)
        self.attempt_id = str(uuid.uuid4())
        self.path = BASE + '/attempts/' + self.attempt_id + '/result'

    def stop(self):
        self.release_body.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)

    def test_retry_sends_identical_utf8_bytes_and_headers(self):
        self.responses = [(503, {}, b'{}'), (201, {}, b'{"data":{"accepted":true}}')]
        raw = ' {"text":"Tiếng Việt\\n"}\r\n'.encode()
        self.assertTrue(self.http.request('PUT', self.path, raw, 't' * 43)['accepted'])
        self.assertEqual([event[2] for event in self.events], [raw, raw])
        self.assertEqual(self.events[0][3]['X-Lease-Token'], 't' * 43)
        # Retries are distinct HTTP requests while idempotency/body/token remain unchanged.
        correlations = [entry[3]['X-Correlation-ID'] for entry in self.events]
        self.assertEqual(len(set(correlations)), 2)
        for correlation in correlations:
            self.assertEqual(str(uuid.UUID(correlation)), correlation)

    def test_redirect_401_403_409_do_not_retry(self):
        for status in (302, 401, 403, 409):
            self.responses = [(status, {'Location': 'http://192.0.2.1/'}, b'{}')]
            before = len(self.events)
            with self.assertRaises(HttpFailure):
                self.http.request('PUT', self.path, b'{}', 't' * 43)
            self.assertEqual(len(self.events), before + 1)

    def test_retry_is_bounded(self):
        self.responses = [(503, {}, b'{}')] * 3
        with self.assertRaises(HttpFailure):
            self.http.request('PUT', self.path, b'{}', 't' * 43)
        self.assertEqual(len(self.events), 3)

    def test_no_job_204_and_duplicate_response_keys(self):
        self.responses = [(204, {}, b'')]
        self.assertIsNone(
            self.http.request('POST', BASE + '/claims', b'{}', 't' * 43, str(uuid.uuid4()))
        )
        self.responses = [(200, {}, b'{"data":{},"data":{}}')]
        with self.assertRaises(ValueError):
            self.http.request('POST', BASE + '/claims', b'{}', 't' * 43, str(uuid.uuid4()))

    def test_absolute_input_url_rejected_before_network(self):
        with self.assertRaises(ValueError):
            self.http.request('GET', 'https://remote.invalid/input', None, 't' * 43)
        self.assertEqual(self.events, [])

    def test_stream_size_and_hash_checked(self):
        raw = b'%PDF-synthetic'
        digest = hashlib.sha256(raw).hexdigest()
        self.responses = [
            (
                200,
                {
                    'Content-Type': 'application/pdf',
                    'Content-Length': str(len(raw)),
                    'X-Content-SHA256': digest,
                },
                raw,
            )
        ]
        with tempfile.TemporaryDirectory() as temp:
            target = Path(temp) / 'input.pdf'
            self.http.download(
                self.attempt_id, 't' * 43, target, len(raw), digest, live=lambda: True
            )
            self.assertEqual(target.read_bytes(), raw)
            if os.name == 'posix':
                self.assertEqual(target.stat().st_mode & 0o777, 0o600)

    def test_ten_mib_and_plus_one_admission(self):
        raw = b'x' * 10485760
        digest = hashlib.sha256(raw).hexdigest()
        self.responses = [
            (
                200,
                {
                    'Content-Type': 'application/pdf',
                    'Content-Length': str(len(raw)),
                    'X-Content-SHA256': digest,
                },
                raw,
            )
        ]
        with tempfile.TemporaryDirectory() as temp:
            self.http.download(
                self.attempt_id,
                't' * 43,
                Path(temp) / 'input.pdf',
                len(raw),
                digest,
                live=lambda: True,
            )
            with self.assertRaises(ValueError):
                self.http.download(
                    self.attempt_id,
                    't' * 43,
                    Path(temp) / 'other.pdf',
                    len(raw) + 1,
                    digest,
                    live=lambda: True,
                )
        self.assertEqual(len(self.events), 1)

    def test_wrong_hash_and_truncation_refused(self):
        for raw in (b'wrong', b'x'):
            self.responses = [
                (
                    200,
                    {
                        'Content-Type': 'application/pdf',
                        'Content-Length': '5',
                        'X-Content-SHA256': 'a' * 64,
                    },
                    raw,
                )
            ]
            with tempfile.TemporaryDirectory() as temp, self.assertRaises((ValueError, OSError)):
                self.http.download(
                    self.attempt_id,
                    't' * 43,
                    Path(temp) / 'input.pdf',
                    5,
                    'a' * 64,
                    live=lambda: True,
                )

    def test_lease_loss_prevents_input_read(self):
        self.responses = [
            (
                200,
                {
                    'Content-Type': 'application/pdf',
                    'Content-Length': '5',
                    'X-Content-SHA256': 'a' * 64,
                },
                b'input',
            )
        ]
        with tempfile.TemporaryDirectory() as temp, self.assertRaises(ValueError):
            self.http.download(
                self.attempt_id, 't' * 43, Path(temp) / 'input.pdf', 5, 'a' * 64, live=lambda: False
            )

    def test_download_socket_deadline_rejects_stalled_body(self):
        self.pause_body = True
        self.responses = [
            (
                200,
                {
                    'Content-Type': 'application/pdf',
                    'Content-Length': '5',
                    'X-Content-SHA256': 'a' * 64,
                },
                b'input',
            )
        ]
        self.http = WorkerHttp(replace(self.cfg, download_timeout_seconds=0.1), 's' * 43)
        with tempfile.TemporaryDirectory() as temp, self.assertRaises(OSError):
            self.http.download(
                self.attempt_id, 't' * 43, Path(temp) / 'input.pdf', 5, 'a' * 64, live=lambda: True
            )


class HttpCleanupTests(unittest.TestCase):
    def test_response_close_failure_still_closes_connection_and_preserves_primary(self):
        from cv_compass_worker.pull_http import Exchange
        from unittest.mock import Mock

        connection = Mock()
        exchange = Exchange(connection, 10)
        exchange.response = Mock()
        exchange.response.close.side_effect = OSError('synthetic close failure')
        primary = HttpFailure(409)
        with self.assertRaises(HttpFailure) as raised:
            try:
                raise primary
            finally:
                exchange.close()
        self.assertIs(raised.exception, primary)
        self.assertTrue(primary.cleanup_failed)
        self.assertIn('HTTP_CLEANUP_FAILED; intake must remain halted', primary.__notes__)
        connection.close.assert_called_once()

    def test_cleanup_flag_prevents_retry_even_for_transport_error(self):
        from unittest.mock import Mock

        http = WorkerHttp(config(), 's' * 43)
        failure = OSError('synthetic primary')
        failure.cleanup_failed = True
        with patch.object(http, '_open', side_effect=failure) as opened, self.assertRaises(OSError):
            http.request('POST', BASE + '/claims', b'{}', 't' * 43, str(uuid.uuid4()))
        opened.assert_called_once()

    def test_close_failure_without_primary_is_reported(self):
        from cv_compass_worker.pull_http import Exchange
        from unittest.mock import Mock

        connection = Mock()
        exchange = Exchange(connection, 10)
        exchange.response = Mock()
        exchange.response.close.side_effect = OSError('synthetic close failure')
        with self.assertRaises(OSError):
            exchange.close()
        connection.close.assert_called_once()


class LeaseTests(unittest.TestCase):
    def test_failed_renewal_cancels_without_granting_new_time(self):
        from unittest.mock import Mock

        http = Mock()
        http.request.side_effect = HttpFailure(409)
        lease = Lease(config(), http, self.claim(), 't' * 43)
        original = lease._expires
        with patch.object(lease.stopped, 'wait', return_value=False):
            lease._renew()
        self.assertTrue(lease.cancelled.is_set())
        self.assertEqual(lease._expires, original)

    def claim(self):
        return dict(
            attempt_id=str(uuid.uuid4()),
            server_time='2026-01-01T00:00:00Z',
            lease_expires_at='2026-01-01T00:02:00Z',
            hard_deadline_at='2026-01-01T00:15:00Z',
        )

    def test_local_deadline_uses_monotonic_not_machine_wall_clock(self):
        with patch('cv_compass_worker.pull.time.monotonic', return_value=100):
            lease = Lease(config(), None, self.claim(), 't' * 43)
        with patch('cv_compass_worker.pull.time.monotonic', return_value=199):
            self.assertTrue(lease.live())
        with patch('cv_compass_worker.pull.time.monotonic', return_value=200):
            self.assertFalse(lease.live())

    def test_db_rollback_cannot_extend_local_hard_deadline(self):
        claim = self.claim()
        with patch('cv_compass_worker.pull.time.monotonic', return_value=100):
            lease = Lease(config(), None, claim, 't' * 43)
        claim['server_time'] = '2025-12-31T23:59:00Z'
        with patch('cv_compass_worker.pull.time.monotonic', return_value=110):
            lease._update(claim)
        self.assertEqual(lease._hard, 1000)


class SubmissionTests(unittest.TestCase):
    def test_raw_m6_bytes_preserved_and_exit_contract_checked(self):
        from cv_compass_worker.pipeline import empty_result

        pipeline = Config()
        result = empty_result(
            dict(schema_version='cv-compass.ocr/2.0', request_id='synthetic', document_id='42'),
            pipeline,
        )
        result['timings_ms']['total'] = 0
        raw = json_bytes(result) + b'\r\n'
        body, _ = submission(raw, 2, config(), pipeline)
        self.assertIn(b'"ocr_result":' + raw + b'}', body)
        with self.assertRaises(ValueError):
            submission(raw, 0, config(), pipeline)

    def test_atomic_spool_does_not_overwrite(self):
        with tempfile.TemporaryDirectory() as temp:
            target = Path(temp) / 'submission.json'
            write_once(target, b'first')
            with self.assertRaises(FileExistsError):
                write_once(target, b'second')
            self.assertEqual(target.read_bytes(), b'first')

    def test_synthetic_profile_and_docker_gate_cannot_be_bypassed(self):
        with tempfile.TemporaryDirectory() as temp:
            os.chmod(temp, 0o700)
            with self.assertRaises(ValueError):
                PullSupervisor(config(spool_root=temp), None, Config())


class SupervisorTests(unittest.TestCase):
    """State orchestration units with explicit fake HTTP and CLI; not integration evidence."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        os.chmod(self.root, 0o700)
        self.pipeline_path = self.root / 'operator.toml'
        self.pipeline_path.write_text(
            'mode="diagnostic"\nadapter="docker"\ntesseract='
            + json.dumps(str(self.root / 'synthetic-tesseract'))
            + '\nmodels='
            + json.dumps(str(self.root / 'synthetic-models'))
            + '\ndocker_image="sha256:'
            + 'b' * 64
            + '"\n'
        )
        self.pipeline = Config.load(self.pipeline_path)
        # Keep the operator file outside the owned spool; cleanup must preserve it.
        self.spool = self.root / 'spool'
        self.spool.mkdir(mode=0o700)
        self.cfg = config(
            spool_root=str(self.spool),
            pipeline_config=str(self.pipeline_path),
            config_fingerprint=self.pipeline.fingerprint(),
        )
        self.attempt = str(uuid.uuid4())
        self.claim = dict(
            protocol=PROTOCOL,
            job_id='1',
            attempt_id=self.attempt,
            attempt_no=1,
            server_time='2026-01-01T00:00:00Z',
            lease_expires_at='2026-01-01T00:02:00Z',
            hard_deadline_at='2026-01-01T00:15:00Z',
            profile_revision='synthetic-v1',
            synthetic_only=True,
            input_path=BASE + '/attempts/' + self.attempt + '/input',
            size_bytes=5,
            ocr_request=dict(
                type='request',
                schema_version='cv-compass.ocr/2.0',
                request_id='a_' + self.attempt.replace('-', ''),
                document_id='42',
                input_ref=self.attempt.replace('-', '') + '.pdf',
                expected_sha256=hashlib.sha256(b'%PDF-').hexdigest(),
                profile='baseline-v1',
            ),
        )
        from unittest.mock import Mock

        self.http = Mock()
        self.http.request.side_effect = lambda method, path, *args, **kwargs: (
            self.claim
            if path.endswith('/claims')
            else dict(accepted=True, job_id='1', attempt_id=self.attempt)
        )
        self.http.download.side_effect = lambda attempt, token, target, *args, **kwargs: (
            target.write_bytes(b'%PDF-')
        )
        if os.name == 'nt':
            # Linux-only host permissions have a separate rejection test; orchestration is a unit here.
            root_patch = patch('cv_compass_worker.pull.private_root', return_value=self.spool)
            root_patch.start()
            self.addCleanup(root_patch.stop)
        self.supervisor = PullSupervisor(self.cfg, self.http, self.pipeline)

    def cli(self, argv, *, cancelled):
        from cv_compass_worker.pipeline import empty_result

        request = strict_json(Path(argv[argv.index('--request') + 1]).read_bytes())
        result = empty_result(request, self.pipeline)
        result['timings_ms']['total'] = 0
        if getattr(self, 'cleanup_failure', False):
            result['warnings'] = ['CLEANUP_FAILED']
            (Path(argv[argv.index('--workspace') + 1]) / '.ocr-lock').touch()
        Path(argv[argv.index('--output') + 1]).write_bytes(json_bytes(result))
        return 2

    def test_calls_existing_cli_and_removes_only_owned_spool_after_ack(self):
        with patch('cv_compass_worker.__main__.main', side_effect=self.cli) as cli:
            self.assertTrue(self.supervisor.once())
        cli.assert_called_once()
        self.assertTrue(self.pipeline_path.exists())
        self.assertEqual(list(self.spool.iterdir()), [])
        sent = self.http.request.call_args.args[2]
        self.assertEqual(strict_json(sent)['exit_code'], 2)

    def test_uncertain_ack_replays_exact_spool_without_download_or_second_ocr(self):
        original = self.http.request.side_effect

        def fail_ack(method, path, *args, **kwargs):
            if path.endswith('/result'):
                raise OSError('synthetic lost ack')
            return original(method, path, *args, **kwargs)

        self.http.request.side_effect = fail_ack
        with patch('cv_compass_worker.__main__.main', side_effect=self.cli) as cli:
            with self.assertRaises(OSError):
                self.supervisor.once()
            sent = self.http.request.call_args.args[2]
            self.http.request.side_effect = original
            self.assertTrue(self.supervisor.once())
            self.assertEqual(self.http.request.call_args.args[2], sent)
            cli.assert_called_once()
        self.http.download.assert_called_once()

    def test_cleanup_warning_is_delivered_terminal_but_intake_halts_and_lock_survives(self):
        self.cleanup_failure = True
        with (
            patch('cv_compass_worker.__main__.main', side_effect=self.cli),
            self.assertRaisesRegex(ValueError, 'CLEANUP_FAILED'),
        ):
            self.supervisor.once()
        sent = self.http.request.call_args.args[2]
        self.assertEqual(strict_json(sent)['ocr_result']['warnings'], ['CLEANUP_FAILED'])
        self.assertEqual(strict_json(sent)['ocr_result']['status'], 'failed')
        self.assertTrue(list(self.spool.glob('*/.ocr-lock')))
        # Even if a lock/ledger disappears, the durable warning still halts replay cleanup.
        for lock in self.spool.glob('*/.ocr-lock'):
            lock.unlink()
        with self.assertRaisesRegex(ValueError, 'CLEANUP_FAILED'):
            self.supervisor.once()
        self.assertTrue((self.spool / 'claim.json').exists())

    def test_operator_config_change_after_validation_does_not_change_pipeline_snapshot(self):
        self.pipeline_path.write_text('mode="production"\n')
        with patch('cv_compass_worker.__main__.main', side_effect=self.cli):
            self.assertTrue(self.supervisor.once())
        # Snapshot was validated before claim; no config downloaded from backend is accepted.

    def test_unenrolled_claim_refused_before_input(self):
        self.claim['synthetic_only'] = False
        with self.assertRaises(ValueError):
            self.supervisor.once()
        self.http.download.assert_not_called()

    def test_local_worker_rejects_synthetic_claim_and_uses_local_cli_gate(self):
        self.cfg = replace(self.cfg, synthetic_only=False, local_only=True)
        self.supervisor = PullSupervisor(self.cfg, self.http, self.pipeline)
        with self.assertRaisesRegex(ValueError, 'CLAIM_CONTRACT'):
            self.supervisor._claim(self.claim)
        self.claim.update(synthetic_only=False, local_only=True)
        self.supervisor._claim(self.claim)
        with patch('cv_compass_worker.__main__.main', side_effect=self.cli) as cli:
            self.assertTrue(self.supervisor.once())
        self.assertIn('--local-only', cli.call_args.args[0])
        self.assertNotIn('--synthetic-only', cli.call_args.args[0])

    def test_foreign_input_path_refused_before_download(self):
        self.claim['input_path'] = 'https://remote.invalid/input'
        with self.assertRaises(ValueError):
            self.supervisor.once()
        self.http.download.assert_not_called()

    def test_hash_mismatch_reports_nonretryable_failure_and_never_invokes_ocr(self):
        from cv_compass_worker.pull_http import InputIntegrityError

        self.http.download.side_effect = InputIntegrityError('INPUT_INTEGRITY')
        with patch('cv_compass_worker.__main__.main') as cli:
            self.assertTrue(self.supervisor.once())
        cli.assert_not_called()
        self.assertEqual(
            strict_json(self.http.request.call_args.args[2])['code'], 'INPUT_HASH_MISMATCH'
        )

    def test_transfer_time_rechecked_before_pipeline_start(self):
        with patch('cv_compass_worker.pull.time.monotonic', return_value=100) as clock:

            def download(attempt, token, target, *args, **kwargs):
                target.write_bytes(b'%PDF-')
                clock.return_value = 500

            self.http.download.side_effect = download
            with patch('cv_compass_worker.__main__.main') as cli:
                self.assertTrue(self.supervisor.once())
            cli.assert_not_called()
        self.assertEqual(
            strict_json(self.http.request.call_args.args[2])['code'], 'CONFIGURATION_ERROR'
        )


class CancellationTests(unittest.TestCase):
    def test_cancelled_attached_child_is_killed_and_waited_by_identity(self):
        from cv_compass_worker import docker_sandbox as ds
        from unittest.mock import Mock

        event = threading.Event()
        event.set()
        attached = Mock()
        attached.poll.return_value = 0

        def command(args):
            if args[0] == 'inspect':
                return json_bytes(
                    [{'State': {'Running': False, 'OOMKilled': False, 'ExitCode': 137}}]
                )
            return b''

        with (
            tempfile.TemporaryDirectory() as temp,
            patch.object(ds, 'docker_cli', return_value=(['unused'], {})),
            patch.object(ds.subprocess, 'Popen', return_value=attached),
            patch.object(ds, 'command', side_effect=command) as calls,
        ):
            result = ds._execute_scope('owned-child', Path(temp), Config(), 1, cancelled=event)
        self.assertEqual(result['result']['error'], 'INTERNAL_ERROR')
        self.assertEqual(calls.call_args_list[0].args[0], ['kill', 'owned-child'])
        attached.wait.assert_called_once_with(timeout=10)

    def test_cancel_cleanup_failure_preserves_flag_even_if_ledger_cannot_write(self):
        from cv_compass_worker import docker_sandbox as ds
        from cv_compass_worker.sandbox import ChildFailure

        event = threading.Event()

        def staged(*args):
            event.set()
            return 'owned-volume'

        with (
            tempfile.TemporaryDirectory() as temp,
            patch.object(ds, 'prepare_control', side_effect=staged),
            patch.object(ds, 'command', side_effect=ChildFailure('INTERNAL_ERROR')),
            patch.object(Path, 'write_text', side_effect=OSError('synthetic full disk')),
        ):
            with self.assertRaises(ChildFailure) as raised:
                ds._run_scope({}, Path(temp) / 'scope', Config(), 1, None, cancelled=event)
            self.assertTrue(raised.exception.cleanup_failed)
            self.assertIn('owned-volume', raised.exception.leftovers)

    def test_cancel_during_staging_removes_owned_volume_without_starting_child(self):
        from cv_compass_worker import docker_sandbox as ds

        event = threading.Event()

        def staged(*args):
            event.set()
            return 'owned-volume'

        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / 'scope'
            with (
                patch.object(ds, 'prepare_control', side_effect=staged),
                patch.object(ds, 'create_scope') as create,
                patch.object(ds, 'command') as command,
            ):
                with self.assertRaises(Exception):
                    ds._run_scope({}, root, Config(), 1, None, cancelled=event)
                create.assert_not_called()
                command.assert_called_once_with(['volume', 'rm', 'owned-volume'])

    def test_cancelled_pipeline_never_starts_engine_and_cleans_owned_workspace(self):
        from cv_compass_worker.pipeline import process

        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / ('a' * 32 + '.pdf')
            source.write_bytes(b'%PDF-synthetic')
            request = dict(
                type='request',
                schema_version='cv-compass.ocr/2.0',
                request_id='synthetic',
                document_id='42',
                input_ref=source.name,
                expected_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                profile='baseline-v1',
            )
            event = threading.Event()
            event.set()
            with patch('cv_compass_worker.pipeline.run_child') as child:
                result = process(request, root, Config(mode='diagnostic'), cancelled=event)
            child.assert_not_called()
            self.assertEqual(result['status'], 'failed')
            self.assertFalse((root / '.ocr-lock').exists())
            self.assertEqual(list(root.glob('.ocr-tmp-*')), [])
