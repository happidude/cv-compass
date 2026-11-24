"""Normalization validation and lease-bounded polling without source diagnostics."""

import time
import unicodedata
from collections import Counter
from datetime import datetime, timezone
from math import ceil
from uuid import UUID

from .backend_client import NORM_PREFIX, BackendClient, BackendError, encode
from .config import NORM_MAX_BODY_BYTES, NORM_MAX_FIELD_CODEPOINTS
from .json_strict import strict_json
from .norm_prompts import SCHEMA, build_prompt
from .progress import command_logging, event
from .providers.base import ProviderError

ERROR_CODES = frozenset(
    {
        'LLM_INVALID_JSON',
        'INVALID_CODE',
        'PROPOSED_LABEL_REQUIRED',
        'NAME_NOT_IN_SOURCE',
        'PARENT_NOT_IN_SOURCE',
        'MISSING_RESULT',
        'LLM_FIELD_TOO_LONG',
        'PARENT_SAME_AS_NAME',
        'RESULT_TOO_LARGE',
        'PROVIDER_ERROR',
    }
)


def validate_input(items, taxonomy):
    """Fail closed on malformed backend metadata before building a prompt."""
    if not isinstance(items, list) or not 1 <= len(items) <= 50:
        raise ValueError('BACKEND_BAD_RESPONSE')
    ids = set()
    for item in items:
        if (
            not isinstance(item, dict)
            or type(item.get('id')) is not int
            or item['id'] <= 0
            or item['id'] in ids
            or item.get('kind') not in ('COMPANY', 'TITLE')
            or not isinstance(item.get('raw'), str)
            or not item['raw'].strip()
            or (
                'contextCompany' in item
                and item['contextCompany'] is not None
                and not isinstance(item['contextCompany'], str)
            )
        ):
            raise ValueError('BACKEND_BAD_RESPONSE')
        ids.add(item['id'])
    if not isinstance(taxonomy, dict):
        raise ValueError('BACKEND_BAD_RESPONSE')
    for kind in ('FUNCTION', 'SENIORITY', 'INDUSTRY', 'SCOPE'):
        rows = taxonomy.get(kind)
        if not isinstance(rows, list) or not rows:
            raise ValueError('BACKEND_BAD_RESPONSE')
        codes = set()
        for row in rows:
            if (
                not isinstance(row, dict)
                or not isinstance(row.get('code'), str)
                or not row['code']
                or row['code'] in codes
                or not isinstance(row.get('label'), str)
            ):
                raise ValueError('BACKEND_BAD_RESPONSE')
            codes.add(row['code'])


def _error(item, code):
    return {'id': item['id'], 'error': code}


def _structure(row):
    if not isinstance(row, dict) or type(row.get('id')) is not int:
        return False
    kind = 'company' if 'company' in row else 'title'
    if set(row) != {'id', kind} or not isinstance(row[kind], dict):
        return False
    value = row[kind]
    properties = SCHEMA['properties']['results']['items']['oneOf'][kind == 'title']
    keys = properties['properties'][kind]['properties']
    if set(value) != set(keys):
        return False
    for key in keys:
        field = value[key]
        if key == 'functions':
            if not isinstance(field, list) or any(not isinstance(code, str) for code in field):
                return False
        elif key in ('parent', 'scope', 'proposedLabel'):
            if field is not None and not isinstance(field, str):
                return False
        elif not isinstance(field, str):
            return False
    return True


def _source(value):
    return ' '.join(unicodedata.normalize('NFC', value).split())


def _fold(value):
    """Match backend Fold for self-parent checks without relaxing source matching."""
    text = unicodedata.normalize('NFD', unicodedata.normalize('NFC', value))
    stripped = ''.join(char for char in text if not unicodedata.category(char).startswith('M'))
    return ' '.join(stripped.replace('đ', 'd').replace('Đ', 'd').lower().split())


def _validate(item, row, codes):
    kind = 'company' if item['kind'] == 'COMPANY' else 'title'
    if kind not in row:
        return 'LLM_INVALID_JSON'
    value = row[kind]
    if any(
        isinstance(value.get(field), str) and len(value[field]) > NORM_MAX_FIELD_CODEPOINTS
        for field in ('proposedLabel', 'name', 'parent')
    ):
        return 'LLM_FIELD_TOO_LONG'
    if kind == 'company':
        selected = [value['industry']]
        if value['industry'] not in codes['INDUSTRY']:
            return 'INVALID_CODE'
        name = _source(value['name'])
        if not name or name not in _source(item['raw']):
            return 'NAME_NOT_IN_SOURCE'
        parent = value['parent']
        if parent is not None and (
            not _source(parent) or _source(parent) not in _source(item['raw'])
        ):
            return 'PARENT_NOT_IN_SOURCE'
        if parent is not None and _fold(parent) == _fold(value['name']):
            return 'PARENT_SAME_AS_NAME'
    else:
        selected = value['functions']
        if not 1 <= len(selected) <= 2 or len(set(selected)) != len(selected):
            return 'LLM_INVALID_JSON'
        if (
            any(code not in codes['FUNCTION'] for code in selected)
            or value['seniority'] not in codes['SENIORITY']
            or (value['scope'] is not None and value['scope'] not in codes['SCOPE'])
        ):
            return 'INVALID_CODE'
    if 'OTHER' in selected and not (value['proposedLabel'] or '').strip():
        return 'PROPOSED_LABEL_REQUIRED'
    return None


