"""L7 API contracts and credential privacy using synthetic, offline openers only."""

import copy
import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from dataclasses import replace
from email.message import Message
from http.client import HTTPException
from pathlib import Path
from unittest.mock import Mock, patch
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler

from cv_compass_llm.cli import create_provider, main
from cv_compass_llm.config import (
    API_KEY_ENV,
    PullConfigError,
    load_bench_config,
    load_config,
    load_norm_config,
    load_pull_config,
)
from cv_compass_llm.providers.anthropic_api import ANTHROPIC_URL, AnthropicProvider
from cv_compass_llm.providers.base import ProviderError
from cv_compass_llm.providers.ollama import MAX_RESPONSE_BYTES, OllamaProvider, _NoRedirect
from cv_compass_llm.providers.openai_api import OPENAI_URL, OpenAIProvider

KEY = 'sk-test-SYNTHETIC-0000'
PROMPT = 'Synthetic extraction prompt'
SCHEMA = {
    'type': 'object',
    'properties': {
        'rows': {
            'type': 'array',
            'minItems': 1,
            'maxItems': 2,
            'items': {
                'anyOf': [
                    {'type': 'string', 'minLength': 1, 'maxLength': 8},
                    {
                        'type': ['number', 'null'],
                        'minimum': 0,
                        'maximum': 9,
                        'exclusiveMinimum': -1,
                        'exclusiveMaximum': 10,
                        'multipleOf': 1,
                    },
                ]
            },
        },
        'minItems': {'type': 'string'},
    },
    'required': ['rows', 'minItems'],
    'additionalProperties': False,
}
TYPES = (OpenAIProvider, AnthropicProvider)


def envelope(provider_type, text='{}', *, length=False, usage=None):
    if provider_type is OpenAIProvider:
        return {
            'status': 'incomplete' if length else 'completed',
            'incomplete_details': {'reason': 'max_output_tokens'} if length else None,
            'output': [
                {'type': 'reasoning'},
                {
                    'type': 'message',
                    'content': [
                        {'type': 'output_text', 'text': text[:1]},
                        {'type': 'output_text', 'text': text[1:]},
                    ],
                },
            ],
            'usage': usage or {'input_tokens': 12, 'output_tokens': 3},
        }
    return {
        'stop_reason': 'max_tokens' if length else 'end_turn',
        'content': [
            {'type': 'thinking', 'thinking': 'Synthetic ignored block'},
            {'type': 'text', 'text': text[:1]},
            {'type': 'text', 'text': text[1:]},
        ],
        'usage': usage or {'input_tokens': 12, 'output_tokens': 3},
    }


class Response(io.BytesIO):
    def __init__(self, body, code=200, headers=None):
        super().__init__(body if isinstance(body, bytes) else json.dumps(body).encode())
        self.code = code
        self.headers = headers or Message()
        self.read_sizes = []

    def read(self, size=-1):
        self.read_sizes.append(size)
        return super().read(size)


class Opener:
    def __init__(self, *responses):
        self.responses = list(responses)
        self.requests = []

    def open(self, request, *, timeout):
        self.requests.append((request, timeout))
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return response


def http_error(code, retry_after=None):
    headers = Message()
    if retry_after is not None:
        headers['Retry-After'] = retry_after
    body = Response(('Synthetic error ' + KEY).encode())
    return HTTPError(OPENAI_URL, code, KEY, headers, body), body


def config(provider='openai', **options):
    values = {'provider': provider, 'model': 'synthetic-model', **options}
    return load_config(
        values,
        {'OPENAI_API_KEY': KEY, 'ANTHROPIC_API_KEY': KEY},
        None,
        env_file_explicit=False,
        _file_values={},
    )


