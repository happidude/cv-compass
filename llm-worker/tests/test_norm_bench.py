"""Invented gold metrics, deduplication and aggregate-only benchmark output."""

import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import Mock, patch

from test_normalize import TAXONOMY
from test_pull import config

from cv_compass_llm.cli import main
from cv_compass_llm.norm_bench import GOLD_VERSION, load_gold, load_taxonomy, run_norm_bench, score
from cv_compass_llm.providers.base import ProviderError

GOLD = {
    'schemaVersion': GOLD_VERSION,
    'companies': [
        {'raw': 'May Lam Ltd', 'name': 'Mây Lam', 'parent': None, 'industry': 'RETAIL'},
        {'raw': 'Sao Tim LLC', 'name': 'SAO TIM', 'parent': None, 'industry': 'RETAIL'},
    ],
    'titles': [
        {
            'raw': 'Regional Sales Manager',
            'contextCompany': 'May Lam',
            'functions': ['SALES', 'HR'],
            'seniority': 'MANAGER',
            'scope': 'REGION',
        },
    ],
}


class NormalizationBenchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.gold = self.root / 'gold'
        self.gold.mkdir()
        self.path = self.gold / 'invented.norm.json'
        self.write_gold(GOLD)
        self.env_path = self.root / 'synthetic.env'
        self.env_path.write_text(
            'CVC_LLM_PROVIDER=ollama\nCVC_LLM_MODEL=synthetic\n', encoding='utf-8'
        )
        self.taxonomy_path = self.root / 'taxonomy.json'
        taxonomy = {
            key: {row['code']: row['label'] for row in TAXONOMY[kind]}
            for kind, key in (
                ('FUNCTION', 'functions'),
                ('SENIORITY', 'seniority'),
                ('INDUSTRY', 'industries'),
                ('SCOPE', 'scopes'),
            )
        }
        self.taxonomy_path.write_text(json.dumps(taxonomy), encoding='utf-8')

    def write_gold(self, value, path=None):
        (path or self.path).write_text(json.dumps(value), encoding='utf-8')

    @staticmethod
    def provider():
        provider = Mock()

        def generate(prompt, _schema, **_options):
            items = json.loads(prompt.split('ITEMS=')[1])
            results = []
            for item in items:
                if item['kind'] == 'COMPANY':
                    value = {
                        'name': item['raw'].split(' ')[0] + ' ' + item['raw'].split(' ')[1],
                        'parent': None,
                        'industry': 'RETAIL',
                        'proposedLabel': None,
                    }
                    results.append({'id': item['id'], 'company': value})
                else:
                    results.append(
                        {
                            'id': item['id'],
                            'title': {
                                'functions': ['SALES'],
                                'seniority': 'MANAGER',
                                'scope': 'REGION',
                                'proposedLabel': None,
                            },
                        }
                    )
            return json.dumps({'results': results})

        provider.generate.side_effect = generate
        return provider

    def run_bench(self, **options):
        return run_norm_bench(
            self.gold,
            ['synthetic=' + str(self.env_path)],
            provider_factory=lambda _: self.provider(),
            taxonomy_path=self.taxonomy_path,
            environ={},
            **options,
        )

    def assert_no_sources(self, output):
        for forbidden in (
            'May Lam',
            'Mây Lam',
            'Sao Tim',
            'Regional Sales Manager',
            'may lam',
            'sao tim',
            'contextCompany',
            'proposedLabel',
        ):
            self.assertNotIn(forbidden, output)

    def test_strict_loose_and_micro_metrics(self):
        report = self.run_bench()
        metrics = report['configurations'][0]['runs'][0]
        self.assertEqual(metrics['companies'], 2)
        self.assertEqual(metrics['titles'], 1)
        self.assertEqual(metrics['name_strict_accuracy'], 0)
        self.assertEqual(metrics['name_loose_accuracy'], 1)
        self.assertEqual(metrics['parent_accuracy'], 1)
        self.assertEqual(metrics['industry_accuracy'], 1)
        self.assertEqual(metrics['functions_micro_precision'], 1)
        self.assertEqual(metrics['functions_micro_recall'], 0.5)
        self.assertEqual(metrics['seniority_accuracy'], 1)
        self.assertEqual(metrics['scope_accuracy'], 1)
        self.assertEqual(metrics['invalid_items'], {})
        self.assertEqual(metrics['llm_calls'], 1)
        self.assertGreaterEqual(metrics['seconds'], 0)
        self.assert_no_sources(json.dumps(report))

    def test_invalid_items_remain_in_accuracy_and_recall_denominators(self):
        _, items, expected = load_gold(self.gold)
        results = [{'id': item['id'], 'error': 'LLM_INVALID_JSON'} for item in items]
        metrics = score(items, expected, results)
        self.assertEqual(metrics['invalid_items'], {'LLM_INVALID_JSON': 3})
        self.assertEqual(metrics['companies'], 2)
        self.assertEqual(metrics['functions_gold'], 2)
        self.assertEqual(metrics['functions_micro_recall'], 0)
        self.assertEqual(metrics['name_loose_accuracy'], 0)

    def test_deduplication_and_conflicting_gold_rejected(self):
        self.write_gold(GOLD, self.gold / 'duplicate.norm.json')
        self.assertEqual(len(load_gold(self.gold)[1]), 3)
        altered = json.loads(json.dumps(GOLD))
        altered['companies'][0]['name'] = 'Conflicting Synthetic Label'
        self.write_gold(altered, self.gold / 'duplicate.norm.json')
        with self.assertRaisesRegex(ValueError, '^INVALID_NORM_GOLD$'):
            load_gold(self.gold)

    def test_wrong_versions_shapes_and_codes_rejected_before_provider(self):
        for changed in (
            GOLD | {'schemaVersion': 'normalization-gold/999'},
            GOLD | {'titles': {}},
            GOLD | {'companies': [{'raw': 'Synthetic'}]},
            GOLD | {'titles': [GOLD['titles'][0] | {'functions': ['INVENTED']}]},
        ):
            self.write_gold(changed)
            factory = Mock()
            with self.assertRaisesRegex(ValueError, '^INVALID_NORM_GOLD$'):
                run_norm_bench(
                    self.gold,
                    ['synthetic=' + str(self.env_path)],
                    provider_factory=factory,
                    taxonomy_path=self.taxonomy_path,
                    environ={},
                )
            factory.assert_not_called()

    def test_repeat_and_batch_config_same_prompt_path_as_worker(self):
        with self.env_path.open('a', encoding='utf-8') as stream:
            stream.write('CVC_NORM_BATCH=1\n')
        report = self.run_bench(repeat=2)
        self.assertEqual(len(report['configurations'][0]['runs']), 2)
        self.assertTrue(all(run['llm_calls'] == 3 for run in report['configurations'][0]['runs']))
        self.assert_no_sources(json.dumps(report))

    def test_keep_raw_opt_in_and_summary_remains_aggregate(self):
        output = self.root / 'output'
        self.run_bench(output_dir=output, repo_root=self.root / 'repo')
        self.assertEqual(sorted(path.name for path in output.iterdir()), ['norm-summary.json'])
        self.assert_no_sources((output / 'norm-summary.json').read_text(encoding='utf-8'))
        output_raw = self.root / 'output-raw'
        self.run_bench(keep_raw=True, output_dir=output_raw, repo_root=self.root / 'repo')
        raw = json.loads((output_raw / 'config-1-run-1.raw.json').read_text(encoding='utf-8'))
        self.assertEqual(len(raw['calls']), 1)
        self.assertIn('May Lam', raw['calls'][0]['response'])
        with self.assertRaisesRegex(ValueError, '^NORM_OUTPUT_REQUIRED$'):
            self.run_bench(keep_raw=True)

    def test_cli_json_and_text_have_no_source_values(self):
        for flags in ([], ['--json']):
            output = io.StringIO()
            with (
                redirect_stdout(output),
                patch('cv_compass_llm.cli.create_provider', side_effect=lambda _: self.provider()),
                patch.dict('os.environ', {}, clear=True),
            ):
                result = main(
                    [
                        'bench-norm',
                        '--gold',
                        str(self.gold),
                        '--taxonomy',
                        str(self.taxonomy_path),
                        '--config',
                        'synthetic=' + str(self.env_path),
                        *flags,
                    ]
                )
            self.assertEqual(result, 0)
            self.assert_no_sources(output.getvalue())
            if flags:
                self.assertEqual(json.loads(output.getvalue())['promptVersion'], 'norm-1')
            else:
                self.assertIn('name_loose_accuracy=1.0', output.getvalue())

    def test_default_taxonomy_resolves_from_repo_not_cwd(self):
        with patch('os.getcwd', return_value=str(self.root)):
            taxonomy = load_taxonomy()
        self.assertIn('SALES', {row['code'] for row in taxonomy['FUNCTION']})
        self.assertNotIn('null', {row['code'] for row in taxonomy['SCOPE']})

    def test_config_errors_and_unsafe_output_are_content_free(self):
        for value in ('0', '51', 'FORBIDDEN'):
            with self.assertRaisesRegex(ValueError, '^INVALID_NORM_BATCH$'):
                run_norm_bench(
                    self.gold,
                    ['synthetic=' + str(self.env_path)],
                    provider_factory=Mock(),
                    taxonomy_path=self.taxonomy_path,
                    environ={'CVC_NORM_BATCH': value},
                )
        with self.assertRaisesRegex(ValueError, '^UNSAFE_OUTPUT$'):
            self.run_bench(output_dir=self.gold / 'output')

    def test_invalid_repeat_and_untrusted_config_keys(self):
        for repeat in (0, -1):
            with self.assertRaisesRegex(ValueError, '^BENCH_INVALID_REPEAT$'):
                self.run_bench(repeat=repeat)
        with self.env_path.open('a', encoding='utf-8') as stream:
            stream.write('CVC_LLM_BACKEND_URL=http://127.0.0.1\n')
        with self.assertRaisesRegex(ValueError, '^BENCH_INVALID_CONFIG_KEY$'):
            self.run_bench()

    def test_no_config_uses_environment_without_reading_dotenv(self):
        with patch('cv_compass_llm.norm_bench.load_config', return_value=config('').llm) as loader:
            report = run_norm_bench(
                self.gold,
                provider_factory=lambda _: self.provider(),
                taxonomy_path=self.taxonomy_path,
                environ={'CVC_LLM_PROVIDER': 'ollama', 'CVC_LLM_MODEL': 'synthetic'},
            )
        self.assertEqual(report['configurations'][0]['name'], 'default')
        self.assertEqual(loader.call_args.kwargs['_file_values'], {})

    def test_provider_failure_keeps_denominators_and_continues_following_batches(self):
        with self.env_path.open('a', encoding='utf-8') as stream:
            stream.write('CVC_NORM_BATCH=1\n')
        for code in ('OLLAMA_TIMEOUT', 'CONTEXT_OVERFLOW'):
            with self.subTest(code=code):
                provider = self.provider()
                valid_generate = provider.generate.side_effect

                def generate(prompt, schema, **options):
                    items = json.loads(prompt.split('ITEMS=')[1])
                    if items[0]['id'] == 1:
                        raise ProviderError(code)
                    return valid_generate(prompt, schema, **options)

                provider.generate.side_effect = generate
                report = run_norm_bench(
                    self.gold,
                    ['synthetic=' + str(self.env_path)],
                    provider_factory=lambda _: provider,
                    taxonomy_path=self.taxonomy_path,
                    environ={},
                )
                metrics = report['configurations'][0]['runs'][0]
                self.assertEqual(provider.generate.call_count, 3)
                self.assertEqual(metrics['invalid_items'], {'PROVIDER_ERROR': 1})
                self.assertEqual(metrics['companies'], 2)
                self.assertEqual(metrics['name_loose_accuracy'], 0.5)
                self.assertEqual(metrics['industry_accuracy'], 0.5)
                self.assertEqual(metrics['functions_gold'], 2)
                self.assertEqual(metrics['functions_micro_recall'], 0.5)
                self.assertEqual(metrics['seniority_accuracy'], 1)
                self.assert_no_sources(json.dumps(report))

    def test_context_guard_overflow_is_a_batch_error_and_keeps_all_denominators(self):
        with self.env_path.open('a', encoding='utf-8') as stream:
            stream.write('CVC_LLM_CTX_GUARD=true\nCVC_LLM_NUM_CTX=1\nCVC_NORM_BATCH=1\n')
        provider = Mock()
        report = run_norm_bench(
            self.gold,
            ['synthetic=' + str(self.env_path)],
            provider_factory=lambda _: provider,
            taxonomy_path=self.taxonomy_path,
            environ={},
        )
        provider.generate.assert_not_called()
        metrics = report['configurations'][0]['runs'][0]
        self.assertEqual(metrics['invalid_items'], {'PROVIDER_ERROR': 3})
        self.assertEqual(metrics['companies'], 2)
        self.assertEqual(metrics['titles'], 1)
        self.assertEqual(metrics['functions_gold'], 2)
        self.assertEqual(metrics['functions_micro_recall'], 0)


if __name__ == '__main__':
    unittest.main()
