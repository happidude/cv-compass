"""Offline extraction benchmarks with aggregate-only reports."""

import hashlib
import json
import os
import re
from collections import Counter
from collections.abc import Callable, Mapping
from dataclasses import replace
from pathlib import Path
from statistics import mean, median
from time import perf_counter
from typing import Literal

from .batch_io import input_directory, load_json, prepare_output
from .compare import compare
from .config import LlmConfig, load_bench_config
from .providers.base import LlmProvider

BENCH_ERRORS = {
    'BENCH_INVALID_CONFIG',
    'BENCH_DUPLICATE_CONFIG',
    'BENCH_INVALID_CONFIG_KEY',
    'BENCH_INVALID_REPEAT',
    'BENCH_INVALID_WARMUP',
    'BENCH_INVALID_PARALLEL',
}


def _configs(specifications: list[str], environ: Mapping[str, str]) -> list[tuple[str, LlmConfig]]:
    if not specifications:
        raise ValueError('BENCH_INVALID_CONFIG')
    parsed, names = [], set()
    for specification in specifications:
        name, separator, path = specification.partition('=')
        if not separator or not path.strip() or not re.fullmatch(r'[A-Za-z0-9_-]{1,32}', name):
            raise ValueError('BENCH_INVALID_CONFIG')
        # Case aliases share an output directory on Windows.
        if name.casefold() in names:
            raise ValueError('BENCH_DUPLICATE_CONFIG')
        names.add(name.casefold())
        parsed.append((name, Path(path)))
    return [(name, load_bench_config(path, environ)) for name, path in parsed]


def _numbers(value: dict, prefix: str = '') -> dict:
    result = {}
    for key, item in value.items():
        name = prefix + key
        if isinstance(item, dict):
            result.update(_numbers(item, name + '.'))
        elif type(item) in (int, float):
            result[name] = item
    return result


def _untimed(value: object) -> object:
    if isinstance(value, dict):
        return {
            key: _untimed(item)
            for key, item in value.items()
            if key not in ('seconds', 'metrics', 'llm_metrics')
        }
    if isinstance(value, list):
        return [_untimed(item) for item in value]
    return value


def _observations(directory: Path) -> tuple[list[float], dict[str, bytes]]:
    seconds, fingerprints = [], {}
    for suffix in ('.result.json', '.error.json'):
        for path in directory.glob('*' + suffix):
            payload = load_json(path, 'INVALID_DATA')
            if suffix == '.result.json':
                seconds.append(payload['seconds'])
            encoded = json.dumps(_untimed(payload), sort_keys=True, allow_nan=False).encode('utf-8')
            fingerprints[path.name.removesuffix(suffix)] = hashlib.sha256(encoded).digest()
    return seconds, fingerprints


def _timing(values: list[float]) -> dict:
    ordered = sorted(values)
    if not ordered:
        return {
            'seconds.count': 0,
            'seconds.average': None,
            'seconds.p50': None,
            'seconds.p90': None,
        }
    index = (len(ordered) - 1) * 0.9
    lower = int(index)
    p90 = ordered[lower] + (ordered[min(lower + 1, len(ordered) - 1)] - ordered[lower]) * (
        index - lower
    )
    return {
        'seconds.count': len(values),
        'seconds.average': mean(values),
        'seconds.p50': median(values),
        'seconds.p90': p90,
    }


def _aggregate(runs: list[dict], comparisons: list[dict], seconds: list[float]) -> dict:
    counts = Counter()
    for comparison in comparisons:
        counts.update(
            {
                key: value
                for key, value in _numbers(comparison).items()
                if key != 'average_seconds' and not key.endswith(('.precision', '.recall'))
            }
        )
    for kind in ('positions', 'companies', 'titles', 'companyPeriods'):
        for level in ('', 'loose.'):
            prefix = f'experiences.{kind}.' + level
            for metric, denominator in (('precision', 'predicted'), ('recall', 'gold')):
                counts[prefix + metric] = (
                    counts[prefix + 'matched'] / counts[prefix + denominator]
                    if counts[prefix + denominator]
                    else 0.0
                )
    for summary in runs:
        counts.update({key: summary[key] for key in ('files', 'ok', 'llm_calls')})
        counts['ctx_retry'] += summary.get('ctx_retry', 0)
        counts.update(
            {
                f'num_ctx_counts.{tier}': count
                for tier, count in summary.get('num_ctx_counts', {}).items()
            }
        )
        if 'fallbacks' in summary:
            counts['fallbacks'] += summary['fallbacks']
            counts.update(
                {
                    f'fallback_reasons.{code}': count
                    for code, count in summary['fallback_reasons'].items()
                }
            )
        for key in ('issues', 'errors'):
            counts.update({f'{key}.{code}': count for code, count in summary[key].items()})
    metrics = dict(counts) | _timing(seconds)
    for key in ('prompt_tokens', 'output_tokens', 'tok_s'):
        samples = [summary.get('llm_metrics', {}).get(key, {}) for summary in runs]
        count = sum(sample.get('count', 0) for sample in samples)
        total = sum(sample.get('total', 0) for sample in samples)
        metrics[key + '.count'] = count
        metrics[key + '.average'] = total / count if count else None
    return metrics


