"""Sequential standalone orchestration, private snapshots, atomic no-clobber output."""

import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import tempfile
import time
import logging
from .observability import context, event, Event
from .contracts import classify, page, validate_page, validate_request
from .sandbox import ChildFailure, run_child


def real_path(path):
    path = Path(os.path.abspath(path))
    if str(path).startswith(('\\\\', '//')):
        raise ValueError('UNSAFE_INPUT')
    for part in [path, *path.parents]:
        info = part.lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
            raise ValueError('UNSAFE_INPUT')
    return path


def snapshot(workspace, request, root, config):
    source = real_path(workspace / request['input_ref'])
    info = source.lstat()
    if not stat.S_ISREG(info.st_mode):
        raise ValueError('UNSAFE_INPUT')
    if info.st_size > config.max_file_bytes:
        raise ValueError('FILE_SIZE_LIMIT')
    if info.st_size > config.max_temp_bytes:
        raise ValueError('TEMP_LIMIT')
    target = root / 'input.pdf'
    sha = hashlib.sha256()
    size = 0
    descriptor = os.open(
        source, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_BINARY', 0)
    )
    with os.fdopen(descriptor, 'rb') as stream, target.open('xb') as output:
        opened = os.fstat(stream.fileno())
        if (info.st_dev, info.st_ino) != (opened.st_dev, opened.st_ino):
            raise ValueError('INPUT_CHANGED')
        while chunk := stream.read(min(65536, config.max_file_bytes - size + 1)):
            size += len(chunk)
            if size > config.max_file_bytes:
                raise ValueError('FILE_SIZE_LIMIT')
            if size > config.max_temp_bytes:
                raise ValueError('TEMP_LIMIT')
            sha.update(chunk)
            output.write(chunk)
        after = os.fstat(stream.fileno())
    current = source.lstat()
    identity = lambda s: (s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns)
    if identity(opened) != identity(after) or identity(after) != identity(current):
        raise ValueError('INPUT_CHANGED')
    if sha.hexdigest() != request['expected_sha256']:
        raise ValueError('INPUT_HASH_MISMATCH')
    with target.open('rb') as stream:
        signature = stream.read(5)
    if size == 0 or signature != b'%PDF-':
        raise ValueError('FILE_CORRUPT')
    return target


def empty_result(request, config):
    names = (
        'python',
        'pdfplumber',
        'pdfminer_six',
        'pypdfium2',
        'pdfium',
        'pillow',
        'tesseract',
        'leptonica',
        'worker_version',
    )
    return dict(
        type='result',
        schema_version=request['schema_version'],
        request_id=request['request_id'],
        document_id=request['document_id'],
        status='failed',
        page_count=None,
        pages=[],
        warnings=[],
        errors=[],
        timings_ms=dict(preflight=None, cleanup=None, total=None),
        engines={k: None for k in names} | {'models': []},
        config_fingerprint=config.fingerprint(),
    )


def process(request, workspace, config, diagnostics=None, *, cancelled=None):
    # PDF children do not inherit Python context. Log validated page IPC here, in the supervisor.
    with context(documentId=request.get('document_id', 'unknown')):
        event(Event.PIPELINE_STARTED)
        try:
            result = _run(request, workspace, config, diagnostics, cancelled=cancelled)
        except BaseException:
            event(
                Event.PIPELINE_FINISHED,
                level=logging.ERROR,
                error_code='PIPELINE_FAILED',
                status='failed',
            )
            raise
        for item in result['pages']:
            event(
                Event.PAGE_FINISHED,
                level=logging.WARN
                if item['status'] in ('failed', 'not_processed')
                else logging.INFO,
                error_code=(item.get('error') or {}).get('code', 'NONE'),
                pageNumber=item['page_number'],
                method=item['method'],
                status=item['status'],
                durationMs=int(item['timings_ms']['total'] or 0),
            )
        for warning in result['warnings']:
            if warning == 'CLEANUP_FAILED':
                event(Event.CLEANUP_FAILED, level=logging.ERROR, error_code='CLEANUP_FAILED')
        event(
            Event.PIPELINE_FINISHED,
            level=logging.ERROR if result['status'] == 'failed' else logging.INFO,
            status=result['status'],
            durationMs=int(result['timings_ms']['total'] or 0),
        )
        return result


