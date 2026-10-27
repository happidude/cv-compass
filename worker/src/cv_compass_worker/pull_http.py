"""Pinned-origin, no-redirect HTTP transport. Retries reuse the caller's exact bytes."""

import hashlib
import http.client
import json
import os
import re
import ssl
import sys
import socket
import threading
import time
import uuid
import logging
from .observability import context, event, Event
from .diagnostics import WorkerFailure, Reason, annotate, transport_failure, failure_scope
from urllib.parse import urlsplit

BASE = '/internal/ocr/v1'
PROTOCOL = 'cv-compass.ocr-job/1.0'


class Exchange:
    """Absolute socket deadline also interrupts slow trickle bodies/headers, not just idle reads."""

    def __init__(self, connection, timeout):
        self.connection = connection
        self.response = None
        try:
            connection.connect()
        except BaseException:
            try:
                connection.close()
            except BaseException as cleanup:
                # Diagnostic only: preserve the existing retry policy for connect/close failures.
                annotate(cleanup, Reason.HTTP_CLEANUP_FAILED)
                raise
            raise
        self.socket = connection.sock
        self.expired = False
        self.timer = threading.Timer(timeout, self.interrupt)
        self.timer.daemon = True
        self.timer.start()

    def interrupt(self):
        self.expired = True
        try:
            self.socket.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass

    def close(self):
        self.timer.cancel()
        primary = sys.exception()
        failure = None
        try:
            if self.response is not None:
                self.response.close()
        except Exception as error:
            failure = error
        try:
            self.connection.close()
        except Exception as error:
            failure = failure or error
        if failure is not None:
            if primary is not None:
                primary.cleanup_failed = True
                primary.add_note('HTTP_CLEANUP_FAILED; intake must remain halted')
            else:
                failure.cleanup_failed = True
                annotate(
                    failure,
                    Reason.HTTP_CLEANUP_FAILED,
                    correlation_id=getattr(self, 'correlation_id', None),
                    http_status=getattr(self, 'http_status', None),
                )
                raise failure


class HttpFailure(Exception):
    def __init__(self, status):
        super().__init__('OCR_HTTP_REJECTED')
        self.status = status
        reason = (
            Reason.HTTP_AUTHENTICATION_FAILED
            if status == 401
            else Reason.HTTP_AUTHORIZATION_FAILED
            if status == 403
            else Reason.HTTP_REJECTED
        )
        annotate(self, reason, http_status=status)


class InputIntegrityError(ValueError):
    """Non-retryable input admission failure; no fabricated M6 JSON."""

    def __init__(self, code):
        super().__init__(code)
        annotate(self, Reason.INPUT_INTEGRITY)


class DownloadUnavailable(OSError):
    """Transport failure only, not a local spool write failure."""


def json_bytes(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(',', ':')).encode(
        'utf-8'
    )


def strict_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('DUPLICATE_KEY')
            result[key] = value
        return result

    def reject(_):
        raise ValueError('NONFINITE_JSON')

    value = json.loads(
        raw.decode('utf-8', errors='strict'), object_pairs_hook=pairs, parse_constant=reject
    )

    def depth(node, level=0):
        if level > 32:
            raise ValueError('JSON_DEPTH')
        for child in (
            node.values() if isinstance(node, dict) else node if isinstance(node, list) else []
        ):
            depth(child, level + 1)

    depth(value)
    return value


