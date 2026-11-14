"""Progress privacy and read-only probes against synthetic loopback services."""

import io
import json
import logging
import os
import socket
import threading
import unittest
from collections import Counter
from contextlib import contextmanager, redirect_stderr, redirect_stdout
from dataclasses import replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import Mock, patch

from cv_compass_llm.backend_client import CONTROL_LIMIT, BackendError
from cv_compass_llm.check import BACKEND_PROBE
from cv_compass_llm.cli import main
from cv_compass_llm.config import load_pull_config
from cv_compass_llm.pipeline import extract
from cv_compass_llm.progress import LOGGER, command_logging, endpoint, model_name
from cv_compass_llm.providers.base import ProviderError
from cv_compass_llm.providers.fake import FakeProvider
from cv_compass_llm.pull import _Attempt, run_pull
from cv_compass_llm.pull_payload import input_document
from test_pull import ATTEMPT_ID, NULL_PERSONAL, Backend, backend_server, claim, config

MARKER = 'SENTINEL_PII_7f3a'
TOKEN = MARKER + '_' + 'x' * 43


def backend_error(code):
    return json.dumps({'code': code, 'message': MARKER, 'correlationId': None,
                       'details': []}).encode()


@contextmanager
def probe_server(*, backend_status=404, backend_raw=None, names=None, tags_status=200, raw=None):
    calls = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def do_GET(self):
            calls.append((self.command, self.path, dict(self.headers)))
            if self.path == BACKEND_PROBE:
                status = backend_status
                body = backend_raw if backend_raw is not None else backend_error(
                    {404: 'NOT_FOUND', 401: 'UNAUTHORIZED'}.get(status, 'REQUEST_ERROR'))
            else:
                status = tags_status
                body = raw if raw is not None else json.dumps({
                    'models': [{'name': name} for name in
                               (['synthetic-model:latest'] if names is None else names)]
                }).encode()
            self.send_response(status)
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Location', '/must-not-follow')
            self.end_headers()
            self.wfile.write(body)

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f'http://127.0.0.1:{server.server_port}', calls
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


