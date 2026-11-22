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
NORM_PREFIX = '/internal/normalization/v1'
INPUT_LIMIT = 16 * 1024 * 1024
CONTROL_LIMIT = 64 * 1024
# Never echo an arbitrary server string, even if it resembles an error code.
ERROR_CODES = frozenset(
    """UNAUTHORIZED TLS_REQUIRED MAPPING_REMOTE_UNAVAILABLE
INVALID_LEASE_TOKEN STALE_ATTEMPT LEASE_EXPIRED HARD_DEADLINE_EXCEEDED INPUT_CHANGED
IDEMPOTENCY_CONFLICT OUTCOME_CONFLICT INVALID_RESULT INVALID_EVIDENCE
UNSUPPORTED_PROTOCOL RESULT_TOO_LARGE UNSUPPORTED_MEDIA_TYPE""".split()
)
NORM_ERROR_CODES = ERROR_CODES | {'LEASE_LOST', 'NORMALIZATION_UNAVAILABLE'}


def encode(payload: dict) -> bytes:
    """Serialize once; retries reuse these exact bytes."""
    return json.dumps(payload, ensure_ascii=False, allow_nan=False, separators=(',', ':')).encode(
        'utf-8'
    )


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
    def __init__(self, config: PullConfig, *, prefix: str = PREFIX) -> None:
        if prefix not in (PREFIX, NORM_PREFIX):
            raise ValueError('INVALID_BACKEND_PREFIX')
        self.prefix = prefix
        self.url = validate_backend_url(config.backend_url) + prefix
        self.token = config.worker_token
        self.timeout = config.http_timeout_s
        self.attempts = config.max_http_attempts
        self.opener = build_opener(ProxyHandler({}), _NoRedirect())

    def claim_norm(self, maximum: int) -> dict | None:
        status, reply = self.request('POST', '/claims', '', encode({'max': maximum}))
        if status == 204:
            return None
        if status != 200:
            raise BackendError('BACKEND_BAD_RESPONSE')
        return reply

    def submit_norm(self, token: str, results: list[dict], *, deadline: float) -> dict:
        body = encode({'token': token, 'results': results})
        # Unlike mapping, normalization has no renew endpoint. Keep the same bytes until expiry.
        while True:
            try:
                status, reply = self.request('PUT', '/results', '', body, deadline=deadline)
                if status != 200:
                    raise BackendError('BACKEND_BAD_RESPONSE')
                return reply
            except BackendError as error:
                if error.status != 503:
                    raise
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise BackendError('BACKEND_DEADLINE_EXCEEDED') from None
                time.sleep(min(8, remaining / 2))

    def request(
        self,
        method: str,
        path: str,
        lease_token: str,
        body: bytes | None = None,
        *,
        claim_key: str | None = None,
        deadline: float | None = None,
    ) -> tuple[int, dict]:
        """Retry transport failures and 503 only, within the attempt's known lease."""
        normalization = self.prefix == NORM_PREFIX
        pattern = (
            r'/claims|/results'
            if normalization
            else r'/claims|/attempts/[0-9a-f-]{36}/(?:input|lease|result|failure)'
        )
        if not re.fullmatch(pattern, path):
            raise BackendError('BACKEND_BAD_RESPONSE')
        headers = {
            'Authorization': 'Bearer ' + self.token,
            'X-Lease-Token': lease_token,
            'Content-Type': 'application/json',
            'Accept': 'application/json',
        }
        if normalization:
            del headers['X-Lease-Token']
        if claim_key is not None:
            headers['Idempotency-Key'] = claim_key
        limit = INPUT_LIMIT if normalization or path.endswith('/input') else CONTROL_LIMIT
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
                    if not isinstance(root, dict) or (
                        not normalization and not isinstance(root.get('data'), dict)
                    ):
                        raise ValueError
                except ValueError, UnicodeError, RecursionError:
                    raise BackendError('BACKEND_BAD_RESPONSE') from None
                return status, root if normalization else root['data']
            except HTTPError as error:
                status = error.code
                if 300 <= status < 400:
                    error.close()
                    raise BackendError('BACKEND_REDIRECT', status) from None
                with error:
                    try:
                        raw = error.read(CONTROL_LIMIT + 1)
                    except OSError, HTTPException:
                        raw = b''
                code = 'BACKEND_HTTP_ERROR'
                try:
                    root = strict_json(raw.decode('utf-8')) if len(raw) <= CONTROL_LIMIT else {}
                    candidate = root.get('code') if isinstance(root, dict) else None
                    allowed_codes = NORM_ERROR_CODES if normalization else ERROR_CODES
                    if isinstance(candidate, str) and candidate in allowed_codes:
                        code = candidate
                except ValueError, UnicodeError, RecursionError:
                    pass
                failure = BackendError(code, status)
                if status != 503:
                    raise failure from None
            except OSError, URLError, HTTPException:
                failure = BackendError('BACKEND_UNREACHABLE')
            if attempt + 1 == self.attempts:
                raise failure from None
            delay = min(2**attempt, 8)
            if deadline is not None and time.monotonic() + delay >= deadline:
                if not normalization:
                    raise BackendError('BACKEND_DEADLINE_EXCEEDED')
                delay = max(0, (deadline - time.monotonic()) / 2)
            time.sleep(delay)
        raise BackendError('BACKEND_UNREACHABLE')
