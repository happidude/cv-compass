"""D1 raw-response capture using synthetic OCR and offline providers only."""

import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

from test_core import document, personal
from test_o8_cascade import settings

from cv_compass_llm.batch_io import write_json
from cv_compass_llm.bench import _observations, run_bench
from cv_compass_llm.cli import main, parser, run_batch
from cv_compass_llm.compare import compare
from cv_compass_llm.gold import draft_gold
from cv_compass_llm.pipeline import extract
from cv_compass_llm.prompts import BEGIN, prompt_version
from cv_compass_llm.providers import FakeProvider, ProviderError

MARKER = 'SYNTHETIC_INPUT_ONLY_MARKER'
TEXT = MARKER + '\nExperience\nRole-Z\nEducation\nCourse-Z\nWork Experience\nRole-Y'
RESPONSES = ['  {"personal": {}}\n', '{"experiences": []}', 'invalid synthetic response']


class KeepRawTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.source = self.root / 'source'
        self.repo = self.root / 'repo'
        self.gold = self.root / 'gold'
        for path in (self.source, self.repo, self.gold):
            path.mkdir()
        write_json(
            self.source / 'sample.json',
            {'pages': [{'page_number': 1, 'status': 'ok', 'raw_text': TEXT}]},
        )

    def batch(self, name, provider=None, **options):
        return run_batch(
            self.source,
            self.root / name,
            provider if provider is not None else FakeProvider(RESPONSES),
            repo_root=self.repo,
            **options,
        )

    def raw(self, directory='raw'):
        return json.loads((self.root / directory / 'sample.raw.json').read_text(encoding='utf-8'))

    def test_default_false_and_true_preserve_result_summary_and_provider_calls(self):
        providers = [FakeProvider(RESPONSES, metrics={'output_tokens': 7}) for _ in range(3)]
        with patch('cv_compass_llm.cli.perf_counter', return_value=1):
            for name, provider, options in zip(
                ('default', 'disabled', 'raw'),
                providers,
                ({}, {'keep_raw': False}, {'keep_raw': True}),
                strict=True,
            ):
                self.batch(name, provider, **options)
        for name in ('default', 'disabled'):
            self.assertEqual(list((self.root / name).glob('*.raw.json')), [])
            for filename in ('sample.result.json', 'run-summary.json'):
                self.assertEqual(
                    (self.root / name / filename).read_bytes(),
                    (self.root / 'raw' / filename).read_bytes(),
                )
        for provider in providers[1:]:
            self.assertEqual(provider.calls, providers[0].calls)
            self.assertEqual(provider.prompts, providers[0].prompts)

    def test_byte_identity_for_whole_guard_cascade_and_provider_error(self):
        scenarios = (
            ('whole', ['{}', '{}'], {'mode': 'whole'}, 2),
            (
                'guard',
                [ProviderError('OLLAMA_CONTEXT_OVERFLOW'), *RESPONSES],
                {'config': settings(ctx_guard=True, num_ctx=8192)},
                4,
            ),
            ('cascade', RESPONSES, {'config': settings(fallback_model='backup')}, 6),
            ('error', [ProviderError('SYNTHETIC_FAILURE'), *RESPONSES[1:]], {}, 3),
        )

        def untimed_bytes(path):
            payload = json.loads(path.read_text(encoding='utf-8'))
            for key in ('seconds', 'total_seconds', 'average_seconds'):
                payload.pop(key, None)
            return json.dumps(payload, ensure_ascii=True, allow_nan=False, indent=2).encode('utf-8')

        for scenario, responses, options, expected_calls in scenarios:
            with self.subTest(scenario=scenario):
                providers = []
                directories = []
                for name, raw_options in (
                    ('default', {}),
                    ('disabled', {'keep_raw': False}),
                    ('raw', {'keep_raw': True}),
                ):
                    provider = FakeProvider(responses, metrics={'output_tokens': 7})
                    if scenario == 'cascade':
                        provider.fallback_provider = FakeProvider(['{}'] * 3)
                    providers.append(provider)
                    directory = f'{scenario}-{name}'
                    directories.append(self.root / directory)
                    summary = self.batch(directory, provider, **options, **raw_options)
                    self.assertEqual(summary['llm_calls'], expected_calls)
                    if scenario == 'guard':
                        self.assertEqual(summary['ctx_retry'], 1)
                    if scenario == 'cascade':
                        self.assertEqual(summary['fallbacks'], 1)
                    self.assertEqual(
                        (self.root / directory / 'sample.raw.json').exists(), name == 'raw'
                    )
                for directory in directories[1:]:
                    for filename in ('sample.result.json', 'run-summary.json'):
                        self.assertEqual(
                            untimed_bytes(directories[0] / filename),
                            untimed_bytes(directory / filename),
                        )
                for provider in providers[1:]:
                    self.assertEqual(provider.calls, providers[0].calls)
                    self.assertEqual(provider.prompts, providers[0].prompts)
                    if scenario == 'cascade':
                        self.assertEqual(
                            provider.fallback_provider.calls, providers[0].fallback_provider.calls
                        )
                        self.assertEqual(
                            provider.fallback_provider.prompts,
                            providers[0].fallback_provider.prompts,
                        )

    def test_blocks_capture_exact_responses_order_and_only_allowed_metadata(self):
        provider = FakeProvider(
            RESPONSES,
            metrics=[{'done_reason': 'stop', 'private': MARKER}, {}, {'done_reason': 'length'}],
        )
        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            self.batch('raw', provider, keep_raw=True)
        self.assertEqual(stdout.getvalue() + stderr.getvalue(), '')
        self.assertEqual(
            self.raw(),
            {
                'calls': [
                    {
                        'call': call,
                        'num_ctx': None,
                        'prompt_version': prompt_version('blocks'),
                        'response': response,
                        'done_reason': reason,
                    }
                    for call, response, reason in zip(
                        ('header', 'block-1', 'block-2'),
                        RESPONSES,
                        ('stop', None, 'length'),
                        strict=True,
                    )
                ]
            },
        )
        encoded = json.dumps(self.raw())
        for forbidden in (MARKER, BEGIN, *provider.prompts):
            self.assertNotIn(forbidden, encoded)

    def test_whole_records_one_call_without_header_and_preserves_existing_header_call(self):
        for text, labels in (('', ['whole']), (TEXT, ['whole', 'header'])):
            with self.subTest(labels=labels):
                rows = []
                provider = FakeProvider(['{}', '{}'])
                cfg = settings(prompt_schema_text=False, whole_personal=False)
                result = extract(
                    document(text), provider, mode='whole', config=cfg, on_raw=rows.append
                )
                self.assertEqual([row['call'] for row in rows], labels)
                self.assertEqual(len(rows), len(provider.calls))
                self.assertEqual(rows[0]['num_ctx'], provider.calls[0][1])
                self.assertTrue(all(row['prompt_version'] == result.prompt_version for row in rows))
                if text:
                    self.assertEqual(rows[1]['num_ctx'], cfg.num_ctx)

    def test_cascade_records_primary_then_fallback_with_stage(self):
        for mode, labels in (
            ('blocks', ['header', 'block-1', 'block-2']),
            ('whole', ['whole', 'header']),
        ):
            with self.subTest(mode=mode):
                cfg = settings(fallback_model='backup', name_style='clean')
                primary = FakeProvider(['invalid'] * len(labels))
                primary.fallback_provider = FakeProvider(['{}'] * len(labels))
                self.batch(mode, primary, config=cfg, mode=mode, keep_raw=True)
                rows = self.raw(mode)['calls']
                self.assertEqual([row['call'] for row in rows], labels + ['fallback'] * len(labels))
                self.assertEqual([row['stage'] for row in rows[len(labels) :]], labels)
                self.assertTrue(all('stage' not in row for row in rows[: len(labels)]))
                self.assertTrue(
                    all(row['prompt_version'] == prompt_version(mode, cfg) for row in rows)
                )

    def test_guard_records_discarded_response_or_error_before_retry(self):
        for first in ('discarded response', ProviderError('OLLAMA_CONTEXT_OVERFLOW')):
            with self.subTest(first=type(first).__name__):
                rows = []
                provider = FakeProvider(
                    [first, personal()],
                    metrics=[{'done_reason': 'length'}, {'done_reason': 'stop'}],
                )
                extract(
                    document(MARKER + '\nExperience\n'),
                    provider,
                    config=settings(ctx_guard=True, num_ctx=8192),
                    on_raw=rows.append,
                )
                self.assertEqual([row['call'] for row in rows], ['header', 'header'])
                self.assertEqual([row['num_ctx'] for row in rows], [8192, 16384])
                self.assertEqual(rows[0]['response'], first if isinstance(first, str) else None)
                self.assertEqual(
                    rows[0]['done_reason'], 'length' if isinstance(first, str) else None
                )
                self.assertEqual(rows[1]['done_reason'], 'stop')

    def test_provider_error_has_null_response_and_reason_but_input_error_has_no_raw(self):
        write_json(self.source / 'bad.json', {'pages': 'invalid synthetic input'})
        provider = FakeProvider([ProviderError(MARKER), *RESPONSES[1:]])
        self.batch('raw', provider, keep_raw=True)
        row = self.raw()['calls'][0]
        self.assertIsNone(row['response'])
        self.assertIsNone(row['done_reason'])
        self.assertTrue((self.root / 'raw' / 'bad.error.json').is_file())
        self.assertFalse((self.root / 'raw' / 'bad.raw.json').exists())
        self.assertNotIn(MARKER, json.dumps(self.raw()))

    def test_fallback_provider_error_records_null_response_and_reason(self):
        provider = FakeProvider(['invalid'] * 3)
        provider.fallback_provider = FakeProvider([ProviderError(MARKER), '{}', '{}'])
        self.batch('raw', provider, config=settings(fallback_model='backup'), keep_raw=True)
        rows = self.raw()['calls']
        self.assertEqual(len(rows), 6)
        self.assertEqual(
            [row['stage'] for row in rows if row['call'] == 'fallback'],
            ['header', 'block-1', 'block-2'],
        )
        self.assertEqual(rows[3]['call'], 'fallback')
        self.assertIsNone(rows[3]['response'])
        self.assertIsNone(rows[3]['done_reason'])
        self.assertNotIn(MARKER, json.dumps(self.raw()))

    def test_missing_metrics_are_null(self):
        class WithoutMetrics:
            name = 'synthetic'

            def generate(self, prompt, schema, **options):
                return '{}'

        rows = []
        extract(document(''), WithoutMetrics(), mode='whole', on_raw=rows.append)
        self.assertIsNone(rows[0]['done_reason'])

    def test_trimmed_empty_block_does_not_consume_call_number(self):
        cfg = settings(trim_input=True, trim_phrases=('Role-Z',))
        self.batch('raw', FakeProvider(['{}', '{}']), config=cfg, keep_raw=True)
        self.assertEqual([row['call'] for row in self.raw()['calls']], ['header', 'block-1'])

    def test_raw_context_uses_provider_default_without_extraction_config(self):
        provider = FakeProvider(['{}'])
        provider.num_ctx = 12288
        rows = []
        extract(document(MARKER + '\nExperience\n'), provider, on_raw=rows.append)
        self.assertEqual(rows[0]['num_ctx'], 12288)

    def test_batch_raw_context_uses_provider_default_without_extraction_config(self):
        provider = FakeProvider(RESPONSES)
        provider.num_ctx = 12288
        self.assertIsNone(provider.extraction_config)
        self.batch('raw', provider, keep_raw=True)
        self.assertEqual([row['num_ctx'] for row in self.raw()['calls']], [12288] * 3)

    def test_raw_writer_uses_exclusive_creation(self):
        with patch('cv_compass_llm.cli.write_json', wraps=write_json) as writer:
            self.batch('raw', keep_raw=True)
        paths = [call.args[0].name for call in writer.call_args_list]
        self.assertLess(paths.index('sample.result.json'), paths.index('sample.raw.json'))
        path = self.root / 'raw' / 'sample.raw.json'
        before = path.read_bytes()
        with self.assertRaises(FileExistsError):
            write_json(path, {'calls': []})
        self.assertEqual(path.read_bytes(), before)

    def test_result_consumers_ignore_raw_including_invalid_json(self):
        self.batch('results')
        results = self.root / 'results'
        draft_gold(results, self.root / 'draft-before', repo_root=self.repo)
        gold = json.loads(
            (self.root / 'draft-before' / 'sample.gold.json').read_text(encoding='utf-8')
        )
        gold['status'] = 'CONFIRMED'
        write_json(self.gold / 'sample.gold.json', gold)
        expected_compare = compare(results, self.gold)
        expected_observations = _observations(results)
        self.batch('selected-before', only_fallback_candidates=results)
        (results / 'sample.raw.json').write_text('invalid raw JSON', encoding='utf-8')
        (results / 'extra.raw.json').write_text('invalid raw JSON', encoding='utf-8')
        self.assertEqual(compare(results, self.gold), expected_compare)
        self.assertEqual(_observations(results), expected_observations)
        draft_gold(results, self.root / 'draft-after', repo_root=self.repo)
        self.batch('selected-after', only_fallback_candidates=results)
        self.assertEqual(
            (self.root / 'draft-before' / 'sample.gold.json').read_bytes(),
            (self.root / 'draft-after' / 'sample.gold.json').read_bytes(),
        )
        for name in ('selected-before', 'selected-after'):
            self.assertEqual(len(list((self.root / name).glob('*.result.json'))), 1)
        self.assertEqual(len(list((self.root / 'draft-after').iterdir())), 1)

    def test_bench_warmup_repeats_and_parallel_keep_raw_without_metric_changes(self):
        config_path = self.root / 'synthetic.env'
        config_path.write_text(
            'CVC_LLM_PROVIDER=ollama\nCVC_LLM_MODEL=synthetic\n', encoding='utf-8'
        )
        reports = []
        for name, options in (('default', {}), ('raw', {'keep_raw': True})):
            with (
                patch('cv_compass_llm.cli.perf_counter', return_value=1),
                patch('cv_compass_llm.bench.perf_counter', return_value=1),
            ):
                reports.append(
                    run_bench(
                        self.source,
                        self.gold,
                        self.root / name,
                        [f'base={config_path}'],
                        provider_factory=lambda cfg: FakeProvider(RESPONSES * 3),
                        repeat=2,
                        warmup=1,
                        parallel=2,
                        environ={},
                        repo_root=self.repo,
                        **options,
                    )
                )
            for iteration in ('warmup', 'r1', 'r2'):
                directory = self.root / name / 'base' / iteration
                self.assertEqual((directory / 'sample.raw.json').exists(), name == 'raw')
        self.assertEqual(reports[0], reports[1])

    def test_cli_forwards_only_enabled_flag_and_never_logs_raw(self):
        for enabled in (False, True):
            for command in ('run', 'bench'):
                with self.subTest(enabled=enabled, command=command):
                    stdout, stderr = io.StringIO(), io.StringIO()
                    args = [command, '--input', 'synthetic-input', '--output', 'synthetic-output']
                    if command == 'bench':
                        args += [
                            '--gold',
                            'synthetic-gold',
                            '--config',
                            'base=synthetic.env',
                            '--json',
                        ]
                        target, result = 'run_bench', {'configurations': []}
                    else:
                        args += ['--provider', 'ollama', '--model', 'synthetic']
                        target, result = 'run_batch', {'files': 1, 'ok': 1, 'errors': {}}
                    if enabled:
                        args += ['--keep-raw']
                    with (
                        patch.dict(os.environ, {}, clear=True),
                        patch('cv_compass_llm.cli.load_config', return_value=settings()),
                        patch('cv_compass_llm.cli.create_provider', return_value=FakeProvider([])),
                        patch('cv_compass_llm.cli.' + target, return_value=result) as runner,
                        redirect_stdout(stdout),
                        redirect_stderr(stderr),
                    ):
                        self.assertEqual(main(args), 0)
                    self.assertEqual(
                        runner.call_args.kwargs.get('keep_raw'), True if enabled else None
                    )
                    self.assertNotIn(MARKER, stdout.getvalue() + stderr.getvalue())
        stderr = io.StringIO()
        with redirect_stderr(stderr), self.assertRaises(SystemExit) as error:
            parser().parse_args(['pull', '--keep-raw'])
        self.assertEqual(error.exception.code, 2)
        self.assertEqual(stderr.getvalue(), 'INVALID_ARGUMENTS\n')
