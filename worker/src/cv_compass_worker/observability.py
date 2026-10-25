"""Allowlisted operational events; no payload/exception interpolation or root logger changes."""
from contextlib import contextmanager
from contextvars import ContextVar
from datetime import datetime, timezone
from enum import StrEnum
import json
import logging
from logging.handlers import RotatingFileHandler
import os
import re
import sys
from .diagnostics import diagnostic, Reason


class Event(StrEnum):
    JOB_RECEIVED = 'JOB_RECEIVED'
    PIPELINE_STARTED = 'PIPELINE_STARTED'
    PIPELINE_FINISHED = 'PIPELINE_FINISHED'
    PAGE_FINISHED = 'PAGE_FINISHED'
    RESULT_SUBMITTING = 'RESULT_SUBMITTING'
    RESULT_ACKNOWLEDGED = 'RESULT_ACKNOWLEDGED'
    WORKER_HALTED = 'WORKER_HALTED'
    RECOVERY_STARTED = 'RECOVERY_STARTED'
    CLEANUP_FAILED = 'CLEANUP_FAILED'
    HEARTBEAT = 'HEARTBEAT'
    HEARTBEAT_FAILED = 'HEARTBEAT_FAILED'
    POLL_EMPTY = 'POLL_EMPTY'
    HTTP_STARTED = 'HTTP_STARTED'
    HTTP_FINISHED = 'HTTP_FINISHED'
    LIBRARY_DIAGNOSTIC = 'LIBRARY_DIAGNOSTIC'


_context = ContextVar('operational_context', default={})
_ids = frozenset(('documentId', 'jobId', 'attemptId', 'workerId', 'correlationId'))
_logger = logging.getLogger('cv_compass.events')
_logger.addHandler(logging.NullHandler())
_logger.propagate = False


def safe_id(value):
    if value is None:
        return None
    value = str(value)
    return value if re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}', value) else None


@contextmanager
def context(**values):
    current = dict(_context.get())
    current.update({key: safe_id(value) for key, value in values.items() if key in _ids and safe_id(value)})
    token = _context.set(current)
    try:
        yield
    finally:
        _context.reset(token)


def event(name: Event, *, level=logging.INFO, error_code='NONE', **details):
    if not isinstance(name, Event):
        raise ValueError('INVALID_LOG_EVENT')
    record = dict(_context.get())
    record.update(event=name.value, message=name.value, errorCode=(error_code if isinstance(error_code, str)
                  and re.fullmatch(r'[A-Z][A-Z0-9_]{0,63}', error_code) else 'INTERNAL_ERROR'))
    if safe_id(details.get('exceptionType')):
        record['exceptionType'] = safe_id(details['exceptionType'])
    for key in ('method', 'status'):
        if key in details and details[key] in ('native', 'ocr', 'none', 'ok', 'blank', 'failed',
                                              'not_processed', 'success', 'partial'):
            record[key] = details[key]
    for key in ('pageNumber', 'durationMs', 'httpStatus'):
        value = details.get(key)
        if type(value) is int and 0 <= value <= 2**53:
            record[key] = value
    _logger.log(level, name.value, extra={'operational': record})


