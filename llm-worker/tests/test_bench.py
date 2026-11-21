"""Offline benchmark acceptance using only synthetic OCR, gold and providers."""

import io
import json
import os
import re
import tempfile
import threading
import unittest
from collections import Counter
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

from cv_compass_llm.batch_io import write_json
from cv_compass_llm.bench import print_bench, run_bench
from cv_compass_llm.cli import create_provider, main
from cv_compass_llm.config import load_bench_config
from cv_compass_llm.gold import PERSONAL_FIELDS
from cv_compass_llm.providers import FakeProvider, ProviderError


class BenchTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.source = self.root / 'ocr'
        self.gold = self.root / 'gold'
        self.repo = self.root / 'repo'
        for path in (self.source, self.gold, self.repo):
            path.mkdir()
        self.output = self.root / 'output'
        self.configs = []
        for name in ('base', 'candidate'):
            path = self.root / (name + '.env')
            path.write_text(f'CVC_LLM_PROVIDER=ollama\nCVC_LLM_MODEL={name}\n', encoding='utf-8')
            self.configs.append(f'{name}={path}')
        self.secret = 'SYNTHETIC_PRIVATE_NAME'
        self.filename = 'SYNTHETIC_PRIVATE_FILE'
        (self.source / (self.filename + '.json')).write_text(
            json.dumps(
                {
                    'pages': [
                        {
                            'page_number': 1,
                            'status': 'ok',
                            'raw_text': self.secret + '\nExperience\n',
                        }
                    ]
                }
            ),
            encoding='utf-8',
        )
        (self.gold / (self.filename + '.gold.json')).write_text(
            json.dumps(
                {
                    'status': 'CONFIRMED',
                    'personal': {
                        key: self.secret if key == 'fullName' else None for key in PERSONAL_FIELDS
                    },
                    'experiences': [],
                }
            ),
            encoding='utf-8',
        )

    def response(self, named=True):
        return {
            'personal': {
                key: (
                    {'value': self.secret, 'quote': self.secret}
                    if key == 'fullName' and named
                    else None
                )
                for key in (*PERSONAL_FIELDS, 'summary')
            }
        }

    def test_o4_env_file_flags_reach_warmup_and_measured_pipeline(self):
        for name in ('base', 'candidate'):
            path = self.root / (name + '.env')
            with path.open('a', encoding='utf-8') as stream:
                stream.write('CVC_LLM_MODE=whole\n')
                if name == 'candidate':
                    stream.write(
                        'CVC_LLM_PROMPT_SCHEMA_TEXT=false\nCVC_LLM_WHOLE_PERSONAL=false\n'
                        'CVC_LLM_MAX_OUTPUT_TOKENS=256\nCVC_LLM_KEEP_ALIVE=10m\n'
                        'CVC_LLM_CTX_CHARS_PER_TOKEN=1.5\nCVC_LLM_CTX_FINE_TIERS=true\n'
                    )
        providers = []

        def local_provider(model, **options):
            whole = (
                dict(self.response(), experiences=[]) if model == 'base' else {'experiences': []}
            )
            provider = FakeProvider(
                [whole, self.response()] * 2,
                num_predict=options.get('num_predict'),
                keep_alive=options.get('keep_alive'),
            )
            providers.append(provider)
            return provider

        with patch('cv_compass_llm.cli.OllamaProvider', side_effect=local_provider):
            self.run_bench(create_provider, warmup=1)
        for name, provider in zip(('base', 'candidate'), providers, strict=True):
            version = 'llm-extraction-prompt/whole-2' + ('-lean-xp' if name == 'candidate' else '')
            for run in ('warmup', 'r1'):
                summary = json.loads(
                    (self.output / name / run / 'run-summary.json').read_text(encoding='utf-8')
                )
                self.assertEqual(summary['prompt_version'], version)
                self.assertEqual(summary['issues'], {})
            self.assertEqual(len(provider.prompts), 4)
            for prompt in provider.prompts:
                self.assertEqual('matching this schema' in prompt, name == 'base')
            if name == 'candidate':
                self.assertTrue(
                    all(
                        set(call[0]['properties']) == {'experiences'}
                        for call in provider.calls[::2]
                    )
                )
                self.assertEqual(
                    provider.request_options, [{'num_predict': 256, 'keep_alive': '10m'}] * 4
                )

    def run_bench(self, factory, **options):
        return run_bench(
            self.source,
            self.gold,
            self.output,
            self.configs,
            provider_factory=factory,
            environ={},
            repo_root=self.repo,
            **options,
        )

    def test_two_configs_numeric_table_delta_metrics_and_input_preservation(self):
        before = (self.source / (self.filename + '.json')).read_bytes()
        configs = []

        def factory(config):
            configs.append(config)
            return FakeProvider(
                [self.response(config.model == 'base')],
                metrics={'prompt_tokens': 100, 'output_tokens': 20, 'output_ns': 2_000_000_000},
            )

        with patch('cv_compass_llm.cli.perf_counter', side_effect=range(6)):
            report = self.run_bench(factory)
        base, candidate = report['configurations']
        self.assertEqual([config.model for config in configs], ['base', 'candidate'])
        self.assertEqual(base['metrics']['personal.fullName.correct'], 1)
        self.assertEqual(candidate['metrics']['personal.fullName.missing'], 1)
        self.assertEqual(candidate['delta']['personal.fullName.correct'], -1)
        self.assertEqual(candidate['delta']['personal.fullName.missing'], 1)
        for kind in ('positions', 'companies', 'titles', 'companyPeriods'):
            self.assertEqual(base['metrics'][f'experiences.{kind}.precision'], 0)
            self.assertEqual(base['metrics'][f'experiences.{kind}.recall'], 0)
        self.assertEqual(base['metrics']['seconds.average'], 1)
        self.assertEqual(base['metrics']['seconds.p50'], 1)
        self.assertEqual(base['metrics']['seconds.p90'], 1)
        self.assertEqual(base['metrics']['prompt_tokens.average'], 100)
        self.assertEqual(base['metrics']['output_tokens.average'], 20)
        self.assertEqual(base['metrics']['tok_s.average'], 10)
        out = io.StringIO()
        with redirect_stdout(out):
            print_bench(report)
        text = out.getvalue()
        self.assertIn('base', text)
        self.assertIn('candidate', text)
        self.assertIn('Δ', text)
        self.assertIn('personal.fullName.missing', text)
        cells = {line.split('\t')[0]: line.split('\t')[1:] for line in text.splitlines()[1:]}
        self.assertEqual(
            cells['personal.fullName.correct'], ['1', '0', '1', '0', '0', '-1', '0', '-1']
        )
        self.assertEqual(
            cells['personal.fullName.missing'], ['0', '0', '0', '0', '1', '1', '1', '1']
        )
        for text in (text, json.dumps(report)):
            for private in (self.secret, self.filename, str(self.root), 'ollama', 'prompt_version'):
                self.assertNotIn(private, text)
        for name in ('base', 'candidate'):
            self.assertTrue((self.output / name / 'r1' / 'run-summary.json').is_file())
        self.assertEqual((self.source / (self.filename + '.json')).read_bytes(), before)

    def test_warmup_repeat_difference_and_missing_metrics(self):
        self.configs = self.configs[:1]
        provider = FakeProvider([self.response(False), self.response(), self.response(False)])
        with patch('cv_compass_llm.cli.perf_counter', side_effect=range(9)):
            report = self.run_bench(lambda config: provider, warmup=1, repeat=2)
        metrics = report['configurations'][0]['metrics']
        self.assertEqual(metrics['files'], 2)
        self.assertEqual(metrics['compared'], 2)
        self.assertEqual(metrics['personal.fullName.correct'], 1)
        self.assertEqual(metrics['personal.fullName.missing'], 1)
        self.assertEqual(metrics['different_cvs'], 1)
        self.assertEqual(metrics['seconds.count'], 2)
        self.assertEqual(metrics['seconds.average'], 1)
        self.assertEqual(metrics['prompt_tokens.count'], 0)
        self.assertIsNone(metrics['prompt_tokens.average'])
        self.assertTrue((self.output / 'base' / 'warmup' / 'run-summary.json').is_file())
        self.assertTrue((self.output / 'base' / 'r2' / 'run-summary.json').is_file())
        self.assertEqual(len(provider.calls), 3)

    def test_repeat_ignores_seconds_and_metrics(self):
        self.configs = self.configs[:1]
        provider = FakeProvider(
            [self.response()] * 3,
            metrics=[{'output_tokens': 1}, {'output_tokens': 2}, {}],
        )
        with patch('cv_compass_llm.cli.perf_counter', side_effect=[0, 1, 2, 3, 6, 7, 8, 13, 14]):
            report = self.run_bench(lambda config: provider, repeat=3, parallel=1)
        metrics = report['configurations'][0]['metrics']
        self.assertEqual(metrics['different_cvs'], 0)
        self.assertEqual(metrics['seconds.average'], 3)
        self.assertEqual(metrics['seconds.p50'], 3)
        self.assertAlmostEqual(metrics['seconds.p90'], 4.6)
        self.assertEqual(metrics['output_tokens.average'], 1.5)

    def test_experience_scores_pool_counts_and_count_changed_cv_only_once(self):
        text = self.secret + '\nExperience\nRole-Z\nOrg-Z\n2020 - 2022\n'
        text += 'Role-X\nOrg-X\n2018 - 2019\n'
        (self.source / (self.filename + '.json')).write_text(
            json.dumps({'pages': [{'page_number': 1, 'status': 'ok', 'raw_text': text}]}),
            encoding='utf-8',
        )
        role = {
            'title': 'Role-Z',
            'company': 'Org-Z',
            'start': '2020',
            'end': '2022',
            'ongoing': False,
        }
        extra = dict(role, title='Role-X', company='Org-X', start='2018', end='2019')
        reference = {
            'status': 'CONFIRMED',
            'personal': {
                key: self.secret if key == 'fullName' else None for key in PERSONAL_FIELDS
            },
            'experiences': [role],
        }
        (self.gold / (self.filename + '.gold.json')).write_text(
            json.dumps(reference), encoding='utf-8'
        )

        def response(items):
            return {
                'experiences': [
                    dict(
                        item,
                        quotes={
                            'title': item['title'],
                            'company': item['company'],
                            'period': f'{item["start"]} - {item["end"]}',
                        },
                    )
                    for item in items
                ]
            }

        def factory(config):
            records = [[role]] * 3 if config.model == 'base' else [[role, extra], [], []]
            return FakeProvider(
                [entry for items in records for entry in (self.response(), response(items))]
            )

        report = self.run_bench(factory, repeat=3)
        base, candidate = report['configurations']
        for kind in ('positions', 'companies', 'titles', 'companyPeriods'):
            for metric in ('precision', 'recall'):
                self.assertEqual(base['metrics'][f'experiences.{kind}.{metric}'], 1)
            self.assertEqual(candidate['metrics'][f'experiences.{kind}.precision'], 0.5)
            self.assertAlmostEqual(candidate['metrics'][f'experiences.{kind}.recall'], 1 / 3)
            self.assertAlmostEqual(candidate['delta'][f'experiences.{kind}.recall'], -2 / 3)
        self.assertEqual(base['metrics']['different_cvs'], 0)
        self.assertEqual(candidate['metrics']['different_cvs'], 1)

    def test_warmup_selects_first_sorted_cv_once_and_all_cvs_are_measured(self):
        self.configs = self.configs[:1]
        (self.source / '000-first.json').write_text(
            json.dumps(
                {'pages': [{'page_number': 1, 'status': 'ok', 'raw_text': 'FIRST\nExperience\n'}]}
            ),
            encoding='utf-8',
        )
        provider = FakeProvider([self.response(False)] * 5)
        report = self.run_bench(lambda config: provider, warmup=1, repeat=2)
        self.assertEqual(len(provider.calls), 5)
        self.assertIn('FIRST', provider.prompts[0])
        self.assertNotIn(self.secret, provider.prompts[0])
        metrics = report['configurations'][0]['metrics']
        self.assertEqual(metrics['files'], 4)
        self.assertEqual(metrics['different_cvs'], 0)
        self.assertTrue((self.output / 'base' / 'warmup' / '000-first.result.json').is_file())
        unselected = self.output / 'base' / 'warmup' / (self.filename + '.result.json')
        self.assertFalse(unselected.exists())

    def test_empty_input_has_missing_measurements_and_no_model_calls(self):
        self.source = self.root / 'empty'
        self.source.mkdir()
        provider = FakeProvider([])
        report = self.run_bench(lambda config: provider, repeat=2, warmup=10)
        for row in report['configurations']:
            self.assertEqual(row['metrics']['files'], 0)
            self.assertEqual(row['metrics']['different_cvs'], 0)
            self.assertEqual(row['metrics']['seconds.count'], 0)
            self.assertIsNone(row['metrics']['seconds.average'])
            self.assertIsNone(row['delta']['seconds.average'])
            self.assertIsNone(row['delta']['tok_s.average'])
        self.assertEqual(provider.calls, [])
        out = io.StringIO()
        with redirect_stdout(out):
            print_bench(report)
        self.assertIn('seconds.average\t-\t-\t-\t-', out.getvalue())

    def test_issues_and_input_errors_have_numeric_deltas(self):
        (self.source / 'invalid.json').write_bytes(b'{INVALID_PRIVATE_OCR')

        def factory(config):
            response = self.response() if config.model == 'base' else ProviderError('PRIVATE')
            return FakeProvider([response])

        report = self.run_bench(factory)
        base, candidate = report['configurations']
        self.assertEqual(base['metrics']['errors.INVALID_JSON'], 1)
        self.assertEqual(base['metrics']['issues.PROVIDER_ERROR'], 0)
        self.assertEqual(candidate['metrics']['issues.PROVIDER_ERROR'], 1)
        self.assertEqual(candidate['delta']['issues.PROVIDER_ERROR'], 1)

    def test_invalid_config_keys_are_rejected_before_factory_or_output(self):
        path = self.root / 'candidate.env'
        for key in (
            'CVC_LLM_WORKER_TOKEN',
            'CVC_LLM_BACKEND_URL',
            'CVC_LLM_POLL_S',
            'CVC_LLM_FUTURE_UNKNOWN',
            'UNRELATED_KEY',
        ):
            path.write_text(f'{key}=PRIVATE_VALUE', encoding='utf-8')
            with (
                self.subTest(key=key),
                self.assertRaisesRegex(ValueError, '^BENCH_INVALID_CONFIG_KEY$'),
            ):
                self.run_bench(lambda config: self.fail('provider must not be created'))
            self.assertFalse(self.output.exists())

    def test_config_overlay_and_no_default_dotenv_or_environment_mutation(self):
        path = self.root / 'base.env'
        path.write_text('CVC_LLM_MODEL="base"\nCVC_LLM_THINK=true\n', encoding='utf-8')
        environment = {'CVC_LLM_PROVIDER': 'ollama', 'CVC_LLM_MODEL': 'ambient'}
        with patch('cv_compass_llm.config.default_env_file', side_effect=AssertionError):
            config = load_bench_config(path, environment)
        self.assertEqual(config.model, 'base')
        self.assertTrue(config.think)
        self.assertEqual(environment['CVC_LLM_MODEL'], 'ambient')
        path.write_text('CVC_LLM_MODEL=" "\n', encoding='utf-8')
        self.assertEqual(load_bench_config(path, environment).model, 'ambient')

    def test_all_extraction_keys_reach_config_and_mode_reaches_batch(self):
        self.configs = self.configs[:1]
        (self.root / 'base.env').write_text(
            'CVC_LLM_PROVIDER=ollama\nCVC_LLM_MODEL=base\n'
            'CVC_LLM_BASE_URL=http://localhost:11434\nCVC_LLM_TIMEOUT_S=12\n'
            'CVC_LLM_MODE=whole\nCVC_LLM_WHOLE_TIMEOUT_S=13\n'
            'CVC_LLM_MAX_BLOCKS=0\nCVC_LLM_TEMPERATURE=0.5\n'
            'CVC_LLM_THINK=true\nCVC_LLM_NUM_CTX=4096\n',
            encoding='utf-8',
        )
        provider = FakeProvider([dict(self.response(), experiences=[]), self.response()])

        def factory(config):
            self.assertEqual(config.base_url, 'http://localhost:11434')
            self.assertEqual(config.timeout_s, 12)
            self.assertEqual(config.max_blocks, 0)
            self.assertEqual(config.temperature, 0.5)
            self.assertTrue(config.think)
            self.assertEqual(config.num_ctx, 4096)
            return provider

        self.run_bench(factory)
        self.assertEqual(len(provider.calls), 2)
        self.assertEqual(provider.calls[0][2], 13)
        summary = json.loads((self.output / 'base' / 'r1' / 'run-summary.json').read_text())
        self.assertEqual(summary['mode'], 'whole')

    def test_names_duplicates_and_counts_fail_before_output(self):
        for names, options, code in (
            (['../private=x'], {}, 'BENCH_INVALID_CONFIG'),
            (['a' * 33 + '=x'], {}, 'BENCH_INVALID_CONFIG'),
            (['missing-separator'], {}, 'BENCH_INVALID_CONFIG'),
            (['base='], {}, 'BENCH_INVALID_CONFIG'),
            ([], {}, 'BENCH_INVALID_CONFIG'),
            ([self.configs[0]] * 2, {}, 'BENCH_DUPLICATE_CONFIG'),
            (
                [self.configs[0], self.configs[0].replace('base=', 'BASE=', 1)],
                {},
                'BENCH_DUPLICATE_CONFIG',
            ),
            (self.configs, {'repeat': 0}, 'BENCH_INVALID_REPEAT'),
            (self.configs, {'warmup': -1}, 'BENCH_INVALID_WARMUP'),
            (self.configs, {'parallel': 0}, 'BENCH_INVALID_PARALLEL'),
            (self.configs, {'parallel': 17}, 'BENCH_INVALID_PARALLEL'),
            (self.configs, {'parallel': True}, 'BENCH_INVALID_PARALLEL'),
            (self.configs, {'parallel': 1.5}, 'BENCH_INVALID_PARALLEL'),
        ):
            self.configs, previous = names, self.configs
            with self.subTest(code=code), self.assertRaisesRegex(ValueError, '^' + code + '$'):
                self.run_bench(lambda config: self.fail('unexpected factory'), **options)
            self.configs = previous
            self.assertFalse(self.output.exists())

    def test_parallel_workers_use_separate_providers_and_process_each_cv_once_per_run(self):
        self.configs = self.configs[:1]
        original = self.source / (self.filename + '.json')
        original_gold = self.gold / (self.filename + '.gold.json')
        reference = original_gold.read_bytes()
        original.rename(self.source / 'cv-0.json')
        original_gold.rename(self.gold / 'cv-0.gold.json')
        for index in range(4):
            (self.source / f'cv-{index}.json').write_text(
                json.dumps(
                    {
                        'pages': [
                            {
                                'page_number': 1,
                                'status': 'ok',
                                'raw_text': f'{self.secret}\nCV-MARKER-{index}\nExperience\n',
                            }
                        ]
                    }
                ),
                encoding='utf-8',
            )
            (self.gold / f'cv-{index}.gold.json').write_bytes(reference)
        (self.source / 'invalid.json').write_bytes(b'{')
        providers, seen = [], Counter()
        paired = threading.Barrier(2, timeout=5)
        lock = threading.Lock()
        response = self.response()
        completed, second_written = [], {}

        def write_out_of_order(path, payload):
            with lock:
                ready = second_written.setdefault(path.parent, threading.Event())
            if path.name == 'cv-0.result.json' and not ready.wait(5):
                raise AssertionError('Second CV did not finish first')
            write_json(path, payload)
            if path.name.endswith('.result.json'):
                with lock:
                    completed.append(path)
            if path.name == 'cv-1.result.json':
                ready.set()

        class PairedProvider(FakeProvider):
            def __init__(self, tokens):
                super().__init__([response] * 5, metrics={'prompt_tokens': tokens})
                self.exclusive = threading.Lock()

            def generate(self, prompt, schema, **options):
                if not self.exclusive.acquire(blocking=False):
                    raise AssertionError('Provider shared concurrently')
                try:
                    value = super().generate(prompt, schema, **options)
                    with lock:
                        seen[re.search(r'CV-MARKER-\d', prompt).group()] += 1
                    paired.wait()
                    return value
                finally:
                    self.exclusive.release()

        def factory(config):
            provider = PairedProvider(10 if not providers else 30)
            providers.append(provider)
            return provider

        with (
            patch('cv_compass_llm.bench.perf_counter', side_effect=[0, 10, 20, 30]),
            patch('cv_compass_llm.cli.write_json', side_effect=write_out_of_order),
        ):
            report = self.run_bench(factory, parallel=2, warmup=2, repeat=2)
        self.assertEqual(len(providers), 2)
        self.assertEqual([len(provider.calls) for provider in providers], [5, 5])
        self.assertEqual(
            seen, {'CV-MARKER-0': 3, 'CV-MARKER-1': 3, 'CV-MARKER-2': 2, 'CV-MARKER-3': 2}
        )
        metrics = report['configurations'][0]['metrics']
        self.assertEqual(metrics['files'], 10)
        self.assertEqual(metrics['ok'], 8)
        self.assertEqual(metrics['errors.INVALID_JSON'], 2)
        self.assertEqual(metrics['llm_calls'], 8)
        self.assertEqual(metrics['prompt_tokens.count'], 8)
        self.assertEqual(metrics['prompt_tokens.average'], 20)
        self.assertEqual(metrics['throughput_cv_per_min'], 24)
        self.assertEqual(metrics['different_cvs'], 0)
        for stage in ('warmup', 'r1', 'r2'):
            names = [path.name for path in completed if path.parent.name == stage]
            self.assertLess(names.index('cv-1.result.json'), names.index('cv-0.result.json'))
        for iteration in (1, 2):
            directory = self.output / 'base' / f'r{iteration}'
            self.assertEqual(len(list(directory.glob('*.result.json'))), 4)
            self.assertTrue((directory / 'invalid.error.json').is_file())
            summary = json.loads((directory / 'run-summary.json').read_text(encoding='utf-8'))
            self.assertEqual(summary['llm_calls'], 4)

    def test_cli_parallel_reaches_benchmark(self):
        with (
            patch('cv_compass_llm.cli.run_bench', return_value={'configurations': []}) as bench,
            redirect_stdout(io.StringIO()),
        ):
            self.assertEqual(
                main(
                    [
                        'bench',
                        '--input',
                        'unused',
                        '--gold',
                        'unused',
                        '--output',
                        'unused',
                        '--config',
                        'base=unused',
                        '--parallel',
                        '2',
                        '--json',
                    ]
                ),
                0,
            )
        self.assertEqual(bench.call_args.kwargs['parallel'], 2)

    def test_output_boundaries_and_nonempty_preserve_existing_data(self):
        def factory(config):
            return FakeProvider([self.response()])

        for output in (
            self.source,
            self.source / 'nested',
            self.gold / 'nested',
            self.repo / 'out',
        ):
            self.output = output
            with (
                self.subTest(output=output),
                self.assertRaisesRegex(ValueError, '^UNSAFE_OUTPUT$'),
            ):
                self.run_bench(factory)
        self.output = self.root / 'occupied'
        self.output.mkdir()
        marker = self.output / 'marker'
        marker.write_bytes(b'preserved')
        with self.assertRaisesRegex(ValueError, '^OUTPUT_NOT_EMPTY$'):
            self.run_bench(factory)
        self.assertEqual(marker.read_bytes(), b'preserved')

    def test_cli_factory_json_and_private_error(self):
        argv = [
            'bench',
            '--input',
            str(self.source),
            '--gold',
            str(self.gold),
            '--output',
            str(self.output),
            '--config',
            self.configs[0],
            '--json',
            '--repeat',
            '2',
            '--warmup',
            '1',
        ]
        out, err = io.StringIO(), io.StringIO()
        with (
            patch.dict(os.environ, {}, clear=True),
            patch(
                'cv_compass_llm.cli.create_provider',
                return_value=FakeProvider([self.response()] * 3),
            ) as factory,
            redirect_stdout(out),
            redirect_stderr(err),
        ):
            code = main(argv)
        self.assertEqual(code, 0)
        factory.assert_called_once()
        report = json.loads(out.getvalue())
        self.assertEqual(report['configurations'][0]['metrics']['files'], 2)
        self.assertEqual(err.getvalue(), '')
        out = io.StringIO()
        (self.root / 'base.env').write_text('CVC_LLM_WORKER_TOKEN=PRIVATE', encoding='utf-8')
        with redirect_stdout(out), redirect_stderr(err):
            code = main(argv)
        self.assertEqual(code, 2)
        self.assertEqual(out.getvalue(), 'BENCH_INVALID_CONFIG_KEY\n')