def parse_results(raw: str, items: list[dict], taxonomy: dict) -> list[dict]:
    """Reject malformed schemas for the batch, then validate individual item semantics."""
    try:
        root = strict_json(raw)
        if not isinstance(root, dict) or set(root) != {'results'}:
            raise ValueError
        rows = root['results']
        if not isinstance(rows, list) or any(not _structure(row) for row in rows):
            raise ValueError
        ids = {item['id'] for item in items}
        if any(row['id'] not in ids for row in rows) or len({r['id'] for r in rows}) != len(rows):
            raise ValueError
    except ValueError, TypeError, RecursionError:
        return [_error(item, 'LLM_INVALID_JSON') for item in items]
    by_id = {row['id']: row for row in rows}
    codes = {kind: {row['code'] for row in table} for kind, table in taxonomy.items()}
    results = []
    for item in items:
        row = by_id.get(item['id'])
        code = _validate(item, row, codes) if row is not None else 'MISSING_RESULT'
        results.append(_error(item, code) if code else row)
    return results


def bound_results(token, results):
    """Replace the largest successes until the exact UTF-8 submission fits the backend limit."""
    bounded = list(results)
    longest = sorted(
        (index for index, row in enumerate(bounded) if 'error' not in row),
        key=lambda index: len(encode(bounded[index])),
        reverse=True,
    )
    for index in longest:
        if len(encode({'token': token, 'results': bounded})) <= NORM_MAX_BODY_BYTES:
            return bounded
        bounded[index] = _error(bounded[index], 'RESULT_TOO_LARGE')
    if len(encode({'token': token, 'results': bounded})) > NORM_MAX_BODY_BYTES:
        raise ValueError('RESULT_TOO_LARGE')
    return bounded


def normalize_batch(items, taxonomy, provider, config, *, deadline=None, on_raw=None):
    """One call per batch, at most two only when the configured context guard requires it."""
    validate_input(items, taxonomy)
    batches = [items]

    def fits(batch):
        return (
            not config.ctx_guard
            or config.num_ctx is None
            or ceil(len(build_prompt(batch, taxonomy)) / config.ctx_chars_per_token)
            + (config.max_output_tokens or 4096)
            <= config.num_ctx
        )

    if not fits(items) and len(items) > 1:
        middle = (len(items) + 1) // 2
        batches = [items[:middle], items[middle:]]
    if any(not fits(batch) for batch in batches):
        raise ProviderError('CONTEXT_OVERFLOW')
    results = []
    for batch in batches:
        remaining = config.timeout_s
        if deadline is not None:
            remaining = min(remaining, deadline - time.monotonic())
        if remaining <= 0:
            raise BackendError('BACKEND_DEADLINE_EXCEEDED')
        raw = provider.generate(
            build_prompt(batch, taxonomy), SCHEMA, num_ctx=config.num_ctx, timeout_s=remaining
        )
        if on_raw is not None:
            on_raw(raw)
        results.extend(parse_results(raw, batch, taxonomy))
    return results


def run_normalize(config, provider, *, once=False):
    """Drop lost/uncertain leases; never log source fields or provider diagnostics."""
    transport = config.transport
    client = BackendClient(transport, prefix=NORM_PREFIX)
    counts = Counter(claimed=0, accepted=0, rejected=0, idle=0, lease_lost=0, dropped_batches=0)
    exit_code = 0
    with command_logging(transport.log_level):
        try:
            while True:
                submitting = False
                try:
                    claim = client.claim_norm(config.batch)
                    if claim is None:
                        counts['idle'] += 1
                    else:
                        token = str(UUID(claim['token']))
                        expiry = datetime.fromisoformat(claim['leaseExpiresAt'])
                        if expiry.tzinfo is None:
                            raise ValueError('BACKEND_BAD_RESPONSE')
                        deadline = (
                            time.monotonic() + (expiry - datetime.now(timezone.utc)).total_seconds()
                        )
                        if len(claim['items']) > config.batch:
                            raise ValueError('BACKEND_BAD_RESPONSE')
                        counts['claimed'] += len(claim['items'])
                        started = time.monotonic()
                        results = normalize_batch(
                            claim['items'],
                            claim['taxonomy'],
                            provider,
                            transport.llm,
                            deadline=deadline,
                        )
                        results = bound_results(token, results)
                        submitting = True
                        reply = client.submit_norm(token, results, deadline=deadline)
                        if (
                            type(reply.get('accepted')) is not int
                            or reply['accepted'] < 0
                            or not isinstance(reply.get('rejected'), list)
                            or reply['accepted'] + len(reply['rejected']) != len(results)
                        ):
                            raise ValueError('BACKEND_BAD_RESPONSE')
                        counts['accepted'] += reply['accepted']
                        counts['rejected'] += len(reply['rejected'])
                        counts.update(row['error'] for row in results if 'error' in row)
                        event(
                            'normalized',
                            count=len(results),
                            seconds=f'{time.monotonic() - started:.3f}',
                        )
                except BackendError as error:
                    if error.status == 409 and error.code == 'LEASE_LOST':
                        counts['lease_lost'] += 1
                    elif error.code == 'BACKEND_DEADLINE_EXCEEDED':
                        counts['lease_lost'] += 1
                    else:
                        counts[error.code] += 1
                        event('backend_error', code=error.code)
                        if submitting and error.status in (400, 413):
                            counts['dropped_batches'] += 1
                            if once:
                                exit_code = 2
                                break
                        elif once or error.status not in (0, 503):
                            exit_code = 2
                            break
                except ProviderError:
                    counts['PROVIDER_ERROR'] += 1
                    event('provider_error', code='PROVIDER_ERROR')
                    if once:
                        exit_code = 2
                except ValueError, TypeError, KeyError, OverflowError:
                    counts['BACKEND_BAD_RESPONSE'] += 1
                    exit_code = 2
                    break
                if once:
                    break
                time.sleep(transport.poll_s)
        except KeyboardInterrupt:
            exit_code = 130
        finally:
            print(' '.join(f'{key}={counts[key]}' for key in sorted(counts)))
    return exit_code
