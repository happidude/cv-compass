"""Offline lifecycle joins and numeric telemetry; source lines never enter reports."""

import math
import re
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from statistics import mean, median
from uuid import UUID

METRICS = (
    'prompt_tokens',
    'output_tokens',
    'prompt_s',
    'output_s',
    'load_s',
    'total_s',
    'tok_s',
)
_ID = re.compile(r'(?:[1-9][0-9]{0,18}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})\Z')
_PAIR = re.compile(r'(?:^|\s)([A-Za-z_][A-Za-z0-9_]*)=([^\s]+)')
_TIMESTAMP = re.compile(r'^\d{4}-\d\d-\d\d[T ]\d\d:\d\d:\d\d(?:[.,]\d+)?(?:Z|[+-]\d\d:\d\d)?')


def call_metrics(raw: object) -> dict:
    """Project only bounded numeric counts/durations, never provider strings."""
    if not isinstance(raw, dict):
        return {}
    result = {}
    for key in (
        'prompt_tokens',
        'output_tokens',
        'prompt_ns',
        'output_ns',
        'load_ns',
        'total_ns',
    ):
        value = raw.get(key)
        if type(value) is int and 0 <= value <= 2**53:
            if key.endswith('_ns'):
                result[key[:-3] + '_s'] = value / 1_000_000_000
            else:
                result[key] = value
    if result.get('output_s', 0) > 0 and 'output_tokens' in result:
        result['tok_s'] = result['output_tokens'] / result['output_s']
    return result


def metric_summary(calls: list[dict]) -> dict:
    """Missing measurements are excluded, with explicit per-metric denominators."""
    return {
        key: {
            **_stats(values),
            'total': sum(values),
        }
        for key in METRICS
        for values in [[call[key] for call in calls if key in call]]
    }


def _number(value: str | None) -> float | None:
    try:
        number = float(value)
        return number if math.isfinite(number) and 0 <= number <= 2**53 else None
    except ValueError, TypeError, OverflowError:
        return None


def _identity(value: str | None) -> str | None:
    return value if isinstance(value, str) and _ID.fullmatch(value) else None


def _attempt(value: str | None) -> str | None:
    try:
        return str(UUID(value)) if value and str(UUID(value)) == value else None
    except ValueError, AttributeError:
        return None


def _read(path: str | Path, source: str, counts: dict) -> list[dict]:
    records = []
    with Path(path).open(encoding='utf-8-sig', errors='replace') as stream:
        for line in stream:
            fields = dict(_PAIR.findall(line))
            name = fields.get('event', '')
            timestamp = fields.get('timestamp')
            match = _TIMESTAMP.match(line)
            if timestamp is None and match:
                timestamp = match[0]
            try:
                if not isinstance(timestamp, str) or not _TIMESTAMP.fullmatch(timestamp):
                    raise ValueError
                at = datetime.fromisoformat(timestamp.replace(',', '.')).timestamp()
                if not math.isfinite(at) or not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*', name):
                    raise ValueError
            except ValueError, TypeError, AttributeError, OverflowError, OSError:
                counts[source] += 1
                continue
            record = {'event': name, 'at': at}
            for key in ('documentId', 'jobId', 'attemptId'):
                value = (
                    _attempt(fields.get(key)) if key == 'attemptId' else _identity(fields.get(key))
                )
                if value:
                    record[key] = value
            if source == 'llm':
                if re.fullmatch(r'[0-9a-f]{8}', fields.get('attempt', '')):
                    record['attempt'] = fields['attempt']
                if name == 'extraction_block':
                    for key in (*METRICS, 'seconds'):
                        value = _number(fields.get(key))
                        if value is not None:
                            record[key] = value
                    block = fields.get('block', '')
                    if re.fullmatch(r'[0-9]{1,9}/[0-9]{1,9}', block):
                        record['block'] = int(block.split('/')[0])
            elif source == 'ocr':
                for key in ('durationMs', 'pageNumber'):
                    value = _number(fields.get(key))
                    if value is not None:
                        record[key] = value
            elif name == 'CANDIDATE_PUBLICATION_RECORDED':
                status = fields.get('status')
                if status in ('PUBLISHED', 'PROPOSED', 'SKIPPED'):
                    record['status'] = status
            records.append(record)
    return records


def _elapsed(start: float | None, end: float | None) -> float | None:
    return end - start if start is not None and end is not None and end >= start else None


