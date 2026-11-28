"""Strict response validation and conservative evidence-backed normalization."""

import re

from .dates import (
    DATE_TOKEN,
    DATE_TOKEN_V2,
    DATE_TOKEN_V3,
    ENGLISH_MONTH,
    PRESENT_TOKEN,
    date_matches_v3,
    find_birth_dates,
    find_dates,
    has_open_period,
    has_present,
    is_weak_date_token,
    normalize_date,
    parse_birth_date,
    parse_date,
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
from .names import trim_company, trim_title
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


def _trim_name(
    value: str | None,
    evidence: Evidence | None,
    *,
    company: str | None = None,
    title: bool = False,
) -> tuple[str | None, Evidence | None]:
    if value is None or evidence is None:
        return value, evidence
    left, right = trim_title(value, company) if title else trim_company(value)
    trimmed = value[left:right]
    # supported() returns the literal value inside a possibly larger quote.
    start = evidence.start + evidence.text.index(value) + left
    return trimmed, Evidence(evidence.page, start, start + len(trimmed), trimmed)


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


def _period_ends_source_line(evidence: Evidence, block: Block) -> bool:
    for span in block.spans:
        if span.page == evidence.page and span.start < evidence.end <= span.end:
            tail = span.text[evidence.end - span.start :].split('\n', 1)[0]
            return not tail.strip()
    return False


def validate_period(
    item: dict,
    block: Block,
    issues: list[Issue],
    *,
    dates: str = 'v1',
    allow_open_period: bool = True,
) -> tuple[str | None, str | None, bool, Evidence | None]:
    """Validate quoted dates, ongoing status and chronological order."""
    if item['start'] is None and item['end'] is None and not item['ongoing']:
        return None, None, False, None
    evidence = locate(item['quotes']['period'], block)
    if evidence is None:
        issues.append(Issue('QUOTE_NOT_FOUND', 'period'))
        return None, None, False, None
    tokens = find_dates(evidence.text, style=dates)
    precision_tokens = tokens
    if dates == 'v3':
        # Only strong month tokens can remove the legacy year fallback.
        matches = date_matches_v3(evidence.text)
        precision_tokens = [
            value
            for match in matches
            if (value := parse_date(match.group(), style=dates)) in tokens
        ]
        precision_tokens.extend(
            value[:4]
            for match in matches
            if is_weak_date_token(match.group())
            and (value := parse_date(match.group(), style=dates)) is not None
            and value[:4] in tokens
        )
    open_period = (
        dates == 'v3'
        and allow_open_period
        and has_open_period(evidence.text)
        and _period_ends_source_line(evidence, block)
    )
    if open_period and item['end'] is not None:
        issues.append(Issue('QUOTE_NOT_FOUND', 'end'))
        return None, None, False, None
    explicit_ongoing = has_present(evidence.text) or (open_period and item['ongoing'])
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
            dates in ('v2', 'v3')
            and date is not None
            and len(date) == 7
            and date not in tokens
            and date[:4] in precision_tokens
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
    name_trim: str = 'off',
) -> tuple[tuple[Experience, ...], tuple[Issue, ...]]:
    """Validate experience records and retain their source evidence."""
    data = parse_response(text, EXPERIENCE_SCHEMA)
    if data is None:
        return (), (Issue('LLM_INVALID_JSON'),)
    return _experiences(data, block, dates=dates, name_trim=name_trim)


def _experiences(
    data: dict, block: Block, *, dates: str = 'v1', name_trim: str = 'off'
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
        if name_trim == 'on':
            company, company_evidence = _trim_name(company, company_evidence)
            title, title_evidence = _trim_name(title, title_evidence, company=company, title=True)
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
    name_trim: str = 'off',
) -> tuple[tuple[FieldValue, ...], tuple[Experience, ...], tuple[Issue, ...]]:
    """Parse once and apply the same evidence rules as block extraction."""
    data = parse_response(text, WHOLE_SCHEMA if whole_personal else EXPERIENCE_SCHEMA)
    if data is None:
        return (), (), (Issue('LLM_INVALID_JSON'),)
    fields, personal_issues = _personal(data, block) if whole_personal else ((), ())
    experiences, experience_issues = _experiences(data, block, dates=dates, name_trim=name_trim)
    return fields, experiences, (*personal_issues, *experience_issues)


