"""API cost metrics and factory limits using synthetic openers only."""

import json
import unittest
from types import SimpleNamespace
from unittest.mock import patch

import test_l7_api_providers as api

from cv_compass_llm.bench import _aggregate
from cv_compass_llm.cli import _MetricsProvider, create_provider, main
from cv_compass_llm.lifecycle import call_metrics, metric_summary
from cv_compass_llm.providers.base import ProviderError
from cv_compass_llm.providers.openai_api import OpenAIProvider

TOKEN_KEYS = (
    'prompt_tokens',
    'output_tokens',
    'reasoning_tokens',
    'cached_input_tokens',
    'cache_write_tokens',
)


def usage(provider_type, value=7):
    result = {'input_tokens': 20, 'output_tokens': 10}
    if provider_type is OpenAIProvider:
        result.update(
            output_tokens_details={'reasoning_tokens': value},
            input_tokens_details={'cached_tokens': value},
        )
    else:
        result.update(cache_read_input_tokens=value, cache_creation_input_tokens=value)
    return result


def expected_usage(provider_type, value=7):
    result = {'prompt_tokens': 20, 'output_tokens': 10, 'cached_input_tokens': value}
    result['reasoning_tokens' if provider_type is OpenAIProvider else 'cache_write_tokens'] = value
    return result


def tokens(metrics):
    return {key: value for key, value in metrics.items() if key in TOKEN_KEYS}


