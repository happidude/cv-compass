"""Synthetic in-process backend tests; no backend, Ollama, credentials or CV files."""

import io
import json
import os
import re
import socket
import tempfile
import threading
import unittest
from contextlib import contextmanager, redirect_stderr, redirect_stdout
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import Mock, patch
from uuid import UUID

from cv_compass_llm.backend_client import BackendClient, BackendError, encode
from cv_compass_llm.cli import main
from cv_compass_llm.config import LlmConfig, PullConfig, load_pull_config
from cv_compass_llm.providers.base import ProviderError
from cv_compass_llm.providers.fake import FakeProvider
from cv_compass_llm.pull import _Attempt, run_pull
from cv_compass_llm.pull_payload import ENGINE, PERSONAL, compact_result, input_document
from cv_compass_llm.schema import Evidence, Experience, ExtractionResult, FieldValue, Issue

TOKEN = 'synthetic_worker_credential_' + 'x' * 32
ATTEMPT_ID = 'a0000000-0000-4000-8000-000000000001'
RAW = (
    '🧪 Synthetic Person\nSynthetic Place\nfirst@example.invalid\n'
    'second@example.invalid\n0123456789\n0987654321\nhttps://example.invalid/profile\n'
    'Experience\nEducation\n'
)
NULL_PERSONAL = {
    'personal': {key: None for key in ('fullName', 'gender', 'location', 'dateOfBirth', 'summary')}
}


def config(url):
    return PullConfig(
        LlmConfig('ollama', 'synthetic-model', 'http://127.0.0.1:11434', 2, 20, 0, False, None),
        url,
        TOKEN,
        'dev',
        1,
        1,
        3,
    )


def instant(seconds):
    return (datetime.now(timezone.utc) + timedelta(seconds=seconds)).isoformat()


def claim(*, interval='PT30S', hard=300, lease=120):
    return {
        'attemptId': ATTEMPT_ID,
        'engineVersion': ENGINE,
        'inputSha256': 'a' * 64,
        'leaseExpiresAt': instant(lease),
        'hardDeadlineAt': instant(hard),
        'heartbeatInterval': interval,
        'status': 'ACTIVE',
        'definition': {
            'schemaVersion': 4,
            'engineCompatibility': ENGINE,
            'revision': 'test',
            'fields': {key: {'enabled': key != 'location'} for key in (*PERSONAL, 'experiences')},
            'other': {'enabled': True, 'maxItems': 2},
        },
    }


class Backend:
    def __init__(self):
        self.calls = []
        self.claim = claim()
        self.input = {'pages': [{'page': 1, 'raw_text': RAW}]}
        self.hook = None
        self.lease_seen = threading.Event()

    def reply(self, method, path, headers, body):
        self.calls.append((method, path, dict(headers), body))
        if self.hook:
            override = self.hook(method, path, body)
            if override is not None:
                return override
        if path.endswith('/claims'):
            return 201, {'data': self.claim}
        if path.endswith('/input'):
            return 200, {'data': self.input}
        if path.endswith('/lease'):
            self.lease_seen.set()
            return 200, {'data': {'leaseExpiresAt': instant(120)}}
        return 200, {'data': {'status': 'ACCEPTED' if path.endswith('/result') else 'FAILED'}}

    def requests(self, suffix):
        return [item for item in self.calls if item[1].endswith('/' + suffix)]


@contextmanager
def backend_server(backend):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def handle_request(self):
            body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
            reply = backend.reply(self.command, self.path, self.headers, body)
            if reply == 'disconnect':
                self.connection.shutdown(socket.SHUT_RDWR)
                self.connection.close()
                return
            status, payload = reply
            raw = payload if isinstance(payload, bytes) else encode(payload)
            self.send_response(status)
            if 300 <= status < 400:
                self.send_header('Location', '/redirect-target')
            self.send_header('Content-Length', str(len(raw)))
            self.end_headers()
            try:
                self.wfile.write(raw)
            except BrokenPipeError, ConnectionResetError:
                pass

        do_POST = do_GET = do_PUT = handle_request

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f'http://127.0.0.1:{server.server_port}'
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


class PullConfigTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'synthetic.env'
        self.path.write_text(
            'CVC_LLM_PROVIDER=ollama\nCVC_LLM_MODEL=synthetic-model\n', encoding='utf-8'
        )
        self.env = {'CVC_LLM_BACKEND_URL': 'http://127.0.0.1', 'CVC_LLM_WORKER_TOKEN': TOKEN}

    def load(self, env=None):
        return load_pull_config(self.env if env is None else env, self.path, env_file_explicit=True)

    def test_required_keys_and_no_value_diagnostics(self):
        for key in ('CVC_LLM_BACKEND_URL', 'CVC_LLM_WORKER_TOKEN'):
            environment = dict(self.env)
            del environment[key]
            with self.assertRaisesRegex(ValueError, '^' + key + ' MISSING_CONFIG$'):
                self.load(environment)
        for key, value, code in (
            ('CVC_LLM_BACKEND_URL', 'http://example.invalid', 'BACKEND_URL_NOT_ALLOWED'),
            ('CVC_LLM_WORKER_TOKEN', 'private-invalid-token', 'INVALID_WORKER_TOKEN'),
            ('CVC_LLM_WORKER_VERSION', 'private version', 'INVALID_PROVENANCE'),
            ('CVC_LLM_MODEL', 'private model', 'INVALID_PROVENANCE'),
            ('CVC_LLM_POLL_S', '301', 'INVALID_CONFIG'),
            ('CVC_LLM_HTTP_TIMEOUT_S', '0', 'INVALID_CONFIG'),
            ('CVC_LLM_MAX_HTTP_ATTEMPTS', '11', 'INVALID_CONFIG'),
        ):
            with self.subTest(key=key):
                environment = dict(self.env, **{key: value})
                out = io.StringIO()
                with patch.dict(os.environ, environment, clear=True), redirect_stdout(out):
                    self.assertEqual(main(['pull', '--once', '--env-file', str(self.path)]), 2)
                self.assertEqual(out.getvalue(), f'{key} {code}\n')
                self.assertNotIn(value, out.getvalue())
                self.assertNotIn(TOKEN, out.getvalue())

    def test_url_policy(self):
        for url in (
            'http://example.invalid',
            'http://127.0.0.2',
            'http://127.0.0.1/',
            'http://user:pass@localhost',
            'https://example.invalid/path',
            'https://example.invalid?',
            'https://example.invalid#',
            'http://localhost:0',
            'http://localhost:99999',
        ):
            with self.subTest(url=url):
                with self.assertRaisesRegex(ValueError, 'BACKEND_URL_NOT_ALLOWED'):
                    self.load(dict(self.env, CVC_LLM_BACKEND_URL=url))
        for url in ('https://example.invalid:443', 'http://localhost', 'http://[::1]:8080'):
            self.assertEqual(self.load(dict(self.env, CVC_LLM_BACKEND_URL=url)).backend_url, url)

    def test_dotenv_precedence_and_token_rejection(self):
        with self.path.open('a', encoding='utf-8') as stream:
            stream.write('CVC_LLM_BACKEND_URL=https://example.invalid\nCVC_LLM_POLL_S=12\n')
        loaded = self.load()
        self.assertEqual(loaded.backend_url, 'http://127.0.0.1')
        self.assertEqual(loaded.poll_s, 12)
        self.assertEqual(loaded.http_timeout_s, 10)
        self.assertEqual(loaded.max_http_attempts, 3)
        self.assertNotIn(TOKEN, repr(loaded))
        with self.path.open('a', encoding='utf-8') as stream:
            stream.write('CVC_LLM_WORKER_TOKEN=' + TOKEN)
        with self.assertRaisesRegex(ValueError, '^CVC_LLM_WORKER_TOKEN TOKEN_IN_ENV_FILE$'):
            self.load()

    def test_no_token_cli_argument(self):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err), self.assertRaises(SystemExit) as caught:
            main(['pull', '--worker-token', TOKEN])
        self.assertEqual(caught.exception.code, 2)
        self.assertEqual(out.getvalue() + err.getvalue(), 'INVALID_ARGUMENTS\n')

    def test_cli_loads_provider_and_passes_once(self):
        with (
            patch.dict(os.environ, self.env, clear=True),
            patch('cv_compass_llm.cli.OllamaProvider') as provider,
            patch('cv_compass_llm.cli.run_pull', return_value=0) as pull,
        ):
            self.assertEqual(main(['pull', '--once', '--env-file', str(self.path)]), 0)
        provider.assert_called_once_with(
            'synthetic-model',
            base_url='http://127.0.0.1:11434',
            timeout_s=120,
            temperature=0,
            think=False,
            num_ctx=8192,
        )
        self.assertEqual(pull.call_args.args[0].backend_url, self.env['CVC_LLM_BACKEND_URL'])
        self.assertEqual(pull.call_args.args[0].worker_token, TOKEN)
        self.assertIs(pull.call_args.args[1], provider.return_value)
        self.assertEqual(pull.call_args.kwargs, {'once': True})


