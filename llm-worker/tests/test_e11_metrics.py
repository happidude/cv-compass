"""Provider telemetry, pull privacy and every-call CLI aggregation."""

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import test_ollama
import test_progress_check
from test_pull import NULL_PERSONAL, Backend

from cv_compass_llm.cli import run_batch
from cv_compass_llm.lifecycle import call_metrics
from cv_compass_llm.providers import FakeProvider, ProviderError
from cv_compass_llm.schema import ExtractionResult

METRICS = {
    'prompt_tokens': 100,
    'output_tokens': 40,
    'prompt_ns': 2_000_000_000,
    'output_ns': 8_000_000_000,
    'load_ns': 500_000_000,
    'total_ns': 10_500_000_000,
    'done_reason': 'PRIVATE_SENTINEL',
    'arbitrary': 'PRIVATE_SENTINEL',
}


class MetricsTests(unittest.TestCase):
    def test_metrics_projection_has_only_fixed_numeric_keys(self):
        self.assertEqual(
            call_metrics(METRICS),
            {
                'prompt_tokens': 100,
                'output_tokens': 40,
                'prompt_s': 2,
                'output_s': 8,
                'load_s': 0.5,
                'total_s': 10.5,
                'tok_s': 5,
            },
        )
        self.assertEqual(
            call_metrics({'output_tokens': 0, 'output_ns': 0}),
            {'output_tokens': 0, 'output_s': 0},
        )
        for raw in (
            None,
            [],
            {'output_tokens': True},
            {'output_ns': -1},
            {'output_ns': float('inf')},
            {'output_tokens': 'PRIVATE_SENTINEL'},
        ):
            self.assertEqual(call_metrics(raw), {})

    def test_fake_resets_metrics_on_missing_measurements_and_failure(self):
        provider = FakeProvider([{}, {}, ProviderError('SYNTHETIC')], metrics=[METRICS, {}])
        provider.generate('synthetic', {})
        self.assertEqual(provider.last_metrics, METRICS)
        provider.generate('synthetic', {})
        self.assertEqual(provider.last_metrics, {})
        with self.assertRaises(ProviderError):
            provider.generate('synthetic', {})
        self.assertEqual(provider.last_metrics, {})

    def test_fake_metrics_appear_in_pull_progress_without_free_text(self):
        backend = Backend()
        backend.input = {'pages': [{'page': 1, 'raw_text': 'Synthetic Person\nExperience\n'}]}
        _, _, stderr, _ = test_progress_check.ProgressTests().run_worker(
            backend, FakeProvider(NULL_PERSONAL, metrics=METRICS)
        )
        for field in (
            'prompt_tokens=100',
            'output_tokens=40',
            'prompt_s=2.000',
            'output_s=8.000',
            'load_s=0.500',
            'tok_s=5.00',
        ):
            self.assertIn(field, stderr)
        self.assertNotIn('PRIVATE_SENTINEL', stderr)

    def test_pull_metrics_use_fixed_decimal_precision(self):
        backend = Backend()
        backend.input = {'pages': [{'page': 1, 'raw_text': 'Synthetic Person\nExperience\n'}]}
        metrics = dict(
            METRICS,
            prompt_ns=123_456_789,
            output_ns=3_000_000_000,
            load_ns=999_600_000,
            output_tokens=7,
        )
        _, _, stderr, _ = test_progress_check.ProgressTests().run_worker(
            backend, FakeProvider(NULL_PERSONAL, metrics=metrics)
        )
        for field in ('prompt_s=0.123', 'output_s=3.000', 'load_s=1.000', 'tok_s=2.33'):
            self.assertIn(field, stderr.split())

    def test_real_ollama_response_reaches_pull_log_and_missing_metrics_are_optional(
        self,
    ):
        transport = test_ollama.OllamaTests()
        transport.setUp()
        self.addCleanup(transport.doCleanups)
        for measured in (True, False):
            response = {'message': {'content': json.dumps(NULL_PERSONAL)}}
            if measured:
                response.update(
                    prompt_eval_count=100,
                    eval_count=40,
                    prompt_eval_duration=2_000_000_000,
                    eval_duration=8_000_000_000,
                    load_duration=500_000_000,
                )
            transport.server.body = json.dumps(response).encode()
            backend = Backend()
            backend.input = {'pages': [{'page': 1, 'raw_text': 'Synthetic Person\nExperience\n'}]}
            _, _, stderr, _ = test_progress_check.ProgressTests().run_worker(
                backend, transport.provider()
            )
            self.assertIn('event=extraction_block', stderr)
            if measured:
                self.assertIn('prompt_tokens=100', stderr)
                self.assertIn('output_tokens=40', stderr)
                self.assertIn('tok_s=5.00', stderr)
            else:
                self.assertNotIn('prompt_tokens=', stderr)

    def test_batch_observes_all_calls_and_uses_measured_denominators(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repo, source, target = (root / name for name in ('repo', 'input', 'output'))
            repo.mkdir()
            (repo / '.git').mkdir()
            source.mkdir()
            (source / 'synthetic.json').write_text(
                json.dumps(
                    {'pages': [{'page_number': 1, 'status': 'ok', 'raw_text': 'Synthetic'}]}
                ),
                encoding='utf-8',
            )
            provider = FakeProvider([{}, {}, {}], metrics=[METRICS, {}, {'output_tokens': 20}])

            def extraction(document, observed, **options):
                for _ in range(3):
                    observed.generate('synthetic', {})
                return ExtractionResult((), (), (), 'synthetic', observed.name)

            with patch('cv_compass_llm.cli.extract', side_effect=extraction):
                summary = run_batch(source, target, provider, repo_root=repo)
            metrics = summary['llm_metrics']
            self.assertEqual(summary['llm_calls'], 3)
            self.assertEqual(metrics['output_tokens']['count'], 2)
            self.assertEqual(metrics['output_tokens']['total'], 60)
            self.assertEqual(metrics['output_tokens']['average'], 30)
            self.assertEqual(metrics['output_s']['count'], 1)
            self.assertEqual(metrics['tok_s']['average'], 5)
            saved = json.loads((target / 'run-summary.json').read_text(encoding='utf-8'))
            self.assertEqual(saved, summary)
            self.assertNotIn('PRIVATE_SENTINEL', json.dumps(saved))
