"""Context guard contracts using synthetic documents and mocked transports only."""

import hashlib
import io
import json
import re
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, Mock, patch
from urllib.error import HTTPError

import test_lifecycle
from test_core import document, experience, personal
from test_o4_quick_wins import settings
from test_pull import TOKEN, Backend, backend_server, config

from cv_compass_llm.bench import _aggregate, run_bench
from cv_compass_llm.cli import create_provider, parser, run_batch
from cv_compass_llm.config import (
    BENCH_ENV_KEYS,
    ENV_KEYS,
    _read_env,
    load_bench_config,
    load_config,
    load_pull_config,
)
from cv_compass_llm.pipeline import extract
from cv_compass_llm.providers import FakeProvider, OllamaProvider, ProviderError
from cv_compass_llm.pull import _ObservedProvider, run_pull
from cv_compass_llm.schema import EXPERIENCE_SCHEMA, PERSONAL_SCHEMA, WHOLE_SCHEMA, Issue

TEXT = 'Synthetic Person\nExperience\nRole-Z\nOrg-Z\n1/2020 - 2021-02'
HEADER_TEXT = 'Synthetic Person\nExperience\n'
OVERFLOW = 'OLLAMA_CONTEXT_OVERFLOW'
NAME = personal(fullName={'value': 'Synthetic Person', 'quote': 'Synthetic Person'})


