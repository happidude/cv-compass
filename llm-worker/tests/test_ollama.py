"""Synthetic in-process HTTP tests for local transport and privacy."""

import json
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch
from urllib.error import URLError

from cv_compass_llm.providers import OllamaProvider, ProviderError


class Handler(BaseHTTPRequestHandler):
    def do_POST(self) -> None:
        """Capture a synthetic request and serve the configured response."""
        self.server.requests.append((self.path, json.loads(
            self.rfile.read(int(self.headers['Content-Length'])),
        )))
        time.sleep(self.server.delay)
        self.send_response(self.server.status)
        if self.server.status == 302:
            self.send_header('Location', self.server.redirect)
        self.end_headers()
        try:
            self.wfile.write(self.server.body)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass

    def log_message(self, format: str, *args: object) -> None:
        """Keep HTTP diagnostics out of test output."""


class OllamaTests(unittest.TestCase):
    def setUp(self) -> None:
        """Start a loopback-only synthetic server on an ephemeral port."""
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.requests = []
        self.server.status = 200
        self.server.delay = 0
        self.server.body = json.dumps({'message': {'content': 'synthetic response'}}).encode()
        self.url = f'http://127.0.0.1:{self.server.server_port}'
        self.server.redirect = self.url + '/redirected'
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)

    def stop_server(self) -> None:
        """Stop the server and join the serving thread."""
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def provider(self, **kwargs: object) -> OllamaProvider:
        """Create a provider pointing only at the ephemeral test server."""
        return OllamaProvider('synthetic-model', base_url=self.url, **kwargs)

    def assert_failure(self, code: str, **kwargs: object) -> None:
        """Assert a fixed diagnostic with no source or response marker."""
        with self.assertRaises(ProviderError) as caught:
            self.provider(**kwargs).generate('PRIVATE_PROMPT_MARKER', {})
        self.assertEqual(str(caught.exception), code)
        self.assertNotIn('PRIVATE', str(caught.exception))

    def test_request_contract_and_content(self) -> None:
        """Send the exact nonstreaming deterministic chat request."""
        schema = {'type': 'object', 'properties': {}}
        provider = self.provider()
        self.assertEqual(provider.name, 'ollama:synthetic-model')
        self.assertEqual(provider.generate('synthetic prompt', schema), 'synthetic response')
        self.assertEqual(self.server.requests, [('/api/chat', {
            'model': 'synthetic-model',
            'messages': [{'role': 'user', 'content': 'synthetic prompt'}],
            'format': schema, 'stream': False, 'think': False, 'options': {'temperature': 0},
        })])

    def test_proxy_environment_is_ignored(self) -> None:
        """Reach loopback even with unusable proxies and no bypass list."""
        with patch.dict('os.environ', {
            'HTTP_PROXY': 'http://127.0.0.1:1', 'http_proxy': 'http://127.0.0.1:1',
            'ALL_PROXY': 'http://127.0.0.1:1', 'NO_PROXY': '', 'no_proxy': '',
        }):
            self.assertEqual(self.provider().generate('synthetic', {}), 'synthetic response')

    def test_configured_generation_options(self) -> None:
        """Send explicit generation options including optional context size."""
        self.provider(temperature=1.25, think=True, num_ctx=4096).generate('synthetic', {})
        payload = self.server.requests[0][1]
        self.assertIs(payload['think'], True)
        self.assertEqual(payload['options'], {'temperature': 1.25, 'num_ctx': 4096})

    def test_generation_option_validation(self) -> None:
        """Reject wrongly typed and out-of-range options before transport."""
        for name, invalid, code in (
            ('temperature', (-1, 2.1, 10 ** 400, float('nan'), float('inf'), True, '0', None),
             'INVALID_TEMPERATURE'),
            ('think', ('false', 0, 1, None), 'INVALID_THINK'),
            ('num_ctx', (0, -1, 1048577, True, 1.0, '1'), 'INVALID_NUM_CTX'),
        ):
            for value in invalid:
                with self.subTest(name=name, value=value):
                    with self.assertRaisesRegex(ValueError, '^' + code + '$'):
                        self.provider(**{name: value})
        for temperature in (0, 2):
            for num_ctx in (None, 1, 1048576):
                self.provider(temperature=temperature, think=False, num_ctx=num_ctx)

    def test_http_error_has_fixed_code(self) -> None:
        """Discard the HTTP error body instead of exposing its contents."""
        self.server.status, self.server.body = 500, b'PRIVATE_RESPONSE_MARKER'
        self.assert_failure('OLLAMA_HTTP_500')

    def test_timeout_has_fixed_code(self) -> None:
        """Translate socket timeouts without retaining request content."""
        self.server.delay = 0.15
        self.assert_failure('OLLAMA_TIMEOUT', timeout_s=0.02)

    def test_bad_responses_have_fixed_code(self) -> None:
        """Reject non-JSON and incorrectly typed chat envelopes."""
        for body in (b'PRIVATE_RESPONSE_MARKER', b'{}', b'[]', b'null',
                     b'{"message": {}}', b'{"message": {"content": 3}}',
                     b'{"message": {"content": "ok", "content": "duplicate"}}', b'\xff'):
            with self.subTest(body=body):
                self.server.body = body
                self.assert_failure('OLLAMA_BAD_RESPONSE')

    def test_response_read_is_bounded(self) -> None:
        """Reject a response exceeding the configured byte boundary."""
        with patch('cv_compass_llm.providers.ollama.MAX_RESPONSE_BYTES', 8):
            self.assert_failure('OLLAMA_BAD_RESPONSE')

    def test_redirect_is_not_followed(self) -> None:
        """Reject even a local redirect before sending a second request."""
        self.server.status = 302
        self.assert_failure('OLLAMA_HTTP_302')
        self.assertEqual(len(self.server.requests), 1)

    def test_unreachable_and_wrapped_timeout(self) -> None:
        """Translate urllib transport failures to fixed codes."""
        provider = self.provider()
        for reason, expected in ((OSError('PRIVATE'), 'OLLAMA_UNREACHABLE'),
                                 (TimeoutError('PRIVATE'), 'OLLAMA_TIMEOUT')):
            with patch.object(provider._opener, 'open', side_effect=URLError(reason)):
                with self.assertRaisesRegex(ProviderError, '^' + expected + '$'):
                    provider.generate('PRIVATE_PROMPT', {})

    def test_only_local_http_endpoints_are_allowed(self) -> None:
        """Reject remote hosts, credentials and ambiguous endpoint components."""
        for url in ('https://127.0.0.1', 'http://example.invalid', 'http://127.0.0.2',
                    'http://localhost.evil.invalid', 'http://user@localhost',
                    'http://localhost/path', 'http://localhost?x=1', 'http://localhost?',
                    'http://localhost#', 'http://127.0.0.1@evil.invalid',
                    'http://localhost#x', 'http://localhost:bad', 'http://[::1',
                    'http://localhost\n', 'file:///tmp/fixture'):
            with self.subTest(url=url):
                with self.assertRaisesRegex(ValueError, '^OLLAMA_HOST_NOT_LOCAL$'):
                    OllamaProvider('synthetic', base_url=url)
        for url in ('http://localhost', 'http://127.0.0.1/', 'http://[::1]:11434'):
            OllamaProvider('synthetic', base_url=url)

    def test_invalid_timeout_and_model(self) -> None:
        """Reject invalid local options before making any request."""
        for timeout in (0, -1, float('nan'), float('inf'), True):
            with self.assertRaisesRegex(ValueError, '^INVALID_TIMEOUT$'):
                self.provider(timeout_s=timeout)
        with self.assertRaisesRegex(ValueError, '^INVALID_MODEL$'):
            OllamaProvider(' ')