class ProgressTests(unittest.TestCase):
    def run_worker(self, backend, provider=None, *, level='INFO', once=True):
        out, err, records = io.StringIO(), io.StringIO(), []
        original = LOGGER.handle

        def capture(record):
            records.append(record)
            original(record)

        with (backend_server(backend) as url, redirect_stdout(out), redirect_stderr(err),
              patch.object(LOGGER, 'handle', side_effect=capture)):
            settings = replace(config(url), worker_token=TOKEN, log_level=level)
            result = run_pull(settings, provider or FakeProvider(NULL_PERSONAL), once=once)
        for marker in (MARKER, TOKEN, url, settings.llm.base_url, ATTEMPT_ID,
                       backend.claim.get('inputSha256', ''), '/attempts/', 'private-message'):
            if marker:
                self.assertNotIn(marker, out.getvalue() + err.getvalue() + repr(
                    [record.__dict__ for record in records]))
        for record in records:
            self.assertIsNone(record.exc_info)
        return result, out.getvalue(), err.getvalue(), records

    def test_complete_flow_order_and_record_privacy(self):
        backend = Backend()
        backend.input = {'pages': [{'page': 1, 'raw_text': MARKER + '\nExperience\n'}]}
        response = json.loads(json.dumps(NULL_PERSONAL))
        response['personal']['fullName'] = {'value': MARKER, 'quote': MARKER}
        result, out, err, records = self.run_worker(backend, FakeProvider(response))
        self.assertEqual(result, 0)
        self.assertEqual(out, 'claimed=1 accepted=1 failed=0\n')
        events = [record.getMessage().split()[0] for record in records]
        self.assertEqual(events, ['event=' + name for name in (
            'worker_start', 'claimed', 'input_fetched', 'extraction_block',
            'extraction_done', 'submitting', 'accepted', 'stopped')])
        self.assertIn('attempt=a0000000 engine=llm-extraction/1.0', err)
        self.assertIn('block=1/1', err)
        self.assertIn('issues=NONE:0', err)
        for line in err.splitlines():
            self.assertRegex(line, r'^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d INFO '
                                   r'event=[a-z_]+(?: [^\s=]+=[^\s]+)*$')

    def test_waiting_throttled_for_empty_503_and_unreachable(self):
        for status, code in ((204, 'NO_WORK'), (503, 'MAPPING_REMOTE_UNAVAILABLE'),
                             (0, 'BACKEND_UNREACHABLE')):
            backend = Backend()
            clock = [0]

            def sleep(_delay):
                clock[0] += 20
                if clock[0] >= 140:
                    raise KeyboardInterrupt

            # Isolate poll throttling from transport retry delays and socket clocks.
            with (patch('cv_compass_llm.pull.time.monotonic', side_effect=lambda: clock[0]),
                  patch('cv_compass_llm.pull.time.sleep', side_effect=sleep),
                  patch('cv_compass_llm.pull.BackendClient') as client):
                calls = [0]

                def request(*_args, **_kwargs):
                    calls[0] += 1
                    if status != 204:
                        raise BackendError(code, status)
                    return 204, {}

                client.return_value.request.side_effect = request
                result, _, _, records = self.run_worker(backend, once=False)
            self.assertEqual(result, 130)
            waits = [row for row in records if row.getMessage().startswith('event=waiting')]
            self.assertEqual(len(waits), 3)  # 0, 60, 120 seconds; seven polls.
            self.assertEqual(calls[0], 7)
            self.assertTrue(all('code=' + code in row.getMessage() for row in waits))
            self.assertEqual(len(records), 5)  # Start, three waits, stop; no extra errors.

    def test_backend_waiting_resets_after_success_without_repeating_idle_polls(self):
        client = Mock()
        client.request.side_effect = [
            BackendError('BACKEND_UNREACHABLE'), BackendError('BACKEND_UNREACHABLE'),
            (204, {}), (204, {}),
            BackendError('BACKEND_UNREACHABLE'), BackendError('BACKEND_UNREACHABLE'),
            KeyboardInterrupt,
        ]
        with (patch('cv_compass_llm.pull.BackendClient', return_value=client),
              patch('cv_compass_llm.pull.time.monotonic', return_value=0),
              patch('cv_compass_llm.pull.time.sleep')):
            result, _, _, records = self.run_worker(Backend(), once=False)
        self.assertEqual(result, 130)
        self.assertTrue(records[0].getMessage().startswith('event=worker_start '))
        self.assertEqual([record.getMessage() for record in records[1:]], [
            'event=waiting code=BACKEND_UNREACHABLE',
            'event=waiting code=NO_WORK', 'event=waiting code=BACKEND_UNREACHABLE',
            'event=stopped',
        ])
        self.assertEqual(client.request.call_count, 7)

    def test_direct_failure_outcome_has_default_failure_code(self):
        client, counts, err = Mock(), Counter(), io.StringIO()
        client.request.return_value = 200, {'status': 'FAILED'}
        attempt = _Attempt(client, claim(), TOKEN, counts)
        with redirect_stderr(err), command_logging('INFO'), attempt.lock:
            attempt.outcome('failure', b'{"code":"INTERNAL_ERROR"}')
        self.assertEqual(attempt.failure_code, 'INTERNAL_ERROR')
        self.assertEqual(counts['failed'], 1)
        self.assertIn('event=failed attempt=a0000000 code=INTERNAL_ERROR', err.getvalue())

    def test_debug_heartbeat_and_loss(self):
        for level, lost in (('INFO', False), ('DEBUG', False), ('INFO', True)):
            with self.subTest(level=level, lost=lost):
                backend = Backend()
                backend.claim = claim(interval='PT0.05S')
                if lost:
                    backend.hook = lambda method, path, body: (
                        (409, {'code': 'STALE_ATTEMPT'}) if path.endswith('/lease') else None)
                lease_seen = threading.Event()
                original = backend.reply

                def reply(*args):
                    result = original(*args)
                    if args[1].endswith('/lease'):
                        lease_seen.set()
                    return result

                backend.reply = reply

                class SlowProvider:
                    name = 'synthetic'

                    def generate(self, prompt, schema):
                        if not lease_seen.wait(3):
                            raise AssertionError('missing heartbeat')
                        return json.dumps(NULL_PERSONAL)

                _, _, err, _ = self.run_worker(backend, SlowProvider(), level=level)
                self.assertEqual('event=heartbeat ' in err, level == 'DEBUG')
                self.assertEqual('event=heartbeat_lost ' in err, lost)
                self.assertEqual(bool(backend.requests('result')), not lost)

    def test_provider_and_backend_messages_never_reach_logs(self):
        class BrokenProvider:
            name = 'synthetic'

            def generate(self, prompt, schema):
                raise ProviderError(MARKER + ' private-message')

        _, _, err, _ = self.run_worker(Backend(), BrokenProvider())
        self.assertIn('event=provider_error', err)
        self.assertIn('event=failed attempt=a0000000 code=PROVIDER_ERROR', err)
        backend = Backend()
        backend.hook = lambda *_args: (422, {'code': MARKER, 'message': MARKER})
        _, _, err, _ = self.run_worker(backend)
        self.assertIn('event=backend_error code=BACKEND_HTTP_ERROR', err)

    def test_command_logger_restores_state_and_does_not_configure_root(self):
        root = logging.getLogger()
        root_state = root.handlers[:], root.level
        state = LOGGER.handlers[:], LOGGER.level, LOGGER.propagate, LOGGER.disabled
        with redirect_stderr(io.StringIO()), command_logging('DEBUG'):
            self.assertFalse(LOGGER.propagate)
        self.assertEqual(state, (LOGGER.handlers, LOGGER.level, LOGGER.propagate, LOGGER.disabled))
        self.assertEqual(root_state, (root.handlers, root.level))
        self.assertEqual(endpoint('https://user:password@[::1]:123/path?query'), '[::1]:123')
        self.assertEqual(endpoint('https://example.invalid'), 'example.invalid:443')
        for value in ('https://example.invalid/model', '/private/model', 'C:/private/model',
                      '../private/model', MARKER + '\nprivate-message'):
            self.assertEqual(model_name(value), 'REDACTED')
        self.assertEqual(model_name('synthetic/model:latest'), 'synthetic/model:latest')

    def test_pipeline_callback_counts_calls_and_preserves_result(self):
        document = input_document({'pages': [{'page': 1, 'raw_text': (
            'Synthetic Person\nExperience\nEngineer at Synthetic\n2020 - 2021\n')}]})
        responses = [NULL_PERSONAL, {'experiences': []}]
        baseline = extract(document, FakeProvider(responses))
        progress = []
        observed = extract(document, FakeProvider(responses),
                           on_block=lambda *args: progress.append(args))
        self.assertEqual(observed, baseline)
        self.assertTrue(progress)
        self.assertEqual([(i, n) for i, n, seconds in progress],
                         [(i + 1, len(progress)) for i in range(len(progress))])
        self.assertTrue(all(seconds >= 0 for i, n, seconds in progress))
        limited = []
        result = extract(document, FakeProvider(NULL_PERSONAL), max_blocks=0,
                         on_block=lambda *args: limited.append(args))
        self.assertEqual([(i, n) for i, n, seconds in limited], [(1, 1)])
        self.assertIn('BLOCK_LIMIT_REACHED', [issue.code for issue in result.issues])
        failures = []
        result = extract(document, FakeProvider([ProviderError(MARKER), {'experiences': []}]),
                         on_block=lambda *args: failures.append(args))
        self.assertEqual([(i, n) for i, n, seconds in failures], [(1, 2), (2, 2)])
        self.assertIn('PROVIDER_ERROR', [issue.code for issue in result.issues])