class GuardTests(unittest.TestCase):
    def test_flag_off_locks_develop_calls_prompts_and_results(self):
        expectations = {
            'whole': (
                [(WHOLE_SCHEMA, 8192, 900), (PERSONAL_SCHEMA, None, None)],
                [
                    'cc39dcd097a7e120981acc39bc2568482e47f4cc02c7eb81440a1c842b8877bd',
                    '81e6e2accdcc63a04e09171ed37e9d550ffb666620ae0f49cb96510884f60f9e',
                ],
                'f143e8e7ebf6a668d75e5f9fa419dc77c9b33a1164785071c879469af8d3cbb6',
            ),
            'blocks': (
                [(PERSONAL_SCHEMA, None, None), (EXPERIENCE_SCHEMA, None, None)],
                [
                    '81e6e2accdcc63a04e09171ed37e9d550ffb666620ae0f49cb96510884f60f9e',
                    'c47c3a97b0cf8be7aada34423347946a634b2495fb4f839652ef0767c2ac577a',
                ],
                'a27a2a255004a54795ef34f343e6e57fd63043e9f4e8c3992d7617ee68f3d671',
            ),
        }
        for mode, (calls, hashes, result_hash) in expectations.items():
            for cfg in (None, settings(ctx_guard=False)):
                with self.subTest(mode=mode, config=cfg is not None):
                    responses = (
                        [dict(personal(), experiences=[experience()]), personal()]
                        if mode == 'whole'
                        else [personal(), {'experiences': [experience()]}]
                    )
                    provider = FakeProvider(responses)
                    result = extract(document(TEXT), provider, mode=mode, config=cfg)
                    self.assertEqual(provider.calls, calls)
                    self.assertEqual(
                        [hashlib.sha256(p.encode()).hexdigest() for p in provider.prompts], hashes
                    )
                    self.assertEqual(result.issues, (Issue('PERSONAL_EMPTY'),))
                    # A1 adds only this internal issue; retain the legacy data fingerprint.
                    self.assertEqual(
                        hashlib.sha256(
                            json.dumps(
                                replace(result, issues=()).to_dict(),
                                sort_keys=True,
                            ).encode()
                        ).hexdigest(),
                        result_hash,
                    )

    def test_each_overflow_condition_retries_each_unit_and_discards_values(self):
        for condition in ('error', 'tokens', 'length'):
            for unit in ('whole', 'header', 'block'):
                with self.subTest(condition=condition, unit=unit):
                    discarded = (
                        dict(NAME, experiences=[experience()])
                        if unit == 'whole'
                        else NAME
                        if unit == 'header'
                        else {'experiences': [experience()]}
                    )
                    final = personal() if unit == 'header' else {'experiences': []}
                    if unit == 'whole':
                        final = dict(personal(), experiences=[])
                    responses = [
                        ProviderError(OVERFLOW) if condition == 'error' else discarded,
                        final,
                    ]
                    metrics = [
                        {'prompt_tokens': 8000, 'output_tokens': 160}
                        if condition == 'tokens'
                        else {'done_reason': 'length'}
                        if condition == 'length'
                        else {},
                        {},
                    ]
                    if unit == 'whole':
                        responses.append(personal())
                        metrics.append({})
                    elif unit == 'header':
                        responses.append({'experiences': []})
                        metrics.append({})
                    else:
                        responses.insert(0, personal())
                        metrics.insert(0, {})
                    provider = FakeProvider(responses, metrics=metrics)
                    contexts, completed = [], []
                    result = extract(
                        document(TEXT),
                        provider,
                        mode='whole' if unit == 'whole' else 'blocks',
                        config=settings(ctx_guard=True, num_ctx=8192),
                        on_context=contexts.append,
                        on_block=lambda *args: completed.append(args),
                    )
                    first = 1 if unit == 'block' else 0
                    self.assertEqual(len(provider.calls), 3)
                    self.assertEqual(provider.calls[first][1], 8192 if unit == 'whole' else None)
                    self.assertEqual(provider.calls[first + 1][1], 16384)
                    self.assertEqual(provider.calls[first][0], provider.calls[first + 1][0])
                    self.assertEqual(provider.prompts[first], provider.prompts[first + 1])
                    self.assertEqual(provider.calls[first][2], provider.calls[first + 1][2])
                    self.assertEqual(result.fields, ())
                    self.assertEqual(result.experiences, ())
                    self.assertEqual(result.issues, (Issue('PERSONAL_EMPTY'),))
                    self.assertEqual(len(completed), 2)
                    self.assertTrue(all(row[2] >= 0 for row in completed))
                    telemetry = contexts[first]
                    self.assertEqual((telemetry['num_ctx'], telemetry['ctx_retry']), (16384, 1))
                    self.assertEqual(len(telemetry['discarded']), 1)
                    self.assertEqual(telemetry['discarded'][0]['num_ctx'], 8192)

    def test_discarded_invalid_json_never_produces_validation_or_truncation_issues(self):
        provider = FakeProvider(['not json', personal()], metrics=[{'done_reason': 'length'}, {}])
        result = extract(document(HEADER_TEXT), provider, config=settings(ctx_guard=True))
        self.assertEqual(result.issues, (Issue('PERSONAL_EMPTY'),))
        self.assertEqual(result.fields, ())

    def test_whole_uses_explicit_context_instead_of_configured_default(self):
        provider = FakeProvider(
            [
                dict(personal(), experiences=[experience()]),
                dict(personal(), experiences=[]),
                personal(),
            ],
            metrics=[{'prompt_tokens': 8000, 'output_tokens': 160}, {}, {}],
        )
        result = extract(
            document(TEXT), provider, mode='whole', config=settings(ctx_guard=True, num_ctx=32768)
        )
        self.assertEqual([call[1] for call in provider.calls], [8192, 16384, None])
        self.assertEqual(result.experiences, ())
        self.assertEqual(result.issues, (Issue('PERSONAL_EMPTY'),))

    def test_metrics_overflow_after_retry_discards_both_responses(self):
        for reason in ('tokens', 'length'):
            with self.subTest(reason=reason):
                metrics = (
                    [
                        {'prompt_tokens': 8160, 'output_tokens': 0},
                        {'prompt_tokens': 16352, 'output_tokens': 0},
                    ]
                    if reason == 'tokens'
                    else [{'done_reason': 'length'}] * 2
                )
                provider = FakeProvider([NAME, NAME], metrics=metrics)
                contexts = []
                result = extract(
                    document(HEADER_TEXT),
                    provider,
                    config=settings(ctx_guard=True, num_ctx=8192),
                    on_context=contexts.append,
                )
                self.assertEqual([call[1] for call in provider.calls], [None, 16384])
                self.assertEqual(result.fields, ())
                self.assertEqual(result.issues, (Issue('CONTEXT_OVERFLOW', 'personal'),))
                self.assertEqual(len(contexts[0]['discarded']), 2)

    def test_length_with_output_limit_keeps_existing_truncation_behavior(self):
        provider = FakeProvider([NAME], metrics={'done_reason': 'length'})
        result = extract(
            document(HEADER_TEXT),
            provider,
            config=settings(ctx_guard=True, max_output_tokens=100),
        )
        self.assertEqual(len(provider.calls), 1)
        self.assertEqual(result.issues, (Issue('OUTPUT_TRUNCATED', 'personal'),))
        self.assertEqual(result.fields[0].value, 'Synthetic Person')

    def test_flag_off_ignores_overflow_metrics_and_keeps_error_behavior(self):
        for cfg in (None, settings(ctx_guard=False)):
            provider = FakeProvider([NAME], metrics={'prompt_tokens': 99999, 'output_tokens': 1})
            result = extract(document(HEADER_TEXT), provider, config=cfg)
            self.assertEqual(len(provider.calls), 1)
            self.assertEqual(result.fields[0].value, 'Synthetic Person')
            provider = FakeProvider([ProviderError(OVERFLOW)])
            result = extract(document(HEADER_TEXT), provider, config=cfg)
            self.assertEqual(provider.calls, [(PERSONAL_SCHEMA, None, None)])
            self.assertEqual(result.issues, (Issue('PROVIDER_ERROR', 'personal'),))

    def test_effective_context_fine_tiers_boundaries_and_unknown_context(self):
        for size, fine, expected in (
            (8192, True, 12288),
            (12288, True, 16384),
            (10000, False, 16384),
            (16384, False, 24576),
            (24576, False, 32768),
            (None, False, 16384),
        ):
            with self.subTest(size=size, fine=fine):
                provider = FakeProvider([ProviderError(OVERFLOW), personal()])
                extract(
                    document(HEADER_TEXT),
                    provider,
                    config=settings(ctx_guard=True, num_ctx=size, ctx_fine_tiers=fine),
                )
                self.assertEqual([call[1] for call in provider.calls], [None, expected])
        for tokens, expected_calls in ((8159, 1), (8160, 2)):
            provider = FakeProvider(
                [personal(), personal()],
                metrics=[{'prompt_tokens': tokens, 'output_tokens': 0}, {}],
            )
            extract(document(HEADER_TEXT), provider, config=settings(ctx_guard=True, num_ctx=8192))
            self.assertEqual(len(provider.calls), expected_calls)
        provider = FakeProvider(NAME, metrics={'prompt_tokens': 99999, 'output_tokens': 99999})
        result = extract(
            document(HEADER_TEXT), provider, config=settings(ctx_guard=True, num_ctx=None)
        )
        self.assertEqual(len(provider.calls), 1)
        self.assertEqual(result.fields[0].value, 'Synthetic Person')

    def test_header_and_block_overflow_twice_or_without_higher_tier(self):
        for unit in ('header', 'block'):
            for size in (8192, 32768):
                with self.subTest(unit=unit, size=size):
                    count = 2 if size == 8192 else 1
                    responses = [ProviderError(OVERFLOW) for _ in range(count)]
                    if unit == 'header':
                        responses.append({'experiences': []})
                    else:
                        responses.insert(0, personal())
                    provider = FakeProvider(responses)
                    result = extract(
                        document(TEXT), provider, config=settings(ctx_guard=True, num_ctx=size)
                    )
                    self.assertEqual(len(provider.calls), count + 1)
                    self.assertIn(
                        Issue(
                            'CONTEXT_OVERFLOW', 'personal' if unit == 'header' else 'experiences'
                        ),
                        result.issues,
                    )
                    self.assertEqual(result.fields, ())
                    self.assertEqual(result.experiences, ())
                    self.assertNotIn('PROVIDER_ERROR', [i.code for i in result.issues])

    def test_whole_top_overflow_routes_to_blocks_with_or_without_retry(self):
        for prompt_size, sizes in ((65000, [32768]), (50000, [24576, 32768])):
            with self.subTest(prompt_size=prompt_size):
                completed = []
                provider = FakeProvider(
                    [ProviderError(OVERFLOW) for _ in sizes]
                    + [NAME, {'experiences': [experience()]}]
                )
                with patch(
                    'cv_compass_llm.pipeline.build_whole_prompt', return_value='x' * prompt_size
                ):
                    result = extract(
                        document(TEXT),
                        provider,
                        mode='whole',
                        config=settings(ctx_guard=True),
                        on_block=lambda *args: completed.append(args),
                    )
                self.assertEqual([call[1] for call in provider.calls], sizes + [None, None])
                self.assertEqual(result.issues, (Issue('WHOLE_TOO_LONG'),))
                self.assertEqual(len(result.experiences), 1)
                self.assertEqual(result.fields[0].value, 'Synthetic Person')
                self.assertEqual([call[0] for call in completed], [1, 2, 3])

    def test_unrelated_provider_error_is_not_retried(self):
        provider = FakeProvider([ProviderError('OLLAMA_TIMEOUT')])
        result = extract(document(HEADER_TEXT), provider, config=settings(ctx_guard=True))
        self.assertEqual(result.issues, (Issue('PROVIDER_ERROR', 'personal'),))
        self.assertEqual(len(provider.calls), 1)

    def test_http_error_is_not_retried_and_marks_observed_attempt_failure(self):
        for observe in (False, True):
            with self.subTest(observe=observe):
                provider = FakeProvider([ProviderError('OLLAMA_HTTP_500'), NAME])
                observed = _ObservedProvider(
                    provider, SimpleNamespace(lost=False, outcome_started=False), ctx_guard=True
                )
                result = extract(
                    document(HEADER_TEXT),
                    observed if observe else provider,
                    config=settings(ctx_guard=True),
                )
                self.assertEqual(result.issues, (Issue('PROVIDER_ERROR', 'personal'),))
                self.assertEqual(provider.calls, [(PERSONAL_SCHEMA, None, None)])
                self.assertNotIn('CONTEXT_OVERFLOW', [issue.code for issue in result.issues])
                if observe:
                    self.assertEqual(observed.failure, 'PROVIDER_ERROR')

    def test_unit_seconds_include_discarded_call_and_metrics_are_final(self):
        provider = FakeProvider(
            [personal(), NAME], metrics=[{'done_reason': 'length'}, {'output_tokens': 7}]
        )
        completed, contexts = [], []
        with patch('cv_compass_llm.pipeline.perf_counter', side_effect=[0, 1, 3, 4, 8]):
            extract(
                document(HEADER_TEXT),
                provider,
                config=settings(ctx_guard=True),
                on_context=contexts.append,
                on_block=lambda *args: completed.append(args),
            )
        self.assertEqual(completed, [(1, 1, 8)])
        self.assertEqual(contexts[0]['discarded'][0]['seconds'], 2)
        self.assertEqual(provider.last_metrics, {'output_tokens': 7})