class PullTests(unittest.TestCase):
    def run_worker(self, backend, provider=None, *, once=True, overrides=None):
        out, err = io.StringIO(), io.StringIO()
        with backend_server(backend) as url, redirect_stdout(out), redirect_stderr(err):
            settings = config(url)
            if overrides:
                settings = replace(settings, **overrides)
            result = run_pull(settings, provider or FakeProvider(NULL_PERSONAL), once=once)
        self.assertTrue(err.getvalue().splitlines())
        for line in err.getvalue().splitlines():
            self.assertRegex(
                line,
                r'^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d '
                r'(INFO|WARNING|ERROR) event=[a-z_]+(?: [^\s=]+=[^\s]+)*$',
            )
        for marker in (RAW, 'Synthetic Person', 'example.invalid', TOKEN, 'private-output'):
            self.assertNotIn(marker, out.getvalue() + err.getvalue())
        return result, out.getvalue()

    def test_complete_flow_headers_and_compact_payload(self):
        backend = Backend()
        response = json.loads(json.dumps(NULL_PERSONAL))
        response['personal']['fullName'] = {
            'value': 'Synthetic Person',
            'quote': '🧪 Synthetic Person',
        }
        response['personal']['location'] = {'value': 'Synthetic Place', 'quote': 'Synthetic Place'}
        provider = FakeProvider(response)
        result, output = self.run_worker(backend, provider)
        self.assertEqual(result, 0)
        self.assertIn('claimed=1 accepted=1 failed=0', output)
        self.assertTrue(provider.prompts)
        requests = (
            backend.requests('claims') + backend.requests('input') + backend.requests('result')
        )
        self.assertEqual(len(requests), 3)
        headers = [{key.lower(): value for key, value in row[2].items()} for row in requests]
        lease = headers[0]['x-lease-token']
        self.assertRegex(lease, r'^[A-Za-z0-9_-]{43}$')
        self.assertEqual(str(UUID(headers[0]['idempotency-key'])), headers[0]['idempotency-key'])
        for header in headers:
            self.assertEqual(header['authorization'], 'Bearer ' + TOKEN)
            self.assertEqual(header['x-lease-token'], lease)
        posted_claim = json.loads(requests[0][3])
        payload = json.loads(requests[-1][3])
        self.assertEqual(payload['provenance'], posted_claim['provenance'])
        self.assertEqual(set(payload['personal']), set(PERSONAL))
        self.assertEqual(payload['personal']['email']['value'], 'first@example.invalid')
        self.assertEqual(payload['personal']['phone']['value'], '0123456789')
        self.assertIsNone(payload['personal']['location'])
        self.assertEqual([item['label'] for item in payload['other']], ['email', 'phone'])
        self.assertIn({'code': 'OTHER_TRUNCATED', 'field': 'other'}, payload['issues'])
        self.assertEqual(payload['inputSha256'], backend.claim['inputSha256'])
        for item in [value for value in payload['personal'].values() if value] + payload['other']:
            evidence = item['evidence']
            self.assertEqual(RAW[evidence['start'] : evidence['end']], evidence['text'])
        self.assertEqual(payload['personal']['fullName']['evidence']['start'], 2)

    def test_claim_and_result_disconnect_replay_exact_bytes_and_headers(self):
        backend = Backend()

        def hook(method, path, body):
            if (
                path.endswith(('/claims', '/result'))
                and len(backend.requests(path.rsplit('/', 1)[1])) == 1
            ):
                return 'disconnect'

        backend.hook = hook
        with patch('cv_compass_llm.backend_client.time.sleep') as sleep:
            self.run_worker(backend)
        self.assertEqual(sleep.call_count, 2)
        for suffix in ('claims', 'result'):
            calls = backend.requests(suffix)
            self.assertEqual(len(calls), 2)
            self.assertEqual(calls[0][2:], calls[1][2:])
        self.assertFalse(backend.requests('failure'))

    def test_no_job_once_does_not_fetch_input(self):
        backend = Backend()
        backend.hook = lambda *_args: (204, {})
        result, output = self.run_worker(backend)
        self.assertEqual(result, 0)
        self.assertIn('claimed=0 accepted=0 failed=0', output)
        self.assertEqual(len(backend.calls), 1)

    def test_terminal_claim_replay_is_not_extracted(self):
        for status in ('ACCEPTED', 'FAILED', 'EXPIRED'):
            backend = Backend()
            backend.claim['status'] = status
            backend.hook = lambda method, path, body: (200, {'data': backend.claim})
            self.run_worker(backend)
            self.assertEqual(len(backend.calls), 1)

    def test_all_additional_contacts_and_urls_keep_source_order(self):
        backend = Backend()
        backend.claim['definition']['other']['maxItems'] = 50
        backend.claim['definition']['fields']['email']['enabled'] = False
        self.run_worker(backend)
        payload = json.loads(backend.requests('result')[0][3])
        self.assertIsNone(payload['personal']['email'])
        self.assertEqual([item['label'] for item in payload['other']], ['phone', 'url'])
        self.assertEqual(payload['other'][-1]['value'], 'https://example.invalid/profile')

    def test_503_waits_then_retries_same_claim_and_body(self):
        backend = Backend()
        backend.hook = lambda method, path, body: (
            (503, {'code': 'MAPPING_REMOTE_UNAVAILABLE'})
            if path.endswith('/claims') and len(backend.requests('claims')) == 1
            else None
        )
        with patch('cv_compass_llm.backend_client.time.sleep') as sleep:
            self.run_worker(backend)
        sleep.assert_called_once_with(1)
        calls = backend.requests('claims')
        self.assertEqual(calls[0][2:], calls[1][2:])

    def test_poll_after_204_and_exhausted_503_reuses_uncertain_claim(self):
        backend = Backend()

        def hook(method, path, body):
            if path.endswith('/claims'):
                count = len(backend.requests('claims'))
                if count == 1:
                    return 204, {}
                if count in (2, 3, 4):
                    return 503, {'code': 'MAPPING_REMOTE_UNAVAILABLE'}
                if count == 6:
                    raise_shutdown.set()
                    return 204, {}

        backend.hook = hook
        raise_shutdown = threading.Event()

        def sleep(_delay):
            if raise_shutdown.is_set():
                raise KeyboardInterrupt

        with patch('cv_compass_llm.pull.time.sleep', side_effect=sleep):
            result, _ = self.run_worker(backend, once=False)
        self.assertEqual(result, 130)
        calls = backend.requests('claims')
        self.assertEqual(len(calls), 6)
        for call in calls[2:5]:
            self.assertEqual(call[2:], calls[1][2:])
        self.assertNotEqual(calls[0][2]['Idempotency-Key'], calls[1][2]['Idempotency-Key'])
        self.assertEqual(len(backend.requests('result')), 1)

    def test_provider_errors_and_invalid_schema_are_failures_only(self):
        for response, code in (
            (ProviderError('private-output'), 'PROVIDER_ERROR'),
            (ProviderError('OLLAMA_TIMEOUT'), 'PROVIDER_TIMEOUT'),
            ('private-output', 'INVALID_OUTPUT'),
            ({'personal': {}}, 'INVALID_OUTPUT'),
        ):
            with self.subTest(code=code):
                backend = Backend()
                self.run_worker(backend, FakeProvider([response]))
                self.assertEqual(json.loads(backend.requests('failure')[0][3]), {'code': code})
                self.assertFalse(backend.requests('result'))

    def test_heartbeat_while_provider_running_and_lost_lease_fences_outcomes(self):
        for lose in (False, 401, 409):
            with self.subTest(lose=lose):
                backend = Backend()
                backend.claim = claim(interval='PT0.02S')
                if lose:

                    def hook(method, path, body):
                        if path.endswith('/lease'):
                            backend.lease_seen.set()
                            return lose, {
                                'code': 'STALE_ATTEMPT' if lose == 409 else 'UNAUTHORIZED'
                            }

                    backend.hook = hook

                class SlowProvider:
                    name = 'synthetic'

                    def generate(self, prompt, schema):
                        if not backend.lease_seen.wait(3):
                            raise AssertionError('heartbeat missing')
                        return json.dumps(NULL_PERSONAL)

                self.run_worker(backend, SlowProvider())
                self.assertTrue(backend.requests('lease'))
                self.assertEqual(len(backend.requests('result')), 0 if lose else 1)
                self.assertFalse(backend.requests('failure'))

    def test_near_deadline_sends_timeout_while_provider_is_still_running(self):
        backend = Backend()
        backend.claim = claim(interval='PT0.1S', hard=0.35, lease=0.3)
        failure_seen = threading.Event()

        def hook(method, path, body):
            if path.endswith('/failure'):
                failure_seen.set()

        backend.hook = hook

        class SlowProvider:
            name = 'synthetic'

            def generate(self, prompt, schema):
                if not failure_seen.wait(3):
                    raise AssertionError('timeout failure missing')
                return json.dumps(NULL_PERSONAL)

        self.run_worker(backend, SlowProvider())
        self.assertEqual(
            json.loads(backend.requests('failure')[0][3]), {'code': 'PROVIDER_TIMEOUT'}
        )
        self.assertFalse(backend.requests('result'))

    def test_short_lease_is_renewed_before_heartbeat_interval(self):
        for ttl in (0.5, 1):
            with self.subTest(ttl=ttl):
                backend = Backend()
                backend.claim = claim(interval='PT1S', hard=10, lease=ttl)

                class SlowProvider:
                    name = 'synthetic'

                    def generate(self, prompt, schema):
                        if not backend.lease_seen.wait(3):
                            raise AssertionError('short lease was not renewed')
                        return json.dumps(NULL_PERSONAL)

                _, output = self.run_worker(backend, SlowProvider())
                self.assertTrue(backend.requests('lease'))
                self.assertEqual(len(backend.requests('result')), 1)
                self.assertNotIn('LEASE_EXPIRED', output)

    def test_result_conflicts_and_validation_errors_are_not_retried_or_changed(self):
        for status, code in (
            (409, 'OUTCOME_CONFLICT'),
            (409, 'LEASE_EXPIRED'),
            (409, 'STALE_ATTEMPT'),
            (422, 'INVALID_EVIDENCE'),
            (422, 'INVALID_RESULT'),
            (413, 'RESULT_TOO_LARGE'),
        ):
            backend = Backend()
            backend.hook = lambda method, path, body: (
                (status, {'code': code}) if path.endswith('/result') else None
            )
            _, output = self.run_worker(backend)
            self.assertIn(code + '=1', output)
            self.assertEqual(len(backend.requests('result')), 1)
            self.assertFalse(backend.requests('failure'))

    def test_shutdown_after_uncertain_result_does_not_send_different_outcome(self):
        backend = Backend()
        backend.hook = lambda method, path, body: 'disconnect' if path.endswith('/result') else None
        with patch('cv_compass_llm.backend_client.time.sleep', side_effect=KeyboardInterrupt):
            result, _ = self.run_worker(backend)
        self.assertEqual(result, 130)
        self.assertEqual(len(backend.requests('result')), 1)
        self.assertFalse(backend.requests('failure'))

    def test_shutdown_during_supervisor_construction_reports_failure(self):
        for phase in ('construction', 'start'):
            with self.subTest(phase=phase):
                backend = Backend()

                def interrupted_factory(*args):
                    if phase == 'construction':
                        raise KeyboardInterrupt
                    attempt = _Attempt(*args)
                    attempt.thread.start = Mock(side_effect=KeyboardInterrupt)
                    return attempt

                with patch('cv_compass_llm.pull._Attempt', side_effect=interrupted_factory):
                    result, _ = self.run_worker(backend)
                self.assertEqual(result, 130)
                self.assertEqual(
                    json.loads(backend.requests('failure')[0][3]), {'code': 'WORKER_SHUTDOWN'}
                )
                self.assertFalse(backend.requests('input'))
                self.assertFalse(backend.requests('result'))

    def test_expired_claim_never_sends_outcome(self):
        backend = Backend()
        backend.claim = claim(lease=-1)
        self.run_worker(backend)
        self.assertFalse(backend.requests('result'))
        self.assertFalse(backend.requests('failure'))

    def test_invalid_input_unsupported_engine_and_shutdown(self):
        for scenario, code in (
            ('input', 'INVALID_INPUT'),
            ('engine', 'UNSUPPORTED_ENGINE'),
            ('shutdown', 'WORKER_SHUTDOWN'),
            ('exception', 'INTERNAL_ERROR'),
        ):
            backend = Backend()
            if scenario == 'input':
                backend.input = {'pages': [{'page': 1, 'raw_text': ''}]}
            if scenario == 'engine':
                backend.claim['engineVersion'] = 'unknown'

            class Provider:
                name = 'synthetic'

                def generate(self, prompt, schema):
                    if scenario == 'shutdown':
                        raise KeyboardInterrupt
                    raise RuntimeError('private-output')

            result, _ = self.run_worker(backend, Provider())
            self.assertEqual(result, 130 if scenario == 'shutdown' else 0)
            self.assertEqual(json.loads(backend.requests('failure')[0][3]), {'code': code})
            self.assertFalse(backend.requests('result'))
            if scenario == 'engine':
                self.assertFalse(backend.requests('input'))

    def test_redirect_is_not_followed_and_proxies_are_ignored(self):
        backend = Backend()
        backend.hook = lambda *_args: (302, {})
        with patch.dict(
            os.environ,
            {
                'http_proxy': 'http://127.0.0.1:1',
                'HTTP_PROXY': 'http://127.0.0.1:1',
                'no_proxy': '',
                'NO_PROXY': '',
            },
        ):
            _, output = self.run_worker(backend)
        self.assertIn('BACKEND_REDIRECT=1', output)
        self.assertEqual(len(backend.calls), 1)
        backend = Backend()
        with patch.dict(os.environ, {'http_proxy': 'http://127.0.0.1:1', 'no_proxy': ''}):
            result, output = self.run_worker(backend)
        self.assertEqual(result, 0)
        self.assertIn('accepted=1', output)

    def test_no_worker_file_writes(self):
        backend = Backend()
        with patch('builtins.open', side_effect=AssertionError('unexpected file access')):
            self.run_worker(backend)

    def test_response_limits_and_unknown_error_body_remain_private(self):
        for path, limit in (
            ('/claims', 64 * 1024),
            ('/attempts/' + ATTEMPT_ID + '/input', 16 * 1024 * 1024),
        ):
            backend = Backend()
            backend.hook = lambda *_args: (200, b'x' * (limit + 1))
            with backend_server(backend) as url:
                with self.assertRaisesRegex(BackendError, '^BACKEND_RESPONSE_TOO_LARGE$'):
                    BackendClient(config(url)).request('GET', path, 'x' * 43)
        backend = Backend()
        backend.hook = lambda *_args: (422, {'code': 'PRIVATE_OUTPUT', 'message': RAW})
        _, output = self.run_worker(backend)
        self.assertIn('BACKEND_HTTP_ERROR=1', output)
        self.assertNotIn('PRIVATE_OUTPUT', output)