class CheckTests(unittest.TestCase):
    def run_check(self, backend, ollama, **overrides):
        environment = {
            'CVC_LLM_PROVIDER': 'ollama', 'CVC_LLM_MODEL': 'synthetic-model',
            'CVC_LLM_BACKEND_URL': backend, 'CVC_LLM_BASE_URL': ollama,
            'CVC_LLM_WORKER_TOKEN': TOKEN, 'CVC_LLM_HTTP_TIMEOUT_S': '1',
            'CVC_LLM_TIMEOUT_S': '1', **overrides,
        }
        out, err = io.StringIO(), io.StringIO()
        # Bypass only the private dotenv file; all actual config validation still runs.
        with (patch('cv_compass_llm.config._read_env', return_value={}),
              patch.dict(os.environ, environment, clear=True),
              redirect_stdout(out), redirect_stderr(err)):
            result = main(['check'])
        text = out.getvalue()
        self.assertEqual(err.getvalue(), '')
        for marker in (TOKEN, MARKER, backend, ollama, 'synthetic-model'):
            self.assertNotIn(marker, text)
        return result, text

    def assert_read_only(self, calls):
        self.assertTrue(calls)
        self.assertTrue(all(method == 'GET' and path in (BACKEND_PROBE, '/api/tags')
                            for method, path, headers in calls))

    def test_all_ok_and_exact_model_and_latest(self):
        for configured, names in (
            ('synthetic-model', ['synthetic-model:latest']),
            ('synthetic-model:custom', ['synthetic-model:custom']),
        ):
            with probe_server(names=names) as (url, calls):
                result, out = self.run_check(url, url, CVC_LLM_MODEL=configured)
            self.assertEqual(result, 0)
            self.assertEqual(out.splitlines(), ['OK config', 'OK backend ' + endpoint(url),
                                                'OK backend_token', 'OK ollama ' + endpoint(url),
                                                'OK model'])
            self.assert_read_only(calls)
            self.assertEqual(calls[0][2]['Authorization'], 'Bearer ' + TOKEN)
            self.assertNotIn('Authorization', calls[1][2])

    def test_dead_backend_and_ollama(self):
        with socket.socket() as reserved:
            reserved.bind(('127.0.0.1', 0))
            dead = f'http://127.0.0.1:{reserved.getsockname()[1]}'
        with probe_server() as (url, calls):
            result, out = self.run_check(dead, url)
            self.assertNotEqual(result, 0)
            self.assertIn('code=BACKEND_UNREACHABLE', out)
            result, out = self.run_check(url, dead)
            self.assertNotEqual(result, 0)
            self.assertIn('code=OLLAMA_UNREACHABLE', out)
        self.assert_read_only(calls)

    def test_missing_model_missing_token_and_invalid_token(self):
        with probe_server(names=['other-model']) as (url, calls):
            result, out = self.run_check(url, url)
            self.assertNotEqual(result, 0)
            self.assertIn('FAIL model code=MODEL_NOT_FOUND', out)
            before = len(calls)
            result, out = self.run_check(url, url, CVC_LLM_WORKER_TOKEN='')
            self.assertNotEqual(result, 0)
            self.assertEqual(out, 'FAIL config code=MISSING_CONFIG\n')
            self.assertEqual(len(calls), before)
        with probe_server(backend_status=401) as (url, calls):
            result, out = self.run_check(url, url)
            self.assertNotEqual(result, 0)
            self.assertIn('FAIL backend_token code=UNAUTHORIZED', out)
        self.assert_read_only(calls)

    def test_redirects_disabled_and_proxies_ignored(self):
        with probe_server(backend_status=302, tags_status=302) as (url, calls):
            result, out = self.run_check(url, url, HTTP_PROXY='http://127.0.0.1:1',
                                        http_proxy='http://127.0.0.1:1', NO_PROXY='', no_proxy='')
        self.assertNotEqual(result, 0)
        self.assertIn('code=BACKEND_REDIRECT', out)
        self.assertIn('code=OLLAMA_REDIRECT', out)
        self.assertEqual(len(calls), 2)
        self.assert_read_only(calls)
        with probe_server() as (url, calls):
            result, _ = self.run_check(url, url, HTTP_PROXY='http://127.0.0.1:1',
                                      http_proxy='http://127.0.0.1:1', NO_PROXY='', no_proxy='')
        self.assertEqual(result, 0)
        self.assertEqual(len(calls), 2)

    def test_backend_token_requires_backend_error_envelope_and_matching_code(self):
        for status, body, expected in (
            (404, backend_error('NOT_FOUND'), 'OK backend_token'),
            (404, b'<html>not found</html>', 'FAIL backend_token code=BACKEND_PROBE_REJECTED'),
            (404, b'', 'FAIL backend_token code=BACKEND_PROBE_REJECTED'),
            (404, b'{"code":"NOT_FOUND"}', 'FAIL backend_token code=BACKEND_PROBE_REJECTED'),
            (404, backend_error('UNAUTHORIZED'), 'FAIL backend_token code=BACKEND_PROBE_REJECTED'),
            (401, backend_error('UNAUTHORIZED'), 'FAIL backend_token code=UNAUTHORIZED'),
            (401, backend_error('AUTH_REQUIRED'), 'FAIL backend_token code=BACKEND_PROBE_REJECTED'),
            (401, b'<html>unauthorized</html>', 'FAIL backend_token code=BACKEND_PROBE_REJECTED'),
            (503, backend_error('MAPPING_REMOTE_UNAVAILABLE'),
             'FAIL backend_token code=MAPPING_REMOTE_UNAVAILABLE'),
            (403, backend_error('TLS_REQUIRED'), 'FAIL backend_token code=TLS_REQUIRED'),
            (503, backend_error(MARKER), 'FAIL backend_token code=BACKEND_PROBE_REJECTED'),
            (403, backend_error('UNAUTHORIZED'), 'FAIL backend_token code=BACKEND_PROBE_REJECTED'),
        ):
            with (self.subTest(status=status, expected=expected),
                  probe_server(backend_status=status, backend_raw=body) as (url, calls)):
                result, out = self.run_check(url, url)
            self.assertEqual(result, 0 if expected == 'OK backend_token' else 2)
            self.assertEqual([line for line in out.splitlines() if 'backend_token' in line],
                             [expected])
            self.assert_read_only(calls)

    def test_bounded_and_malformed_tags_and_backend_rejection(self):
        for options, code in (
            ({'backend_raw': b'x' * (CONTROL_LIMIT + 1)}, 'BACKEND_RESPONSE_TOO_LARGE'),
            ({'raw': b'x' * (4 * 1024 * 1024 + 1)}, 'OLLAMA_RESPONSE_TOO_LARGE'),
            ({'raw': MARKER.encode()}, 'OLLAMA_BAD_RESPONSE'),
            ({'raw': b'{"models":[{"name":null}]}'}, 'OLLAMA_BAD_RESPONSE'),
            ({'backend_status': 503}, 'BACKEND_PROBE_REJECTED'),
            ({'names': ['synthetic-model:other']}, 'MODEL_NOT_FOUND'),
        ):
            with self.subTest(code=code), probe_server(**options) as (url, calls):
                result, out = self.run_check(url, url)
            self.assertNotEqual(result, 0)
            self.assertIn('code=' + code, out)
            self.assert_read_only(calls)

    def test_log_level_precedence_and_safe_error(self):
        environment = {'CVC_LLM_PROVIDER': 'ollama', 'CVC_LLM_MODEL': 'synthetic-model',
                       'CVC_LLM_BACKEND_URL': 'http://localhost',
                       'CVC_LLM_WORKER_TOKEN': TOKEN}
        with patch('cv_compass_llm.config._read_env',
                   return_value={'CVC_LLM_LOG_LEVEL': 'WARNING'}):
            def load(cli=None):
                return load_pull_config(environment, Path('synthetic.env'),
                                        env_file_explicit=True, cli=cli)

            self.assertEqual(load().log_level, 'WARNING')
            environment['CVC_LLM_LOG_LEVEL'] = 'ERROR'
            self.assertEqual(load().log_level, 'ERROR')
            self.assertEqual(load({'log_level': 'DEBUG'}).log_level, 'DEBUG')
            environment['CVC_LLM_LOG_LEVEL'] = MARKER
            with self.assertRaisesRegex(ValueError, '^CVC_LLM_LOG_LEVEL INVALID_LOG_LEVEL$'):
                load()
        with probe_server() as (url, calls):
            result, out = self.run_check(url, url, CVC_LLM_LOG_LEVEL=MARKER)
        self.assertEqual(result, 2)
        self.assertEqual(out, 'FAIL config code=INVALID_LOG_LEVEL\n')
        self.assertFalse(calls)