class ApiUsageTests(unittest.TestCase):
    def test_usage_requires_text_or_token_limit_completion(self):
        for provider_type in api.TYPES:
            for length in (False, True):
                for text in ('{}', '{', ''):
                    with self.subTest(provider=provider_type, length=length, text=text):
                        body = api.envelope(
                            provider_type, text, length=length, usage=usage(provider_type)
                        )
                        body['usage']['arbitrary'] = 'PRIVATE_SENTINEL'
                        opener = api.Opener(api.Response(body))
                        provider = provider_type('synthetic', api_key=api.KEY, _opener=opener)
                        observed = _MetricsProvider(provider)
                        if text:
                            self.assertEqual(observed.generate('synthetic', {}), text)
                            self.assertEqual(
                                provider.last_metrics['done_reason'], 'length' if length else 'stop'
                            )
                        else:
                            with self.assertRaisesRegex(ProviderError, '^API_BAD_RESPONSE$'):
                                observed.generate('synthetic', {})
                        expected = expected_usage(provider_type) if text or length else {}
                        self.assertEqual(tokens(provider.last_metrics), expected)
                        self.assertEqual(tokens(observed.calls[0]), expected)
                        self.assertIn('total_s', observed.calls[0])
                        self.assertTrue(
                            all(type(value) in (int, float) for value in observed.calls[0].values())
                        )
                        self.assertNotIn('PRIVATE_SENTINEL', json.dumps(observed.calls))
                        summary = metric_summary(observed.calls)
                        for key in TOKEN_KEYS:
                            self.assertEqual(summary[key]['count'], int(key in expected))
                            self.assertEqual(summary[key]['total'], expected.get(key, 0))

    def test_refusal_malformed_and_other_incomplete_responses_do_not_record_tokens(self):
        for provider_type in api.TYPES:
            refusal = api.envelope(provider_type, usage=usage(provider_type))
            malformed = api.envelope(provider_type, usage=usage(provider_type))
            if provider_type is OpenAIProvider:
                refusal['output'][1]['content'].append({'type': 'refusal'})
                malformed['output'] = None
            else:
                refusal['stop_reason'] = 'refusal'
                malformed['content'] = None
            bodies = [refusal, malformed]
            if provider_type is OpenAIProvider:
                incomplete = api.envelope(provider_type, length=True, usage=usage(provider_type))
                incomplete['incomplete_details']['reason'] = 'other'
                bodies.append(incomplete)
            for index, body in enumerate(bodies):
                with self.subTest(provider=provider_type, case=index):
                    opener = api.Opener(api.Response(body))
                    provider = provider_type('synthetic', api_key=api.KEY, _opener=opener)
                    observed = _MetricsProvider(provider)
                    with self.assertRaisesRegex(ProviderError, '^API_BAD_RESPONSE$'):
                        observed.generate('synthetic', {})
                    self.assertEqual(tokens(provider.last_metrics), {})
                    self.assertEqual(tokens(observed.calls[0]), {})
                    self.assertIn('total_s', observed.calls[0])

    def test_reasoning_and_cache_measurements_are_independently_optional(self):
        for provider_type, variants in (
            (
                OpenAIProvider,
                (
                    ({'output_tokens_details': {'reasoning_tokens': 4}}, {'reasoning_tokens': 4}),
                    ({'input_tokens_details': {'cached_tokens': 5}}, {'cached_input_tokens': 5}),
                ),
            ),
            (
                api.AnthropicProvider,
                (
                    ({'cache_read_input_tokens': 6}, {'cached_input_tokens': 6}),
                    ({'cache_creation_input_tokens': 8}, {'cache_write_tokens': 8}),
                ),
            ),
        ):
            for source, expected in variants:
                opener = api.Opener(api.Response(api.envelope(provider_type, usage=source)))
                provider = provider_type('synthetic', api_key=api.KEY, _opener=opener)
                provider.generate('synthetic', {})
                self.assertEqual(tokens(provider.last_metrics), expected)

    def test_only_bounded_integers_are_recorded_and_optional_metrics_reset(self):
        for provider_type in api.TYPES:
            for value in (0, 2**53, -1, 2**53 + 1, True, 1.5, '7', None, {}, []):
                with self.subTest(provider=provider_type, value=value):
                    opener = api.Opener(
                        api.Response(api.envelope(provider_type, usage=usage(provider_type, value)))
                    )
                    provider = provider_type('synthetic', api_key=api.KEY, _opener=opener)
                    provider.generate('synthetic', {})
                    expected = (
                        expected_usage(provider_type, value)
                        if type(value) is int and 0 <= value <= 2**53
                        else {'prompt_tokens': 20, 'output_tokens': 10}
                    )
                    self.assertEqual(tokens(provider.last_metrics), expected)
            for details in (None, [], 'PRIVATE_SENTINEL', {'reasoning_tokens': True}):
                body = api.envelope(
                    provider_type,
                    usage={
                        'input_tokens': 20,
                        'output_tokens': 10,
                        'input_tokens_details': details,
                        'output_tokens_details': details,
                    },
                )
                provider = provider_type(
                    'synthetic', api_key=api.KEY, _opener=api.Opener(api.Response(body))
                )
                provider.generate('synthetic', {})
                self.assertEqual(
                    tokens(provider.last_metrics), {'prompt_tokens': 20, 'output_tokens': 10}
                )
            missing = api.envelope(provider_type)
            missing.pop('usage')
            opener = api.Opener(
                api.Response(api.envelope(provider_type, usage=usage(provider_type))),
                api.Response(api.envelope(provider_type)),
                api.Response(missing),
                TimeoutError('PRIVATE_SENTINEL'),
            )
            provider = provider_type('synthetic', api_key=api.KEY, _opener=opener)
            observed = _MetricsProvider(provider)
            observed.generate('synthetic', {})
            observed.generate('synthetic', {})
            self.assertEqual(
                tokens(provider.last_metrics), {'prompt_tokens': 12, 'output_tokens': 3}
            )
            observed.generate('synthetic', {})
            self.assertEqual(tokens(provider.last_metrics), {})
            with self.assertRaisesRegex(ProviderError, '^API_TIMEOUT$'):
                observed.generate('synthetic', {})
            self.assertEqual(tokens(provider.last_metrics), {})
            self.assertEqual(tokens(observed.calls[-1]), {})
            totals = metric_summary(observed.calls)
            self.assertEqual(totals['prompt_tokens']['count'], 2)
            self.assertEqual(totals['prompt_tokens']['total'], 32)
            self.assertEqual(totals['cached_input_tokens']['count'], 1)

    def test_projection_and_pooled_bench_totals_use_measured_denominators(self):
        for value in (0, 2**53, -1, 2**53 + 1, True, 1.5, '7', None):
            raw = dict.fromkeys(TOKEN_KEYS, value) | {'provider': 'PRIVATE_SENTINEL'}
            expected = (
                dict.fromkeys(TOKEN_KEYS, value)
                if type(value) is int and 0 <= value <= 2**53
                else {}
            )
            self.assertEqual(call_metrics(raw), expected)
        summaries = [
            metric_summary([dict.fromkeys(TOKEN_KEYS, 10), {'prompt_tokens': 30}]),
            metric_summary([dict.fromkeys(TOKEN_KEYS, 20), {}]),
        ]
        runs = [
            dict(files=2, ok=1, llm_calls=2, issues={}, errors={}, llm_metrics=summary)
            for summary in summaries
        ]
        metrics = _aggregate(runs, [], [])
        for key in TOKEN_KEYS:
            total, count = (60, 3) if key == 'prompt_tokens' else (30, 2)
            self.assertEqual(metrics[key + '.count'], count)
            self.assertEqual(metrics[key + '.total'], total)
            self.assertEqual(metrics[key + '.average'], total / count)
        empty = _aggregate([], [], [])
        for key in TOKEN_KEYS:
            self.assertEqual(empty[key + '.count'], 0)
            self.assertEqual(empty[key + '.total'], 0)
            self.assertIsNone(empty[key + '.average'])


