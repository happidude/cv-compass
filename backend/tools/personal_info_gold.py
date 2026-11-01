"""Local evaluation input validation and comparison; never extracts candidate information."""

import json
import re
import unicodedata
from pathlib import Path

FIELDS = ('fullName', 'email', 'phone', 'location', 'summary', 'gender')
STATES = {'PROPOSED', 'NOT_FOUND', 'AMBIGUOUS', 'INVALID'}
SUBJECTS = {'CANDIDATE', 'REFERENCE', 'RECRUITER', 'COMPANY', 'UNKNOWN'}
LABELS = {
    'layout': {'SINGLE_COLUMN', 'MULTI_COLUMN', 'TABLE', 'MIXED', 'UNKNOWN'},
    'language': {'VI', 'EN', 'MIXED', 'OTHER', 'UNKNOWN'},
}


class InputError(ValueError):
    """A fixed diagnostic prevents input values and private paths leaking via CLI errors."""

    def __init__(self) -> None:
        super().__init__('EVALUATION_INPUT_REJECTED')


def require(condition: bool) -> None:
    if not condition:
        raise InputError()


def canonical(field: str, value: object) -> str | tuple[str, str | None]:
    """Apply the approved measurement equivalences without modifying source or stored values."""
    require(field in FIELDS)
    if field == 'gender':
        _keys(value, {'raw', 'normalized'})
        raw, normalized = value['raw'], value['normalized']
        require(isinstance(raw, str) and bool(raw.strip()))
        recognized = {'nam': 'MALE', 'male': 'MALE', 'm': 'MALE',
                      'nữ': 'FEMALE', 'female': 'FEMALE', 'f': 'FEMALE'}
        expected = recognized.get(unicodedata.normalize('NFC', raw).strip().casefold())
        require(normalized == expected)
        # This compares annotated values only; label/ownership evidence is audited by a human.
        return ('normalized', normalized) if normalized is not None else ('raw', raw)
    if field == 'phone':
        require(isinstance(value, dict) and set(value) == {'number', 'extension'})
        number, extension = value['number'], value['extension']
        require(isinstance(number, str) and bool(number.strip()))
        require(extension is None or isinstance(extension, str) and bool(extension.strip()))
        # Extensions are explicit annotation data: no guessing country codes or parsing CV text.
        return re.sub(r'[\s().-]', '', number), extension
    require(isinstance(value, str) and bool(value.strip()))
    if field == 'email':
        local, separator, domain = value.strip().rpartition('@')
        # Malformed selected values must remain measurable false positives, not disappear.
        return local + '@' + domain.lower() if separator else value.strip()
    value = unicodedata.normalize('NFC', value)
    if field == 'summary':
        return value.replace('\r\n', '\n').replace('\r', '\n').strip()
    return ' '.join(value.split())


def _keys(
    value: object, required: set[str], optional: set[str] | frozenset[str] = frozenset()
) -> None:
    require(isinstance(value, dict) and required <= set(value) <= required | optional)


def _integer(value: object, minimum: int) -> None:
    require(type(value) is int and value >= minimum)


def _evidence(evidence: object) -> None:
    require(isinstance(evidence, list))
    for span in evidence:
        _keys(span, {'extractionId', 'page', 'start', 'end', 'text'}, {'subject', 'supportsValue'})
        require(isinstance(span['extractionId'], str) and bool(span['extractionId']))
        for key, minimum in [('page', 1), ('start', 0), ('end', 1)]:
            _integer(span[key], minimum)
        require(span['start'] < span['end'] and isinstance(span['text'], str))
        if 'subject' in span:
            require(span['subject'] in SUBJECTS)
        if 'supportsValue' in span:
            require(span['supportsValue'] is None or type(span['supportsValue']) is bool)


def _source(source: object) -> None:
    _keys(source, {'extractionId', 'pages'})
    require(isinstance(source['extractionId'], str) and bool(source['extractionId']))
    require(isinstance(source['pages'], list))
    seen = set()
    for page in source['pages']:
        _keys(page, {'page', 'rawText'})
        _integer(page['page'], 1)
        require(page['page'] not in seen and isinstance(page['rawText'], str))
        seen.add(page['page'])


def validate(payload: object, gold: bool) -> dict[str, dict]:
    """Validate local measurement input and index documents for exact joining."""
    required = {'schemaVersion', 'documents'} | ({'confirmation'} if gold else set())
    _keys(payload, required)
    require(type(payload['schemaVersion']) is int and payload['schemaVersion'] == 1)
    if gold:
        # Draft annotations, including real drafts, must never produce an accuracy report.
        require(payload['confirmation'] == 'CONFIRMED')
    require(isinstance(payload['documents'], list) and bool(payload['documents']))
    documents = {}
    for doc in payload['documents']:
        _keys(doc, {'documentKey', 'fields'} | ({'layout', 'language'} if gold else set()),
              {'source'} if gold else set())
        key = doc['documentKey']
        require(isinstance(key, str) and bool(key) and key not in documents)
        documents[key] = doc
        _keys(doc['fields'], set(FIELDS))
        if gold:
            for dimension, allowed in LABELS.items():
                require(isinstance(doc[dimension], str) and doc[dimension] in allowed)
            if 'source' in doc:
                _source(doc['source'])
        for field, value in doc['fields'].items():
            if gold:
                _keys(value, {'status', 'acceptedValues'})
                require(value['status'] in {'ANNOTATED', 'UNANNOTATED', 'DISPUTED'})
                require(isinstance(value['acceptedValues'], list))
                require(value['status'] == 'ANNOTATED' or not value['acceptedValues'])
                for alternative in value['acceptedValues']:
                    canonical(field, alternative)
            else:
                _keys(value, {'enabled', 'state', 'value', 'subject', 'alternatives',
                              'issues', 'evidence'})
                require(type(value['enabled']) is bool)
                require(value['state'] in STATES and value['subject'] in SUBJECTS)
                require(isinstance(value['alternatives'], list))
                for alternative in value['alternatives']:
                    canonical(field, alternative)
                require(isinstance(value['issues'], list)
                        and all(isinstance(issue, str) for issue in value['issues']))
                if field == 'gender':
                    candidates = value['alternatives'] + (
                        [value['value']] if value['state'] == 'PROPOSED' else [])
                    if any(canonical(field, candidate)[0] == 'raw' for candidate in candidates):
                        require('GENDER_UNRECOGNIZED' in value['issues'])
                _evidence(value['evidence'])
                if value['state'] == 'PROPOSED':
                    canonical(field, value['value'])
                else:
                    require(value['value'] is None)
                if not value['enabled']:
                    require(value['state'] == 'NOT_FOUND' and 'FIELD_DISABLED' in value['issues'])
    return documents


def _unique_object(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        require(key not in result)
        result[key] = value
    return result


def load(path: str | Path) -> object:
    """Read only a caller-specified JSON file, without resolving any paths found in its contents."""
    try:
        return json.loads(Path(path).read_text(encoding='utf-8'), object_pairs_hook=_unique_object)
    except (OSError, ValueError, RecursionError):
        raise InputError() from None
