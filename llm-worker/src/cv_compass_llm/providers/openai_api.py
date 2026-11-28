"""Bounded API transport and OpenAI structured JSON responses."""

import copy
import json
import re
import time
from http.client import HTTPException
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import ProxyHandler, Request, build_opener

from ..config import LlmConfig, validate_api_key
from ..json_strict import strict_json
from .base import ProviderError
from .ollama import MAX_RESPONSE_BYTES, _NoRedirect, _validate_call_options

OPENAI_URL = 'https://api.openai.com/v1/responses'


class _ApiProvider:
    """Share credential validation, bounded reads and content-free retry diagnostics."""

    provider = 'openai'
    api_host = 'api.openai.com'
    url = OPENAI_URL
    default_effort = 'none'
    efforts = ('none', 'low', 'medium', 'high')

    def __init__(
        self,
        model: str,
        *,
        api_key: str = '',
        timeout_s: float = 120.0,
        reasoning_effort: str | None = None,
        max_output_tokens: int | None = None,
        _opener=None,
        _sleep=time.sleep,
    ) -> None:
        if not self._host_allowed():
            raise ValueError('API_HOST_NOT_ALLOWED')
        validate_api_key(api_key)
        if not isinstance(model, str) or not model.strip():
            raise ValueError('INVALID_MODEL')
        _validate_call_options(None, timeout_s)
        if max_output_tokens is not None and (
            type(max_output_tokens) is not int or max_output_tokens < 1
        ):
            raise ValueError('INVALID_MAX_OUTPUT_TOKENS')
        effort = self.default_effort if reasoning_effort is None else reasoning_effort
        if effort not in self.efforts:
            raise ValueError('INVALID_REASONING_EFFORT')
        self.model = model
        self.name = self.provider + ':' + model
        self._api_key = api_key
        self.timeout_s = timeout_s
        self.reasoning_effort = effort
        self.max_output_tokens = max_output_tokens
        self.num_ctx = None
        self.last_metrics: dict[str, int | str] = {}
        self.extraction_config: LlmConfig | None = None
        self._opener = build_opener(ProxyHandler({}), _NoRedirect()) if _opener is None else _opener
        self._sleep = _sleep

    def _host_allowed(self) -> bool:
        """Validate the provider's own HTTPS origin even when assertions are disabled."""
        try:
            url = urlsplit(self.url)
            return (
                url.scheme == 'https'
                and url.hostname == self.api_host
                and url.username is None
                and url.password is None
                and url.port in (None, 443)
                and not url.netloc.endswith(':')
                and '\\' not in self.url
                and not any(ord(char) <= 32 or ord(char) == 127 for char in self.url)
            )
        except ValueError, TypeError, AttributeError:
            return False

    def _request(self, payload, timeout_s):
        if not self._host_allowed():
            raise ProviderError('API_HOST_NOT_ALLOWED') from None
        request = Request(
            self.url,
            data=json.dumps(payload).encode('utf-8'),
            headers=self._headers(),
            method='POST',
        )
        for attempt in range(4):
            try:
                with self._opener.open(request, timeout=timeout_s) as response:
                    # Also reject non-success responses from injected/custom openers.
                    code = getattr(response, 'status', getattr(response, 'code', 200))
                    if not 200 <= code < 300:
                        raise HTTPError(
                            self.url, code, '', getattr(response, 'headers', None), None
                        )
                    data = response.read(MAX_RESPONSE_BYTES + 1)
                if len(data) > MAX_RESPONSE_BYTES:
                    raise ProviderError('API_BAD_RESPONSE') from None
                return data
            except HTTPError as error:
                code = error.code
                retry_after = error.headers.get('Retry-After', '') if error.headers else ''
                error.close()
                if attempt == 3 or not (code == 429 or 500 <= code <= 599):
                    raise ProviderError(f'API_HTTP_{code}') from None
                delay = 2**attempt
                if re.fullmatch(r'[0-9]+', retry_after):
                    # Bound before conversion, including arbitrarily long header values.
                    digits = retry_after.lstrip('0') or '0'
                    delay = 30 if len(digits) > 2 else min(int(digits), 30)
                self._sleep(delay)
            except TimeoutError:
                raise ProviderError('API_TIMEOUT') from None
            except URLError as error:
                code = (
                    'API_TIMEOUT' if isinstance(error.reason, TimeoutError) else 'API_UNREACHABLE'
                )
                raise ProviderError(code) from None
            except OSError:
                raise ProviderError('API_UNREACHABLE') from None
            except HTTPException:
                raise ProviderError('API_BAD_RESPONSE') from None

    def generate(
        self,
        prompt: str,
        json_schema: dict,
        *,
        num_ctx: int | None = None,
        timeout_s: float | None = None,
    ) -> str:
        """Return JSON text; per-call context overrides have no meaning for API providers."""
        self.last_metrics = {}
        timeout_s = self.timeout_s if timeout_s is None else timeout_s
        _validate_call_options(None, timeout_s)
        started = time.perf_counter_ns()
        try:
            data = self._request(self._payload(prompt, json_schema), timeout_s)
            try:
                root = strict_json(data.decode('utf-8'))
                text, reason = self._text(root)
                if not text:
                    raise ValueError
            except ValueError, UnicodeError, KeyError, TypeError, AttributeError, RecursionError:
                raise ProviderError('API_BAD_RESPONSE') from None
            usage = root.get('usage')
            if isinstance(usage, dict):
                for source, target in (
                    ('input_tokens', 'prompt_tokens'),
                    ('output_tokens', 'output_tokens'),
                ):
                    value = usage.get(source)
                    if type(value) is int and 0 <= value <= 2**53:
                        self.last_metrics[target] = value
            self.last_metrics['done_reason'] = reason
            return text
        finally:
            self.last_metrics['total_ns'] = time.perf_counter_ns() - started


class OpenAIProvider(_ApiProvider):
    def _headers(self):
        return {'Authorization': 'Bearer ' + self._api_key, 'Content-Type': 'application/json'}

    def _payload(self, prompt, json_schema):
        payload = {
            'model': self.model,
            'input': prompt,
            'text': {
                'format': {
                    'type': 'json_schema',
                    'name': 'cv_extraction',
                    'schema': copy.deepcopy(json_schema),
                    'strict': True,
                }
            },
            'reasoning': {'effort': self.reasoning_effort},
            'store': False,
        }
        if self.max_output_tokens is not None:
            payload['max_output_tokens'] = self.max_output_tokens
        return payload

    def _text(self, root):
        status = root['status']
        reason = 'stop'
        if status == 'incomplete' and root['incomplete_details']['reason'] == 'max_output_tokens':
            reason = 'length'
        elif status != 'completed':
            raise ValueError
        parts = []
        for output in root['output']:
            if output.get('type') == 'refusal':
                raise ValueError
            if output.get('type') != 'message':
                continue
            for content in output['content']:
                if content.get('type') == 'refusal':
                    raise ValueError
                if content.get('type') == 'output_text':
                    parts.append(content['text'])
        return ''.join(parts), reason