def run_bench(
    input_dir: str | Path,
    gold_dir: str | Path,
    output_dir: str | Path,
    configurations: list[str],
    *,
    provider_factory: Callable[[LlmConfig], LlmProvider],
    repeat: int = 1,
    warmup: int = 0,
    parallel: int = 1,
    environ: Mapping[str, str] | None = None,
    repo_root: Path | None | Literal['auto'] = 'auto',
) -> dict:
    """Reuse the run pipeline; exclude warmup and retain only numbers in the report."""
    from .cli import run_batch

    if type(repeat) is not int or repeat < 1:
        raise ValueError('BENCH_INVALID_REPEAT')
    if type(warmup) is not int or warmup < 0:
        raise ValueError('BENCH_INVALID_WARMUP')
    if type(parallel) is not int or not 1 <= parallel <= 16:
        raise ValueError('BENCH_INVALID_PARALLEL')
    configs = _configs(configurations, os.environ if environ is None else environ)
    source, gold = input_directory(input_dir), input_directory(gold_dir)
    target = Path(output_dir).resolve()
    # Gold is also an immutable input, including when it contains no confirmed CVs.
    if target == gold or gold in target.parents or target in gold.parents:
        raise ValueError('UNSAFE_OUTPUT')
    target = prepare_output(source, target, repo_root=repo_root)
    rows = []
    for name, config in configs:
        providers = [provider_factory(config) for _ in range(parallel)]
        if config.fallback_model:
            for local_provider in providers:
                if getattr(local_provider, 'fallback_provider', None) is None:
                    local_provider.fallback_provider = provider_factory(
                        replace(config, model=config.fallback_model, fallback_model='')
                    )
        provider = providers[0]
        options = {
            'max_blocks': config.max_blocks,
            'mode': config.mode,
            'whole_timeout': config.whole_timeout,
            'repo_root': repo_root,
            'config': config,
        }
        if parallel > 1:
            options['_providers'] = providers
        if warmup:
            run_batch(source, target / name / 'warmup', provider, limit=warmup, **options)
        runs, comparisons, seconds = [], [], []
        first, different = None, set()
        wall_seconds = 0.0
        for iteration in range(1, repeat + 1):
            directory = target / name / f'r{iteration}'
            started = perf_counter()
            runs.append(run_batch(source, directory, provider, **options))
            wall_seconds += perf_counter() - started
            comparisons.append(compare(directory, gold))
            elapsed, fingerprints = _observations(directory)
            seconds.extend(elapsed)
            if first is None:
                first = fingerprints
            else:
                different.update(
                    key
                    for key in first.keys() | fingerprints.keys()
                    if first.get(key) != fingerprints.get(key)
                )
        metrics = _aggregate(runs, comparisons, seconds)
        metrics['different_cvs'] = len(different)
        metrics['throughput_cv_per_min'] = (
            metrics['ok'] * 60 / wall_seconds if wall_seconds > 0 else None
        )
        rows.append({'name': name, 'metrics': metrics})
    keys = sorted(set().union(*(row['metrics'] for row in rows)))
    baseline = rows[0]['metrics']
    for row in rows:
        row['metrics'] = {key: row['metrics'].get(key, 0) for key in keys}
        row['delta'] = {
            key: (
                value - baseline.get(key, 0)
                if value is not None and baseline.get(key, 0) is not None
                else None
            )
            for key, value in row['metrics'].items()
        }
    return {'configurations': rows}


def print_bench(report: dict) -> None:
    """Render the same numeric cells as JSON; missing measurements use a dash."""
    rows = report['configurations']
    print('metric\t' + '\t'.join(f'{row["name"]}\tΔ\t{row["name"]} loose\tΔ' for row in rows))
    for key in rows[0]['metrics']:
        if '.loose.' in key:
            continue
        prefix, separator, metric = key.rpartition('.')
        loose = prefix + separator + 'loose.' + metric
        cells = [key]
        for row in rows:
            for value in (
                row['metrics'][key],
                row['delta'][key],
                row['metrics'].get(loose),
                row['delta'].get(loose),
            ):
                cells.append('-' if value is None else format(value, '.8g'))
        print('\t'.join(cells))
