"""Strict response validation and conservative evidence-backed normalization."""

import re

from .dates import (
    DATE_TOKEN,
    DATE_TOKEN_V2,
    ENGLISH_MONTH,
    PRESENT_TOKEN,
    find_birth_dates,
    find_dates,
    has_present,
    normalize_date,
    parse_birth_date,
)
from .evidence import (
    find_personal,
    locate,
    locate_personal,
    personal_text,
    source_value,
    summary_evidence,
)
from .json_strict import strict_json
from .lines import line_summary
from .schema import (
    EXPERIENCE_SCHEMA,
    LINES_EXPERIENCE_SCHEMA,
    LINES_PERSONAL_SCHEMA,
    PERSONAL_SCHEMA,
    WHOLE_SCHEMA,
    Evidence,
    Experience,
    FieldValue,
    Issue,
)
from .segmenter import Block, Span

GENDERS = {
    'nam': 'MALE',
    'male': 'MALE',
    'nữ': 'FEMALE',
    'female': 'FEMALE',
    'other': 'OTHER',
    'khác': 'OTHER',
}
GENDER_LABEL = re.compile(r'^(?:gender|giới\s+tính)\s*[:-]\s*')
GENERIC_NAME_HEADINGS = {
    'curriculum vitae',
    'cv',
    'resume',
    'résumé',
    'sơ yếu lý lịch',
    'lý lịch',
}


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
    if kind == 'integer':
        return type(value) is int
    if kind == 'array':
        return (
            isinstance(value, list)
            and schema.get('minItems', 0) <= len(value) <= schema.get('maxItems', len(value))
            and all(matches_schema(item, schema['items']) for item in value)
        )
    if kind == 'object':
        return (
            isinstance(value, dict)
            and set(value) == set(schema['properties'])
            and all(matches_schema(value[key], spec) for key, spec in schema['properties'].items())
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
    value: str | None,
    quote: str | None,
    block: Block,
    field: str,
    issues: list[Issue],
) -> tuple[str | None, Evidence | None]:
    """Retain a value only when its quote and text are supported."""
    if value is None:
        return None, None
    evidence = locate(quote, block)
    original = source_value(value, evidence.text) if evidence is not None else None
    if original is None:
        issues.append(Issue('QUOTE_NOT_FOUND', field))
        return None, None
    return original, evidence


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
        if (start and source[start - 1].isalnum()) or (end < len(source) and source[end].isalnum()):
            return None
        offset = 0
        for line in source.splitlines(keepends=True):
            if offset <= start < end <= offset + len(line):
                return line
            offset += len(line)
    return None


def validate_gender(
    item: dict,
    block: Block,
    issues: list[Issue],
) -> tuple[str | None, Evidence | None]:
    """Accept gender only on an explicit standalone or labelled source line."""
    evidence = locate(item['quote'], block, ignore_case=False)
    value = _gender_value(item['quote'])
    line = _gender_source_line(evidence, block) if evidence is not None else None
    if value is None or item['value'] != value or line is None or _gender_value(line) != value:
        issues.append(Issue('QUOTE_NOT_FOUND', 'gender'))
        return None, None
    return value, evidence


def validate_birth_date(
    item: dict,
    block: Block,
    issues: list[Issue],
) -> tuple[str | None, Evidence | None]:
    """Normalize an explicit supported birth date without inferring missing parts."""
    value, ambiguous = parse_birth_date(item['value'])
    if value is None:
        issues.append(Issue('INVALID_DATE', 'dateOfBirth'))
        return None, None
    evidence = locate(item['quote'], block)
    tokens = find_birth_dates(evidence.text) if evidence is not None else []
    matches = [assumed for token, assumed in tokens if token == value]
    if evidence is None or not matches:
        issues.append(Issue('QUOTE_NOT_FOUND', 'dateOfBirth'))
        return None, None
    if ambiguous or any(matches):
        issues.append(Issue('DOB_DAY_FIRST_ASSUMED', 'dateOfBirth'))
    return value, evidence