def _line_value(
    item: dict, table: dict[int, Span], field: str, issues: list[Issue], *, personal: bool = False
) -> tuple[str | None, Evidence | None]:
    value, number = item['value'], item['line']
    end = item.get('endLine')
    if value is None and number is None and end is None:
        return None, None
    block = _line_range(number, end, table, limit=3 if field == 'location' else 1)
    if block is not None and value is not None:
        evidence = locate_personal(value, block) if personal else locate(value, block)
        first = block.spans[0]
        if evidence is not None and evidence.start < first.start + len(first.text.rstrip()):
            original = evidence.text
            if not (
                field == 'fullName'
                and personal_text(original, fold=True)[0] in GENERIC_NAME_HEADINGS
            ):
                return original, evidence
    issues.append(Issue('QUOTE_NOT_FOUND', field))
    return None, None


def _line_gender(
    item: dict, table: dict[int, Span], issues: list[Issue]
) -> tuple[str | None, Evidence | None]:
    """Normalize an explicit gender token on exactly the referenced source line."""
    if item['line'] is None and item['value'] is None:
        return None, None
    span = table.get(item['line'])
    if span is not None:
        text, offsets = personal_text(span.text, fold=True)
        label = GENDER_LABEL.match(text)
        start = label.end() if label is not None else 0
        value = _gender_value(text)
        if value is not None and value == item['value']:
            left, right = offsets[start][0], offsets[-1][1]
            return value, Evidence(
                span.page, span.start + left, span.start + right, span.text[left:right]
            )
    issues.append(Issue('QUOTE_NOT_FOUND', 'gender'))
    return None, None


def _line_range(
    start: int | None, end: int | None, table: dict[int, Span], *, limit: int
) -> Block | None:
    """Allow only bounded, contiguous source lines on the same page."""
    if start not in table:
        return None
    end = start if end is None else end
    if not start <= end < start + limit:
        return None
    spans = []
    for number in range(start, end + 1):
        span = table.get(number)
        if span is None or (spans and (span.page != spans[-1].page or span.start != spans[-1].end)):
            return None
        spans.append(span)
    return Block(tuple(spans))