def _stats(values: list[float]) -> dict:
    ordered = sorted(values)
    if not ordered:
        return {'count': 0, 'average': None, 'p50': None, 'p90': None}
    position = (len(ordered) - 1) * 0.9
    lower = math.floor(position)
    p90 = ordered[lower] + (ordered[math.ceil(position)] - ordered[lower]) * (position - lower)
    return {
        'count': len(values),
        'average': mean(values),
        'p50': median(values),
        'p90': p90,
    }


def _first(rows: list[dict], *names: str) -> float | None:
    return next((row['at'] for row in rows if row['event'] in names), None)


def _publication(row: dict) -> bool:
    return row['event'] == 'CANDIDATE_PUBLICATION_RECORDED' and row.get('status') in (
        'PUBLISHED',
        'PROPOSED',
    )


def lifecycle_report(backend_log: str | Path, ocr_log: str | Path, llm_log: str | Path) -> dict:
    """Join IDs across unordered log files, refusing ambiguous eight-character prefixes."""
    ignored = dict.fromkeys(('backend', 'ocr', 'llm'), 0)
    backend = _read(backend_log, 'backend', ignored)
    ocr = _read(ocr_log, 'ocr', ignored)
    llm = _read(llm_log, 'llm', ignored)
    attempts, jobs = defaultdict(set), defaultdict(set)
    for row in backend:
        document = row.get('documentId')
        if document and row.get('attemptId') and row['event'].startswith('MAPPING_REMOTE_'):
            attempts[row['attemptId']].add(document)
        if document and row.get('jobId') and row['event'].startswith('MAPPING_'):
            jobs[row['jobId']].add(document)
    prefixes = defaultdict(set)
    for attempt in attempts:
        prefixes[attempt[:8]].add(attempt)
    documents = defaultdict(lambda: {'backend': [], 'ocr': [], 'calls': []})
    for row in backend:
        if document := row.get('documentId'):
            documents[document]['backend'].append(row)
    unmatched_ocr = unmatched_llm = 0
    # OCR job IDs belong to a different queue; never join them to mapping job IDs.
    ocr_jobs = defaultdict(set)
    ocr_events = [row for row in backend if row['event'].startswith('OCR_')]
    for row in [*ocr_events, *ocr]:
        if row.get('documentId') and row.get('jobId'):
            ocr_jobs[row['jobId']].add(row['documentId'])
    for row in ocr:
        document = row.get('documentId')
        if not document and len(ocr_jobs[row.get('jobId')]) == 1:
            document = next(iter(ocr_jobs[row['jobId']]))
        if document:
            documents[document]['ocr'].append(row)
        elif row['event'] in ('PIPELINE_STARTED', 'PIPELINE_FINISHED', 'PAGE_FINISHED'):
            unmatched_ocr += 1
    for row in llm:
        if row['event'] != 'extraction_block':
            continue
        matches = prefixes[row.get('attempt')]
        if len(matches) != 1:
            unmatched_llm += 1
            continue
        attempt = next(iter(matches))
        if len(attempts[attempt]) != 1:
            unmatched_llm += 1
            continue
        if 'seconds' not in row:
            ignored['llm'] += 1
            continue
        document = next(iter(attempts[attempt]))
        call = {
            'attemptId': attempt,
            **{key: row[key] for key in (*METRICS, 'seconds', 'block') if key in row},
        }
        call['_at'] = row['at']
        documents[document]['calls'].append(call)

    output, starts, finishes = [], [], []
    for document, data in sorted(documents.items(), key=lambda item: (len(item[0]), item[0])):
        events = sorted(data['backend'], key=lambda row: row['at'])
        worker = sorted(data['ocr'], key=lambda row: row['at'])
        stored = _first(events, 'DOCUMENT_STORED')
        ocr_runs, pending = [], {}
        for row in worker:
            key = (row.get('jobId'), row.get('attemptId'))
            if row['event'] == 'PIPELINE_STARTED':
                pending[key] = row['at']
            elif row['event'] == 'PIPELINE_FINISHED':
                start = pending.pop(key, None)
                duration = _elapsed(start, row['at'])
                if row.get('durationMs') is not None:
                    duration = row['durationMs'] / 1000
                ocr_runs.append({'jobId': row.get('jobId'), 'seconds': duration})
        first_ocr = _first(worker, 'PIPELINE_STARTED')
        queued = _first(events, 'OCR_ENQUEUED')
        claimed = _first(events, 'OCR_CLAIMED')
        mapping_rows = defaultdict(list)
        for row in events:
            if (
                row['event'].startswith('MAPPING_')
                or row['event'] == 'CANDIDATE_PUBLICATION_RECORDED'
            ):
                if row.get('jobId') and jobs[row['jobId']] == {document}:
                    mapping_rows[row['jobId']].append(row)
        mappings = []
        for job, rows in mapping_rows.items():
            enqueued = _first(rows, 'MAPPING_ENQUEUED')
            claimed_at = _first(rows, 'MAPPING_CLAIMED', 'MAPPING_REMOTE_CLAIMED')
            completed = _first(rows, 'MAPPING_COMPLETED', 'MAPPING_REMOTE_ACCEPTED')
            published = next((row['at'] for row in rows if _publication(row)), None)
            mappings.append(
                {
                    'jobId': job,
                    'wait_s': _elapsed(enqueued, claimed_at),
                    'publish_wait_s': _elapsed(completed, published),
                }
            )
        published = next((row['at'] for row in events if _publication(row)), None)
        total = _elapsed(stored, published)
        if total is not None:
            starts.append(stored)
            finishes.append(published)
        calls = sorted(data['calls'], key=lambda row: row['_at'])
        for call in calls:
            del call['_at']
        pages = [
            {
                'jobId': row.get('jobId'),
                'page': row.get('pageNumber'),
                'seconds': row['durationMs'] / 1000,
            }
            for row in worker
            if row['event'] == 'PAGE_FINISHED' and 'durationMs' in row
        ]
        ocr_durations = [run['seconds'] for run in ocr_runs if run['seconds'] is not None]
        output.append(
            {
                'documentId': document,
                'ocr_wait_s': _elapsed(queued, claimed if claimed is not None else first_ocr),
                'ocr_s': (
                    sum(ocr_durations) if ocr_runs and len(ocr_durations) == len(ocr_runs) else None
                ),
                'ocr_runs': ocr_runs,
                'pages': pages,
                'mappings': mappings,
                'calls': calls,
                'total_s': total,
                'skipped': sum(
                    row['event'] == 'CANDIDATE_PUBLICATION_RECORDED'
                    and row.get('status') == 'SKIPPED'
                    for row in events
                ),
            }
        )
    stats = {
        key: _stats([row[key] for row in output if row[key] is not None])
        for key in ('ocr_wait_s', 'ocr_s', 'total_s')
    }
    for key in ('wait_s', 'publish_wait_s'):
        stats['mapping_' + key] = _stats(
            [
                mapping[key]
                for row in output
                for mapping in row['mappings']
                if mapping[key] is not None
            ]
        )
    stats['llm_seconds'] = _stats([call['seconds'] for row in output for call in row['calls']])
    window = max(finishes) - min(starts) if starts else 0
    return {
        'documents': output,
        'summary': {
            'documents': len(output),
            'completed': len(finishes),
            'cv_per_hour': len(finishes) * 3600 / window if window > 0 else None,
            'metrics': metric_summary([call for row in output for call in row['calls']]),
            **stats,
        },
        'ignored_lines': ignored,
        'unmatched_ocr': unmatched_ocr,
        'unmatched_llm': unmatched_llm,
    }


