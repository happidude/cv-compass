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


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(
        self, req: Request, fp: BinaryIO, code: int, msg: str, headers: Message, newurl: str,
    ) -> None:
        """Reject redirects before forwarding any request data."""
        return None


class OllamaProvider:
    def __init__(
        self, model: str, *, base_url: str = 'http://127.0.0.1:11434',
        timeout_s: float = 120.0,
    ) -> None:
        """Validate the local endpoint and timeout for each socket operation."""
        try:
            url = urlsplit(base_url)
            valid = (
                url.scheme == 'http' and url.hostname in ('127.0.0.1', 'localhost', '::1')
                and url.username is None and url.password is None
                and url.path in ('', '/') and '?' not in base_url and '#' not in base_url
                and (url.port is None or 0 < url.port <= 65535)
                and not any(character.isspace() for character in base_url)
            )
        except (ValueError, TypeError):
            valid = False
        if not valid:
            raise ValueError('OLLAMA_HOST_NOT_LOCAL')
        if not isinstance(model, str) or not model.strip():
            raise ValueError('INVALID_MODEL')
        if (isinstance(timeout_s, bool) or not isinstance(timeout_s, (int, float))
                or not math.isfinite(timeout_s) or timeout_s <= 0):
            raise ValueError('INVALID_TIMEOUT')
        self.model = model
        self.name = 'ollama:' + model
        self.url = urlunsplit((url.scheme, url.netloc, '/api/chat', '', ''))
        self.timeout_s = timeout_s
        self._opener = build_opener(ProxyHandler({}), _NoRedirect())

    def generate(self, prompt: str, json_schema: dict) -> str:
        """Return bounded chat content or a content-free transport error."""
        payload = {
            'model': self.model, 'messages': [{'role': 'user', 'content': prompt}],
            'format': json_schema, 'stream': False, 'think': False,
            'options': {'temperature': 0},
        }
        try:
            request = Request(
                self.url, data=json.dumps(payload).encode('utf-8'),
                headers={'Content-Type': 'application/json'}, method='POST',
            )
            with self._opener.open(request, timeout=self.timeout_s) as response:
                data = response.read(MAX_RESPONSE_BYTES + 1)
        except HTTPError as error:
            code = error.code
            error.close()
            raise ProviderError(f'OLLAMA_HTTP_{code}') from None
        except TimeoutError:
            raise ProviderError('OLLAMA_TIMEOUT') from None
        except URLError as error:
            code = (
                'OLLAMA_TIMEOUT' if isinstance(error.reason, TimeoutError)
                else 'OLLAMA_UNREACHABLE'
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
        except (ValueError, UnicodeError, KeyError, TypeError):
            raise ProviderError('OLLAMA_BAD_RESPONSE') from None
        return content