def _period_continues(text: str, following: str, *, dates: str = 'v1') -> bool:
    """Require an explicit range boundary or an unfinished date before joining lines."""
    text, following = text.strip(), following.strip()
    date_token = DATE_TOKEN_V3 if dates == 'v3' else DATE_TOKEN_V2 if dates == 'v2' else DATE_TOKEN
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
    number, text = item['period']['line'], item['period']['text']
    if number is None and text is None and not item['ongoing']:
        return None, None, False, None
    span = table.get(number)
    if span is None:
        issues.append(Issue('QUOTE_NOT_FOUND', 'period'))
        return None, None, False, None
    block = Block((span,))
    evidence = locate(text, block)
    following = table.get(number + 1)
    if (
        evidence is None
        and following is not None
        and following.page == span.page
        and span.end == following.start
        and (
            dates not in ('v2', 'v3')
            or (
                number + 1 not in occupied_lines
                and len(
                    date_matches_v3(following.text)
                    if dates == 'v3'
                    else find_dates(following.text, style=dates)
                )
                <= 1
            )
        )
        and _period_continues(span.text, following.text, dates=dates)
    ):
        # A single period Evidence cannot represent an omitted source gap.
        block = Block((span, following))
        evidence = locate(text, block)
    if evidence is None or evidence.start >= span.start + len(span.text.rstrip()):
        issues.append(Issue('QUOTE_NOT_FOUND', 'period'))
        return None, None, False, None
    text = evidence.text
    tokens = find_dates(text, style=dates)
    date_token = DATE_TOKEN_V3 if dates == 'v3' else DATE_TOKEN_V2 if dates == 'v2' else DATE_TOKEN
    matches = date_matches_v3(text) if dates == 'v3' else list(date_token.finditer(text))
    for match in matches:
        try:
            normalize_date(match.group(), style=dates)
        except ValueError:
            issues.append(Issue('INVALID_DATE', 'period'))
            return None, None, False, None
    if dates == 'v3':
        # Overlapping v2 year fallbacks are support for quotes, not extra endpoints.
        tokens = [
            value
            for match in matches
            if (value := parse_date(match.group(), style=dates)) in tokens
        ]
    present = PRESENT_TOKEN.search(text)
    if (
        not tokens
        or len(tokens) > 2
        or len(matches) != len(tokens)
        or (present and len(tokens) != 1)
    ):
        issues.append(Issue('QUOTE_NOT_FOUND', 'period'))
        return None, None, False, None
    return validate_period(
        {
            'start': tokens[0],
            'end': tokens[1] if len(tokens) == 2 else None,
            'ongoing': has_present(text),
            'quotes': {'period': text},
        },
        block,
        issues,
        dates=dates,
        allow_open_period=False,
    )


def validate_line_experiences(
    text: str, table: dict[int, Span], *, dates: str = 'v1', name_trim: str = 'off'
) -> tuple[tuple[Experience, ...], tuple[Issue, ...]]:
    """Resolve short model values and derive periods from numbered source lines."""
    data = parse_response(text, LINES_EXPERIENCE_SCHEMA)
    if data is None:
        return (), (Issue('LLM_INVALID_JSON'),)
    rows, issues = [], []
    item_lines = (
        [_experience_lines(item) for item in data['experiences']] if dates in ('v2', 'v3') else []
    )
    for index, item in enumerate(data['experiences']):
        title, title_evidence = _line_value(item['title'], table, 'title', issues)
        company, company_evidence = _line_value(item['company'], table, 'company', issues)
        if name_trim == 'on':
            company, company_evidence = _trim_name(company, company_evidence)
            title, title_evidence = _trim_name(title, title_evidence, company=company, title=True)
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


def _experience_lines(item: dict) -> frozenset[int]:
    """Reserve another item's explicit role, company and period references."""
    return frozenset(
        {item['title']['line'], item['company']['line'], item['period']['line']} - {None}
    )


def validate_line_personal(
    text: str, table: dict[int, Span]
) -> tuple[tuple[FieldValue, ...], tuple[Issue, ...]]:
    """Resolve personal fields with the existing E8 normalization and gender rules."""
    data = parse_response(text, LINES_PERSONAL_SCHEMA)
    if data is None:
        return (), (Issue('LLM_INVALID_JSON'),)
    fields, issues = [], []
    for key, item in data['personal'].items():
        if key == 'gender':
            value, evidence = _line_gender(item, table, issues)
        elif key != 'summary':
            value, evidence = _line_value(item, table, key, issues, personal=True)
            if value is None or evidence is None:
                continue
            if key == 'dateOfBirth':
                span = table[item['line']]
                if len(find_birth_dates(span.text.replace('‐', '-'))) > 1:
                    issues.append(Issue('QUOTE_NOT_FOUND', key))
                    continue
                tokens = find_birth_dates(value.replace('‐', '-'))
                if len(tokens) != 1:
                    code = (
                        'INVALID_DATE'
                        if not tokens and DATE_TOKEN.search(value)
                        else 'QUOTE_NOT_FOUND'
                    )
                    issues.append(Issue(code, key))
                    continue
                value, ambiguous = tokens[0]
                if ambiguous:
                    issues.append(Issue('DOB_DAY_FIRST_ASSUMED', key))
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