def print_report(report: dict) -> None:
    """Render fixed headings and IDs/numbers only; unknown durations stay unavailable."""
    print(
        'documentId ocr_wait_s ocr_s mapping_wait_s publish_wait_s total_s prompt_tokens output_tokens'
    )
    for row in report['documents']:

        def total(key):
            values = [mapping[key] for mapping in row['mappings']]
            return sum(values) if values and all(value is not None for value in values) else None

        metrics = metric_summary(row['calls'])
        values = [
            row['documentId'],
            row['ocr_wait_s'],
            row['ocr_s'],
            total('wait_s'),
            total('publish_wait_s'),
            row['total_s'],
            metrics['prompt_tokens']['total'] if metrics['prompt_tokens']['count'] else None,
            metrics['output_tokens']['total'] if metrics['output_tokens']['count'] else None,
        ]
        print(' '.join('-' if value is None else str(value) for value in values))
        for call in row['calls']:
            print(
                ' '.join(
                    f'{key}={call[key]}'
                    for key in ('attemptId', 'block', 'seconds', *METRICS)
                    if key in call
                )
            )

    def aggregates(value, prefix=''):
        for key, item in value.items():
            if isinstance(item, dict):
                aggregates(item, prefix + key + '.')
            else:
                print(f'{prefix}{key}={"-" if item is None else item}')

    aggregates(
        {key: report[key] for key in ('summary', 'ignored_lines', 'unmatched_ocr', 'unmatched_llm')}
    )
