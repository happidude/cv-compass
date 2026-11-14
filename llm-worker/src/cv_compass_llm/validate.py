"""Strict response validation and conservative evidence-backed normalization."""

import re

from .evidence import locate, normalize
from .json_strict import strict_json
from .schema import (
    EXPERIENCE_SCHEMA,
    PERSONAL_SCHEMA,
    Evidence,
    Experience,
    FieldValue,
    Issue,
)
from .segmenter import Block

PRESENT = ('present', 'now', 'current', 'hiện tại', 'nay')
DATE_TOKEN = re.compile(
    r'(?<![\w/])(?:\d{4}-(?:0?[1-9]|1[0-2])(?!\d)|\d{1,2}/\d{4}|\d{4})(?![\w/])'
)
GENDERS = {
    'nam': 'MALE', 'male': 'MALE', 'nữ': 'FEMALE', 'female': 'FEMALE',
    'other': 'OTHER', 'khác': 'OTHER',
}
GENDER_LABEL = re.compile(r'^(?:gender|giới\s+tính)\s*[:-]\s*')
PRESENT_TOKEN = re.compile(r'(?i)\b(?:present|now|current|hiện\s+tại|nay)\b')


def matches_schema(value: object, schema: dict) -> bool:
    """Check the supported strict JSON schema subset."""
    if 'enum' in schema and value not in schema['enum']:
        return False
    if 'anyOf' in schema:
        return any(matches_schema(value, option) for option in schema['anyOf'])
    kind = schema['type']
    if isinstance(kind, list):
        return any(matches_schema(value, {'type': item}) for item in kind)
    if kind == 'null':
        return value is None
    if kind == 'string':
        return isinstance(value, str)
    if kind == 'boolean':
        return type(value) is bool
    if kind == 'array':
        return isinstance(value, list) and all(
            matches_schema(item, schema['items']) for item in value
        )
    if kind == 'object':
        return (
            isinstance(value, dict) and set(value) == set(schema['properties'])
            and all(matches_schema(value[key], spec)
                    for key, spec in schema['properties'].items())
        )
    return False


def parse_response(text: str, schema: dict) -> dict | None:
    """Parse a model response only when its entire schema is valid."""
    if not isinstance(text, str):
        return None
    try:
        value = strict_json(text)
    except ValueError:
        return None
    return value if matches_schema(value, schema) else None


def supported(
    value: str | None, quote: str | None, block: Block, field: str, issues: list[Issue],
) -> tuple[str | None, Evidence | None]:
    """Retain a value only when its quote and text are supported."""
    if value is None:
        return None, None
    evidence = locate(quote, block)
    if not normalize(value) or evidence is None or normalize(value) not in normalize(evidence.text):
        issues.append(Issue('QUOTE_NOT_FOUND', field))
        return None, None
    return value, evidence


def _gender_value(text: str) -> str | None:
    return GENDERS.get(GENDER_LABEL.sub('', text.strip().casefold()))


def _gender_source_line(evidence: Evidence, block: Block) -> str | None:
    for span in block.spans:
        if span.page != evidence.page or not (
            span.start <= evidence.start < evidence.end <= span.end
        ):
            continue
        source = span.raw_text if span.raw_text is not None else span.text
        origin = 0 if span.raw_text is not None else span.start
        start, end = evidence.start - origin, evidence.end - origin
        if (start and source[start - 1].isalnum()) or (
            end < len(source) and source[end].isalnum()
        ):
            return None
        offset = 0
        for line in source.splitlines(keepends=True):
            if offset <= start < end <= offset + len(line):
                return line
            offset += len(line)
    return None


def validate_gender(
    item: dict, block: Block, issues: list[Issue],
) -> tuple[str | None, Evidence | None]:
    """Accept gender only on an explicit standalone or labelled source line."""
    evidence = locate(item['quote'], block)
    value = _gender_value(item['quote'])
    line = _gender_source_line(evidence, block) if evidence is not None else None
    if value is None or item['value'] != value or line is None or _gender_value(line) != value:
        issues.append(Issue('QUOTE_NOT_FOUND', 'gender'))
        return None, None
    return value, evidence


def validate_birth_date(
    item: dict, block: Block, issues: list[Issue],
) -> tuple[str | None, Evidence | None]:
    """Normalize an explicit supported birth date without inferring missing parts."""
    try:
        value, present = normalize_date(item['value'])
    except ValueError:
        issues.append(Issue('INVALID_DATE', 'dateOfBirth'))
        return None, None
    if present:
        issues.append(Issue('INVALID_DATE', 'dateOfBirth'))
        return None, None
    evidence = locate(item['quote'], block)
    tokens = date_tokens(evidence.text) if evidence is not None else []
    if evidence is None or value not in tokens:
        issues.append(Issue('QUOTE_NOT_FOUND', 'dateOfBirth'))
        return None, None
    return value, evidence