class PayloadTests(unittest.TestCase):
    def test_input_adapter_retains_page_offsets_and_blank_pages(self):
        document = input_document(
            {'pages': [{'page': 1, 'raw_text': ''}, {'page': 2, 'raw_text': '🧪 synthetic'}]}
        )
        self.assertEqual(document.pages[0].status, 'blank')
        self.assertEqual(document.pages[1].number, 2)
        for value in (
            {'pages': []},
            {'pages': [{'page': True, 'raw_text': 'a'}]},
            {'pages': [{'page': 2, 'raw_text': 'a'}]},
            {'pages': [{'page': 1, 'raw_text': None}]},
        ):
            with self.assertRaisesRegex(ValueError, '^INVALID_INPUT$'):
                input_document(value)

    def test_experience_evidence_issue_fields_and_other_switch(self):
        evidence = Evidence(1, 0, 1, 'x')
        result = ExtractionResult(
            (FieldValue('url', 'x', evidence),),
            (
                Experience(
                    'x',
                    None,
                    None,
                    None,
                    False,
                    {'title': evidence, 'company': evidence, 'period': evidence},
                ),
            ),
            (Issue('QUOTE_NOT_FOUND', 'period'), Issue('FIELD_DISABLED', 'fullName')),
            'synthetic',
            'fake',
        )
        pinned = claim()
        pinned['definition']['other']['enabled'] = False
        payload = json.loads(compact_result(result, pinned, {}))
        self.assertEqual(payload['other'], [])
        self.assertEqual(set(payload['experiences'][0]['evidence']), {'title'})
        self.assertEqual(payload['issues'], [{'code': 'QUOTE_NOT_FOUND', 'field': 'experiences'}])
        pinned['definition']['fields']['experiences']['enabled'] = False
        self.assertEqual(json.loads(compact_result(result, pinned, {}))['experiences'], [])

    def test_oversized_result_is_rejected_without_truncation(self):
        value = 'x' * (1024 * 1024)
        result = ExtractionResult(
            (FieldValue('fullName', value, Evidence(1, 0, len(value), value)),),
            (),
            (),
            'synthetic',
            'fake',
        )
        with self.assertRaisesRegex(ValueError, '^RESULT_TOO_LARGE$'):
            compact_result(result, claim(), {})


