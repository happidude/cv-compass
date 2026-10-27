"""Safe ERROR diagnostics over real loopback HTTP plus explicit failure boundaries."""

from dataclasses import replace
import io
import json
import logging
from pathlib import Path
import socket
import ssl
import unittest
from unittest.mock import patch
import uuid
import test_pull
from cv_compass_worker import pull
from cv_compass_worker.diagnostics import Reason, WorkerFailure
from cv_compass_worker.observability import (
    _logger,
    Formatter,
    context,
    event,
    Event,
    report_failure,
)
from cv_compass_worker.pull_http import WorkerHttp

PRIVATE = 'PRIVATE_TOKEN_email@example.test'


class HaltDiagnosticsTests(unittest.TestCase):
    def setUp(self):
        self.previous = _logger.handlers[:], _logger.level
        self.output = io.StringIO()
        handler = logging.StreamHandler(self.output)
        handler.setFormatter(Formatter(True))
        _logger.handlers = [handler]
        _logger.setLevel(logging.INFO)
        self.addCleanup(self.restore)
        self.fixture = test_pull.SupervisorTests(
            'test_calls_existing_cli_and_removes_only_owned_spool_after_ack'
        )
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)

    def restore(self):
        _logger.handlers, level = self.previous
        _logger.setLevel(level)

    def errors(self):
        return [
            json.loads(line)
            for line in self.output.getvalue().splitlines()
            if json.loads(line)['level'] == 'ERROR'
        ]

    def http_fixture(self):
        fixture = test_pull.HttpTests('test_retry_is_bounded')
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        return fixture

    def run_halt(self, http):
        self.fixture.supervisor.http = http
        with context(workerId='synthetic-worker'):
            with self.assertRaises(Exception):
                self.fixture.supervisor.once()
        (row,) = self.errors()
        self.assertEqual(row['event'], 'WORKER_HALTED')
        self.assertIn('exceptionType', row)
        self.assertNotIn(PRIVATE, self.output.getvalue())
        self.assertNotIn('s' * 43, self.output.getvalue())
        self.assertNotIn('Authorization', self.output.getvalue())
        return row

    def test_connection_refused_retains_failed_request_correlation_and_cleans_scope(self):
        fixture = self.http_fixture()
        with socket.socket() as reserved:
            reserved.bind(('127.0.0.1', 0))  # Bound but not listening: deterministic refusal.
            config = replace(
                fixture.cfg,
                origin='http://127.0.0.1:' + str(reserved.getsockname()[1]),
                max_http_attempts=1,
            )
            with patch(
                'cv_compass_worker.pull_http.uuid.uuid4',
                return_value=uuid.UUID('00000000-0000-4000-8000-000000000001'),
            ):
                row = self.run_halt(WorkerHttp(config, 's' * 43))
        self.assertEqual(row['errorCode'], 'CONNECTION_REFUSED')
        self.assertEqual(row['exceptionType'], 'ConnectionRefusedError')
        self.assertEqual(row['correlationId'], '00000000-0000-4000-8000-000000000001')
        event(Event.POLL_EMPTY)
        clean = json.loads(self.output.getvalue().splitlines()[-1])
        self.assertNotIn('correlationId', clean)
        self.assertNotIn('jobId', clean)
        self.assertTrue((self.fixture.spool / 'claim.json').exists())

    def test_socket_deadline_has_specific_timeout_code_and_request_correlation(self):
        fixture = self.http_fixture()
        fixture.pause_body = True
        fixture.responses = [(201, {}, b'{"data":{}}')]
        row = self.run_halt(
            WorkerHttp(
                replace(fixture.cfg, http_timeout_seconds=0.1, max_http_attempts=1), 's' * 43
            )
        )
        self.assertEqual(row['errorCode'], 'HTTP_TIMEOUT')
        self.assertEqual(row['correlationId'], fixture.events[0][3]['X-Correlation-ID'])

    def test_401_and_403_are_distinct_errors_with_exact_http_correlation(self):
        for status, code in (
            (401, 'HTTP_AUTHENTICATION_FAILED'),
            (403, 'HTTP_AUTHORIZATION_FAILED'),
        ):
            with self.subTest(status=status):
                self.output.seek(0)
                self.output.truncate(0)
                fixture = self.http_fixture()
                fixture.responses = [(status, {}, PRIVATE.encode())]
                row = self.run_halt(fixture.http)
                self.assertEqual(row['errorCode'], code)
                self.assertEqual(row['httpStatus'], status)
                self.assertEqual(row['correlationId'], fixture.events[0][3]['X-Correlation-ID'])
                self.assertEqual(len(fixture.events), 1)

    def test_tls_failure_never_formats_exception_cause_or_notes(self):
        fixture = self.http_fixture()
        failure = ssl.SSLCertVerificationError(PRIVATE)
        failure.__cause__ = ValueError(PRIVATE)
        failure.add_note(PRIVATE)
        with patch('cv_compass_worker.pull_http.http.client.HTTPSConnection') as connection:
            connection.return_value.connect.side_effect = failure
            row = self.run_halt(
                WorkerHttp(
                    replace(fixture.cfg, origin='https://example.test', max_http_attempts=1),
                    's' * 43,
                )
            )
        self.assertEqual(row['errorCode'], 'TLS_FAILED')
        self.assertEqual(row['exceptionType'], 'SSLCertVerificationError')
        self.assertIn('correlationId', row)

    def test_bad_claim_has_specific_code_and_known_task_ids(self):
        self.fixture.claim['protocol'] = PRIVATE
        row = self.run_halt(self.fixture.http)
        self.assertEqual(row['errorCode'], 'CLAIM_CONTRACT')
        self.assertEqual(row['documentId'], '42')
        self.assertEqual(row['jobId'], '1')
        self.assertEqual(row['attemptId'], self.fixture.attempt)
        self.fixture.http.download.assert_not_called()

    def test_old_halt_marker_reports_unknown_history_without_mutating_spool(self):
        marker = self.fixture.spool / 'halt.json'
        marker.write_bytes(b'{"code":"OPERATOR_RECOVERY_REQUIRED"}')
        before = marker.read_bytes()
        row = self.run_halt(self.fixture.http)
        self.assertEqual(row['errorCode'], 'OPERATOR_RECOVERY_REQUIRED')
        self.assertEqual(marker.read_bytes(), before)
        self.fixture.http.request.assert_not_called()

    def test_invalid_config_and_missing_credential_are_distinct(self):
        path = self.fixture.root / 'invalid.toml'
        path.write_text('unknown=' + json.dumps(PRIVATE))
        with patch('cv_compass_worker.pull.configure'), patch('sys.stderr', io.StringIO()):
            self.assertEqual(pull.main(['--config', str(path), '--once']), 3)
        (row,) = self.errors()
        self.assertEqual(row['errorCode'], 'PULL_CONFIGURATION')
        self.output.seek(0)
        self.output.truncate(0)
        with (
            patch('cv_compass_worker.pull.configure'),
            patch('cv_compass_worker.pull.PullConfig.load', return_value=self.fixture.cfg),
            patch('cv_compass_worker.pull.Config.load', return_value=self.fixture.pipeline),
            patch.dict('os.environ', {self.fixture.cfg.credential_env: ''}),
            patch('sys.stderr', io.StringIO()),
        ):
            self.assertEqual(pull.main(['--config', str(path), '--once']), 3)
        (row,) = self.errors()
        self.assertEqual(row['errorCode'], 'CREDENTIAL_MISSING')
        self.assertNotIn(PRIVATE, self.output.getvalue())

    def test_logging_initialization_fallback_is_safe_and_keeps_exit_stdout_contract(self):
        stderr, stdout = io.StringIO(), io.StringIO()
        with (
            patch.dict('os.environ', {'CV_LOG_FORMAT': PRIVATE}),
            patch('sys.stderr', stderr),
            patch('sys.stdout', stdout),
        ):
            self.assertEqual(pull.main(['--config', str(self.fixture.pipeline_path), '--once']), 3)
        self.assertEqual(stdout.getvalue(), '')
        row = json.loads(stderr.getvalue().splitlines()[0])
        self.assertEqual(row['errorCode'], 'LOG_CONFIGURATION_ERROR')
        self.assertEqual(row['level'], 'ERROR')
        self.assertNotIn(PRIVATE, stderr.getvalue())

    def test_typed_codes_not_arbitrary_message_parsing_and_one_owner(self):
        failure = ValueError('LEASE_LOST ' + PRIVATE)
        failure.__cause__ = RuntimeError(PRIVATE)
        failure.add_note(PRIVATE)
        report_failure(failure)
        report_failure(failure)
        (row,) = self.errors()
        self.assertEqual(row['errorCode'], 'UNEXPECTED_WORKER_FAILURE')
        self.assertNotIn(PRIVATE, self.output.getvalue())
        self.assertNotIn('cause', row)

    def test_receipt_lease_spool_and_cleanup_codes_remain_specific(self):
        for reason in (
            Reason.RECEIPT_IDENTITY,
            Reason.LEASE_LOST,
            Reason.SPOOL_IDENTITY,
            Reason.CLEANUP_FAILED,
        ):
            with self.subTest(reason=reason):
                self.output.seek(0)
                self.output.truncate(0)
                report_failure(WorkerFailure(reason))
                (row,) = self.errors()
                self.assertEqual(row['errorCode'], reason.value)

    def test_connect_cleanup_diagnostic_does_not_change_retry_count(self):
        fixture = self.http_fixture()
        with patch('cv_compass_worker.pull_http.http.client.HTTPConnection') as connection:
            connection.return_value.connect.side_effect = ConnectionRefusedError(PRIVATE)
            connection.return_value.close.side_effect = OSError(PRIVATE)
            row = self.run_halt(fixture.http)
        self.assertEqual(connection.return_value.connect.call_count, fixture.cfg.max_http_attempts)
        self.assertEqual(row['errorCode'], 'HTTP_CLEANUP_FAILED')

    def test_deadline_does_not_overwrite_independent_spool_or_lease_failure(self):
        from cv_compass_worker.diagnostics import annotate, transport_failure, diagnostic

        failure = OSError(PRIVATE)
        annotate(failure, Reason.SPOOL_IO_FAILED)
        transport_failure(failure, 'synthetic-http', timed_out=True)
        self.assertEqual(diagnostic(failure).reason, Reason.SPOOL_IO_FAILED)
        lease = WorkerFailure(Reason.LEASE_LOST)
        transport_failure(lease, 'synthetic-http', timed_out=True)
        self.assertEqual(diagnostic(lease).reason, Reason.LEASE_LOST)
        unexpected = RuntimeError(PRIVATE)
        transport_failure(unexpected, 'synthetic-http')
        self.assertEqual(diagnostic(unexpected).reason, Reason.UNEXPECTED_WORKER_FAILURE)

    def test_unsafe_exception_type_uses_safe_fallback(self):
        unsafe = type('Bad\n' + PRIVATE, (ValueError,), {})
        report_failure(unsafe(PRIVATE))
        (row,) = self.errors()
        self.assertEqual(row['exceptionType'], 'Exception')
        self.assertNotIn(PRIVATE, self.output.getvalue())