class TransportTests(unittest.TestCase):
    def test_flag_off_request_bytes_match_develop(self):
        schema = {'type': 'object', 'properties': {}}
        for guard in (False, True):
            provider = OllamaProvider('synthetic-model', ctx_guard=guard)
            reply = Mock()
            reply.read.return_value = b'{"message":{"content":"synthetic"}}'
            opener = MagicMock()
            opener.open.return_value.__enter__.return_value = reply
            provider._opener = opener
            provider.generate('synthetic prompt', schema)
            raw = opener.open.call_args.args[0].data
            self.assertEqual(
                hashlib.sha256(raw).hexdigest(),
                '1b4961e962342b7db77b4b464a42f5f6488514e59b33a4813f56b13d726c81f4',
            )

    def test_http_error_matching_is_bounded_opt_in_and_content_free(self):
        for guard in (False, True):
            for status in (400, 500, 401, 404):
                for body, expected_overflow in (
                    (b'PRIVATE context LENGTH exceeded', True),
                    (b'PRIVATE context window is too LONG', True),
                    (b'PRIVATE context size was too large', True),
                    (b'PRIVATE input length exceeds the context length', True),
                    (b'PRIVATE the input length exceeds the context length (8192)', True),
                    (b'PRIVATE prompt is too long for context window', True),
                    (b'PRIVATE CONTEXT window', False),
                    (b'PRIVATE input length', False),
                    (b'PRIVATE exceeds limit', False),
                    (b'PRIVATE too LONG', False),
                    (b'PRIVATE num_ctx', False),
                    (b"PRIVATE model 'x' not found", False),
                    (b'PRIVATE internal error', False),
                    (b'PRIVATE invalid option num_ctx', False),
                    (b'PRIVATE value exceeds limit', False),
                    (b'PRIVATE request too long', False),
                    (b'PRIVATE unrelated', False),
                    (b'x' * 4096 + b'input length exceeds the context length', False),
                ):
                    with self.subTest(guard=guard, status=status, body_length=len(body)):
                        stream = io.BytesIO(body)
                        read = Mock(wraps=stream.read)
                        error = HTTPError('http://localhost', status, 'synthetic', {}, stream)
                        error.read = read
                        provider = OllamaProvider('synthetic-model', ctx_guard=guard)
                        provider._opener = MagicMock()
                        provider._opener.open.side_effect = error
                        stderr = io.StringIO()
                        with redirect_stderr(stderr), self.assertRaises(ProviderError) as caught:
                            provider.generate('synthetic', {})
                        matched = guard and status in (400, 500) and expected_overflow
                        self.assertEqual(
                            str(caught.exception), OVERFLOW if matched else f'OLLAMA_HTTP_{status}'
                        )
                        self.assertNotIn('PRIVATE', str(caught.exception) + stderr.getvalue())
                        self.assertTrue(stream.closed)
                        if guard and status in (400, 500):
                            read.assert_called_once_with(4096)
                        else:
                            read.assert_not_called()

    def test_error_body_read_failure_retains_safe_http_code(self):
        error = HTTPError('http://localhost', 400, 'synthetic', {}, io.BytesIO())
        error.read = Mock(side_effect=OSError('PRIVATE'))
        provider = OllamaProvider('synthetic-model', ctx_guard=True)
        provider._opener = MagicMock()
        provider._opener.open.side_effect = error
        with self.assertRaisesRegex(ProviderError, '^OLLAMA_HTTP_400$'):
            provider.generate('synthetic', {})


