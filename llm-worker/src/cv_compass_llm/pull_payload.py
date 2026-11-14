"""Translate L1 records into the compact L4 contract, entirely in memory."""

import re
from dataclasses import asdict

from .backend_client import encode
from .ocr_input import Document, Page
from .schema import Evidence, ExtractionResult

PROTOCOL = 'cv-compass.llm-mapping/1.0'
ENGINE = 'llm-extraction/1.0'
PERSONAL = ('fullName', 'gender', 'dateOfBirth', 'email', 'phone', 'location', 'summary')
WORKER_ISSUES = frozenset('''QUOTE_NOT_FOUND INVALID_DATE PERIOD_ORDER
EXPERIENCE_WITHOUT_ROLE BLOCK_LIMIT_REACHED BLOCK_LINE_TOO_LONG
EXPERIENCE_HEADING_NOT_FOUND OTHER_TRUNCATED'''.split())
# Java Pattern (?U)\\s uses Unicode White_Space, unlike Python's extra U+001C..001F.
_SPACE = r'[\t-\r \x85\xa0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]'


def _string_evidence(value: str, evidence: Evidence) -> dict | None:
    """Narrow a trusted L1 quote without changing its raw code-point offsets."""
    normalized = re.sub(_SPACE + '+', ' ', value).strip(' \x1c\x1d\x1e\x1f')
    if not normalized:
        return None
    pattern = (_SPACE + '+').join(re.escape(part) for part in normalized.split(' '))
    match = re.search(pattern, evidence.text)
    if match is None:
        return None
    return asdict(Evidence(evidence.page, evidence.start + match.start(),
                           evidence.start + match.end(), match.group()))


def _date_valid(value: str | None, minimum: int) -> bool:
    return value is None or (
        re.fullmatch(r'[0-9]{4}(?:-(?:0[1-9]|1[0-2]))?', value) is not None
        and minimum <= int(value[:4]) <= 2100
    )


def _period_valid(row: dict) -> bool:
    start, end = row['start'], row['end']
    if (not _date_valid(start, 1950) or not _date_valid(end, 1950)
            or row['ongoing'] and end is not None):
        return False
    # Partial years are intervals, matching verifier earliest()/latest().
    return (start is None or end is None
            or (start + '-01' if len(start) == 4 else start)
            <= (end + '-12' if len(end) == 4 else end))


def input_document(data: dict) -> Document:
    """L4 input has page/raw_text, without the offline OCR status envelope."""
    rows = data.get('pages')
    if not isinstance(rows, list) or not rows:
        raise ValueError('INVALID_INPUT')
    pages = []
    for number, row in enumerate(rows, 1):
        if (not isinstance(row, dict) or type(row.get('page')) is not int
                or row['page'] != number or not isinstance(row.get('raw_text'), str)):
            raise ValueError('INVALID_INPUT')
        raw = row['raw_text']
        pages.append(Page(number, 'ok' if raw.strip() else 'blank', raw))
    if not any(page.status == 'ok' for page in pages):
        raise ValueError('INVALID_INPUT')
    return Document(tuple(pages))


def compact_result(result: ExtractionResult, claim: dict, provenance: dict) -> bytes:
    """Respect pinned switches and preserve evidence and contact ordering."""
    definition = claim['definition']
    switches = definition['fields']
    personal = dict.fromkeys(PERSONAL)
    seen = set()
    other = []
    issues = []
    for field in sorted(result.fields, key=lambda item: (item.evidence.page, item.evidence.start)):
        if field.key in personal and not switches[field.key]['enabled']:
            continue
        primary = field.key in personal and field.key not in seen
        extra = field.key in ('email', 'phone', 'url') and definition['other']['enabled']
        if not primary and not extra:
            continue
        seen.add(field.key)
        issue_field = field.key if primary else 'other'
        if field.key == 'dateOfBirth' and not _date_valid(field.value, 1900):
            issues.append({'code': 'INVALID_DATE', 'field': issue_field})
            continue
        evidence = (asdict(field.evidence) if field.key in ('gender', 'dateOfBirth')
                    else _string_evidence(field.value, field.evidence))
        if evidence is None:
            issues.append({'code': 'QUOTE_NOT_FOUND', 'field': issue_field})
            continue
        value = {'value': field.value, 'evidence': evidence}
        if primary:
            personal[field.key] = value
        else:
            other.append({'label': field.key, **value})
    for issue in result.issues:
        if issue.code not in WORKER_ISSUES:
            continue
        field = issue.field
        if field in ('title', 'company', 'period', 'start', 'end', 'ongoing'):
            field = 'experiences'
        if field not in (*PERSONAL, 'experiences', 'other'):
            field = None
        issues.append({'code': issue.code, 'field': field})
    if not definition['other']['enabled']:
        other = []
    elif len(other) > definition['other']['maxItems']:
        other = other[:definition['other']['maxItems']]
        issues.append({'code': 'OTHER_TRUNCATED', 'field': 'other'})
    experiences = []
    if switches['experiences']['enabled']:
        for item in result.experiences:
            row = asdict(item)
            for key in ('title', 'company'):
                if row[key] is not None:
                    evidence = _string_evidence(row[key], item.evidence[key])
                    if evidence is None:
                        row[key] = None
                        issues.append({'code': 'QUOTE_NOT_FOUND', 'field': 'experiences'})
                    else:
                        row['evidence'][key] = evidence
            if not _period_valid(row):
                row.update(start=None, end=None, ongoing=False)
                issues.append({'code': 'INVALID_DATE', 'field': 'experiences'})
            keys = {key for key in ('title', 'company') if row[key] is not None}
            if row['start'] is not None or row['end'] is not None or row['ongoing']:
                keys.add('period')
            row['evidence'] = {key: row['evidence'][key] for key in keys}
            experiences.append(row)
    payload = {
        'protocol': PROTOCOL, 'inputSha256': claim['inputSha256'], 'personal': personal,
        'experiences': experiences, 'other': other, 'issues': issues, 'provenance': provenance,
    }
    body = encode(payload)
    if len(body) > 1024 * 1024:
        raise ValueError('RESULT_TOO_LARGE')
    # Fail instead of silently cutting evidence, experiences, issues or long values.
    def bounded(value):
        if isinstance(value, str) and len(value) > 2000:
            raise ValueError('INVALID_OUTPUT')
        if isinstance(value, dict):
            for child in value.values():
                bounded(child)
        if isinstance(value, list):
            for child in value:
                bounded(child)
    if len(experiences) > 50 or len(issues) > 200:
        raise ValueError('INVALID_OUTPUT')
    bounded(payload)
    return body
