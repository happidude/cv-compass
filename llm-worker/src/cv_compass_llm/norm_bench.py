"""Offline normalization gold scoring with aggregate-only output."""

import os
import re
import unicodedata
from collections import Counter
from pathlib import Path
from time import perf_counter

from .batch_io import input_directory, load_json, prepare_output, write_json
from .config import (
    BENCH_ENV_KEYS,
    ENV_KEYS,
    _integer,
    _read_env,
    load_config,
)
from .norm_prompts import PROMPT_VERSION
from .normalize import normalize_batch, validate_input
from .providers.base import ProviderError

BENCH_ERRORS = {'INVALID_NORM_GOLD', 'INVALID_NORM_TAXONOMY', 'NORM_OUTPUT_REQUIRED'}
GOLD_VERSION = 'normalization-gold/0-draft'


def load_taxonomy(path=None):
    """Adapt the repository's offline copy to the claim table shape."""
    if path is None:
        path = (
            Path(__file__).resolve().parents[3]
            / 'backend/src/main/resources/catalog/taxonomy-v0.json'
        )
    root = load_json(path, 'INVALID_NORM_TAXONOMY')
    try:
        taxonomy = {
            kind: [
                {'code': code, 'label': label}
                for code, label in root[key].items()
                if code != 'null'
            ]
            for kind, key in (
                ('FUNCTION', 'functions'),
                ('SENIORITY', 'seniority'),
                ('INDUSTRY', 'industries'),
                ('SCOPE', 'scopes'),
            )
        }
        validate_input([{'id': 1, 'kind': 'COMPANY', 'raw': 'Synthetic'}], taxonomy)
        return taxonomy
    except ValueError, TypeError, KeyError, AttributeError:
        raise ValueError('INVALID_NORM_TAXONOMY') from None


def load_gold(directory):
    """Deduplicate exact raw strings per kind, rejecting contradictory gold entries."""
    source = input_directory(directory)
    entries = {}
    try:
        paths = sorted(source.glob('*.norm.json'))
        if not paths:
            raise ValueError
        for path in paths:
            if path.is_symlink() or not path.is_file():
                raise ValueError
            root = load_json(path, 'INVALID_NORM_GOLD')
            if (
                not isinstance(root, dict)
                or root.get('schemaVersion') != GOLD_VERSION
                or set(root) != {'schemaVersion', 'companies', 'titles'}
            ):
                raise ValueError
            for kind, key in (('COMPANY', 'companies'), ('TITLE', 'titles')):
                if not isinstance(root[key], list):
                    raise ValueError
                for row in root[key]:
                    required = (
                        {'raw', 'name', 'parent', 'industry'}
                        if kind == 'COMPANY'
                        else {'raw', 'functions', 'seniority', 'scope'}
                    )
                    if (
                        not isinstance(row, dict)
                        or not required <= set(row)
                        or set(row) - required - ({'contextCompany'} if kind == 'TITLE' else set())
                        or not isinstance(row['raw'], str)
                        or not row['raw'].strip()
                    ):
                        raise ValueError
                    if kind == 'COMPANY':
                        if (
                            not isinstance(row['name'], str)
                            or not row['name'].strip()
                            or not isinstance(row['industry'], str)
                            or (row['parent'] is not None and not isinstance(row['parent'], str))
                        ):
                            raise ValueError
                    elif (
                        not isinstance(row['functions'], list)
                        or not 1 <= len(row['functions']) <= 2
                        or any(not isinstance(code, str) for code in row['functions'])
                        or len(set(row['functions'])) != len(row['functions'])
                        or not isinstance(row['seniority'], str)
                        or (row['scope'] is not None and not isinstance(row['scope'], str))
                        or ('contextCompany' in row and not isinstance(row['contextCompany'], str))
                    ):
                        raise ValueError
                    identity = kind, row['raw']
                    if identity in entries and entries[identity] != row:
                        raise ValueError
                    entries[identity] = row
    except ValueError, TypeError, KeyError:
        raise ValueError('INVALID_NORM_GOLD') from None
    items, expected = [], {}
    for index, ((kind, raw), row) in enumerate(entries.items(), 1):
        item = {'id': index, 'kind': kind, 'raw': raw}
        if 'contextCompany' in row:
            item['contextCompany'] = row['contextCompany']
        items.append(item)
        expected[index] = row
    return source, items, expected


def _fold(value):
    text = unicodedata.normalize('NFD', value.casefold().replace('đ', 'd'))
    return ' '.join(''.join(char for char in text if unicodedata.category(char) != 'Mn').split())


