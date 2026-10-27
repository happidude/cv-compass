import contextvars
import io
import json
import logging
import threading
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
from cv_compass_worker.observability import (
    Event,
    Formatter,
    context,
    event,
    _logger,
    configure,
    configure_child_diagnostics,
)


class ObservabilityTests(unittest.TestCase):
    def setUp(self):
        self.stream = io.StringIO()
        self.handler = logging.StreamHandler(self.stream)
        self.handler.setFormatter(Formatter(True))
        self.previous = _logger.handlers[:], _logger.level
        _logger.handlers = [self.handler]
        _logger.setLevel(logging.DEBUG)
        self.addCleanup(self.restore)

    def restore(self):
        _logger.handlers, level = self.previous
        _logger.setLevel(level)

    def rows(self):
        return [json.loads(line) for line in self.stream.getvalue().splitlines()]

    def test_nested_cleanup_and_thread_propagation_are_explicit(self):
        with context(documentId='42', jobId='51'):
            inherited = contextvars.copy_context()
            thread = threading.Thread(target=lambda: inherited.run(event, Event.HEARTBEAT))
            thread.start()
            thread.join()
            try:
                with context(attemptId='test-attempt'):
                    raise ValueError('SECRET')
            except ValueError:
                event(Event.PIPELINE_FINISHED)
        event(Event.POLL_EMPTY)
        first, second, third = self.rows()
        self.assertEqual(first['documentId'], '42')
        self.assertNotIn('attemptId', second)
        self.assertNotIn('documentId', third)

    def test_payload_exception_and_newline_never_formatted(self):
        with context(documentId='42\nPRIVATE', token='PRIVATE'):
            event(
                Event.WORKER_HALTED,
                error_code='secret@email.test',
                rawText='PRIVATE',
                payload='PRIVATE',
            )
        try:
            raise RuntimeError('PRIVATE')
        except RuntimeError:
            _logger.exception('PRIVATE %s', 'PRIVATE')
        self.assertNotIn('PRIVATE', self.stream.getvalue())
        self.assertNotIn('Traceback', self.stream.getvalue())
        self.assertEqual(self.rows()[0]['errorCode'], 'INTERNAL_ERROR')

    def test_level_filtering_and_structured_fields(self):
        _logger.setLevel(logging.INFO)
        event(Event.POLL_EMPTY, level=logging.DEBUG)
        event(Event.PAGE_FINISHED, pageNumber=1, durationMs=12, method='native', status='ok')
        (row,) = self.rows()
        self.assertEqual(row['event'], 'PAGE_FINISHED')
        self.assertTrue(row['timestamp'].endswith('+00:00'))
        self.assertEqual(row['service'], 'cv-compass-worker')
        self.assertEqual(row['pageNumber'], 1)

    def test_configured_logs_use_stderr_without_stdout(self):
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch('sys.stdout', stdout), patch('sys.stderr', stderr):
            configure({'CV_LOG_FORMAT': 'json'})
            event(Event.PIPELINE_STARTED)
        self.assertEqual(stdout.getvalue(), '')
        self.assertEqual(json.loads(stderr.getvalue())['event'], 'PIPELINE_STARTED')

    def test_bad_config_does_not_change_existing_handlers(self):
        with self.assertRaises(ValueError):
            configure({'CV_LOG_FORMAT': 'bad'})
        self.assertEqual(_logger.handlers, [self.handler])

    def test_unknown_events_rejected(self):
        with self.assertRaises(ValueError):
            event('PRIVATE')

    def test_child_library_warnings_keep_severity_without_private_messages(self):
        isolated = logging.Logger('isolated-child')
        with patch('logging.getLogger', return_value=isolated), patch('sys.stderr', self.stream):
            configure_child_diagnostics()
            try:
                raise ValueError('PRIVATE CV')
            except ValueError:
                isolated.warning('PRIVATE %s', 'token', exc_info=True)
        (row,) = self.rows()
        self.assertEqual(row['level'], 'WARN')
        self.assertEqual(row['event'], 'LIBRARY_DIAGNOSTIC')
        self.assertNotIn('PRIVATE', self.stream.getvalue())
        self.assertNotIn('token', self.stream.getvalue())

    def test_formatter_rejects_injected_payload_and_message_fields(self):
        _logger.warning(
            'PRIVATE',
            extra={
                'operational': {
                    'event': 'PAGE_FINISHED',
                    'message': 'PRIVATE',
                    'rawText': 'PRIVATE',
                    'payload': 'PRIVATE',
                }
            },
        )
        self.assertNotIn('PRIVATE', self.stream.getvalue())
        self.assertEqual(self.rows()[0]['level'], 'WARN')

    def test_optional_file_rotation_is_bounded(self):
        with tempfile.TemporaryDirectory() as directory, patch('sys.stderr', self.stream):
            path = Path(directory) / 'worker.log'
            configure(
                {'CV_LOG_FILE': str(path), 'CV_LOG_MAX_BYTES': '1024', 'CV_LOG_BACKUP_COUNT': '1'}
            )
            try:
                for _ in range(30):
                    event(Event.PIPELINE_STARTED)
                self.assertEqual(
                    {p.name for p in Path(directory).iterdir()}, {'worker.log', 'worker.log.1'}
                )
                self.assertTrue(all(p.stat().st_size <= 1024 for p in Path(directory).iterdir()))
            finally:
                for handler in _logger.handlers:
                    handler.close()

    def test_ack_event_only_after_validated_receipt_and_has_ids(self):
        from cv_compass_worker.pull import PullSupervisor

        claim = {'job_id': '51', 'attempt_id': 'attempt-1', 'ocr_request': {'document_id': '42'}}
        with self.assertRaises(ValueError):
            PullSupervisor._receipt({'accepted': False}, claim)
        self.assertEqual(self.rows(), [])
        PullSupervisor._receipt(
            {'accepted': True, 'job_id': '51', 'attempt_id': 'attempt-1'}, claim
        )
        (row,) = self.rows()
        self.assertEqual(row['event'], 'RESULT_ACKNOWLEDGED')
        self.assertEqual(row['documentId'], '42')
        self.assertEqual(row['attemptId'], 'attempt-1')