class ApiTransportTests(unittest.TestCase):
    def test_exact_request_contracts_and_schema_isolation(self):
        original = copy.deepcopy(SCHEMA)
        for provider_type, url in (
            (OpenAIProvider, OPENAI_URL),
            (AnthropicProvider, ANTHROPIC_URL),
        ):
            with self.subTest(provider=provider_type):
                response = Response(envelope(provider_type))
                opener = Opener(response)
                provider = provider_type(
                    'synthetic-model',
                    api_key=KEY,
                    reasoning_effort='high',
                    max_output_tokens=1234,
                    _opener=opener,
                )
                self.assertEqual(provider.generate(PROMPT, SCHEMA, num_ctx=999, timeout_s=17), '{}')
                request, timeout = opener.requests[0]
                self.assertEqual(
                    (request.full_url, request.get_method(), timeout), (url, 'POST', 17)
                )
                headers = dict((key.lower(), value) for key, value in request.header_items())
                self.assertEqual(headers['content-type'], 'application/json')
                payload = json.loads(request.data)
                if provider_type is OpenAIProvider:
                    self.assertEqual(headers['authorization'], 'Bearer ' + KEY)
                    expected = {
                        'model': 'synthetic-model',
                        'input': PROMPT,
                        'text': {
                            'format': {
                                'type': 'json_schema',
                                'name': 'cv_extraction',
                                'schema': original,
                                'strict': True,
                            }
                        },
                        'reasoning': {'effort': 'high'},
                        'store': False,
                        'max_output_tokens': 1234,
                    }
                else:
                    self.assertEqual(headers['x-api-key'], KEY)
                    self.assertEqual(headers['anthropic-version'], '2023-06-01')
                    reduced = copy.deepcopy(original)
                    rows = reduced['properties']['rows']
                    del rows['minItems'], rows['maxItems']
                    rows['items']['anyOf'] = [{'type': 'string'}, {'type': ['number', 'null']}]
                    expected = {
                        'model': 'synthetic-model',
                        'max_tokens': 1234,
                        'messages': [{'role': 'user', 'content': PROMPT}],
                        'thinking': {'type': 'disabled'},
                        'output_config': {
                            'effort': 'high',
                            'format': {'type': 'json_schema', 'schema': reduced},
                        },
                    }
                self.assertEqual(payload, expected)
                self.assertEqual(SCHEMA, original)
                self.assertEqual(response.read_sizes, [MAX_RESPONSE_BYTES + 1])
                self.assertIsNone(provider.num_ctx)
                self.assertEqual(provider.name, provider.provider + ':synthetic-model')
                self.assertEqual(provider.last_metrics['prompt_tokens'], 12)
                self.assertEqual(provider.last_metrics['output_tokens'], 3)
                self.assertEqual(provider.last_metrics['done_reason'], 'stop')
                self.assertGreaterEqual(provider.last_metrics['total_ns'], 0)

    def test_default_payload_options(self):
        for provider_type in TYPES:
            opener = Opener(Response(envelope(provider_type)))
            provider_type('synthetic', api_key=KEY, _opener=opener).generate(PROMPT, {})
            payload = json.loads(opener.requests[0][0].data)
            if provider_type is OpenAIProvider:
                self.assertNotIn('max_output_tokens', payload)
                self.assertEqual(payload['reasoning'], {'effort': 'none'})
            else:
                self.assertEqual(payload['max_tokens'], 8192)
                self.assertEqual(payload['output_config']['effort'], 'low')

    def test_total_time_includes_retry_and_per_attempt_timeout(self):
        for provider_type in TYPES:
            error, _ = http_error(429)
            opener = Opener(error, Response(envelope(provider_type)))
            elapsed = [100]

            def sleep(seconds):
                elapsed[0] += seconds * 10**9

            provider = provider_type('synthetic', api_key=KEY, _opener=opener, _sleep=sleep)
            with patch(
                'cv_compass_llm.providers.openai_api.time.perf_counter_ns',
                side_effect=lambda: elapsed[0],
            ):
                self.assertEqual(provider.generate(PROMPT, {}, timeout_s=23), '{}')
            self.assertEqual(provider.last_metrics['total_ns'], 10**9)
            self.assertEqual([timeout for _, timeout in opener.requests], [23, 23])

    def test_non_success_response_is_closed_without_reading(self):
        for provider_type in TYPES:
            response = Response(b'Synthetic redirect body', code=302)
            opener = Opener(response)
            provider = provider_type('synthetic', api_key=KEY, _opener=opener)
            with self.assertRaisesRegex(ProviderError, '^API_HTTP_302$'):
                provider.generate(PROMPT, {})
            self.assertEqual(len(opener.requests), 1)
            self.assertEqual(response.read_sizes, [])
            self.assertTrue(response.closed)

    def test_retry_policy_and_error_bodies_never_read(self):
        cases = (
            (429, [None], 2, [1]),
            (429, ['90'], 2, [30]),
            (503, ['0'], 2, [0]),
            (500, [None] * 4, 4, [1, 2, 4]),
            (400, [None], 1, []),
            (302, [None], 1, []),
            (429, ['invalid'], 2, [1]),
            (429, ['-1'], 2, [1]),
        )
        for provider_type in TYPES:
            for code, retries, count, delays in cases:
                with self.subTest(provider=provider_type, code=code, retries=retries):
                    errors = [http_error(code, retry) for retry in retries]
                    responses = [error for error, _ in errors]
                    if code in (429, 503):
                        responses.append(Response(envelope(provider_type)))
                    opener, sleep = Opener(*responses), Mock()
                    provider = provider_type('synthetic', api_key=KEY, _opener=opener, _sleep=sleep)
                    if code in (429, 503):
                        self.assertEqual(provider.generate(PROMPT, {}), '{}')
                    else:
                        with self.assertRaises(ProviderError) as caught:
                            provider.generate(PROMPT, {})
                        self.assertEqual(str(caught.exception), f'API_HTTP_{code}')
                        self.assertNotIn(KEY, repr(caught.exception))
                        self.assertTrue(caught.exception.__suppress_context__)
                    self.assertEqual(len(opener.requests), count)
                    self.assertEqual([call.args[0] for call in sleep.call_args_list], delays)
                    for _, body in errors:
                        self.assertEqual(body.read_sizes, [])
                        self.assertTrue(body.closed)

    def test_timeout_unreachable_and_http_protocol_errors(self):
        for provider_type in TYPES:
            for error, code in (
                (TimeoutError(KEY), 'API_TIMEOUT'),
                (URLError(TimeoutError(KEY)), 'API_TIMEOUT'),
                (URLError(KEY), 'API_UNREACHABLE'),
                (OSError(KEY), 'API_UNREACHABLE'),
                (HTTPException(KEY), 'API_BAD_RESPONSE'),
            ):
                opener = Opener(error)
                provider = provider_type('synthetic', api_key=KEY, _opener=opener)
                with self.assertRaises(ProviderError) as caught:
                    provider.generate(PROMPT, {})
                self.assertEqual(str(caught.exception), code)
                self.assertNotIn(KEY, repr(caught.exception))
                self.assertEqual(len(opener.requests), 1)

    def test_truncation_and_metrics_reset_and_validation(self):
        for provider_type in TYPES:
            opener = Opener(
                Response(envelope(provider_type, '{', length=True)),
                Response(
                    envelope(
                        provider_type, usage={'input_tokens': True, 'output_tokens': 2**53 + 1}
                    )
                ),
                Response(
                    envelope(provider_type, usage={'input_tokens': 0, 'output_tokens': 2**53})
                ),
            )
            provider = provider_type('synthetic', api_key=KEY, _opener=opener)
            self.assertEqual(provider.generate(PROMPT, {}), '{')
            self.assertEqual(provider.last_metrics['done_reason'], 'length')
            provider.generate(PROMPT, {})
            self.assertNotIn('prompt_tokens', provider.last_metrics)
            self.assertNotIn('output_tokens', provider.last_metrics)
            provider.generate(PROMPT, {})
            self.assertEqual(provider.last_metrics['prompt_tokens'], 0)
            self.assertEqual(provider.last_metrics['output_tokens'], 2**53)
            for timeout in (True, 0, -1, float('nan'), float('inf'), '1'):
                with self.assertRaisesRegex(ValueError, '^INVALID_TIMEOUT$'):
                    provider.generate(PROMPT, {}, timeout_s=timeout)
            self.assertEqual(provider.last_metrics, {})

    def test_bad_responses_and_refusals(self):
        for provider_type in TYPES:
            refusal = envelope(provider_type)
            if provider_type is OpenAIProvider:
                refusal['output'][1]['content'].append({'type': 'refusal', 'refusal': KEY})
            else:
                refusal['stop_reason'] = 'refusal'
            for body in (
                b'not JSON',
                b'\xff',
                b'{"a":1,"a":2}',
                b'[]',
                b'{}',
                b'x' * (MAX_RESPONSE_BYTES + 1),
                envelope(provider_type, ''),
                refusal,
            ):
                with self.subTest(provider=provider_type, body_type=type(body)):
                    opener = Opener(Response(body))
                    provider = provider_type('synthetic', api_key=KEY, _opener=opener)
                    with self.assertRaisesRegex(ProviderError, '^API_BAD_RESPONSE$'):
                        provider.generate(PROMPT, {})
                    self.assertNotIn('prompt_tokens', provider.last_metrics)

    def test_default_transport_has_no_proxy_or_redirect_and_checks_destination(self):
        with patch('cv_compass_llm.providers.openai_api.build_opener') as build:
            for provider_type in TYPES:
                provider = provider_type('synthetic', api_key=KEY)
                handlers = build.call_args.args
                self.assertIsInstance(handlers[0], ProxyHandler)
                self.assertEqual(handlers[0].proxies, {})
                self.assertIsInstance(handlers[1], _NoRedirect)
                self.assertIsNone(
                    handlers[1].redirect_request(None, None, 302, '', None, OPENAI_URL)
                )
                for url in ('http://api.openai.com/v1/responses', 'https://example.invalid'):
                    provider.url = url
                    with self.assertRaisesRegex(ProviderError, '^API_HOST_NOT_ALLOWED$'):
                        provider.generate(PROMPT, {})
                build.return_value.open.assert_not_called()

    def test_each_provider_rejects_unsafe_subclass_and_mutated_urls(self):
        for provider_type in TYPES:
            host = provider_type.api_host
            other_host = (
                'api.anthropic.com' if provider_type is OpenAIProvider else 'api.openai.com'
            )
            for url in (
                f'http://{host}/',
                'https://example.invalid/',
                f'https://{other_host}/',
                f'https://user@{host}/',
                f'https://user:password@{host}/',
                f'https://{host}:8443/',
                f'https://{host}:invalid/',
                f'https://{host}:/',
            ):
                with self.subTest(provider=provider_type, url=url):
                    opener = Mock()
                    subclass = type('UnsafeProvider', (provider_type,), {'url': url})
                    with self.assertRaisesRegex(ValueError, '^API_HOST_NOT_ALLOWED$'):
                        subclass('synthetic', api_key=KEY, _opener=opener)
                    opener.open.assert_not_called()
                    provider = provider_type('synthetic', api_key=KEY, _opener=opener)
                    provider.url = url
                    with self.assertRaisesRegex(ProviderError, '^API_HOST_NOT_ALLOWED$'):
                        provider.generate(PROMPT, {})
                    opener.open.assert_not_called()


