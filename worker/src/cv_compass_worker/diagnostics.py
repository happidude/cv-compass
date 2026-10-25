"""Typed, safe failure metadata. Never inspect exception/cause messages to classify a halt."""
from dataclasses import dataclass
from contextlib import contextmanager
from enum import StrEnum
import http.client
import ssl


class Reason(StrEnum):
    UNEXPECTED_WORKER_FAILURE = 'UNEXPECTED_WORKER_FAILURE'
    LOG_CONFIGURATION_ERROR = 'LOG_CONFIGURATION_ERROR'
    CONFIG_READ_FAILED = 'CONFIG_READ_FAILED'
    CONFIG_PARSE_FAILED = 'CONFIG_PARSE_FAILED'
    PIPELINE_CONFIG_INVALID = 'PIPELINE_CONFIG_INVALID'
    PULL_CONFIGURATION = 'PULL_CONFIGURATION'
    PULL_ORIGIN = 'PULL_ORIGIN'
    PULL_DISABLED = 'PULL_DISABLED'
    CREDENTIAL_MISSING = 'CREDENTIAL_MISSING'
    CREDENTIAL_INVALID = 'CREDENTIAL_INVALID'
    CONNECTION_REFUSED = 'CONNECTION_REFUSED'
    HTTP_TIMEOUT = 'HTTP_TIMEOUT'
    TLS_FAILED = 'TLS_FAILED'
    NETWORK_FAILED = 'NETWORK_FAILED'
    HTTP_AUTHENTICATION_FAILED = 'HTTP_AUTHENTICATION_FAILED'
    HTTP_AUTHORIZATION_FAILED = 'HTTP_AUTHORIZATION_FAILED'
    HTTP_REJECTED = 'HTTP_REJECTED'
    HTTP_PROTOCOL_ERROR = 'HTTP_PROTOCOL_ERROR'
    HTTP_CLEANUP_FAILED = 'HTTP_CLEANUP_FAILED'
    PULL_PATH = 'PULL_PATH'
    PULL_ENCODING = 'PULL_ENCODING'
    HTTP_BODY_LIMIT = 'HTTP_BODY_LIMIT'
    HTTP_BODY = 'HTTP_BODY'
    HTTP_ATTEMPTS = 'HTTP_ATTEMPTS'
    CLAIM_CONTRACT = 'CLAIM_CONTRACT'
    CLAIM_IDENTITY = 'CLAIM_IDENTITY'
    LEASE_CLOCK = 'LEASE_CLOCK'
    LEASE_TIMING = 'LEASE_TIMING'
    LEASE_IDENTITY = 'LEASE_IDENTITY'
    LEASE_LOST = 'LEASE_LOST'
    LEASE_OR_CLEANUP_FAILED = 'LEASE_OR_CLEANUP_FAILED'
    HEARTBEAT_NOT_REAPED = 'HEARTBEAT_NOT_REAPED'
    RECEIPT_IDENTITY = 'RECEIPT_IDENTITY'
    PRIVATE_LINUX_SPOOL_REQUIRED = 'PRIVATE_LINUX_SPOOL_REQUIRED'
    SPOOL_IO_FAILED = 'SPOOL_IO_FAILED'
    SPOOL_LIMIT = 'SPOOL_LIMIT'
    SPOOL_TYPE = 'SPOOL_TYPE'
    SPOOL_IDENTITY = 'SPOOL_IDENTITY'
    PIPELINE_REGISTRY = 'PIPELINE_REGISTRY'
    OPERATOR_RECOVERY_REQUIRED = 'OPERATOR_RECOVERY_REQUIRED'
    INCOMPLETE_ATTEMPT_REQUIRES_OPERATOR = 'INCOMPLETE_ATTEMPT_REQUIRES_OPERATOR'
    CLEANUP_FAILED = 'CLEANUP_FAILED'
    CLEANUP_OWNERSHIP = 'CLEANUP_OWNERSHIP'
    WORKER_LOCK_FAILED = 'WORKER_LOCK_FAILED'
    RESULT_LIMIT = 'RESULT_LIMIT'
    DELIVERY_CONTRACT = 'DELIVERY_CONTRACT'
    ENVELOPE_LIMIT = 'ENVELOPE_LIMIT'
    INPUT_INTEGRITY = 'INPUT_INTEGRITY'


class WorkerFailure(ValueError):
    def __init__(self, reason: Reason):
        self.reason = reason
        super().__init__(reason.value)


@dataclass(frozen=True)
class Diagnostic:
    reason: Reason
    exception_type: str
    correlation_id: str | None = None
    http_status: int | None = None


@contextmanager
def failure_scope(reason):
    """Classify at a known operation boundary without replacing the original exception."""
    try:
        yield
    except Exception as error:
        annotate(error, reason)
        raise


def annotate(error, reason, *, correlation_id=None, http_status=None):
    previous = getattr(error, '_ocr_diagnostic', None)
    if isinstance(previous, Diagnostic):
        reason = previous.reason
    elif isinstance(error, WorkerFailure):
        reason = error.reason
    if getattr(error, 'cleanup_failed', False):
        reason = Reason.HTTP_CLEANUP_FAILED
    error._ocr_diagnostic = Diagnostic(reason, type(error).__name__,
            correlation_id or (previous.correlation_id if isinstance(previous, Diagnostic) else None),
            http_status if http_status is not None else (previous.http_status if isinstance(previous, Diagnostic) else None))
    return error


def transport_failure(error, correlation_id, *, timed_out=False, http_status=None):
    reason = (Reason.HTTP_TIMEOUT if timed_out or isinstance(error, TimeoutError) else
              Reason.TLS_FAILED if isinstance(error, ssl.SSLError) else
              Reason.CONNECTION_REFUSED if isinstance(error, ConnectionRefusedError) else
              Reason.HTTP_PROTOCOL_ERROR if isinstance(error, http.client.HTTPException) else
              Reason.NETWORK_FAILED if isinstance(error, OSError) else Reason.UNEXPECTED_WORKER_FAILURE)
    previous = getattr(error, '_ocr_diagnostic', None)
    explicit = previous.reason if isinstance(previous, Diagnostic) else error.reason if isinstance(error, WorkerFailure) else None
    if timed_out and explicit in (None, Reason.HTTP_BODY):
        error._ocr_diagnostic = Diagnostic(Reason.HTTP_TIMEOUT, type(error).__name__, correlation_id, http_status)
        return error
    return annotate(error, reason, correlation_id=correlation_id, http_status=http_status)


def diagnostic(error, fallback=Reason.UNEXPECTED_WORKER_FAILURE):
    known = getattr(error, '_ocr_diagnostic', None)
    if isinstance(known, Diagnostic):
        if getattr(error, 'cleanup_failed', False):
            return Diagnostic(Reason.HTTP_CLEANUP_FAILED, known.exception_type, known.correlation_id, known.http_status)
        return known
    return Diagnostic(error.reason if isinstance(error, WorkerFailure) else fallback, type(error).__name__)
