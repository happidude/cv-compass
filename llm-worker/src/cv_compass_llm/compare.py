"""Aggregate-only comparison against explicitly confirmed gold."""

from collections import Counter
from collections.abc import Callable
from pathlib import Path

from .batch_io import input_directory
from .gold import (
    PERSONAL_FIELDS,
    company_period,
    effective_period,
    group_experiences,
    load_gold,
    normalize,
    normalize_loose,
    result_values,
)


def experience_keys(
    items: list[dict],
    kind: str,
    normalizer: Callable[[str | None], str | None] = normalize,
) -> Counter:
    """Build a multiset so duplicate predictions cannot inflate matches."""
    keys = []
    for item in items:
        company = normalizer(item['company'])
        if company is None:
            continue
        period = company_period(item)
        if kind == 'companies':
            keys.append(company)
        elif kind == 'companyPeriods':
            if period != (None, None, False):
                keys.append((company, *period))
        else:
            for position in item['positions']:
                title = normalizer(position['title'])
                if title is not None:
                    keys.append(
                        (company, title)
                        if kind == 'titles'
                        else (company, title, effective_period(position, period))
                    )
    return Counter(keys)


def compare(results: str | Path, gold: str | Path) -> dict:
    """Return only aggregate metrics for paired CONFIRMED documents."""
    source, references = input_directory(results), input_directory(gold)
    personal = {
        key: dict.fromkeys(('correct', 'wrong', 'missing', 'extra'), 0) for key in PERSONAL_FIELDS
    }
    experiences = {
        kind: {'matched': 0, 'predicted': 0, 'gold': 0}
        for kind in ('companies', 'companyPeriods', 'titles', 'positions')
    }
    for counts in personal.values():
        counts['loose'] = dict.fromkeys(('correct', 'wrong', 'missing', 'extra'), 0)
    for counts in experiences.values():
        counts['loose'] = {'matched': 0, 'predicted': 0, 'gold': 0}
    summary = {
        'compared': 0,
        'skipped_draft': 0,
        'missing_results': 0,
        'unpaired_results': 0,
        'invalid_gold': 0,
        'invalid_results': 0,
        'personal': personal,
        'experiences': experiences,
        'average_seconds': 0.0,
        'companyLocation': 'NOT_SCORED',
    }
    gold_paths = sorted(references.glob('*.gold.json'))
    summary['unpaired_results'] = sum(
        not (references / (path.name.removesuffix('.result.json') + '.gold.json')).is_file()
        for path in source.glob('*.result.json')
        if path.is_file()
    )
    for path in gold_paths:
        try:
            reference = load_gold(path)
        except ValueError:
            summary['invalid_gold'] += 1
            continue
        if reference['status'] == 'DRAFT':
            summary['skipped_draft'] += 1
            continue
        result = source / (path.name.removesuffix('.gold.json') + '.result.json')
        if not result.is_file():
            summary['missing_results'] += 1
            continue
        try:
            fields, records, seconds = result_values(result)
        except ValueError:
            summary['invalid_results'] += 1
            continue
        summary['compared'] += 1
        records = group_experiences(records)
        summary['average_seconds'] += (seconds - summary['average_seconds']) / summary['compared']
        for key in PERSONAL_FIELDS:
            for counts, normalizer in (
                (personal[key], normalize),
                (personal[key]['loose'], normalize_loose),
            ):
                expected = normalizer(reference['personal'][key])
                actual = normalizer(fields[key])
                if expected == actual:
                    category = 'correct'
                elif expected is None:
                    category = 'extra'
                elif actual is None:
                    category = 'missing'
                else:
                    category = 'wrong'
                counts[category] += 1
        for kind, counts in experiences.items():
            for level, normalizer in ((counts, normalize), (counts['loose'], normalize_loose)):
                expected = experience_keys(reference['experiences'], kind, normalizer)
                actual = experience_keys(records, kind, normalizer)
                level['matched'] += sum((expected & actual).values())
                level['predicted'] += actual.total()
                level['gold'] += expected.total()
    for counts in experiences.values():
        for level in (counts, counts['loose']):
            level['precision'] = (
                level['matched'] / level['predicted'] if level['predicted'] else 0.0
            )
            level['recall'] = level['matched'] / level['gold'] if level['gold'] else 0.0
    return summary