class WorkerHttp:
    def __init__(self, config, credential):
        if credential is None or credential == '':
            raise WorkerFailure(Reason.CREDENTIAL_MISSING)
        if not isinstance(credential, str) or not re.fullmatch(
            r'[A-Za-z0-9_-]{43,256}', credential
        ):
            raise WorkerFailure(Reason.CREDENTIAL_INVALID)
        self.config, self._credential = config, credential
        self.origin = urlsplit(config.origin)

    def _open(self, method, path, data, token, claim_id=None, *, exchange_timeout=None):
        correlation = str(uuid.uuid4())
        # Job input URLs are never trusted; caller constructs the one allowed relative path.
        if not re.fullmatch(
            BASE + r'/(?:claims|attempts/[0-9a-f-]{36}/(?:input|lease|result|failure))', path
        ):
            raise WorkerFailure(Reason.PULL_PATH)
        headers = {
            'Authorization': 'Bearer ' + self._credential,
            'X-Lease-Token': token,
            'X-Correlation-ID': correlation,
            'Accept-Encoding': 'identity',
            'Content-Type': 'application/json',
        }
        if claim_id is not None:
            headers['Idempotency-Key'] = claim_id
        exchange = None
        try:
            if self.origin.scheme == 'https':
                connection = http.client.HTTPSConnection(
                    self.origin.hostname,
                    self.origin.port,
                    timeout=self.config.http_timeout_seconds,
                    context=ssl.create_default_context(),
                )
            else:
                connection = http.client.HTTPConnection(
                    self.origin.hostname, self.origin.port, timeout=self.config.http_timeout_seconds
                )
            exchange = Exchange(
                connection,
                self.config.http_timeout_seconds if exchange_timeout is None else exchange_timeout,
            )
            exchange.correlation_id = correlation
            exchange.http_status = None
            with context(correlationId=correlation):
                event(Event.HTTP_STARTED, level=logging.DEBUG)
            connection.request(method, path, body=data, headers=headers)
            response = connection.getresponse()
            exchange.http_status = response.status
            with context(correlationId=correlation):
                event(Event.HTTP_FINISHED, level=logging.DEBUG, httpStatus=response.status)
            exchange.response = response
            if response.getheader('Content-Encoding', 'identity').lower() != 'identity':
                raise WorkerFailure(Reason.PULL_ENCODING)
            return exchange, response
        except BaseException as failure:
            transport_failure(
                failure,
                correlation,
                timed_out=exchange is not None and exchange.expired,
                http_status=getattr(exchange, 'http_status', None),
            )
            if exchange is not None:
                exchange.close()
            raise

    def request(self, method, path, data, token, claim_id=None, *, live=lambda: True):
        for index in range(self.config.max_http_attempts):
            connection = None
            try:
                if not live():
                    raise WorkerFailure(Reason.LEASE_LOST)
                connection, response = self._open(method, path, data, token, claim_id)
                raw = response.read(16385)
                if len(raw) > 16384:
                    raise WorkerFailure(Reason.HTTP_BODY_LIMIT)
                if response.status == 204:
                    if raw:
                        raise WorkerFailure(Reason.HTTP_BODY)
                    return None
                if response.status not in (200, 201):
                    raise HttpFailure(response.status)
                try:
                    tree = strict_json(raw)
                except (ValueError, UnicodeError) as failure:
                    annotate(failure, Reason.HTTP_BODY)
                    raise
                if (
                    not isinstance(tree, dict)
                    or set(tree) != {'data'}
                    or not isinstance(tree['data'], dict)
                ):
                    raise WorkerFailure(Reason.HTTP_BODY)
                return tree['data']
            except Exception as failure:
                transport_failure(
                    failure,
                    getattr(connection, 'correlation_id', None),
                    timed_out=getattr(connection, 'expired', False),
                    http_status=getattr(connection, 'http_status', None),
                )
                if not isinstance(failure, (OSError, http.client.HTTPException, HttpFailure)):
                    raise
                if getattr(failure, 'cleanup_failed', False):
                    raise
                if isinstance(failure, HttpFailure) and failure.status < 500:
                    raise
                if index + 1 == self.config.max_http_attempts or not live():
                    raise
            finally:
                if connection is not None:
                    connection.close()
            time.sleep(self.config.retry_backoff_seconds)
        raise WorkerFailure(Reason.HTTP_ATTEMPTS)

    def download(self, attempt, token, target, size, sha256, *, live):
        if (
            type(size) is not int
            or not 1 <= size <= self.config.max_file_bytes
            or not re.fullmatch(r'[0-9a-f]{64}', sha256)
        ):
            raise ValueError('INPUT_MANIFEST')
        try:
            connection, response = self._open(
                'GET',
                BASE + '/attempts/' + attempt + '/input',
                None,
                token,
                exchange_timeout=self.config.download_timeout_seconds,
            )
        except (OSError, http.client.HTTPException) as failure:
            if getattr(failure, 'cleanup_failed', False):
                raise
            wrapped = DownloadUnavailable('DOWNLOAD_UNAVAILABLE')
            wrapped._ocr_diagnostic = getattr(failure, '_ocr_diagnostic', None)
            raise wrapped from None
        try:
            if response.status != 200:
                raise HttpFailure(response.status)
            if (
                response.getheader('Content-Type') != 'application/pdf'
                or response.getheader('Content-Length') != str(size)
                or response.getheader('X-Content-SHA256') != sha256
            ):
                raise InputIntegrityError('INPUT_MANIFEST')
            digest, total = hashlib.sha256(), 0
            with failure_scope(Reason.SPOOL_IO_FAILED):
                descriptor = os.open(
                    target,
                    os.O_WRONLY
                    | os.O_CREAT
                    | os.O_EXCL
                    | getattr(os, 'O_NOFOLLOW', 0)
                    | getattr(os, 'O_BINARY', 0),
                    0o600,
                )
            with os.fdopen(descriptor, 'wb') as output:
                while True:
                    if not live():
                        raise WorkerFailure(Reason.LEASE_LOST)
                    try:
                        chunk = response.read(min(65536, size - total + 1))
                    except (OSError, http.client.HTTPException) as failure:
                        transport_failure(
                            failure,
                            connection.correlation_id,
                            timed_out=connection.expired,
                            http_status=response.status,
                        )
                        wrapped = DownloadUnavailable('DOWNLOAD_UNAVAILABLE')
                        wrapped._ocr_diagnostic = failure._ocr_diagnostic
                        raise wrapped from None
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > size:
                        raise InputIntegrityError('INPUT_SIZE')
                    digest.update(chunk)
                    with failure_scope(Reason.SPOOL_IO_FAILED):
                        output.write(chunk)
            if total != size:
                raise DownloadUnavailable('DOWNLOAD_UNAVAILABLE')
            if digest.hexdigest() != sha256:
                raise InputIntegrityError('INPUT_INTEGRITY')
        except Exception as failure:
            transport_failure(
                failure,
                connection.correlation_id,
                timed_out=connection.expired,
                http_status=response.status,
            )
            raise
        finally:
            connection.close()