def validate_personal(text: str, block: Block) -> tuple[tuple[FieldValue, ...], tuple[Issue, ...]]:
    """Validate personal fields and retain only supported values."""
    data = parse_response(text, PERSONAL_SCHEMA)
    if data is None:
        return (), (Issue('LLM_INVALID_JSON'),)
    fields, issues = [], []
    for key, item in data['personal'].items():
        if item is None:
            continue
        if key == 'summary':
            evidence = summary_evidence(item, block)
            if evidence is None:
                issues.append(Issue('QUOTE_NOT_FOUND', key))
            else:
                fields.append(
                    FieldValue(
                        key,
                        '\n'.join(part.text for part in evidence),
                        evidence[0],
                        evidence[1:],
                    )
                )
            continue
        value, evidence = _personal_value(key, item, block, issues)
        if value is not None and evidence is not None:
            fields.append(FieldValue(key, value, evidence))
    return tuple(fields), tuple(issues)


def _personal_value(
    key: str, item: dict, block: Block, issues: list[Issue]
) -> tuple[str | None, Evidence | None]:
    evidence = locate_personal(item['quote'], block, ignore_case=key != 'gender')
    value = None
    if key == 'gender':
        line = _gender_source_line(evidence, block) if evidence is not None else None
        quoted = _gender_value(personal_text(item['quote'])[0])
        if (
            quoted is not None
            and quoted == item['value']
            and line is not None
            and _gender_value(personal_text(line)[0]) == quoted
        ):
            value = quoted
    elif key == 'dateOfBirth':
        parsed, ambiguous = parse_birth_date(item['value'])
        if parsed is None:
            issues.append(Issue('INVALID_DATE', key))
            return None, None
        tokens = find_birth_dates(evidence.text.replace('‐', '-')) if evidence is not None else []
        matches = [assumed for token, assumed in tokens if token == parsed]
        if matches:
            value = parsed
            if ambiguous or any(matches):
                issues.append(Issue('DOB_DAY_FIRST_ASSUMED', key))
    elif evidence is not None:
        match = find_personal(item['value'], evidence.text)
        if match is not None:
            value = evidence.text[match[0] : match[1]]
            if key == 'fullName' and personal_text(value, fold=True)[0] in GENERIC_NAME_HEADINGS:
                value = None
    if value is None:
        issues.append(Issue('QUOTE_NOT_FOUND', key))
        return None, None
    return value, evidence


def _personal(data: dict, block: Block) -> tuple[tuple[FieldValue, ...], tuple[Issue, ...]]:
    fields, issues = [], []
    validators = {'gender': validate_gender, 'dateOfBirth': validate_birth_date}
    for key, item in data['personal'].items():
        if item is None:
            continue
        if (
            key == 'fullName'
            and ' '.join(item['value'].casefold().split()) in GENERIC_NAME_HEADINGS
        ):
            issues.append(Issue('QUOTE_NOT_FOUND', 'fullName'))
            continue
        if key in validators:
            value, evidence = validators[key](item, block, issues)
        else:
            value, evidence = supported(item['value'], item['quote'], block, key, issues)
        if value is not None and evidence is not None:
            fields.append(FieldValue(key, value, evidence))
    return tuple(fields), tuple(issues)


