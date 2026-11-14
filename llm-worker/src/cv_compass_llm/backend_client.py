"""Bounded authenticated backend HTTP; no proxies, redirects or source diagnostics."""

import json
import re
import time
from http.client import HTTPException
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

from .config import PullConfig, validate_backend_url
from .json_strict import strict_json

PREFIX = '/internal/mapping/v1'
INPUT_LIMIT = 16 * 1024 * 1024
CONTROL_LIMIT = 64 * 1024
# Never echo an arbitrary server string, even if it resembles an error code.
ERROR_CODES = frozenset('''UNAUTHORIZED TLS_REQUIRED MAPPING_REMOTE_UNAVAILABLE
INVALID_LEASE_TOKEN STALE_ATTEMPT LEASE_EXPIRED HARD_DEADLINE_EXCEEDED INPUT_CHANGED
IDEMPOTENCY_CONFLICT OUTCOME_CONFLICT INVALID_RESULT INVALID_EVIDENCE
UNSUPPORTED_PROTOCOL RESULT_TOO_LARGE UNSUPPORTED_MEDIA_TYPE'''.split())


def encode(payload: dict) -> bytes:
    """Serialize once; retries reuse these exact bytes."""
    return json.dumps(payload, ensure_ascii=False, allow_nan=False,
                      separators=(',', ':')).encode('utf-8')


class BackendError(Exception):
    """Retain only a fixed diagnostic code and HTTP status."""

    def __init__(self, code: str, status: int = 0) -> None:
        super().__init__(code)
        self.code = code
        self.status = status


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class BackendClient:
    def __init__(self, config: PullConfig) -> None:
        self.url = validate_backend_url(config.backend_url) + PREFIX
        self.token = config.worker_token
        self.timeout = config.http_timeout_s
        self.attempts = config.max_http_attempts
        self.opener = build_opener(ProxyHandler({}), _NoRedirect())

    def request(
        self, method: str, path: str, lease_token: str, body: bytes | None = None,
        *, claim_key: str | None = None, deadline: float | None = None,
    ) -> tuple[int, dict]:
        """Retry transport failures and 503 only, within the attempt's known lease."""
        if not re.fullmatch(r'/claims|/attempts/[0-9a-f-]{36}/(?:input|lease|result|failure)', path):
            raise BackendError('BACKEND_BAD_RESPONSE')
        headers = {'Authorization': 'Bearer ' + self.token, 'X-Lease-Token': lease_token,
                   'Content-Type': 'application/json', 'Accept': 'application/json'}
        if claim_key is not None:
            headers['Idempotency-Key'] = claim_key
        limit = INPUT_LIMIT if path.endswith('/input') else CONTROL_LIMIT
        for attempt in range(self.attempts):
            remaining = deadline - time.monotonic() if deadline is not None else self.timeout
            if remaining <= 0:
                raise BackendError('BACKEND_DEADLINE_EXCEEDED')
            try:
                request = Request(self.url + path, data=body, headers=headers, method=method)
                with self.opener.open(request, timeout=min(self.timeout, remaining)) as response:
                    status = response.status
                    data = response.read(limit + 1)
                if len(data) > limit:
                    raise BackendError('BACKEND_RESPONSE_TOO_LARGE')
                if status == 204:
                    return status, {}
                try:
                    root = strict_json(data.decode('utf-8'))
                    if not isinstance(root, dict) or not isinstance(root.get('data'), dict):
                        raise ValueError
                except (ValueError, UnicodeError, RecursionError):
                    raise BackendError('BACKEND_BAD_RESPONSE') from None
                return status, root['data']
            except HTTPError as error:
                status = error.code
                if 300 <= status < 400:
                    error.close()
                    raise BackendError('BACKEND_REDIRECT', status) from None
                with error:
                    try:
                        raw = error.read(CONTROL_LIMIT + 1)
                    except (OSError, HTTPException):
                        raw = b''
                code = 'BACKEND_HTTP_ERROR'
                try:
                    root = strict_json(raw.decode('utf-8')) if len(raw) <= CONTROL_LIMIT else {}
                    candidate = root.get('code') if isinstance(root, dict) else None
                    if isinstance(candidate, str) and candidate in ERROR_CODES:
                        code = candidate
                except (ValueError, UnicodeError, RecursionError):
                    pass
                failure = BackendError(code, status)
                if status != 503:
                    raise failure from None
            except (OSError, URLError, HTTPException):
                failure = BackendError('BACKEND_UNREACHABLE')
            if attempt + 1 == self.attempts:
                raise failure from None
            delay = min(2 ** attempt, 8)
            if deadline is not None and time.monotonic() + delay >= deadline:
                raise BackendError('BACKEND_DEADLINE_EXCEEDED')
            time.sleep(delay)
        raise BackendError('BACKEND_UNREACHABLE')
