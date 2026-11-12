"""Strict human-confirmed gold records and private draft conversion."""

import math
from pathlib import Path
from typing import Literal

from .batch_io import input_directory, load_json, prepare_output, write_json

PERSONAL_FIELDS = ('fullName', 'gender', 'location', 'dateOfBirth')
EXPERIENCE_FIELDS = ('title', 'company', 'start', 'end', 'ongoing')


def valid_values(personal: object, experiences: object) -> bool:
    """Validate exact value-only personal and experience structures."""
    return (
        isinstance(personal, dict) and set(personal) == set(PERSONAL_FIELDS)
        and all(value is None or isinstance(value, str) for value in personal.values())
        and isinstance(experiences, list)
        and all(isinstance(item, dict) and set(item) == set(EXPERIENCE_FIELDS)
                and type(item['ongoing']) is bool
                and all(item[key] is None or isinstance(item[key], str)
                        for key in EXPERIENCE_FIELDS[:-1]) for item in experiences)
    )


def load_gold(path: str | Path) -> dict:
    """Read an exact gold record or raise INVALID_GOLD."""
    data = load_json(path, 'INVALID_GOLD')
    if (not isinstance(data, dict) or set(data) != {'status', 'personal', 'experiences'}
            or data['status'] not in ('DRAFT', 'CONFIRMED')
            or not valid_values(data['personal'], data['experiences'])):
        raise ValueError('INVALID_GOLD')
    return data


def result_values(path: Path) -> tuple[dict, list[dict], float]:
    """Read extraction values while discarding evidence and issue contents."""
    data = load_json(path, 'INVALID_RESULT')
    try:
        if (not isinstance(data, dict) or not isinstance(data['fields'], list)
                or not isinstance(data['experiences'], list)):
            raise ValueError
        personal = dict.fromkeys(PERSONAL_FIELDS)
        for field in data['fields']:
            key, value = field['key'], field['value']
            if not isinstance(key, str) or not isinstance(value, str):
                raise ValueError
            if key in personal:
                if personal[key] is not None:
                    raise ValueError
                personal[key] = value
        experiences = [{key: item[key] for key in EXPERIENCE_FIELDS}
                       for item in data['experiences']]
        seconds = data['seconds']
        if (not valid_values(personal, experiences) or type(seconds) not in (int, float)
                or not math.isfinite(seconds) or seconds < 0):
            raise ValueError
    except (KeyError, TypeError, ValueError, OverflowError):
        raise ValueError('INVALID_RESULT') from None
    return personal, experiences, float(seconds)


def draft_gold(
    results: str | Path, output: str | Path, *,
    repo_root: Path | None | Literal['auto'] = 'auto',
) -> dict:
    """Create DRAFT value records and skip every existing destination."""
    source = input_directory(results)
    target = prepare_output(source, output, allow_existing=True, repo_root=repo_root)
    counts = {'created': 0, 'skipped_existing': 0, 'errors': 0}
    for path in sorted(source.glob('*.result.json')):
        destination = target / (path.name.removesuffix('.result.json') + '.gold.json')
        if destination.exists() or destination.is_symlink():
            counts['skipped_existing'] += 1
            continue
        try:
            personal, experiences, _ = result_values(path)
        except ValueError:
            counts['errors'] += 1
            continue
        try:
            write_json(destination, {
                'status': 'DRAFT', 'personal': personal, 'experiences': experiences,
            })
        except FileExistsError:
            counts['skipped_existing'] += 1
        else:
            counts['created'] += 1
    return counts