def validate_personal(text: str, block: Block) -> tuple[tuple[FieldValue, ...], tuple[Issue, ...]]:
    """Validate personal fields and retain only supported values."""
    data = parse_response(text, PERSONAL_SCHEMA)
    if data is None:
        return (), (Issue('LLM_INVALID_JSON'),)
    fields, issues = [], []
    validators = {'gender': validate_gender, 'dateOfBirth': validate_birth_date}
    for key, item in data['personal'].items():
        if item is None:
            continue
        if key in validators:
            value, evidence = validators[key](item, block, issues)
        else:
            value, evidence = supported(item['value'], item['quote'], block, key, issues)
        if value is not None and evidence is not None:
            fields.append(FieldValue(key, value, evidence))
    return tuple(fields), tuple(issues)


def normalize_date(value: str) -> tuple[str | None, bool]:
    """Normalize a supported date or detect an explicit ongoing marker."""
    value = value.strip()
    if value.casefold() in PRESENT:
        return None, True
    year, month = 0, None
    if re.fullmatch(r'\d{4}', value):
        year = int(value)
    elif re.fullmatch(r'\d{1,2}/\d{4}', value):
        raw_month, raw_year = value.split('/')
        month, year = int(raw_month), int(raw_year)
    elif re.fullmatch(r'\d{4}-\d{2}', value):
        raw_year, raw_month = value.split('-')
        year, month = int(raw_year), int(raw_month)
    if not 1950 <= year <= 2100 or (month is not None and not 1 <= month <= 12):
        raise ValueError('INVALID_DATE')
    return (f'{year:04d}-{month:02d}' if month is not None else f'{year:04d}'), False


def date_tokens(text: str) -> list[str]:
    """Collect valid normalized dates from a verbatim period quote."""
    tokens = []
    for match in DATE_TOKEN.finditer(text):
        try:
            token, _ = normalize_date(match.group())
            if token is not None:
                tokens.append(token)
        except ValueError:
            pass
    return tokens


def validate_period(
    item: dict, block: Block, issues: list[Issue],
) -> tuple[str | None, str | None, bool, Evidence | None]:
    """Validate quoted dates, ongoing status and chronological order."""
    if item['start'] is None and item['end'] is None and not item['ongoing']:
        return None, None, False, None
    evidence = locate(item['quotes']['period'], block)
    if evidence is None:
        issues.append(Issue('QUOTE_NOT_FOUND', 'period'))
        return None, None, False, None
    tokens = date_tokens(evidence.text)
    explicit_ongoing = PRESENT_TOKEN.search(evidence.text) is not None
    dates = []
    ongoing = item['ongoing']
    for key in ('start', 'end'):
        raw = item[key]
        if raw is None:
            dates.append(None)
            continue
        try:
            date, present = normalize_date(raw)
        except ValueError:
            issues.append(Issue('INVALID_DATE', key))
            return None, None, False, None
        if (present and (key != 'end' or not explicit_ongoing)) or (
            not present and date not in tokens
        ):
            issues.append(Issue('QUOTE_NOT_FOUND', key))
            return None, None, False, None
        dates.append(date)
        ongoing |= present
    if ongoing and (not explicit_ongoing or dates[1] is not None):
        issues.append(Issue('QUOTE_NOT_FOUND', 'ongoing'))
        return None, None, False, None
    start, end = dates
    # Mixed precision years represent intervals, not invented months.
    if start is not None and end is not None:
        earliest_start = start if len(start) == 7 else start + '-01'
        latest_end = end if len(end) == 7 else end + '-12'
        if earliest_start > latest_end:
            issues.append(Issue('PERIOD_ORDER', 'period'))
            return None, None, False, None
    return start, end, ongoing, evidence


def validate_experiences(
    text: str, block: Block,
) -> tuple[tuple[Experience, ...], tuple[Issue, ...]]:
    """Validate experience records and retain their source evidence."""
    data = parse_response(text, EXPERIENCE_SCHEMA)
    if data is None:
        return (), (Issue('LLM_INVALID_JSON'),)
    experiences, issues = [], []
    for item in data['experiences']:
        title, title_evidence = supported(
            item['title'], item['quotes']['title'], block, 'title', issues,
        )
        company, company_evidence = supported(
            item['company'], item['quotes']['company'], block, 'company', issues,
        )
        start, end, ongoing, period = validate_period(item, block, issues)
        if title is None and company is None:
            issues.append(Issue('EXPERIENCE_WITHOUT_ROLE'))
            continue
        evidence = {key: value for key, value in (
            ('title', title_evidence), ('company', company_evidence), ('period', period),
        ) if value is not None}
        experiences.append(Experience(title, company, start, end, ongoing, evidence))
    return tuple(experiences), tuple(issues)