class Formatter(logging.Formatter):
    def __init__(self, json_output=False):
        super().__init__()
        self.json_output = json_output

    def format(self, record):
        # Never format record.msg/args/exc_info, even if a caller accidentally supplies secrets.
        level = 'WARN' if record.levelno == logging.WARNING else 'ERROR' if record.levelno >= logging.ERROR else record.levelname
        data = dict(timestamp=datetime.fromtimestamp(record.created, timezone.utc).isoformat(timespec='milliseconds'),
                    level=level, service='cv-compass-worker')
        supplied = getattr(record, 'operational', {})
        name = supplied.get('event') if isinstance(supplied, dict) else None
        if name not in {item.value for item in Event}:
            data.update(event='LIBRARY_DIAGNOSTIC', message='LIBRARY_DIAGNOSTIC', errorCode='LIBRARY_DIAGNOSTIC')
        else:
            data.update(event=name, message=name, errorCode='NONE')
            code = supplied.get('errorCode')
            if isinstance(code, str) and re.fullmatch(r'[A-Z][A-Z0-9_]{0,63}', code):
                data['errorCode'] = code
            for key in _ids | {'exceptionType'}:
                if safe_id(supplied.get(key)):
                    data[key] = safe_id(supplied[key])
            for key in ('pageNumber', 'durationMs', 'httpStatus'):
                value = supplied.get(key)
                if type(value) is int and 0 <= value <= 2**53:
                    data[key] = value
            for key in ('method', 'status'):
                value = supplied.get(key)
                if value in ('native', 'ocr', 'none', 'ok', 'blank', 'failed', 'not_processed', 'success', 'partial'):
                    data[key] = value
        return json.dumps(data, ensure_ascii=False) if self.json_output else ' '.join(f'{k}={v}' for k, v in data.items())


def report_failure(error, fallback=Reason.UNEXPECTED_WORKER_FAILURE, *, name=Event.WORKER_HALTED,
                   level=logging.ERROR, initialized=True):
    """Called by the error owner before its ID scope closes; never formats the exception."""
    if name == Event.WORKER_HALTED and getattr(error, '_operational_logged', False):
        return
    info = diagnostic(error, fallback)
    with context(correlationId=info.correlation_id):
        if initialized:
            event(name, level=level, error_code=info.reason.value, exceptionType=safe_id(info.exception_type) or 'Exception',
                  httpStatus=info.http_status)
        else:
            # No logger/handler exists yet. A fixed safe JSON diagnostic still reaches stderr.
            record = dict(timestamp=datetime.now(timezone.utc).isoformat(timespec='milliseconds'),
                          level='ERROR', service='cv-compass-worker', event=name.value, message=name.value,
                          errorCode=info.reason.value, exceptionType=safe_id(info.exception_type) or 'Exception')
            record.update(_context.get())
            print(json.dumps(record), file=sys.stderr)
    if name == Event.WORKER_HALTED:
        error._operational_logged = True


def configure_child_diagnostics():
    """Fresh PDF child only: keep library severity without emitting library messages/causes."""
    root = logging.getLogger()
    for previous in list(root.handlers):
        root.removeHandler(previous)
        previous.close()
    handler = logging.StreamHandler(sys.stderr)
    handler.setFormatter(Formatter(True))
    root.addHandler(handler)


def configure(environ=None):
    """Pull supervisor only. Offline CLI receipts/child IPC keep their existing streams."""
    env = os.environ if environ is None else environ
    level = env.get('CV_LOG_LEVEL', 'INFO').upper()
    output = env.get('CV_LOG_FORMAT', 'text')
    if level not in ('DEBUG', 'INFO', 'WARNING', 'WARN', 'ERROR', 'CRITICAL') or output not in ('text', 'json', 'logstash'):
        raise ValueError('INVALID_LOG_CONFIGURATION')
    size = int(env.get('CV_LOG_MAX_BYTES', '10485760'))
    backups = int(env.get('CV_LOG_BACKUP_COUNT', '7'))
    if not 1024 <= size <= 1073741824 or not 1 <= backups <= 365:
        raise ValueError('INVALID_LOG_CONFIGURATION')
    formatter = Formatter(output != 'text')
    handlers = [logging.StreamHandler(sys.stderr)]
    try:
        if env.get('CV_LOG_FILE'):
            handlers.append(RotatingFileHandler(env['CV_LOG_FILE'], maxBytes=size, backupCount=backups, encoding='utf-8'))
        for handler in handlers:
            handler.setFormatter(formatter)
    except Exception:
        for handler in handlers:
            handler.close()
        raise
    for previous in list(_logger.handlers):
        _logger.removeHandler(previous)
        previous.close()
    for handler in handlers:
        _logger.addHandler(handler)
    _logger.setLevel(level)
