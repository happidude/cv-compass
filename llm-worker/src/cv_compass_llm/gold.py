"""Strict human-confirmed gold records and private draft conversion."""

import math
import re
import unicodedata
from datetime import date
from pathlib import Path
from typing import Literal

from .batch_io import input_directory, load_json, prepare_output, write_json

PERSONAL_FIELDS = ('fullName', 'gender', 'location', 'dateOfBirth')
EXPERIENCE_FIELDS = ('title', 'company', 'start', 'end', 'ongoing')
POSITION_FIELDS = ('title', 'start', 'end', 'ongoing')
COMPANY_FIELDS = ('company', 'companyLocation', 'start', 'end', 'ongoing', 'positions')
SCHEMA_VERSION = 'experience-gold/2'


def normalize(value: str | None) -> str | None:
    """Normalize Unicode, case and whitespace without inferring values."""
    if value is None:
        return None
    return ' '.join(unicodedata.normalize('NFC', value).casefold().split())


def group_experiences(items: list[dict]) -> list[dict]:
    """Group adjacent known companies, preserving source values and order."""
    companies = []
    for item in items:
        company = normalize(item['company'])
        if not companies or company is None or normalize(companies[-1]['company']) != company:
            companies.append(
                {
                    'company': item['company'],
                    'companyLocation': None,
                    'start': None,
                    'end': None,
                    'ongoing': False,
                    'positions': [],
                }
            )
        companies[-1]['positions'].append({key: item[key] for key in POSITION_FIELDS})
    return companies


def company_period(company: dict) -> tuple[str | None, str | None, bool]:
    """Use an explicit company period or derive its outer bounds from positions."""
    if company['start'] is not None or company['end'] is not None or company['ongoing']:
        return company['start'], company['end'], company['ongoing']
    starts = [item['start'] for item in company['positions'] if item['start'] is not None]
    ends = [item['end'] for item in company['positions'] if item['end'] is not None]
    # Partial years span January through December; keep the selected source string.
    start = min(starts, key=lambda value: value if len(value) == 7 else value + '-01', default=None)
    end = max(ends, key=lambda value: value if len(value) == 7 else value + '-12', default=None)
    ongoing = any(item['ongoing'] for item in company['positions'])
    return start, None if ongoing else end, ongoing


def effective_period(
    position: dict,
    period: tuple[str | None, str | None, bool],
) -> tuple[str | None, str | None, bool]:
    """Use a complete position period, otherwise inherit the company period."""
    if position['start'] is not None and (position['end'] is not None or position['ongoing']):
        return position['start'], position['end'], position['ongoing']
    return period


def _valid_date(value: str | None, *, birth: bool = False) -> bool:
    if value is None or re.fullmatch(r'\d{4}(-(0[1-9]|1[0-2]))?', value):
        return True
    if birth and re.fullmatch(r'\d{4}-\d{2}-\d{2}', value):
        try:
            date.fromisoformat(value)
        except ValueError:
            return False
        return True
    return False


def _valid_period(item: dict) -> bool:
    return (
        type(item['ongoing']) is bool
        and all(value is None or isinstance(value, str) for value in (item['start'], item['end']))
        and _valid_date(item['start'])
        and _valid_date(item['end'])
        and (not item['ongoing'] or item['end'] is None)
    )


def _valid_companies(items: object) -> bool:
    if not isinstance(items, list):
        return False
    for company in items:
        if (
            not isinstance(company, dict)
            or set(company) != set(COMPANY_FIELDS)
            or not all(
                company[key] is None or isinstance(company[key], str)
                for key in ('company', 'companyLocation')
            )
            or not _valid_period(company)
            or not isinstance(company['positions'], list)
            or not company['positions']
        ):
            return False
        for position in company['positions']:
            if (
                not isinstance(position, dict)
                or set(position) != set(POSITION_FIELDS)
                or not (position['title'] is None or isinstance(position['title'], str))
                or (position['title'] is None and company['company'] is None)
                or not _valid_period(position)
            ):
                return False
    return True


def valid_values(personal: object, experiences: object) -> bool:
    """Validate exact value-only personal and experience structures."""
    return (
        isinstance(personal, dict)
        and set(personal) == set(PERSONAL_FIELDS)
        and all(value is None or isinstance(value, str) for value in personal.values())
        and isinstance(experiences, list)
        and all(
            isinstance(item, dict)
            and set(item) == set(EXPERIENCE_FIELDS)
            and type(item['ongoing']) is bool
            and all(
                item[key] is None or isinstance(item[key], str) for key in EXPERIENCE_FIELDS[:-1]
            )
            for item in experiences
        )
    )


def load_gold(path: str | Path) -> dict:
    """Read an exact gold record or raise INVALID_GOLD."""
    data = load_json(path, 'INVALID_GOLD')
    if not isinstance(data, dict):
        raise ValueError('INVALID_GOLD')
    versioned = 'schemaVersion' in data
    keys = {'status', 'personal', 'experiences'} | ({'schemaVersion'} if versioned else set())
    if (
        set(data) != keys
        or data['status'] not in ('DRAFT', 'CONFIRMED')
        or (versioned and data['schemaVersion'] != SCHEMA_VERSION)
        or not valid_values(data['personal'], [])
    ):
        raise ValueError('INVALID_GOLD')
    experiences = data['experiences']
    if not versioned:
        if not valid_values(data['personal'], experiences):
            raise ValueError('INVALID_GOLD')
        experiences = group_experiences(experiences)
    elif not _valid_date(data['personal']['dateOfBirth'], birth=True) or not _valid_companies(
        experiences
    ):
        raise ValueError('INVALID_GOLD')
    return dict(data, schemaVersion=SCHEMA_VERSION, experiences=experiences)


def result_values(path: Path) -> tuple[dict, list[dict], float]:
    """Read extraction values while discarding evidence and issue contents."""
    data = load_json(path, 'INVALID_RESULT')
    try:
        if (
            not isinstance(data, dict)
            or not isinstance(data['fields'], list)
            or not isinstance(data['experiences'], list)
        ):
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
        experiences = [
            {key: item[key] for key in EXPERIENCE_FIELDS} for item in data['experiences']
        ]
        seconds = data['seconds']
        if (
            not valid_values(personal, experiences)
            or type(seconds) not in (int, float)
            or not math.isfinite(seconds)
            or seconds < 0
        ):
            raise ValueError
    except KeyError, TypeError, ValueError, OverflowError:
        raise ValueError('INVALID_RESULT') from None
    return personal, experiences, float(seconds)


def draft_gold(
    results: str | Path,
    output: str | Path,
    *,
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
            write_json(
                destination,
                {
                    'schemaVersion': SCHEMA_VERSION,
                    'status': 'DRAFT',
                    'personal': personal,
                    'experiences': group_experiences(experiences),
                },
            )
        except FileExistsError:
            counts['skipped_existing'] += 1
        else:
            counts['created'] += 1
    return counts
