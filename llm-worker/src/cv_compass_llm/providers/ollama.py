"""Bounded local Ollama transport with no proxies or redirects."""

import json
import math
from email.message import Message
from http.client import HTTPException
from typing import BinaryIO
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit, urlunsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

from ..json_strict import strict_json
from .base import ProviderError

MAX_RESPONSE_BYTES = 4 * 1024 * 1024


def _validate_call_options(num_ctx: int | None, timeout_s: float) -> None:
    if (
        isinstance(timeout_s, bool)
        or not isinstance(timeout_s, (int, float))
        or not math.isfinite(timeout_s)
        or timeout_s <= 0
    ):
        raise ValueError('INVALID_TIMEOUT')
    if num_ctx is not None and (type(num_ctx) is not int or not 1 <= num_ctx <= 1048576):
        raise ValueError('INVALID_NUM_CTX')


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(
        self,
        req: Request,
        fp: BinaryIO,
        code: int,
        msg: str,
        headers: Message,
        newurl: str,
    ) -> None:
        """Reject redirects before forwarding any request data."""
        return None


class OllamaProvider:
    def __init__(
        self,
        model: str,
        *,
        base_url: str = 'http://127.0.0.1:11434',
        timeout_s: float = 120.0,
        temperature: float = 0.0,
        think: bool = False,
        num_ctx: int | None = None,
    ) -> None:
        """Validate the local endpoint and timeout for each socket operation."""
        try:
            url = urlsplit(base_url)
            valid = (
                url.scheme == 'http'
                and url.hostname in ('127.0.0.1', 'localhost', '::1')
                and url.username is None
                and url.password is None
                and url.path in ('', '/')
                and '?' not in base_url
                and '#' not in base_url
                and (url.port is None or 0 < url.port <= 65535)
                and not any(character.isspace() for character in base_url)
            )
        except ValueError, TypeError:
            valid = False
        if not valid:
            raise ValueError('OLLAMA_HOST_NOT_LOCAL')
        if not isinstance(model, str) or not model.strip():
            raise ValueError('INVALID_MODEL')
        _validate_call_options(num_ctx, timeout_s)
        if (
            isinstance(temperature, bool)
            or not isinstance(temperature, (int, float))
            or not 0 <= temperature <= 2
            or not math.isfinite(temperature)
        ):
            raise ValueError('INVALID_TEMPERATURE')
        if type(think) is not bool:
            raise ValueError('INVALID_THINK')
        self.model = model
        self.name = 'ollama:' + model
        self.url = urlunsplit((url.scheme, url.netloc, '/api/chat', '', ''))
        self.timeout_s = timeout_s
        self.temperature = temperature
        self.think = think
        self.num_ctx = num_ctx
        self.last_metrics: dict[str, int | str] = {}
        self._opener = build_opener(ProxyHandler({}), _NoRedirect())

    def generate(
        self,
        prompt: str,
        json_schema: dict,
        *,
        num_ctx: int | None = None,
        timeout_s: float | None = None,
    ) -> str:
        """Return bounded chat content or a content-free transport error."""
        self.last_metrics = {}
        num_ctx = self.num_ctx if num_ctx is None else num_ctx
        timeout_s = self.timeout_s if timeout_s is None else timeout_s
        _validate_call_options(num_ctx, timeout_s)
        payload = {
            'model': self.model,
            'messages': [{'role': 'user', 'content': prompt}],
            'format': json_schema,
            'stream': False,
            'think': self.think,
            'options': {'temperature': self.temperature if self.temperature else 0},
        }
        if num_ctx is not None:
            payload['options']['num_ctx'] = num_ctx
        try:
            request = Request(
                self.url,
                data=json.dumps(payload).encode('utf-8'),
                headers={'Content-Type': 'application/json'},
                method='POST',
            )
            with self._opener.open(request, timeout=timeout_s) as response:
                data = response.read(MAX_RESPONSE_BYTES + 1)
        except HTTPError as error:
            code = error.code
            error.close()
            raise ProviderError(f'OLLAMA_HTTP_{code}') from None
        except TimeoutError:
            raise ProviderError('OLLAMA_TIMEOUT') from None
        except URLError as error:
            code = (
                'OLLAMA_TIMEOUT' if isinstance(error.reason, TimeoutError) else 'OLLAMA_UNREACHABLE'
            )
            raise ProviderError(code) from None
        except OSError:
            raise ProviderError('OLLAMA_UNREACHABLE') from None
        except HTTPException:
            raise ProviderError('OLLAMA_BAD_RESPONSE') from None
        try:
            if len(data) > MAX_RESPONSE_BYTES:
                raise ValueError
            root = strict_json(data.decode('utf-8'))
            content = root['message']['content']
            if not isinstance(content, str):
                raise ValueError
        except ValueError, UnicodeError, KeyError, TypeError:
            raise ProviderError('OLLAMA_BAD_RESPONSE') from None
        for source, target in (
            ('prompt_eval_count', 'prompt_tokens'),
            ('eval_count', 'output_tokens'),
            ('prompt_eval_duration', 'prompt_ns'),
            ('eval_duration', 'output_ns'),
            ('load_duration', 'load_ns'),
            ('total_duration', 'total_ns'),
        ):
            value = root.get(source)
            if type(value) is int and 0 <= value <= 2**53:
                self.last_metrics[target] = value
        reason = root.get('done_reason')
        if reason in ('stop', 'length', 'load', 'unload'):
            self.last_metrics['done_reason'] = reason
        return content