class ConfigTests(unittest.TestCase):
    def test_flag_default_validation_precedence_cli_and_provider(self):
        self.assertFalse(settings().ctx_guard)
        for command in ('run', 'pull'):
            args = [command, '--ctx-guard', 'true']
            if command == 'run':
                args += ['--input', 'synthetic-input', '--output', 'synthetic-output']
            self.assertEqual(parser().parse_args(args).ctx_guard, 'true')
        self.assertIn('CVC_LLM_CTX_GUARD', BENCH_ENV_KEYS)
        base = {'provider': 'ollama', 'model': 'synthetic-model'}
        for value, expected in (('true', True), ('false', False), ('TRUE', True)):
            cfg = load_config(
                base,
                {'CVC_LLM_CTX_GUARD': value},
                None,
                env_file_explicit=False,
                _file_values={},
            )
            self.assertEqual(cfg.ctx_guard, expected)
            self.assertEqual(create_provider(cfg).ctx_guard, expected)
        cfg = load_config(
            base | {'ctx_guard': 'false'},
            {'CVC_LLM_CTX_GUARD': 'true'},
            None,
            env_file_explicit=False,
            _file_values={'CVC_LLM_CTX_GUARD': 'true'},
        )
        self.assertFalse(cfg.ctx_guard)
        with self.assertRaisesRegex(ValueError, '^INVALID_CTX_GUARD$'):
            load_config(
                base,
                {'CVC_LLM_CTX_GUARD': 'invalid'},
                None,
                env_file_explicit=False,
                _file_values={},
            )

    def test_bench_config_and_pull_error_mapping(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'synthetic.env'
            path.write_text(
                'CVC_LLM_PROVIDER=ollama\nCVC_LLM_MODEL=synthetic-model\nCVC_LLM_CTX_GUARD=true\n',
                encoding='utf-8',
            )
            self.assertTrue(load_bench_config(path, {}).ctx_guard)
            with self.assertRaisesRegex(ValueError, '^CVC_LLM_CTX_GUARD INVALID_CTX_GUARD$'):
                load_pull_config(
                    {
                        'CVC_LLM_WORKER_TOKEN': TOKEN,
                        'CVC_LLM_BACKEND_URL': 'http://localhost',
                        'CVC_LLM_CTX_GUARD': 'invalid',
                    },
                    path,
                    env_file_explicit=True,
                )

    def test_env_example_covers_all_keys_and_loads_without_invalid_empty_defaults(self):
        path = Path(__file__).resolve().parents[1] / '.env.example'
        text = path.read_text(encoding='utf-8')
        listed = re.findall(r'^\s*(?:#\s*)?(CVC_LLM_[A-Z_]+)=', text, re.MULTILINE)
        self.assertEqual(set(listed), set(ENV_KEYS.values()) - {'CVC_LLM_WORKER_TOKEN'})
        self.assertEqual(len(listed), len(set(listed)))
        values = _read_env(path, True)
        self.assertNotIn('CVC_LLM_WORKER_NAME', values)
        cfg = load_pull_config(
            {'CVC_LLM_WORKER_TOKEN': TOKEN, 'CVC_LLM_MODEL': 'synthetic-model'},
            path,
            env_file_explicit=True,
        )
        self.assertFalse(cfg.llm.ctx_guard)
        self.assertFalse(cfg.llm.ctx_fine_tiers)


class TelemetryTests(unittest.TestCase):
    def test_pull_http_error_fails_attempt_without_submitting_partial_result(self):
        backend = Backend()
        backend.input = {'pages': [{'page': 1, 'raw_text': HEADER_TEXT}]}
        provider = FakeProvider([ProviderError('OLLAMA_HTTP_500'), NAME])
        stderr = io.StringIO()
        with (
            backend_server(backend) as url,
            redirect_stderr(stderr),
            redirect_stdout(io.StringIO()),
        ):
            cfg = config(url)
            run_pull(replace(cfg, llm=replace(cfg.llm, ctx_guard=True)), provider, once=True)
        self.assertEqual(provider.calls, [(PERSONAL_SCHEMA, None, None)])
        self.assertEqual(backend.requests('result'), [])
        failures = backend.requests('failure')
        self.assertEqual(len(failures), 1)
        self.assertEqual(json.loads(failures[0][3]), {'code': 'PROVIDER_ERROR'})
        self.assertIn('code=PROVIDER_ERROR', stderr.getvalue())
        for field in ('CONTEXT_OVERFLOW', 'ctx_retry=1', 'event=ctx_discarded'):
            self.assertNotIn(field, stderr.getvalue())

    def test_observed_overflow_does_not_poison_guarded_attempt(self):
        for guard in (True, False):
            provider = FakeProvider([ProviderError(OVERFLOW), personal()])
            observed = _ObservedProvider(
                provider, SimpleNamespace(lost=False, outcome_started=False), ctx_guard=guard
            )
            with self.assertRaisesRegex(ProviderError, f'^{OVERFLOW}$'):
                observed.generate('synthetic', PERSONAL_SCHEMA)
            self.assertEqual(observed.failure, None if guard else 'PROVIDER_ERROR')
            if guard:
                self.assertEqual(
                    json.loads(observed.generate('synthetic', PERSONAL_SCHEMA)), personal()
                )
            else:
                with self.assertRaisesRegex(ProviderError, '^PROVIDER_ERROR$'):
                    observed.generate('synthetic', PERSONAL_SCHEMA)
                self.assertEqual(len(provider.calls), 1)

    def test_pull_logs_final_context_retry_and_discarded_metrics_without_content(self):
        for twice in (False, True):
            backend = Backend()
            backend.input = {'pages': [{'page': 1, 'raw_text': HEADER_TEXT}]}
            provider = FakeProvider(
                [ProviderError(OVERFLOW), ProviderError(OVERFLOW) if twice else NAME],
            )
            stderr = io.StringIO()
            with (
                backend_server(backend) as url,
                redirect_stderr(stderr),
                redirect_stdout(io.StringIO()),
            ):
                cfg = config(url)
                cfg = replace(cfg, llm=replace(cfg.llm, ctx_guard=True, num_ctx=8192))
                code = run_pull(cfg, provider, once=True)
            self.assertEqual(code, 0)
            self.assertEqual(len(backend.requests('result')), 1)
            self.assertEqual(len(provider.calls), 2)
            log = stderr.getvalue()
            block_log = next(line for line in log.splitlines() if 'event=extraction_block ' in line)
            self.assertIn('num_ctx=16384', block_log)
            self.assertIn('ctx_retry=1', block_log)
            self.assertIn('ctx_guard=1', log)
            self.assertEqual(log.count('event=ctx_discarded '), 2 if twice else 1)
            if twice:
                self.assertIn('CONTEXT_OVERFLOW.personal:1', log)
            body = backend.requests('result')[0][3]
            self.assertNotIn(b'CONTEXT_OVERFLOW', body)
            self.assertNotIn('Synthetic Person', log)
            self.assertNotIn('PROVIDER_ERROR', log)

    def test_pull_discarded_call_metrics_and_seconds_are_logged(self):
        backend = Backend()
        backend.input = {'pages': [{'page': 1, 'raw_text': HEADER_TEXT}]}
        provider = FakeProvider(
            [NAME, personal()],
            metrics=[
                {
                    'prompt_tokens': 8000,
                    'output_tokens': 160,
                    'output_ns': 2_000_000_000,
                    'arbitrary': 'PRIVATE',
                },
                {'output_tokens': 7},
            ],
        )
        stderr = io.StringIO()
        with (
            backend_server(backend) as url,
            redirect_stderr(stderr),
            redirect_stdout(io.StringIO()),
        ):
            cfg = config(url)
            run_pull(
                replace(cfg, llm=replace(cfg.llm, ctx_guard=True, num_ctx=8192)),
                provider,
                once=True,
            )
        lines = stderr.getvalue().splitlines()
        discarded = next(line for line in lines if 'event=ctx_discarded ' in line)
        final = next(line for line in lines if 'event=extraction_block ' in line)
        for field in (
            'num_ctx=8192',
            'prompt_tokens=8000',
            'output_tokens=160',
            'output_s=2.0',
            'seconds=',
        ):
            self.assertIn(field, discarded)
        self.assertIn('output_tokens=7', final)
        self.assertNotIn('prompt_tokens=8000', final)
        self.assertNotIn('PRIVATE', stderr.getvalue())

    def test_batch_and_bench_count_retries_and_each_cv_at_max_final_context(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, gold = root / 'source', root / 'gold'
            repo = root / 'repo'
            repo.mkdir()
            source.mkdir()
            gold.mkdir()
            for name in ('one', 'two'):
                (source / f'{name}.json').write_text(
                    json.dumps(
                        {
                            'pages': [{'page_number': 1, 'status': 'ok', 'raw_text': HEADER_TEXT}],
                        }
                    ),
                    encoding='utf-8',
                )
            path = root / 'synthetic.env'
            path.write_text(
                'CVC_LLM_PROVIDER=ollama\nCVC_LLM_MODEL=synthetic-model\nCVC_LLM_CTX_GUARD=true\n',
                encoding='utf-8',
            )

            def factory(cfg):
                return FakeProvider(
                    [ProviderError(OVERFLOW), personal(), personal()] * 2, extraction_config=cfg
                )

            summary = run_batch(
                source,
                root / 'batch',
                factory(settings(ctx_guard=True, num_ctx=8192)),
                repo_root=repo,
            )
            self.assertEqual(summary['ctx_retry'], 1)
            self.assertEqual(summary['num_ctx_counts'], {'16384': 1, '8192': 1})
            self.assertEqual(summary['llm_calls'], 3)
            report = run_bench(
                source,
                gold,
                root / 'bench',
                [f'guard={path}'],
                provider_factory=factory,
                repeat=2,
                environ={},
                repo_root=repo,
            )
            metrics = report['configurations'][0]['metrics']
            self.assertEqual(metrics['ctx_retry'], 2)
            self.assertEqual(metrics['num_ctx_counts.16384'], 2)
            self.assertEqual(metrics['num_ctx_counts.8192'], 2)
            self.assertEqual(metrics['llm_calls'], 6)

    def test_bench_aggregates_numeric_context_counts(self):
        report = _aggregate(
            [
                {
                    'files': 2,
                    'ok': 2,
                    'llm_calls': 5,
                    'issues': {},
                    'errors': {},
                    'ctx_retry': 2,
                    'num_ctx_counts': {'16384': 1, '24576': 1},
                },
                {
                    'files': 1,
                    'ok': 1,
                    'llm_calls': 1,
                    'issues': {},
                    'errors': {},
                    'ctx_retry': 0,
                    'num_ctx_counts': {'16384': 1},
                },
            ],
            [],
            [],
        )
        self.assertEqual(report['ctx_retry'], 2)
        self.assertEqual(report['num_ctx_counts.16384'], 2)
        self.assertEqual(report['num_ctx_counts.24576'], 1)

    def test_lifecycle_counts_retries_and_max_final_tier_once_per_document(self):
        fixture = test_lifecycle.LifecycleTests()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        backend, ocr, llm = fixture.fixture()
        llm = [
            llm[0] + ' num_ctx=8192',
            fixture.llm_line(32, seconds=2, block='2/2', num_ctx=16384, ctx_retry=1),
        ]
        backend += [
            fixture.backend_line(
                20,
                'MAPPING_REMOTE_CLAIMED',
                '2',
                jobId=21,
                attemptId='b0000000-0000-4000-8000-000000000001',
            )
        ]
        llm += [
            fixture.llm_line(33, seconds=1, block='1/1', num_ctx=24576, ctx_retry=1).replace(
                'attempt=a0000000', 'attempt=b0000000'
            )
        ]
        fixture.save(backend, ocr, llm)
        report = fixture.report()
        self.assertEqual(report['summary']['ctx_retry'], 2)
        self.assertEqual(report['summary']['num_ctx_counts'], {'16384': 1, '24576': 1})
        self.assertEqual(report['documents'][0]['calls'][1]['ctx_retry'], 1)

    def test_lifecycle_ignores_invalid_context_and_retry_metadata(self):
        fixture = test_lifecycle.LifecycleTests()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        backend, ocr, _ = fixture.fixture()
        llm = [
            fixture.llm_line(30 + index, seconds=1, num_ctx=size, ctx_retry=retry)
            for index, (size, retry) in enumerate(
                (('PRIVATE', 'PRIVATE'), ('nan', -1), (0, 0), (1.5, 2), (1048577, 'nan'))
            )
        ]
        fixture.save(backend, ocr, llm)
        report = fixture.report()
        self.assertEqual(report['summary']['num_ctx_counts'], {})
        self.assertEqual(report['summary']['ctx_retry'], 0)
        self.assertNotIn('PRIVATE', json.dumps(report))