def validate_period(
    item: dict,
    block: Block,
    issues: list[Issue],
    *,
    dates: str = 'v1',
) -> tuple[str | None, str | None, bool, Evidence | None]:
    """Validate quoted dates, ongoing status and chronological order."""
    if item['start'] is None and item['end'] is None and not item['ongoing']:
        return None, None, False, None
    evidence = locate(item['quotes']['period'], block)
    if evidence is None:
        issues.append(Issue('QUOTE_NOT_FOUND', 'period'))
        return None, None, False, None
    tokens = find_dates(evidence.text, style=dates)
    explicit_ongoing = has_present(evidence.text)
    normalized_dates = []
    ongoing = item['ongoing']
    for key in ('start', 'end'):
        raw = item[key]
        if raw is None:
            normalized_dates.append(None)
            continue
        try:
            date, present = normalize_date(raw, style=dates)
        except ValueError:
            issues.append(Issue('INVALID_DATE', key))
            return None, None, False, None
        if (
            dates == 'v2'
            and date is not None
            and len(date) == 7
            and date not in tokens
            and date[:4] in tokens
        ):
            date = date[:4]
        if (present and (key != 'end' or not explicit_ongoing)) or (
            not present and date not in tokens
        ):
            issues.append(Issue('QUOTE_NOT_FOUND', key))
            return None, None, False, None
        normalized_dates.append(date)
        ongoing |= present
    if ongoing and (not explicit_ongoing or normalized_dates[1] is not None):
        issues.append(Issue('QUOTE_NOT_FOUND', 'ongoing'))
        return None, None, False, None
    start, end = normalized_dates
    # Mixed precision years represent intervals, not invented months.
    if start is not None and end is not None:
        earliest_start = start if len(start) == 7 else start + '-01'
        latest_end = end if len(end) == 7 else end + '-12'
        if earliest_start > latest_end:
            issues.append(Issue('PERIOD_ORDER', 'period'))
            return None, None, False, None
    return start, end, ongoing, evidence


def validate_experiences(
    text: str,
    block: Block,
    *,
    dates: str = 'v1',
) -> tuple[tuple[Experience, ...], tuple[Issue, ...]]:
    """Validate experience records and retain their source evidence."""
    data = parse_response(text, EXPERIENCE_SCHEMA)
    if data is None:
        return (), (Issue('LLM_INVALID_JSON'),)
    return _experiences(data, block, dates=dates)


def _experiences(
    data: dict, block: Block, *, dates: str = 'v1'
) -> tuple[tuple[Experience, ...], tuple[Issue, ...]]:
    experiences, issues = [], []
    for item in data['experiences']:
        title, title_evidence = supported(
            item['title'],
            item['quotes']['title'],
            block,
            'title',
            issues,
        )
        company, company_evidence = supported(
            item['company'],
            item['quotes']['company'],
            block,
            'company',
            issues,
        )
        start, end, ongoing, period = validate_period(item, block, issues, dates=dates)
        if title is None and company is None:
            issues.append(Issue('EXPERIENCE_WITHOUT_ROLE'))
            continue
        evidence = {
            key: value
            for key, value in (
                ('title', title_evidence),
                ('company', company_evidence),
                ('period', period),
            )
            if value is not None
        }
        experiences.append(Experience(title, company, start, end, ongoing, evidence))
    return tuple(experiences), tuple(issues)


def validate_whole(
    text: str,
    block: Block,
    *,
    whole_personal: bool = True,
    dates: str = 'v1',
) -> tuple[tuple[FieldValue, ...], tuple[Experience, ...], tuple[Issue, ...]]:
    """Parse once and apply the same evidence rules as block extraction."""
    data = parse_response(text, WHOLE_SCHEMA if whole_personal else EXPERIENCE_SCHEMA)
    if data is None:
        return (), (), (Issue('LLM_INVALID_JSON'),)
    fields, personal_issues = _personal(data, block) if whole_personal else ((), ())
    experiences, experience_issues = _experiences(data, block, dates=dates)
    return fields, experiences, (*personal_issues, *experience_issues)


def _line_value(
    item: dict, table: dict[int, Span], field: str, issues: list[Issue], *, personal: bool = False
) -> tuple[str | None, Evidence | None]:
    value, number = item['text'], item['line']
    if value is None and number is None:
        return None, None
    if number in table and value is not None:
        for candidate in (number, number - 1, number + 1):
            span = table.get(candidate)
            if span is None or span.page != table[number].page:
                continue
            if personal:
                evidence = locate_personal(value, Block((span,)))
            else:
                evidence = locate(value, Block((span,)))
            if evidence is not None:
                original = evidence.text
                if (
                    field == 'fullName'
                    and personal_text(original, fold=True)[0] in GENERIC_NAME_HEADINGS
                ):
                    continue
                return original, evidence
    issues.append(Issue('QUOTE_NOT_FOUND', field))
    return None, None