class ApiDefaultLimitTests(unittest.TestCase):
    def test_factory_payload_defaults_overrides_and_fallback(self):
        for name, provider_type, key in (
            ('openai', OpenAIProvider, 'max_output_tokens'),
            ('anthropic', api.AnthropicProvider, 'max_tokens'),
        ):
            for configured in (None, 1234):
                for default in (4000, 2000):
                    with self.subTest(provider=name, configured=configured, default=default):
                        settings = api.config(
                            name,
                            max_output_tokens=str(configured) if configured is not None else None,
                            fallback_model='synthetic-backup',
                        )
                        opener = api.Opener(
                            *[api.Response(api.envelope(provider_type)) for _ in range(2)]
                        )
                        with patch(
                            'cv_compass_llm.providers.openai_api.build_opener', return_value=opener
                        ):
                            provider = (
                                create_provider(settings)
                                if default == 4000
                                else create_provider(settings, default_max_output_tokens=default)
                            )
                        provider.generate('synthetic', {})
                        provider.fallback_provider.generate('synthetic', {})
                        for request, _ in opener.requests:
                            self.assertEqual(json.loads(request.data)[key], configured or default)

    def test_normalize_main_selects_2000_and_preserves_explicit_limit(self):
        for name, provider_type, key in (
            ('openai', OpenAIProvider, 'max_output_tokens'),
            ('anthropic', api.AnthropicProvider, 'max_tokens'),
        ):
            for configured in (None, 1234):
                settings = api.config(
                    name, max_output_tokens=str(configured) if configured is not None else None
                )
                config = SimpleNamespace(transport=SimpleNamespace(llm=settings))
                opener = api.Opener(api.Response(api.envelope(provider_type)))

                def run(config, provider, *, once):
                    self.assertTrue(once)
                    provider.generate('synthetic', {})
                    return 0

                with (
                    patch('cv_compass_llm.cli.load_norm_config', return_value=config),
                    patch('cv_compass_llm.cli.run_normalize', side_effect=run),
                    patch('cv_compass_llm.providers.openai_api.build_opener', return_value=opener),
                ):
                    self.assertEqual(main(['normalize', '--once']), 0)
                self.assertEqual(json.loads(opener.requests[0][0].data)[key], configured or 2000)

    def test_ollama_never_receives_api_default(self):
        for default in (4000, 2000):
            with patch('cv_compass_llm.cli.OllamaProvider') as constructor:
                create_provider(api.config('ollama'), default_max_output_tokens=default)
            self.assertNotIn('num_predict', constructor.call_args.kwargs)
            with patch('cv_compass_llm.cli.OllamaProvider') as constructor:
                create_provider(
                    api.config('ollama', max_output_tokens='1234'),
                    default_max_output_tokens=default,
                )
            self.assertEqual(constructor.call_args.kwargs['num_predict'], 1234)
            opener = api.Opener(api.Response({'message': {'content': '{}'}}))
            with patch('cv_compass_llm.providers.ollama.build_opener', return_value=opener):
                provider = create_provider(api.config('ollama'), default_max_output_tokens=default)
            provider.generate('synthetic', {})
            self.assertNotIn('num_predict', json.loads(opener.requests[0][0].data)['options'])


if __name__ == '__main__':
    unittest.main()
