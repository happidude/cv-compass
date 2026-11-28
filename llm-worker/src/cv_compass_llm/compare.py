"""Aggregate-only comparison against explicitly confirmed gold."""

import re
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

LEGAL = re.compile(
    r'\b(công ty|cty|cổ phần|tnhh|co|ltd|jsc|corp|corporation|company|companny|group|vietnam|việt nam|viet nam|vn)\b'
)
CUT = re.compile(r'\s[-–—]\s|\s*\|\s*|\(')


def _clean(value: str) -> str:
    return re.sub(r'[^0-9a-zà-ỹđ]+', '', value)


def core_company(value: str | None) -> str | None:
    if not value:
        return None
    value = re.sub(r'\(.*?\)', ' ', value)
    value = CUT.split(value)[0]
    value = normalize_loose(value) or ''
    return _clean(LEGAL.sub(' ', value)) or None


def core_title(value: str | None) -> str | None:
    if not value:
        return None
    return _clean(normalize_loose(CUT.split(value)[0]) or '') or None


def core_name(value: str | None) -> str | None:
    if not value:
        return None
    value = re.sub(r'\(.*?\)', ' ', value)
    value = re.sub(r'(?i)\b(mr|mrs|ms|miss)\b\.?', ' ', value)
    return _clean(normalize_loose(value) or '') or None


def core_experience_keys(items: list[dict], kind: str) -> Counter:
    """Score core names without regrouping companies or collapsing duplicates."""
    keys = []
    for item in items:
        company = core_company(item['company'])
        if company is None:
            continue
        if kind == 'companies':
            keys.append(company)
            continue
        period = company_period(item)
        for position in item['positions']:
            title = core_title(position['title'])
            if title is None:
                continue
            start, end, ongoing = effective_period(position, period)
            if kind == 'titles':
                keys.append((company, title))
            elif kind == 'positionsYear':
                keys.append(
                    (company, title, start[:4] if start else None, end[:4] if end else None)
                )
            else:
                keys.append((company, title, start, end, ongoing))
    return Counter(keys)


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
    personal['fullName']['core'] = dict.fromkeys(('correct', 'wrong', 'missing', 'extra'), 0)
    for kind in ('companies', 'titles', 'positions'):
        experiences[kind]['core'] = {'matched': 0, 'predicted': 0, 'gold': 0}
    experiences['positions']['coreYear'] = {'matched': 0, 'predicted': 0, 'gold': 0}
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
            levels = [
                (personal[key], normalize),
                (personal[key]['loose'], normalize_loose),
            ]
            if key == 'fullName':
                levels.append((personal[key]['core'], core_name))
            for counts, normalizer in levels:
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
            levels = [
                (
                    counts,
                    experience_keys(reference['experiences'], kind),
                    experience_keys(records, kind),
                ),
                (
                    counts['loose'],
                    experience_keys(reference['experiences'], kind, normalize_loose),
                    experience_keys(records, kind, normalize_loose),
                ),
            ]
            for name, core_kind in (('core', kind), ('coreYear', 'positionsYear')):
                if name in counts:
                    levels.append(
                        (
                            counts[name],
                            core_experience_keys(reference['experiences'], core_kind),
                            core_experience_keys(records, core_kind),
                        )
                    )
            for level, expected, actual in levels:
                level['matched'] += sum((expected & actual).values())
                level['predicted'] += actual.total()
                level['gold'] += expected.total()
    for counts in experiences.values():
        levels = [counts, *(counts[key] for key in ('loose', 'core', 'coreYear') if key in counts)]
        for level in levels:
            level['precision'] = (
                level['matched'] / level['predicted'] if level['predicted'] else 0.0
            )
            level['recall'] = level['matched'] / level['gold'] if level['gold'] else 0.0
    return summary