def _period_continues(text: str, following: str, *, dates: str = 'v1') -> bool:
    """Require an explicit range boundary or an unfinished date before joining lines."""
    text, following = text.strip(), following.strip()
    date_token = DATE_TOKEN_V2 if dates == 'v2' else DATE_TOKEN
    connector = r'(?:[-–—~]|\b(?:to|đến)\b)'
    if (
        re.search(connector + r'$', text, re.IGNORECASE)
        or re.match(connector, following, re.IGNORECASE)
        or date_token.match(following)
        or PRESENT_TOKEN.match(following)
    ):
        return True
    partial = re.search(
        r'(?<![\w/])(?:'
        + ENGLISH_MONTH
        + r'\.?(?:\s+[0-9]{1,2}(?:st|nd|rd|th)?[,]?)?'
        + r'|[0-9]{1,2}(?:st|nd|rd|th)?\s+'
        + ENGLISH_MONTH
        + r'\.?'
        + r'|(?:tháng\s+|t)[0-9]{1,2}(?:\s+năm)?'
        + r'|[0-9]{1,2}(?:[/.-][0-9]{1,2})?[/.-]?)$',
        text,
        re.IGNORECASE,
    )
    # The tail of a complete date (e.g. the 01 in 2020-01) is not a continuation.
    return partial is not None and not any(
        match.start() < partial.end() and partial.start() < match.end()
        for match in date_token.finditer(text)
    )


def _line_period(
    item: dict,
    table: dict[int, Span],
    issues: list[Issue],
    *,
    dates: str = 'v1',
    occupied_lines: frozenset[int] = frozenset(),
) -> tuple[str | None, str | None, bool, Evidence | None]:
    number = item['periodLine']
    if number is None and not item['ongoing']:
        return None, None, False, None
    span = table.get(number)
    if span is None:
        issues.append(Issue('QUOTE_NOT_FOUND', 'period'))
        return None, None, False, None
    text = span.text
    tokens = find_dates(text, style=dates)
    following = table.get(number + 1)
    if (
        len(tokens) == 1
        and not has_present(text)
        and following is not None
        and following.page == span.page
        and span.end == following.start
        and (
            dates != 'v2'
            or (
                number + 1 not in occupied_lines
                and len(find_dates(following.text, style=dates)) <= 1
            )
        )
        and _period_continues(text, following.text, dates=dates)
    ):
        # A single period Evidence cannot represent an omitted source gap.
        text += following.text
        tokens = find_dates(text, style=dates)
    date_token = DATE_TOKEN_V2 if dates == 'v2' else DATE_TOKEN
    matches = list(date_token.finditer(text))
    for match in matches:
        try:
            normalize_date(match.group(), style=dates)
        except ValueError:
            issues.append(Issue('INVALID_DATE', 'period'))
            return None, None, False, None
    present = PRESENT_TOKEN.search(text)
    if (
        not tokens
        or len(tokens) > 2
        or len(matches) != len(tokens)
        or (present and len(tokens) != 1)
    ):
        issues.append(Issue('QUOTE_NOT_FOUND', 'period'))
        return None, None, False, None
    left = matches[0].start()
    right = max(matches[-1].end(), present.end() if present is not None else 0)
    source = text[left:right]
    period_block = Block((Span(span.page, span.start, span.start + len(text), text),))
    return validate_period(
        {
            'start': tokens[0],
            'end': tokens[1] if len(tokens) == 2 else None,
            'ongoing': has_present(source),
            'quotes': {'period': source},
        },
        period_block,
        issues,
        dates=dates,
    )