class ApiConfigurationTests(unittest.TestCase):
    def test_config_defaults_and_ignored_ollama_settings(self):
        for name, effort in (('openai', 'none'), ('anthropic', 'low')):
            settings = config(
                name,
                keep_alive='ignored',
                ctx_guard='ignored',
                num_ctx='ignored',
                base_url='ignored',
                temperature='1',
            )
            self.assertEqual(settings.reasoning_effort, effort)
            self.assertEqual(settings.api_key, KEY)
            self.assertFalse(settings.ctx_guard)
            self.assertIsNone(settings.num_ctx)
            self.assertIsNone(settings.keep_alive)
            self.assertNotIn(KEY, repr(settings))
            self.assertEqual(settings, replace(settings, api_key='another-SYNTHETIC-key'))

    def test_keys_and_reasoning_validation_in_config_and_constructors(self):
        for name, provider_type in (('openai', OpenAIProvider), ('anthropic', AnthropicProvider)):
            for key, code in (
                ('', 'MISSING_API_KEY'),
                (' ', 'INVALID_API_KEY'),
                (KEY + '\n', 'INVALID_API_KEY'),
                (' ' + KEY, 'INVALID_API_KEY'),
                (KEY + '\t', 'INVALID_API_KEY'),
                ('é', 'INVALID_API_KEY'),
                ('x' * 513, 'INVALID_API_KEY'),
            ):
                with self.subTest(provider=name, code=code):
                    with self.assertRaisesRegex(ValueError, '^' + code + '$') as caught:
                        load_config(
                            {'provider': name, 'model': 'synthetic'},
                            {API_KEY_ENV[name]: key},
                            None,
                            env_file_explicit=False,
                            _file_values={},
                        )
                    self.assertNotIn(KEY, repr(caught.exception))
                    with self.assertRaisesRegex(ValueError, '^' + code + '$'):
                        provider_type('synthetic', api_key=key)
            for effort in ('invalid', 'minimal', 'HIGH', ''):
                with self.assertRaisesRegex(ValueError, '^INVALID_REASONING_EFFORT$'):
                    provider_type('synthetic', api_key=KEY, reasoning_effort=effort)
            with self.assertRaisesRegex(ValueError, '^INVALID_REASONING_EFFORT$'):
                config(name, reasoning_effort='invalid')
            provider = provider_type('synthetic', api_key=KEY)
            self.assertNotIn(KEY, str(provider))
            self.assertNotIn(KEY, repr(provider))
        with self.assertRaisesRegex(ValueError, '^INVALID_REASONING_EFFORT$'):
            config('ollama', reasoning_effort='none')
        with self.assertRaisesRegex(ValueError, '^INVALID_REASONING_EFFORT$'):
            config('anthropic', reasoning_effort='none')

    def test_factory_keeps_ollama_arguments_and_api_fallback_identity(self):
        settings = config('ollama', keep_alive='1m', ctx_guard='true', max_output_tokens='17')
        provider = create_provider(settings)
        self.assertIsInstance(provider, OllamaProvider)
        self.assertEqual(provider.model, settings.model)
        self.assertEqual(provider.temperature, settings.temperature)
        self.assertEqual(provider.think, settings.think)
        self.assertEqual(provider.num_ctx, settings.num_ctx)
        self.assertEqual(provider.timeout_s, settings.timeout_s)
        self.assertEqual(provider.keep_alive, '1m')
        self.assertEqual(provider.num_predict, 17)
        self.assertTrue(provider.ctx_guard)
        for name, provider_type in (('openai', OpenAIProvider), ('anthropic', AnthropicProvider)):
            settings = config(name, fallback_model='synthetic-backup')
            provider = create_provider(settings)
            self.assertIsInstance(provider, provider_type)
            self.assertIsInstance(provider.fallback_provider, provider_type)
            self.assertEqual(provider.fallback_provider.model, 'synthetic-backup')
            self.assertEqual(provider.fallback_provider._api_key, KEY)
            self.assertEqual(provider.fallback_provider.reasoning_effort, settings.reasoning_effort)
            self.assertIs(provider.extraction_config, settings)
            self.assertFalse(provider.fallback_provider.extraction_config.fallback_model)

    def test_bench_empty_key_falls_back_to_injected_environment_and_file_precedence(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'synthetic.example'
            for name in ('openai', 'anthropic'):
                key = API_KEY_ENV[name]
                text = f'CVC_LLM_PROVIDER={name}\nCVC_LLM_MODEL=synthetic\n{key}='
                path.write_text(text + '\n', encoding='utf-8')
                settings = load_bench_config(path, {key: KEY})
                self.assertEqual(settings.api_key, KEY)
                path.write_text(text + KEY + '\n', encoding='utf-8')
                self.assertEqual(load_bench_config(path, {}).api_key, KEY)
                self.assertEqual(load_bench_config(path, {key: 'other-SYNTHETIC'}).api_key, KEY)
                settings = load_config({}, {key: 'other-SYNTHETIC'}, path, env_file_explicit=True)
                self.assertEqual(settings.api_key, 'other-SYNTHETIC')
                path.write_text(text + KEY + ' \n', encoding='utf-8')
                with self.assertRaisesRegex(ValueError, '^INVALID_API_KEY$'):
                    load_bench_config(path, {})

    def test_example_files_are_valid_bench_configs_with_environment_keys(self):
        root = Path(__file__).resolve().parents[1]
        for provider in ('openai', 'anthropic'):
            settings = load_bench_config(
                root / f'.env.{provider}.example', {API_KEY_ENV[provider]: KEY}
            )
            self.assertEqual(settings.provider, provider)
            self.assertEqual(settings.api_key, KEY)

    def test_api_providers_are_rejected_by_pull_normalize_check_and_bench_norm(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'synthetic.example'
            for provider in ('openai', 'anthropic'):
                path.write_text(
                    f'CVC_LLM_PROVIDER={provider}\nCVC_LLM_MODEL=synthetic\n', encoding='utf-8'
                )
                for loader in (load_pull_config, load_norm_config):
                    with self.assertRaisesRegex(PullConfigError, 'INVALID_PROVIDER$'):
                        loader({}, path, env_file_explicit=True)
                for command in ('pull', 'normalize', 'check', 'bench-norm'):
                    args = (
                        [command, '--gold', 'synthetic-unused', '--config', f'api={path}']
                        if command == 'bench-norm'
                        else [command, '--env-file', str(path)]
                    )
                    stdout, stderr = io.StringIO(), io.StringIO()
                    with (
                        patch.dict(os.environ, {}, clear=True),
                        redirect_stdout(stdout),
                        redirect_stderr(stderr),
                    ):
                        self.assertEqual(main(args), 2)
                    self.assertIn('INVALID_PROVIDER', stdout.getvalue())
                    self.assertEqual(stderr.getvalue(), '')

    def test_cli_diagnostics_and_logs_do_not_expose_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / 'synthetic.example'
            source = root / 'input'
            source.mkdir()
            repository = root / 'synthetic-repository'
            repository.mkdir()
            (source / 'synthetic.json').write_text(
                json.dumps(
                    {'pages': [{'page_number': 1, 'status': 'ok', 'raw_text': 'Synthetic Person'}]}
                ),
                encoding='utf-8',
            )
            for name in ('openai', 'anthropic'):
                path.write_text(
                    f'CVC_LLM_PROVIDER={name}\nCVC_LLM_MODEL=synthetic\n', encoding='utf-8'
                )
                for key in ('', KEY + '\n', KEY):
                    stdout, stderr = io.StringIO(), io.StringIO()
                    opener = Opener()
                    if key == KEY:
                        error, _ = http_error(400)
                        self.addCleanup(error.close)
                        opener.responses.append(error)
                    target = root / ('output-' + name)

                    with (
                        patch.dict(os.environ, {API_KEY_ENV[name]: key}, clear=True),
                        patch(
                            'cv_compass_llm.providers.openai_api.build_opener', return_value=opener
                        ),
                        # Verification snapshots can omit .git; keep output safety deterministic.
                        patch('cv_compass_llm.batch_io.find_repository', return_value=repository),
                        redirect_stdout(stdout),
                        redirect_stderr(stderr),
                        self.assertNoLogs(),
                    ):
                        self.assertEqual(
                            main(
                                [
                                    'run',
                                    '--env-file',
                                    str(path),
                                    '--input',
                                    str(source),
                                    '--output',
                                    str(target),
                                    '--max-blocks',
                                    '0',
                                ]
                            ),
                            0 if key == KEY else 2,
                        )
                    output = stdout.getvalue() + stderr.getvalue()
                    self.assertNotIn(KEY, output)
                    self.assertNotIn('INTERNAL_ERROR', output)
                    if key == '':
                        self.assertIn('MISSING_API_KEY', output)
                    elif key != KEY:
                        self.assertIn('INVALID_API_KEY', output)
                    else:
                        self.assertEqual(len(opener.requests), 1)
                        self.assertTrue((target / 'synthetic.result.json').is_file())
                        self.assertTrue((target / 'run-summary.json').is_file())
                        self.assertIn('processed=1', output)


if __name__ == '__main__':
    unittest.main()
