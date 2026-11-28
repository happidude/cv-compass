"""E13 API consent, readiness and normalization using synthetic offline transports."""

import copy
import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from dataclasses import replace
from pathlib import Path
from unittest.mock import Mock, patch
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler

import test_l7_api_providers as api
from test_normalize import ITEMS, RESULTS, TAXONOMY, response
from test_pull import TOKEN, config

from cv_compass_llm.backend_client import _NoRedirect
from cv_compass_llm.check import _get, run_check
from cv_compass_llm.cli import _create_ollama_provider, _require_ollama_bench, create_provider, main
from cv_compass_llm.config import (
    API_KEY_ENV,
    BENCH_ENV_KEYS,
    ENV_KEYS,
    load_norm_config,
    load_pull_config,
)
from cv_compass_llm.norm_bench import _configurations
from cv_compass_llm.norm_prompts import SCHEMA, build_prompt
from cv_compass_llm.normalize import normalize_batch, run_normalize
from cv_compass_llm.providers.fake import FakeProvider
from cv_compass_llm.providers.ollama import MAX_RESPONSE_BYTES
from cv_compass_llm.providers.openai_api import OpenAIProvider
from cv_compass_llm.pull import run_pull
from cv_compass_llm.schema import (
    EXPERIENCE_SCHEMA,
    LINES_EXPERIENCE_SCHEMA,
    LINES_PERSONAL_SCHEMA,
    PERSONAL_SCHEMA,
    WHOLE_PERSONAL_SCHEMA,
    WHOLE_SCHEMA,
)

KEY = 'sk-test-E13-SYNTHETIC'
BODY = b'PRIVATE_SYNTHETIC_RESPONSE'
BACKEND_BODY = {'code': 'NOT_FOUND', 'message': '', 'correlationId': None, 'details': []}


def environment(provider, allow=None):
    values = {
        'CVC_LLM_PROVIDER': provider,
        'CVC_LLM_MODEL': 'synthetic-model',
        'CVC_LLM_BACKEND_URL': 'http://127.0.0.1:8000',
        'CVC_LLM_WORKER_TOKEN': TOKEN,
        API_KEY_ENV[provider]: KEY,
    }
    if allow is not None:
        values['CVC_LLM_ALLOW_API'] = allow
    return values


class AllowApiTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.env_file = Path(self.temp.name) / 'synthetic.env'
        self.env_file.write_text('', encoding='utf-8')

    def load(self, values, *, norm=False, cli=None):
        loader = load_norm_config if norm else load_pull_config
        return loader(values, self.env_file, env_file_explicit=True, cli=cli)

    def test_all_commands_reject_api_without_consent_and_reject_invalid_flag(self):
        for provider in API_KEY_ENV:
            for allow, code in (
                (None, 'INVALID_PROVIDER'),
                ('false', 'INVALID_PROVIDER'),
                ('yes', 'INVALID_ALLOW_API'),
            ):
                for command in ('pull', 'normalize', 'check', 'bench-norm'):
                    with self.subTest(provider=provider, allow=allow, command=command):
                        args = [command]
                        if command == 'bench-norm':
                            args += ['--gold', 'unused-synthetic-directory']
                        else:
                            args += ['--env-file', str(self.env_file)]
                        out = io.StringIO()
                        with (
                            patch.dict(os.environ, environment(provider, allow), clear=True),
                            redirect_stdout(out),
                            patch('urllib.request.OpenerDirector.open') as network,
                        ):
                            self.assertEqual(main(args), 2)
                        self.assertIn(code, out.getvalue())
                        self.assertNotIn(KEY, out.getvalue())
                        network.assert_not_called()

    def test_enabled_config_precedence_and_dotenv_keys(self):
        self.assertEqual(ENV_KEYS['allow_api'], 'CVC_LLM_ALLOW_API')
        self.assertIn('CVC_LLM_ALLOW_API', BENCH_ENV_KEYS)
        for provider in API_KEY_ENV:
            for norm in (False, True):
                values = environment(provider, 'TrUe')
                loaded = self.load(values, norm=norm)
                llm = loaded.transport.llm if norm else loaded.llm
                self.assertEqual(llm.provider, provider)
                self.assertTrue(llm.allow_api)
                self.assertEqual(llm.api_key, KEY)
                with self.assertRaisesRegex(ValueError, 'CVC_LLM_PROVIDER INVALID_PROVIDER'):
                    self.load(values, norm=norm, cli={'allow_api': 'false'})
                with self.assertRaisesRegex(ValueError, 'CVC_LLM_ALLOW_API INVALID_ALLOW_API'):
                    self.load(values, norm=norm, cli={'allow_api': 'invalid'})
            self.env_file.write_text(
                f'CVC_LLM_ALLOW_API=true\n{API_KEY_ENV[provider]}={KEY}\n', encoding='utf-8'
            )
            values = environment(provider)
            del values[API_KEY_ENV[provider]]
            self.assertEqual(self.load(values).llm.api_key, KEY)
            self.assertEqual(self.load(values, norm=True).transport.llm.api_key, KEY)
            with self.assertRaisesRegex(ValueError, 'INVALID_PROVIDER'):
                self.load(values | {'CVC_LLM_ALLOW_API': 'false'})
            self.assertTrue(self.load(values, cli={'allow_api': 'TRUE'}).llm.allow_api)
            self.env_file.write_text('', encoding='utf-8')

    def test_enabled_cli_calls_and_pull_factory_error_is_sanitized(self):
        for provider in API_KEY_ENV:
            with patch.dict(os.environ, environment(provider, 'true'), clear=True):
                for command, target in (('pull', 'run_pull'), ('normalize', 'run_normalize')):
                    with patch('cv_compass_llm.cli.' + target, return_value=0) as run:
                        self.assertEqual(main([command, '--env-file', str(self.env_file)]), 0)
                    supplied = run.call_args.args[1]
                    self.assertEqual(supplied.provider, provider)
                    self.assertEqual(
                        supplied.max_output_tokens, 2000 if command == 'normalize' else 4000
                    )
                out = io.StringIO()
                with (
                    patch('cv_compass_llm.cli.create_provider', side_effect=ValueError(KEY)),
                    redirect_stdout(out),
                ):
                    self.assertEqual(main(['pull', '--env-file', str(self.env_file)]), 2)
                self.assertEqual(out.getvalue(), 'CVC_LLM_PROVIDER INVALID_CONFIG\n')
                self.assertNotIn(KEY, out.getvalue())

    def test_bench_snapshot_and_environment_consent_and_factory(self):
        for provider in API_KEY_ENV:
            for snapshot in (False, True):
                values = environment(provider, 'true')
                specifications = None
                if snapshot:
                    keys = (
                        'CVC_LLM_PROVIDER',
                        'CVC_LLM_MODEL',
                        'CVC_LLM_ALLOW_API',
                        API_KEY_ENV[provider],
                    )
                    self.env_file.write_text(
                        '\n'.join(f'{key}={values[key]}' for key in keys), encoding='utf-8'
                    )
                    values = {}
                    specifications = ['synthetic=' + str(self.env_file)]
                with patch.dict(os.environ, values, clear=True):
                    _require_ollama_bench(specifications)
                    llm = _configurations(specifications, values)[0][1]
                    instance = _create_ollama_provider(llm)
                    self.assertEqual(instance.provider, provider)
                    self.assertEqual(instance.max_output_tokens, 2000)
                    limited = _create_ollama_provider(replace(llm, max_output_tokens=37))
                    self.assertEqual(limited.max_output_tokens, 37)
                    with self.assertRaisesRegex(ValueError, '^INVALID_PROVIDER$'):
                        _create_ollama_provider(replace(llm, allow_api=False))
                    args = ['bench-norm', '--gold', 'unused-synthetic-directory']
                    if specifications:
                        args += ['--config', specifications[0]]
                    with patch(
                        'cv_compass_llm.cli.run_norm_bench', return_value={'configurations': []}
                    ) as run:
                        self.assertEqual(main(args), 0)
                    self.assertIs(run.call_args.kwargs['provider_factory'], _create_ollama_provider)

    def test_check_api_one_get_headers_status_limits_and_privacy(self):
        for provider in API_KEY_ENV:
            http_error, error_body = api.http_error(401)
            self.addCleanup(error_body.close)
            for outcome, code in (
                (200, None),
                (401, 'API_HTTP_401'),
                (http_error, 'API_HTTP_401'),
                (302, 'API_HTTP_302'),
                (URLError(KEY), 'API_UNREACHABLE'),
                (TimeoutError(KEY), 'API_UNREACHABLE'),
                (b'x' * (MAX_RESPONSE_BYTES + 1), 'API_RESPONSE_TOO_LARGE'),
            ):
                reply = (
                    outcome
                    if isinstance(outcome, Exception)
                    else api.Response(
                        outcome if isinstance(outcome, bytes) else BODY,
                        outcome if type(outcome) is int else 200,
                    )
                )
                opener = api.Opener(api.Response(BACKEND_BODY, 404), reply)
                out = io.StringIO()
                with redirect_stdout(out), patch('cv_compass_llm.check.OllamaProvider') as ollama:
                    result = run_check(
                        environment(provider, 'true'),
                        self.env_file,
                        env_file_explicit=True,
                        _opener=opener,
                    )
                ollama.assert_not_called()
                self.assertEqual(result, 2 if code else 0)
                text = out.getvalue()
                self.assertIn('OK config\n', text)
                self.assertIn('SKIP model\n', text)
                self.assertIn(
                    f'FAIL api {provider} code={code}\n' if code else f'OK api {provider}\n', text
                )
                self.assertNotIn(KEY, text)
                self.assertNotIn(api.KEY, text)
                self.assertNotIn(BODY.decode(), text)
                self.assertNotIn('Synthetic error', text)
                self.assertEqual(len(opener.requests), 2)
                request = opener.requests[1][0]
                self.assertEqual(request.full_url, f'https://api.{provider}.com/v1/models')
                self.assertEqual(request.get_method(), 'GET')
                self.assertIsNone(request.data)
                headers = {key.lower(): value for key, value in request.header_items()}
                if provider == 'openai':
                    self.assertEqual(headers['authorization'], 'Bearer ' + KEY)
                else:
                    self.assertEqual(headers['x-api-key'], KEY)
                    self.assertEqual(headers['anthropic-version'], '2023-06-01')
                if isinstance(reply, api.Response):
                    self.assertEqual(reply.read_sizes, [MAX_RESPONSE_BYTES + 1])
            self.assertTrue(error_body.closed)

    def test_check_api_exception_reasons_and_bodies_are_not_printed(self):
        for provider in API_KEY_ENV:
            body = io.BytesIO(BODY + KEY.encode())
            self.addCleanup(body.close)
            for error, code, reason in (
                (URLError('Connection refused: ' + KEY), 'API_UNREACHABLE', 'Connection refused'),
                (
                    HTTPError(
                        f'https://api.{provider}.com/v1/models',
                        401,
                        'Unauthorized: ' + KEY,
                        {},
                        body,
                    ),
                    'API_HTTP_401',
                    'Unauthorized',
                ),
            ):
                opener = api.Opener(api.Response(BACKEND_BODY, 404), error)
                out = io.StringIO()
                with redirect_stdout(out):
                    self.assertEqual(
                        run_check(
                            environment(provider, 'true'),
                            self.env_file,
                            env_file_explicit=True,
                            _opener=opener,
                        ),
                        2,
                    )
                self.assertIn(f'FAIL api {provider} code={code}\n', out.getvalue())
                for private in (KEY, BODY.decode(), reason):
                    self.assertNotIn(private, out.getvalue())
                self.assertEqual(len(opener.requests), 2)
            self.assertTrue(body.closed)

    def test_bench_snapshot_false_overrides_environment_true(self):
        for provider in API_KEY_ENV:
            self.env_file.write_text(
                f'CVC_LLM_PROVIDER={provider}\nCVC_LLM_ALLOW_API=false\n', encoding='utf-8'
            )
            specifications = ['synthetic=' + str(self.env_file)]
            out = io.StringIO()
            with (
                patch.dict(os.environ, environment(provider, 'true'), clear=True),
                redirect_stdout(out),
                patch('cv_compass_llm.cli.run_norm_bench') as run,
            ):
                with self.assertRaisesRegex(ValueError, '^INVALID_PROVIDER$'):
                    _require_ollama_bench(specifications)
                llm = _configurations(specifications, os.environ)[0][1]
                self.assertFalse(llm.allow_api)
                with self.assertRaisesRegex(ValueError, '^INVALID_PROVIDER$'):
                    _create_ollama_provider(llm)
                self.assertEqual(
                    main(
                        [
                            'bench-norm',
                            '--gold',
                            'unused-synthetic-directory',
                            '--config',
                            specifications[0],
                        ]
                    ),
                    2,
                )
            run.assert_not_called()
            self.assertEqual(out.getvalue(), 'INVALID_PROVIDER\n')
            self.assertNotIn(KEY, out.getvalue())

    def test_check_default_opener_disables_proxies_and_redirects(self):
        opener = api.Opener(api.Response(BODY, 302))
        with patch('cv_compass_llm.check.build_opener', return_value=opener) as build:
            self.assertEqual(_get('https://api.openai.com/v1/models', 1)[0], 302)
        handlers = build.call_args.args
        self.assertIsInstance(handlers[0], ProxyHandler)
        self.assertEqual(handlers[0].proxies, {})
        self.assertIsInstance(handlers[1], _NoRedirect)

    def test_normalization_payload_and_local_validation_remain_equivalent(self):
        original = copy.deepcopy(SCHEMA)
        for provider in API_KEY_ENV:
            llm = self.load(environment(provider, 'true'), norm=True).transport.llm
            results = copy.deepcopy(RESULTS)
            cases = [results, results + [results[0]]]
            duplicate = copy.deepcopy(results)
            duplicate[1]['title']['functions'] = ['SALES', 'SALES']
            cases.append(duplicate)
            too_many = copy.deepcopy(results)
            too_many[1]['title']['functions'] = ['SALES', 'HR', 'OTHER']
            cases.append(too_many)
            invalid_code = copy.deepcopy(results)
            invalid_code[0]['company']['industry'] = 'INVALID_SYNTHETIC_CODE'
            cases.append(invalid_code)
            invented_name = copy.deepcopy(results)
            invented_name[0]['company']['name'] = 'Absent Synthetic Name'
            cases.append(invented_name)
            for rows in cases:
                text = response(rows)
                opener = api.Opener(
                    api.Response(
                        api.envelope(
                            OpenAIProvider if provider == 'openai' else api.AnthropicProvider, text
                        )
                    )
                )
                with patch('cv_compass_llm.providers.openai_api.build_opener', return_value=opener):
                    instance = create_provider(llm, default_max_output_tokens=2000)
                actual = normalize_batch(ITEMS, TAXONOMY, instance, llm)
                fake = FakeProvider([text])
                self.assertEqual(
                    actual,
                    normalize_batch(ITEMS, TAXONOMY, fake, config('http://127.0.0.1').llm),
                )
                payload = json.loads(opener.requests[0][0].data)
                if provider == 'openai':
                    self.assertEqual(payload['input'], build_prompt(ITEMS, TAXONOMY))
                    self.assertEqual(payload['max_output_tokens'], 2000)
                    self.assert_strict_schema(payload['text']['format']['schema'])
                    self.assertIn(
                        'anyOf',
                        payload['text']['format']['schema']['properties']['results']['items'],
                    )
                else:
                    self.assertEqual(payload['max_tokens'], 2000)
                self.assertEqual(SCHEMA, original)

    def test_run_bench_extraction_schema_payload_is_unchanged(self):
        for schema in (
            PERSONAL_SCHEMA,
            WHOLE_PERSONAL_SCHEMA,
            EXPERIENCE_SCHEMA,
            WHOLE_SCHEMA,
            LINES_PERSONAL_SCHEMA,
            LINES_EXPERIENCE_SCHEMA,
        ):
            original = copy.deepcopy(schema)
            opener = api.Opener(api.Response(api.envelope(OpenAIProvider)))
            with patch('cv_compass_llm.providers.openai_api.build_opener', return_value=opener):
                provider = create_provider(api.config('openai'))
            self.assertEqual(provider.generate('Synthetic extraction prompt', schema), '{}')
            request = opener.requests[0][0]
            self.assertEqual(
                json.loads(request.data),
                {
                    'model': 'synthetic-model',
                    'input': 'Synthetic extraction prompt',
                    'text': {
                        'format': {
                            'type': 'json_schema',
                            'name': 'cv_extraction',
                            'schema': original,
                            'strict': True,
                        }
                    },
                    'reasoning': {'effort': 'none'},
                    'store': False,
                    'max_output_tokens': 4000,
                },
            )
            self.assertEqual(schema, original)

    def assert_strict_schema(self, schema):
        self.assertLessEqual(
            set(schema),
            {
                'type',
                'properties',
                'required',
                'additionalProperties',
                'items',
                'anyOf',
                'minItems',
                'maxItems',
            },
        )
        if schema.get('type') == 'object':
            self.assertIs(schema['additionalProperties'], False)
            self.assertEqual(set(schema['properties']), set(schema['required']))
            for child in schema['properties'].values():
                self.assert_strict_schema(child)
        for child in schema.get('anyOf', []):
            self.assert_strict_schema(child)
        if 'items' in schema:
            self.assert_strict_schema(schema['items'])

    def test_api_worker_start_does_not_expose_base_url_or_credentials(self):
        for provider in API_KEY_ENV:
            transport = self.load(environment(provider, 'true'))
            transport = replace(
                transport,
                llm=replace(transport.llm, base_url='https://PRIVATE.invalid/path', model=KEY),
            )
            for norm in (False, True):
                client = Mock()
                client.request.return_value = (204, None)
                client.claim_norm.return_value = None
                out, err = io.StringIO(), io.StringIO()
                module = 'normalize' if norm else 'pull'
                with (
                    patch(f'cv_compass_llm.{module}.BackendClient', return_value=client),
                    redirect_stdout(out),
                    redirect_stderr(err),
                ):
                    fake = FakeProvider({})
                    settings = (
                        self.load(environment(provider, 'true'), norm=True) if norm else transport
                    )
                    if norm:
                        settings = replace(settings, transport=transport)
                        result = run_normalize(settings, fake, once=True)
                    else:
                        result = run_pull(settings, fake, once=True)
                self.assertEqual(result, 0)
                log = out.getvalue() + err.getvalue()
                self.assertIn('event=worker_start', log)
                self.assertIn('provider=' + provider, log)
                for secret in (KEY, TOKEN, 'PRIVATE.invalid', 'base_url=', 'ollama='):
                    self.assertNotIn(secret, log)


if __name__ == '__main__':
    unittest.main()