def validate_line_experiences(
    text: str, table: dict[int, Span], *, dates: str = 'v1'
) -> tuple[tuple[Experience, ...], tuple[Issue, ...]]:
    """Resolve short model values and derive periods from numbered source lines."""
    data = parse_response(text, LINES_EXPERIENCE_SCHEMA)
    if data is None:
        return (), (Issue('LLM_INVALID_JSON'),)
    rows, issues = [], []
    item_lines = (
        [_experience_lines(item, table) for item in data['experiences']] if dates == 'v2' else []
    )
    for index, item in enumerate(data['experiences']):
        title, title_evidence = _line_value(item['title'], table, 'title', issues)
        company, company_evidence = _line_value(item['company'], table, 'company', issues)
        # An adjacent item's explicit evidence is not a continuation of this period.
        occupied_lines = frozenset(
            number
            for other_index, numbers in enumerate(item_lines)
            if other_index != index
            for number in numbers
        )
        start, end, ongoing, period = _line_period(
            item, table, issues, dates=dates, occupied_lines=occupied_lines
        )
        if title is None and company is None:
            issues.append(Issue('EXPERIENCE_WITHOUT_ROLE'))
            continue
        evidence = {
            key: value
            for key, value in (
                ('title', title_evidence),
                ('company', company_evidence),
                ('period', period),
            )
            if value is not None
        }
        rows.append(Experience(title, company, start, end, ongoing, evidence))
    return tuple(rows), tuple(issues)


def _experience_lines(item: dict, table: dict[int, Span]) -> frozenset[int]:
    """Reserve explicit references and the actual lines resolved by neighbor tolerance."""
    numbers = {item['title']['line'], item['company']['line'], item['periodLine']} - {None}
    for field in ('title', 'company'):
        _, evidence = _line_value(item[field], table, field, [])
        if evidence is not None:
            numbers.update(
                number
                for number, span in table.items()
                if span.page == evidence.page
                and span.start <= evidence.start < evidence.end <= span.end
            )
    return frozenset(numbers)


def validate_line_personal(
    text: str, table: dict[int, Span]
) -> tuple[tuple[FieldValue, ...], tuple[Issue, ...]]:
    """Resolve personal fields with the existing E8 normalization and gender rules."""
    data = parse_response(text, LINES_PERSONAL_SCHEMA)
    if data is None:
        return (), (Issue('LLM_INVALID_JSON'),)
    fields, issues = [], []
    for key, item in data['personal'].items():
        if key in ('fullName', 'location'):
            value, evidence = _line_value(item, table, key, issues, personal=True)
        elif key == 'gender':
            if item['line'] is None and item['value'] is None:
                continue
            span = table.get(item['line'])
            if span is None:
                issues.append(Issue('QUOTE_NOT_FOUND', key))
                continue
            value, evidence = _personal_value(
                key, {'value': item['value'], 'quote': span.text.strip()}, Block((span,)), issues
            )
        elif key == 'dateOfBirthLine':
            if item is None:
                continue
            key = 'dateOfBirth'
            span = table.get(item)
            tokens = find_birth_dates(span.text.replace('‐', '-')) if span is not None else []
            if len(tokens) > 1:
                issues.append(Issue('QUOTE_NOT_FOUND', key))
                continue
            if not tokens:
                invalid = span is not None and DATE_TOKEN.search(span.text) is not None
                issues.append(Issue('INVALID_DATE' if invalid else 'QUOTE_NOT_FOUND', key))
                continue
            value, evidence = _personal_value(
                key, {'value': tokens[0][0], 'quote': span.text.strip()}, Block((span,)), issues
            )
        else:
            if not item:
                continue
            parts = line_summary(item, table)
            if parts is None:
                issues.append(Issue('QUOTE_NOT_FOUND', 'summary'))
            else:
                fields.append(
                    FieldValue(
                        'summary',
                        '\n'.join(part.text for part in parts),
                        parts[0],
                        parts[1:],
                    )
                )
            continue
        if value is not None and evidence is not None:
            fields.append(FieldValue(key, value, evidence))
    return tuple(fields), tuple(issues)