def score(items, expected, results):
    counts = Counter()
    errors = Counter()
    by_id = {row['id']: row for row in results}
    for item in items:
        gold = expected[item['id']]
        result = by_id.get(item['id'], {'error': 'MISSING_RESULT'})
        company = item['kind'] == 'COMPANY'
        counts['companies' if company else 'titles'] += 1
        if not company:
            counts['functions_gold'] += len(gold['functions'])
        if 'error' in result:
            errors[result['error']] += 1
            continue
        value = result['company' if company else 'title']
        if company:
            counts['name_strict_matched'] += value['name'] == gold['name']
            counts['name_loose_matched'] += _fold(value['name']) == _fold(gold['name'])
            counts['parent_matched'] += value['parent'] == gold['parent']
            counts['industry_matched'] += value['industry'] == gold['industry']
        else:
            counts['functions_predicted'] += len(value['functions'])
            counts['functions_matched'] += len(set(value['functions']) & set(gold['functions']))
            counts['seniority_matched'] += value['seniority'] == gold['seniority']
            counts['scope_matched'] += value['scope'] == gold['scope']
    metrics = {
        key: counts[key]
        for key in (
            'companies',
            'titles',
            'name_strict_matched',
            'name_loose_matched',
            'parent_matched',
            'industry_matched',
            'functions_gold',
            'functions_predicted',
            'functions_matched',
            'seniority_matched',
            'scope_matched',
        )
    }
    for field, denominator in (
        ('name_strict', 'companies'),
        ('name_loose', 'companies'),
        ('parent', 'companies'),
        ('industry', 'companies'),
        ('seniority', 'titles'),
        ('scope', 'titles'),
    ):
        metrics[field + '_accuracy'] = (
            counts[field + '_matched'] / counts[denominator] if counts[denominator] else 0.0
        )
    for field, denominator in (('precision', 'functions_predicted'), ('recall', 'functions_gold')):
        metrics['functions_micro_' + field] = (
            counts['functions_matched'] / counts[denominator] if counts[denominator] else 0.0
        )
    return metrics | {'invalid_items': dict(errors)}


def _configurations(specifications, environ):
    if not specifications:
        specifications = [None]
    configurations, names = [], set()
    for specification in specifications:
        if specification is None:
            name, values = 'default', {}
        else:
            name, separator, path = specification.partition('=')
            if not separator or not path.strip() or not re.fullmatch(r'[A-Za-z0-9_-]{1,32}', name):
                raise ValueError('BENCH_INVALID_CONFIG')
            values = _read_env(Path(path), True, allowed_keys=BENCH_ENV_KEYS | {'CVC_NORM_BATCH'})
        if name.casefold() in names:
            raise ValueError('BENCH_DUPLICATE_CONFIG')
        names.add(name.casefold())
        batch = _integer(
            values.get('CVC_NORM_BATCH', environ.get('CVC_NORM_BATCH', '50')),
            'INVALID_NORM_BATCH',
            minimum=1,
            maximum=50,
        )
        llm = load_config(
            {key: values.get(env) for key, env in ENV_KEYS.items()},
            environ,
            None,
            env_file_explicit=False,
            _file_values={},
        )
        configurations.append((name, llm, batch))
    return configurations


def run_norm_bench(
    gold_dir,
    configurations=None,
    *,
    provider_factory,
    repeat=1,
    taxonomy_path=None,
    output_dir=None,
    keep_raw=False,
    environ=None,
    repo_root='auto',
):
    if type(repeat) is not int or repeat < 1:
        raise ValueError('BENCH_INVALID_REPEAT')
    if keep_raw and output_dir is None:
        raise ValueError('NORM_OUTPUT_REQUIRED')
    configs = _configurations(configurations, os.environ if environ is None else environ)
    source, items, expected = load_gold(gold_dir)
    taxonomy = load_taxonomy(taxonomy_path)
    for item in items:
        gold = expected[item['id']]
        checks = (
            [('INDUSTRY', gold['industry'])]
            if item['kind'] == 'COMPANY'
            else [('FUNCTION', code) for code in gold['functions']]
            + [('SENIORITY', gold['seniority'])]
            + ([('SCOPE', gold['scope'])] if gold['scope'] is not None else [])
        )
        if any(code not in {row['code'] for row in taxonomy[kind]} for kind, code in checks):
            raise ValueError('INVALID_NORM_GOLD')
    target = (
        prepare_output(source, output_dir, repo_root=repo_root) if output_dir is not None else None
    )
    report = {'promptVersion': PROMPT_VERSION, 'configurations': []}
    for config_index, (name, config, batch) in enumerate(configs, 1):
        provider = provider_factory(config)
        runs = []
        for iteration in range(1, repeat + 1):
            results, raw_calls = [], []
            calls = 0

            def capture(raw):
                nonlocal calls
                calls += 1
                if keep_raw:
                    raw_calls.append({'response': raw})

            started = perf_counter()
            for offset in range(0, len(items), batch):
                batch_items = items[offset : offset + batch]
                try:
                    results.extend(
                        normalize_batch(batch_items, taxonomy, provider, config, on_raw=capture)
                    )
                except ProviderError:
                    results.extend(
                        {'id': item['id'], 'error': 'PROVIDER_ERROR'} for item in batch_items
                    )
            metrics = score(items, expected, results)
            metrics.update(seconds=perf_counter() - started, llm_calls=calls)
            runs.append(metrics)
            if keep_raw:
                write_json(
                    target / f'config-{config_index}-run-{iteration}.raw.json', {'calls': raw_calls}
                )
        report['configurations'].append({'name': name, 'runs': runs})
    if target is not None:
        write_json(target / 'norm-summary.json', report)
    return report
