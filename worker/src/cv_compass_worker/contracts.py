"""Local v1 request/result validation and non-truncating publication budget."""

import copy
import json
import re
from jsonschema import Draft202012Validator
from .config import DESIGN

SCHEMA = json.loads((DESIGN / 'ocr-v2.schema.json').read_text(encoding='utf-8'))
VALIDATOR = Draft202012Validator(SCHEMA)


def validate_request(request):
    VALIDATOR.validate(request)
    if request['type'] != 'request':
        raise ValueError('INVALID_REQUEST')
    for key, pattern in (
        ('request_id', r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}'),
        ('document_id', r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}'),
        ('input_ref', r'[0-9a-f]{32}\.pdf'),
        ('expected_sha256', r'[0-9a-f]{64}'),
    ):
        # JSON Schema's regex $ can match before a final newline. Exact local
        # semantic validation keeps opaque identifiers/ref truly path-free.
        if re.fullmatch(pattern, request[key]) is None:
            raise ValueError('INVALID_REQUEST')


def classify(pages, errors):
    if not any(p['status'] == 'ok' for p in pages):
        return 'failed'
    return (
        'partial'
        if errors or any(p['status'] in ('failed', 'not_processed') for p in pages)
        else 'success'
    )


def page(number, status='not_processed', code=None, stage='supervisor'):
    return dict(
        page_number=number,
        status=status,
        method='none',
        raw_text='',
        warnings=['BLANK_PAGE'] if status == 'blank' else [],
        error={'code': code, 'stage': stage} if code else None,
        timings_ms={
            k: (0 if k == 'total' else None)
            for k in ('native', 'render', 'preprocess', 'ocr', 'total')
        },
        confidence=None,
    )


def encoded(result):
    return json.dumps(result, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode(
        'utf-8'
    )


def validate_result(result, config):
    VALIDATOR.validate(result)
    pages, count = result['pages'], result['page_count']
    if (count is None and pages) or (
        count is not None and (len(pages) != count or count > config.max_pages)
    ):
        raise ValueError('PAGE_COUNT_LIMIT')
    if sum(len(p['raw_text']) for p in pages) > config.max_document_text:
        raise ValueError('OUTPUT_LIMIT')
    if result['status'] != classify(pages, result['errors']):
        raise ValueError('DOCUMENT_STATUS')
    for index, item in enumerate(pages, 1):
        validate_page(item, index, config)


def validate_page(item, number, config):
    Draft202012Validator({'$ref': '#/$defs/page', '$defs': SCHEMA['$defs']}).validate(item)
    if item['page_number'] != number:
        raise ValueError('PAGE_ORDER')
    if len(item['raw_text']) > config.max_text_code_points_per_field:
        raise ValueError('OUTPUT_LIMIT')
    if item['status'] == 'ok' and not item['raw_text'].strip():
        raise ValueError('PAGE_STATUS')


def fit_result(result, config):
    """Preserve a prefix of whole page results; never cut a text field."""
    result = copy.deepcopy(result)
    original = result['pages']
    result['pages'] = []
    used = 0
    limited = False
    for item in original:
        chars = len(item['raw_text'])
        if limited or chars + used > config.max_document_text:
            limited = True
            result['pages'].append(page(item['page_number'], code='OUTPUT_LIMIT', stage='output'))
        else:
            result['pages'].append(item)
            used += chars

    def add_limit():
        error = {'code': 'OUTPUT_LIMIT', 'stage': 'output'}
        if error not in result['errors']:
            result['errors'].append(error)

    if limited:
        add_limit()
    for keep in range(len(original), -1, -1):
        result['status'] = classify(result['pages'], result['errors'])
        data = encoded(result)
        if len(data) <= config.max_result_bytes:
            validate_result(result, config)
            return data
        if keep:
            result['pages'][keep - 1] = page(keep, code='OUTPUT_LIMIT', stage='output')
            add_limit()
    raise ValueError('OUTPUT_LIMIT')  # Caller: exit 3, no delivery.