class PayloadVerifierRegressionTests(unittest.TestCase):
    def payload(self, fields=(), experiences=(), pinned=None):
        result = ExtractionResult(tuple(fields), tuple(experiences), (), 'synthetic', 'fake')
        return json.loads(compact_result(result, pinned or claim(), {}))

    def assert_verifier_strings(self, payload, pages):
        """Independently mirror Java (?U) whitespace equality and code-point slicing."""

        def whitespace(text):
            return re.sub(
                r'[\u0009-\u000d\u0020\u0085\u00a0\u1680\u2000-\u200a'
                r'\u2028\u2029\u202f\u205f\u3000]+',
                ' ',
                text,
            ).strip(' \u001c\u001d\u001e\u001f')

        def check(value, evidence):
            raw = pages[evidence['page']]
            self.assertLess(evidence['start'], evidence['end'])
            self.assertGreaterEqual(evidence['start'], 0)
            self.assertLessEqual(evidence['end'], len(raw))
            self.assertEqual(raw[evidence['start'] : evidence['end']], evidence['text'])
            self.assertEqual(whitespace(value), whitespace(evidence['text']))

        for key, field in payload['personal'].items():
            if field is not None and key not in ('gender', 'dateOfBirth'):
                check(field['value'], field['evidence'])
        for item in payload['other']:
            check(item['value'], item['evidence'])
        for row in payload['experiences']:
            for key in ('title', 'company'):
                if row[key] is not None:
                    check(row[key], row['evidence'][key])

    def test_string_evidence_is_narrowed_with_unicode_offsets_and_whitespace(self):
        raw = '🧪 prefix 😀 Name: Synthetic\u00a0 \nPerson; suffix'
        quote_start = raw.index('😀')
        evidence = Evidence(1, quote_start, len(raw), raw[quote_start:])
        fields = [
            FieldValue(key, 'Synthetic Person', evidence)
            for key in ('fullName', 'email', 'phone', 'location', 'summary', 'url')
        ]
        fields += [
            FieldValue('email', 'Synthetic Person', evidence),
            FieldValue('phone', 'Synthetic Person', evidence),
        ]
        pinned = claim()
        pinned['definition']['fields']['location']['enabled'] = True
        pinned['definition']['other']['maxItems'] = 50
        experience = Experience(
            'Synthetic Person',
            'Synthetic\tPerson',
            None,
            None,
            False,
            {'title': evidence, 'company': evidence},
        )
        payload = self.payload(fields, (experience,), pinned)
        self.assert_verifier_strings(payload, {1: raw})
        self.assertEqual(
            payload['personal']['fullName']['evidence']['start'], raw.index('Synthetic')
        )
        self.assertEqual(
            payload['personal']['fullName']['evidence']['text'], 'Synthetic\u00a0 \nPerson'
        )
        self.assertEqual(len(payload['other']), 3)
        self.assertFalse(payload['issues'])

    def test_unmatched_strings_are_removed_with_quote_issues(self):
        evidence = Evidence(1, 0, 5, 'other')
        fields = [
            FieldValue(key, 'missing', evidence)
            for key in ('fullName', 'email', 'phone', 'location', 'summary', 'url')
        ]
        fields += [FieldValue('email', 'missing', evidence)]
        pinned = claim()
        pinned['definition']['fields']['location']['enabled'] = True
        experience = Experience(
            'missing', 'missing', None, None, False, {'title': evidence, 'company': evidence}
        )
        payload = self.payload(fields, (experience,), pinned)
        self.assertTrue(all(value is None for value in payload['personal'].values()))
        self.assertEqual(payload['other'], [])
        self.assertIsNone(payload['experiences'][0]['title'])
        self.assertIsNone(payload['experiences'][0]['company'])
        self.assertEqual(payload['experiences'][0]['evidence'], {})
        self.assertEqual({issue['code'] for issue in payload['issues']}, {'QUOTE_NOT_FOUND'})
        self.assertEqual(
            {issue['field'] for issue in payload['issues']},
            {'fullName', 'email', 'phone', 'location', 'summary', 'other', 'experiences'},
        )
        self.assert_verifier_strings(payload, {1: 'other'})

    def test_python_only_whitespace_does_not_bypass_backend_equality(self):
        evidence = Evidence(1, 0, 3, 'A\x1cB')
        payload = self.payload((FieldValue('fullName', 'A B', evidence),))
        self.assertIsNone(payload['personal']['fullName'])
        self.assertEqual(payload['issues'], [{'code': 'QUOTE_NOT_FOUND', 'field': 'fullName'}])

    def test_gender_birth_and_period_quotes_are_not_narrowed(self):
        evidence = Evidence(1, 0, 19, 'Gender: Other; 2000')
        fields = (
            FieldValue('gender', 'OTHER', evidence),
            FieldValue('dateOfBirth', '2000', evidence),
        )
        experience = Experience(None, None, '2000', None, True, {'period': evidence})
        payload = self.payload(fields, (experience,))
        for key in ('gender', 'dateOfBirth'):
            self.assertEqual(payload['personal'][key]['evidence']['text'], evidence.text)
        self.assertEqual(payload['experiences'][0]['evidence']['period']['text'], evidence.text)

    def test_invalid_birth_dates_are_omitted(self):
        evidence = Evidence(1, 0, 1, 'x')
        for value in (
            '1899',
            '2101',
            '2000-00',
            '2000-13',
            '2000-1',
            '2000-02-30',
            '２０００',
            ' 2000',
        ):
            with self.subTest(value=value):
                payload = self.payload((FieldValue('dateOfBirth', value, evidence),))
                self.assertIsNone(payload['personal']['dateOfBirth'])
                self.assertEqual(
                    payload['issues'], [{'code': 'INVALID_DATE', 'field': 'dateOfBirth'}]
                )
        for value in ('1900', '2100', '1900-01', '2100-12', '2000-01-01'):
            payload = self.payload((FieldValue('dateOfBirth', value, evidence),))
            self.assertEqual(payload['personal']['dateOfBirth']['value'], value)
            self.assertFalse(payload['issues'])

    def test_invalid_experience_periods_are_cleared_together(self):
        evidence = Evidence(1, 0, 1, 'x')
        for start, end, ongoing in (
            ('1949', None, False),
            (None, '2101', False),
            ('2000-00', None, False),
            (None, '2000-13', False),
            ('2000-1', None, False),
            ('２０００', None, False),
            ('2000-01-01', None, False),
            ('2001', '2000', False),
            ('2000-12', '2000-11', False),
            ('2000', '2001', True),
            (None, '2000', True),
        ):
            with self.subTest(start=start, end=end, ongoing=ongoing):
                experience = Experience(
                    'x',
                    'x',
                    start,
                    end,
                    ongoing,
                    {'title': evidence, 'company': evidence, 'period': evidence},
                )
                payload = self.payload(experiences=(experience,))
                row = payload['experiences'][0]
                self.assertIsNone(row['start'])
                self.assertIsNone(row['end'])
                self.assertIs(row['ongoing'], False)
                self.assertEqual(set(row['evidence']), {'title', 'company'})
                self.assertEqual(
                    payload['issues'], [{'code': 'INVALID_DATE', 'field': 'experiences'}]
                )
                self.assert_verifier_strings(payload, {1: 'x'})

    def test_valid_partial_periods_follow_backend_interval_order(self):
        evidence = Evidence(1, 0, 1, 'x')
        for start, end, ongoing in (
            ('1950', '2100-12', False),
            ('2000-12', '2000', False),
            ('2000', '2000-01', False),
            ('2000', None, True),
            (None, None, True),
            (None, None, False),
        ):
            with self.subTest(start=start, end=end, ongoing=ongoing):
                experience = Experience(
                    'x', None, start, end, ongoing, {'title': evidence, 'period': evidence}
                )
                payload = self.payload(experiences=(experience,))
                row = payload['experiences'][0]
                self.assertEqual((row['start'], row['end'], row['ongoing']), (start, end, ongoing))
                self.assertEqual(
                    'period' in row['evidence'], start is not None or end is not None or ongoing
                )
                self.assertFalse(payload['issues'])

    def test_disabled_contact_labels_never_leak_into_other(self):
        evidence = Evidence(1, 0, 1, 'x')
        fields = [
            FieldValue(key, 'x', evidence) for key in ('email', 'email', 'phone', 'phone', 'url')
        ]
        for other_enabled in (True, False):
            pinned = claim()
            pinned['definition']['fields']['email']['enabled'] = False
            pinned['definition']['fields']['phone']['enabled'] = False
            pinned['definition']['other']['enabled'] = other_enabled
            payload = self.payload(fields, pinned=pinned)
            self.assertIsNone(payload['personal']['email'])
            self.assertIsNone(payload['personal']['phone'])
            self.assertEqual(
                [item['label'] for item in payload['other']], ['url'] if other_enabled else []
            )
            self.assert_verifier_strings(payload, {1: 'x'})
