"""Synthetic configuration precedence, validation and diagnostic privacy tests."""

import io
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from dataclasses import FrozenInstanceError
from pathlib import Path
from unittest.mock import patch

from cv_compass_llm.cli import main
from cv_compass_llm.config import ENV_KEYS, default_env_file, load_config


class ConfigTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.path = self.root / 'synthetic-private.env'
        self.required = {'provider': 'ollama', 'model': 'synthetic-model'}

    def load(self, cli=None, environ=None, *, explicit=False):
        """Read only the temporary synthetic file, never the developer's dotenv."""
        return load_config(
            self.required if cli is None else cli,
            {} if environ is None else environ,
            self.path,
            env_file_explicit=explicit,
        )

    def test_defaults_and_frozen_result(self) -> None:
        config = self.load()
        self.assertEqual(config.provider, 'ollama')
        self.assertEqual(config.model, 'synthetic-model')
        self.assertEqual(config.base_url, 'http://127.0.0.1:11434')
        self.assertEqual(config.timeout_s, 120)
        self.assertEqual(config.max_blocks, 20)
        self.assertEqual(config.temperature, 0)
        self.assertIs(config.think, False)
        self.assertEqual(config.num_ctx, 8192)
        self.assertEqual(config.mode, 'blocks')
        self.assertEqual(config.whole_timeout, 900)
        self.assertTrue(config.prompt_schema_text)
        self.assertTrue(config.whole_personal)
        self.assertIsNone(config.max_output_tokens)
        self.assertIsNone(config.keep_alive)
        self.assertEqual(config.ctx_chars_per_token, 2.5)
        self.assertFalse(config.ctx_fine_tiers)
        with self.assertRaises(FrozenInstanceError):
            config.model = 'synthetic-other'

    def test_precedence_per_key(self) -> None:
        """Resolve every setting independently rather than selecting one source."""
        for key, file_value, env_value, cli_value, attribute, converted in (
            ('model', 'synthetic-file', 'synthetic-env', 'synthetic-cli', 'model', str),
            ('base_url', 'http://localhost', 'http://[::1]', 'http://127.0.0.1', 'base_url', str),
            ('timeout', '10', '20', '30', 'timeout_s', float),
            ('max_blocks', '1', '2', '3', 'max_blocks', int),
            ('temperature', '0.5', '1', '2', 'temperature', float),
            ('think', 'false', 'true', 'false', 'think', lambda x: x == 'true'),
            ('num_ctx', '1024', '2048', '4096', 'num_ctx', int),
            ('mode', 'whole', 'blocks', 'whole', 'mode', str),
            ('whole_timeout', '600', '800', '1000', 'whole_timeout', float),
            (
                'prompt_schema_text',
                'false',
                'true',
                'false',
                'prompt_schema_text',
                lambda x: x == 'true',
            ),
            ('whole_personal', 'false', 'true', 'false', 'whole_personal', lambda x: x == 'true'),
            ('max_output_tokens', '128', '256', '512', 'max_output_tokens', int),
            ('keep_alive', '1m', '2h', '-1', 'keep_alive', str),
            ('ctx_chars_per_token', '0.5', '2.5', '10', 'ctx_chars_per_token', float),
            ('ctx_fine_tiers', 'true', 'false', 'true', 'ctx_fine_tiers', lambda x: x == 'true'),
        ):
            with self.subTest(key=key):
                self.path.write_text(f'{ENV_KEYS[key]}={file_value}\n', encoding='utf-8')
                base = {k: v for k, v in self.required.items() if k != key}
                environment = {ENV_KEYS[key]: env_value}
                self.assertEqual(getattr(self.load(base), attribute), converted(file_value))
                self.assertEqual(
                    getattr(self.load(base, environment), attribute), converted(env_value)
                )
                self.assertEqual(
                    getattr(self.load(dict(base, **{key: cli_value}), environment), attribute),
                    converted(cli_value),
                )
        self.path.write_text('CVC_LLM_PROVIDER=invalid-file\n', encoding='utf-8')
        self.assertEqual(
            self.load({'model': 'synthetic-model'}, {'CVC_LLM_PROVIDER': 'ollama'}).provider,
            'ollama',
        )
        self.assertEqual(self.load(environ={'CVC_LLM_PROVIDER': 'invalid-env'}).provider, 'ollama')

    def test_empty_values_fall_through_per_key(self) -> None:
        """Treat whitespace as unset at every priority, including quoted dotenv."""
        self.path.write_text(
            'CVC_LLM_PROVIDER=ollama\nCVC_LLM_MODEL=synthetic-file\n'
            'CVC_LLM_TIMEOUT_S=17\nCVC_LLM_NUM_CTX="  "\n',
            encoding='utf-8',
        )
        for empty in ('', ' \t '):
            config = self.load(
                {key: empty for key in ENV_KEYS},
                {key: empty for key in ENV_KEYS.values()},
            )
            self.assertEqual(config.model, 'synthetic-file')
            self.assertEqual(config.timeout_s, 17)
            self.assertEqual(config.max_blocks, 20)
            self.assertEqual(config.num_ctx, 8192)

    def test_missing_required_settings(self) -> None:
        for cli in (
            {},
            {'provider': 'ollama'},
            {'model': 'synthetic-model'},
            {'provider': ' ', 'model': 'synthetic-model'},
            {'provider': 'ollama', 'model': ' '},
        ):
            with self.subTest(cli=cli):
                with self.assertRaisesRegex(ValueError, '^MISSING_CONFIG$'):
                    self.load(cli)

    def test_invalid_values(self) -> None:
        for key, values, code in (
            ('provider', ('synthetic-provider', 'OLLAMA'), 'INVALID_PROVIDER'),
            ('timeout', ('invalid', '0', '-1', 'nan', 'inf', '-inf'), 'INVALID_TIMEOUT'),
            ('max_blocks', ('invalid', '-1', '1.5', 'true'), 'INVALID_MAX_BLOCKS'),
            ('temperature', ('invalid', '-0.1', '2.1', 'nan', 'inf'), 'INVALID_TEMPERATURE'),
            ('think', ('invalid', '1', '0', 'yes'), 'INVALID_THINK'),
            ('num_ctx', ('invalid', '0', '-1', '1048577', '1.5', 'true'), 'INVALID_NUM_CTX'),
            ('mode', ('invalid', 'WHOLE', 'block'), 'INVALID_MODE'),
            ('prompt_schema_text', ('private-value', '0', '1'), 'INVALID_PROMPT_SCHEMA_TEXT'),
            ('whole_personal', ('private-value', '0', '1'), 'INVALID_WHOLE_PERSONAL'),
            (
                'max_output_tokens',
                ('private-value', '0', '-1', '1.5', 'true'),
                'INVALID_MAX_OUTPUT_TOKENS',
            ),
            (
                'keep_alive',
                ('private-value', '-2', '1d', '1.5s', '1 m', '1000000ms'),
                'INVALID_KEEP_ALIVE',
            ),
            (
                'ctx_chars_per_token',
                ('private-value', '0.49', '10.01', 'nan', 'inf'),
                'INVALID_CTX_CHARS_PER_TOKEN',
            ),
            ('ctx_fine_tiers', ('private-value', '0', '1'), 'INVALID_CTX_FINE_TIERS'),
            (
                'whole_timeout',
                ('invalid', '0', '-1', 'nan', 'inf', '-inf'),
                'INVALID_WHOLE_TIMEOUT',
            ),
        ):
            for value in values:
                for source in ('cli', 'environment', 'file'):
                    with self.subTest(key=key, value=value, source=source):
                        cli, environment = dict(self.required), {}
                        self.path.write_text('', encoding='utf-8')
                        if source == 'cli':
                            cli[key] = value
                        elif source == 'environment':
                            cli.pop(key, None)
                            environment[ENV_KEYS[key]] = value
                        else:
                            cli.pop(key, None)
                            self.path.write_text(f'{ENV_KEYS[key]}={value}', encoding='utf-8')
                        with self.assertRaisesRegex(ValueError, '^' + code + '$'):
                            self.load(cli, environment)

    def test_inclusive_boundaries_and_case_insensitive_boolean(self) -> None:
        for temperature in ('0', '2'):
            for num_ctx in ('1', '1048576'):
                config = self.load(
                    dict(
                        self.required,
                        timeout='0.01',
                        max_blocks='0',
                        temperature=temperature,
                        num_ctx=num_ctx,
                        think='TrUe',
                    )
                )
                self.assertEqual(config.temperature, float(temperature))
                self.assertEqual(config.num_ctx, int(num_ctx))
                self.assertEqual(config.max_blocks, 0)
                self.assertEqual(config.timeout_s, 0.01)
                self.assertIs(config.think, True)
        self.assertIs(self.load(dict(self.required, think='FaLsE')).think, False)

    def test_keep_alive_digit_boundaries(self) -> None:
        for suffix in ('', 'ms', 's', 'm', 'h'):
            for digits in ('0', '1', '999999', '000000'):
                value = digits + suffix
                with self.subTest(value=value):
                    self.assertEqual(
                        self.load(dict(self.required, keep_alive=value)).keep_alive, value
                    )
            for digits in ('1000000', '0000000'):
                with self.subTest(value=digits + suffix):
                    with self.assertRaisesRegex(ValueError, '^INVALID_KEEP_ALIVE$'):
                        self.load(dict(self.required, keep_alive=digits + suffix))

    def test_default_path_is_package_relative(self) -> None:
        package = self.root / 'llm-worker' / 'src' / 'cv_compass_llm' / 'config.py'
        with patch('cv_compass_llm.config.__file__', str(package)):
            self.assertEqual(default_env_file(), (self.root / 'llm-worker' / '.env').resolve())
        with patch('cv_compass_llm.config.default_env_file', return_value=self.path):
            self.assertEqual(
                load_config(self.required, {}, None, env_file_explicit=False).model,
                'synthetic-model',
            )
            with self.assertRaisesRegex(ValueError, '^ENV_FILE_NOT_FOUND$'):
                load_config(self.required, {}, None, env_file_explicit=True)
        with self.assertRaisesRegex(ValueError, '^ENV_FILE_NOT_FOUND$'):
            self.load(explicit=True)

    def test_dotenv_syntax_and_environment_unchanged(self) -> None:
        self.path.write_text(
            '# Cấu hình tổng hợp\n\n CVC_LLM_PROVIDER = "ollama" \n'
            "CVC_LLM_MODEL = ' synthetic-model '\nCVC_LLM_THINK=TRUE\n"
            'UNRELATED_KEY=ignored\nCVC_LLM_TIMEOUT_S=4\n',
            encoding='utf-8',
        )
        environment = {'UNRELATED': 'synthetic-environment'}
        before = dict(os.environ)
        config = self.load({}, environment, explicit=True)
        self.assertEqual(config.model, 'synthetic-model')
        self.assertEqual(config.timeout_s, 4)
        self.assertIs(config.think, True)
        self.assertEqual(environment, {'UNRELATED': 'synthetic-environment'})
        self.assertEqual(dict(os.environ), before)

    def test_invalid_dotenv_is_rejected_even_if_cli_overrides_it(self) -> None:
        for data in (
            b'not-an-assignment',
            b'=value',
            b'BAD KEY=value',
            b'\xff',
            b'CVC_LLM_UNKNOWN=synthetic-private-value',
            b'CVC_LLM_TIMEOUT_S=3\nCVC_LLM_TIMEOUT_S=4',
            b'CVC_LLM_MODEL="unclosed',
            b'CVC_LLM_MODEL=\'mismatch"',
            b'#' * (65536 + 1),
        ):
            with self.subTest(data=data[:40]):
                self.path.write_bytes(data)
                with self.assertRaisesRegex(ValueError, '^INVALID_ENV_FILE$'):
                    self.load(explicit=True)
        self.path.write_bytes(b'#' * 65536)
        self.assertEqual(self.load().model, 'synthetic-model')
        with patch.object(Path, 'open', side_effect=PermissionError('synthetic-private')):
            with self.assertRaisesRegex(ValueError, '^INVALID_ENV_FILE$'):
                self.load()

    def test_invalid_cli_think_keeps_argparse_diagnostics_private(self) -> None:
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            with self.assertRaises(SystemExit) as caught:
                main(
                    [
                        'run',
                        '--input',
                        'synthetic-private-input',
                        '--output',
                        'synthetic-private-output',
                        '--think',
                        'synthetic-private-value',
                    ]
                )
        self.assertEqual(caught.exception.code, 2)
        self.assertEqual(out.getvalue() + err.getvalue(), 'INVALID_ARGUMENTS\n')

    def test_main_errors_are_content_free(self) -> None:
        argv = [
            'run',
            '--input',
            'synthetic-private-input',
            '--output',
            'synthetic-private-output',
            '--env-file',
            str(self.path),
        ]
        cases = [
            (None, [], 'ENV_FILE_NOT_FOUND'),
            (b'CVC_LLM_UNKNOWN=synthetic-private-value', [], 'INVALID_ENV_FILE'),
            (b'\xff', [], 'INVALID_ENV_FILE'),
            (b'CVC_LLM_PROVIDER=ollama', [], 'MISSING_CONFIG'),
        ]
        for flag, code in (
            ('--provider', 'INVALID_PROVIDER'),
            ('--timeout', 'INVALID_TIMEOUT'),
            ('--max-blocks', 'INVALID_MAX_BLOCKS'),
            ('--temperature', 'INVALID_TEMPERATURE'),
            ('--mode', 'INVALID_MODE'),
            ('--whole-timeout', 'INVALID_WHOLE_TIMEOUT'),
            ('--num-ctx', 'INVALID_NUM_CTX'),
            ('--base-url', 'OLLAMA_HOST_NOT_LOCAL'),
        ):
            cases.append(
                (
                    b'CVC_LLM_PROVIDER=ollama\nCVC_LLM_MODEL=synthetic-model',
                    [flag, 'synthetic-private-value'],
                    code,
                )
            )
        cases.append(
            (
                b'CVC_LLM_PROVIDER=ollama\nCVC_LLM_MODEL=synthetic-model\n'
                b'CVC_LLM_THINK=synthetic-private-value',
                [],
                'INVALID_THINK',
            )
        )
        for data, flags, expected in cases:
            with self.subTest(expected=expected):
                if data is not None:
                    self.path.write_bytes(data)
                out, err = io.StringIO(), io.StringIO()
                environment = {
                    key: value
                    for key, value in os.environ.items()
                    if not key.startswith('CVC_LLM_')
                }
                with (
                    patch.dict(os.environ, environment, clear=True),
                    redirect_stdout(out),
                    redirect_stderr(err),
                ):
                    result = main(argv + flags)
                self.assertEqual(result, 2)
                text = out.getvalue() + err.getvalue()
                self.assertEqual(text, expected + '\n')
                for marker in (
                    str(self.path),
                    'synthetic-private-value',
                    'synthetic-private-input',
                    'synthetic-private-output',
                    'CVC_LLM_UNKNOWN',
                ):
                    self.assertNotIn(marker, text)

    def test_main_wires_file_environment_and_cli_to_provider_and_batch(self) -> None:
        self.path.write_text(
            'CVC_LLM_PROVIDER=ollama\nCVC_LLM_MODEL=synthetic-model\n'
            'CVC_LLM_BASE_URL=http://localhost:11434\nCVC_LLM_TIMEOUT_S=19\n'
            'CVC_LLM_MAX_BLOCKS=3\nCVC_LLM_TEMPERATURE=1.25\n'
            'CVC_LLM_THINK=true\nCVC_LLM_NUM_CTX=2048\n',
            encoding='utf-8',
        )
        out, err = io.StringIO(), io.StringIO()
        environment = {
            key: value for key, value in os.environ.items() if not key.startswith('CVC_LLM_')
        }
        environment['CVC_LLM_NUM_CTX'] = '4096'
        with (
            patch.dict(os.environ, environment, clear=True),
            patch('cv_compass_llm.cli.OllamaProvider') as provider,
            patch(
                'cv_compass_llm.cli.run_batch', return_value={'files': 0, 'ok': 0, 'errors': {}}
            ) as batch,
            redirect_stdout(out),
            redirect_stderr(err),
        ):
            result = main(
                [
                    'run',
                    '--input',
                    'synthetic-input',
                    '--output',
                    'synthetic-output',
                    '--env-file',
                    str(self.path),
                    '--max-blocks',
                    '0',
                    '--limit',
                    '2',
                ]
            )
        self.assertEqual(result, 0)
        self.assertEqual(out.getvalue() + err.getvalue(), 'processed=0 ok=0 errors=0\n')
        provider.assert_called_once_with(
            'synthetic-model',
            base_url='http://localhost:11434',
            timeout_s=19.0,
            temperature=1.25,
            think=True,
            num_ctx=4096,
        )
        batch.assert_called_once_with(
            'synthetic-input',
            'synthetic-output',
            provider.return_value,
            max_blocks=0,
            limit=2,
            mode='blocks',
            whole_timeout=900,
        )