def _run(request, workspace, config, diagnostics=None, *, cancelled=None):
    if config.adapter == 'docker':
        from .docker_sandbox import docker_session

        try:
            with docker_session():
                return _process(request, workspace, config, diagnostics, cancelled=cancelled)
        except ChildFailure as exc:
            result = empty_result(request, config)
            result['errors'] = [{'code': exc.code, 'stage': 'preflight'}]
            result['timings_ms']['total'] = 0
            return result
    return _process(request, workspace, config, diagnostics, cancelled=cancelled)


def _process(request, workspace, config, diagnostics=None, *, cancelled=None):
    validate_request(request)
    # Fail closed irrespective of operator-provided booleans: no production
    # adapter or reviewed replacement binary has been implemented/approved.
    if config.mode != 'diagnostic':
        raise ValueError('INVALID_CONFIGURATION')
    workspace = real_path(workspace)
    if not workspace.is_dir():
        raise ValueError('UNSAFE_INPUT')
    lock = workspace / '.ocr-lock'
    fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    os.close(fd)
    root = None
    result = empty_result(request, config)
    started = time.monotonic()
    deadline = started + config.document_timeout - 7  # cleanup/publication reserve
    stage = 'preflight'
    scope_cleanup_failed = False

    def child(task, directory, timeout):
        if cancelled is not None and cancelled.is_set():
            raise ChildFailure('INTERNAL_ERROR')
        return (
            run_child(task, directory, config, timeout)
            if cancelled is None
            else run_child(task, directory, config, timeout, cancelled=cancelled)
        )

    try:
        root = Path(tempfile.mkdtemp(prefix='.ocr-tmp-', dir=workspace))
        source = snapshot(workspace, request, root, config)
        info = child(
            dict(action='preflight', source=str(source)),
            root / 'preflight',
            min(config.preflight_timeout, max(0.001, deadline - time.monotonic())),
        )
        scope_cleanup_failed |= getattr(info, 'cleanup_failed', False)
        if diagnostics is not None and (root / 'preflight/isolation.json').is_file():
            diagnostics.append(
                {
                    'stage': 'preflight',
                    'isolation': json.loads((root / 'preflight/isolation.json').read_text()),
                }
            )
        count = info['page_count']
        if type(count) is not int or not 1 <= count <= config.max_pages:
            raise ValueError('PAGE_COUNT_LIMIT')
        result.update(page_count=count, engines=info['engines'])
        result['pages'] = [page(n, code='DOCUMENT_TIMEOUT') for n in range(1, count + 1)]
        result['timings_ms']['preflight'] = (time.monotonic() - started) * 1000
        if (root / 'preflight/scope-cleanup.json').exists():
            scope_cleanup_failed = True
        try:
            if not scope_cleanup_failed:
                shutil.rmtree(root / 'preflight')
        except OSError:
            result['warnings'].append('CLEANUP_FAILED')
        stage = 'supervisor'
        used_text = 0
        for n in range(1, count + 1):
            if cancelled is not None and cancelled.is_set():
                raise ChildFailure('INTERNAL_ERROR')
            if scope_cleanup_failed:
                mark_pending(result, 'INTERNAL_ERROR', 'cleanup')
                break
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                result['errors'].append({'code': 'DOCUMENT_TIMEOUT', 'stage': 'supervisor'})
                break
            directory = root / f'page-{n}'
            attempt_started = time.monotonic()
            try:
                item = child(
                    dict(action='page', source=str(source), number=n),
                    directory,
                    min(config.page_timeout, remaining),
                )
                scope_cleanup_failed |= getattr(item, 'cleanup_failed', False)
                # Check structure/status before accepting IPC. Result validation
                # also checks semantic page order and configured text ceilings.
                try:
                    validate_page(item, n, config)
                except Exception:
                    raise ChildFailure('ENGINE_CRASH') from None
                chars = len(item['raw_text'])
                if used_text + chars > config.max_document_text:
                    for index in range(n - 1, count):
                        result['pages'][index] = page(
                            index + 1, code='OUTPUT_LIMIT', stage='output'
                        )
                    result['errors'].append({'code': 'OUTPUT_LIMIT', 'stage': 'output'})
                    break
                used_text += chars
                result['pages'][n - 1] = item
            except ChildFailure as exc:
                scope_cleanup_failed = scope_cleanup_failed or getattr(exc, 'cleanup_failed', False)
                code = 'DOCUMENT_TIMEOUT' if time.monotonic() >= deadline else exc.code
                error_stage = (
                    'ocr'
                    if code in ('OCR_FAILED', 'NO_USABLE_TEXT')
                    else 'render'
                    if code in ('IMAGE_LIMIT', 'RENDER_FAILED')
                    else 'output'
                    if code == 'OUTPUT_LIMIT'
                    else 'supervisor'
                )
                result['pages'][n - 1] = page(n, status='failed', code=code, stage=error_stage)
                result['pages'][n - 1]['timings_ms']['total'] = (
                    time.monotonic() - attempt_started
                ) * 1000
                if code in ('TEMP_LIMIT', 'RESOURCE_LIMIT', 'DOCUMENT_TIMEOUT'):
                    result['errors'].append({'code': code, 'stage': 'supervisor'})
                    for index in range(n, count):
                        result['pages'][index] = page(index + 1, code=code)
                    break
            finally:
                if (directory / 'scope-cleanup.json').exists():
                    scope_cleanup_failed = True
                if diagnostics is not None and directory.exists():
                    diagnostics.append(
                        {
                            'page_number': n,
                            'isolation': (
                                json.loads((directory / 'isolation.json').read_text())
                                if (directory / 'isolation.json').is_file()
                                else None
                            ),
                            'stderr_lines': sum(
                                len(p.read_bytes().splitlines())
                                for p in directory.glob('*.stderr.log')
                                if p.stat().st_size <= config.max_engine_output_bytes
                            ),
                        }
                    )
                if directory.exists() and not scope_cleanup_failed:
                    try:
                        shutil.rmtree(directory)
                    except OSError:
                        if 'CLEANUP_FAILED' not in result['warnings']:
                            result['warnings'].append('CLEANUP_FAILED')
        if not any(p['status'] == 'ok' for p in result['pages']) and not result['errors']:
            result['errors'].append({'code': 'NO_USABLE_TEXT', 'stage': 'supervisor'})
    except ChildFailure as exc:
        scope_cleanup_failed = scope_cleanup_failed or getattr(exc, 'cleanup_failed', False)
        result['errors'].append({'code': exc.code, 'stage': stage})
        mark_pending(result, exc.code, stage)
    except Exception as exc:
        scope_cleanup_failed |= getattr(exc, 'cleanup_failed', False)
        from .contracts import SCHEMA

        codes = SCHEMA['$defs']['error']['properties']['code']['enum']
        code = (
            str(exc)
            if isinstance(exc, ValueError) and str(exc) in codes
            else (
                'INPUT_NOT_FOUND'
                if isinstance(exc, FileNotFoundError)
                else 'INPUT_ACCESS_DENIED'
                if isinstance(exc, PermissionError)
                else 'INTERNAL_ERROR'
            )
        )
        result['errors'].append({'code': code, 'stage': stage})
        mark_pending(result, code, stage)
    finally:
        tick = time.monotonic()
        try:
            if root is not None and (
                scope_cleanup_failed or list(root.rglob('scope-cleanup.json'))
            ):
                raise OSError('SCOPE_CLEANUP_REQUIRED')
            if root is not None:
                shutil.rmtree(root)
            lock.unlink()
        except OSError:
            if 'CLEANUP_FAILED' not in result['warnings']:
                result['warnings'].append('CLEANUP_FAILED')
            # Keep the lock when cleanup failed. No subsequent intake until an
            # operator inspects the owned directory; no automatic stale recovery.
        result['timings_ms']['cleanup'] = (time.monotonic() - tick) * 1000
    result['timings_ms']['total'] = (time.monotonic() - started) * 1000
    result['status'] = classify(result['pages'], result['errors'])
    return result


def mark_pending(result, code, stage):
    for index, item in enumerate(result['pages']):
        if item['status'] == 'not_processed':
            result['pages'][index] = page(index + 1, code=code, stage=stage)


def publish(path, data):
    """Atomic name creation on the same filesystem; existing targets survive."""
    parent = real_path(path.parent)
    fd, name = tempfile.mkstemp(prefix='.ocr-publish-', dir=parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.link(name, path)
    finally:
        Path(name).unlink()
