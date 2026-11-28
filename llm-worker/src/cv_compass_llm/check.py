"""Read-only readiness probes with bounded, proxy-free, redirect-free HTTP."""

from http.client import HTTPException
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, Request, build_opener

from .backend_client import CONTROL_LIMIT, ERROR_CODES, PREFIX, _NoRedirect
from .config import PullConfigError, load_pull_config
from .json_strict import strict_json
from .progress import endpoint
from .providers.ollama import MAX_RESPONSE_BYTES, OllamaProvider

BACKEND_PROBE = PREFIX + '/check'


def _get(
    url: str,
    timeout: float,
    *,
    token: str | None = None,
    limit: int = MAX_RESPONSE_BYTES,
    headers: dict[str, str] | None = None,
    _opener=None,
) -> tuple[int, bytes]:
    headers = {'Accept': 'application/json'} | (headers or {})
    if token is not None:
        headers['Authorization'] = 'Bearer ' + token
    opener = build_opener(ProxyHandler({}), _NoRedirect()) if _opener is None else _opener
    try:
        response = opener.open(Request(url, headers=headers, method='GET'), timeout=timeout)
    except HTTPError as error:
        response = error
    with response:
        data = response.read(limit + 1)
        if len(data) > limit:
            raise ValueError('RESPONSE_TOO_LARGE')
        return response.code, data


def _backend_code(data: bytes) -> str | None:
    """Recognize the backend error envelope without exposing its source fields."""
    try:
        root = strict_json(data.decode('utf-8'))
        if (
            not isinstance(root, dict)
            or set(root) != {'code', 'message', 'correlationId', 'details'}
            or not isinstance(root['code'], str)
            or not isinstance(root['message'], str)
            or (root['correlationId'] is not None and not isinstance(root['correlationId'], str))
            or not isinstance(root['details'], list)
        ):
            return None
        code = root['code']
        return code if code in ERROR_CODES | {'NOT_FOUND'} else None
    except ValueError, UnicodeError, RecursionError:
        return None


def run_check(
    environ, env_file: Path | None, *, env_file_explicit: bool, cli=None, _opener=None
) -> int:
    """Never call claim/input/lease/result/failure or generate a model response."""
    try:
        config = load_pull_config(environ, env_file, env_file_explicit=env_file_explicit, cli=cli)
        llm = config.llm
        if llm.provider == 'ollama':
            OllamaProvider(
                llm.model,
                base_url=llm.base_url,
                timeout_s=llm.timeout_s,
                temperature=llm.temperature,
                think=llm.think,
                num_ctx=llm.num_ctx,
            )
    except PullConfigError as error:
        print('FAIL config code=' + str(error).split()[-1])
        return 2
    except ValueError as error:
        code = str(error)
        allowed = {'ENV_FILE_NOT_FOUND', 'INVALID_ENV_FILE', 'OLLAMA_HOST_NOT_LOCAL'}
        print('FAIL config code=' + (code if code in allowed else 'INVALID_CONFIG'))
        return 2
    print('OK config')
    failed = False

    def fail(name, code):
        nonlocal failed
        failed = True
        print(f'FAIL {name} code={code}')

    backend = 'backend ' + endpoint(config.backend_url)
    try:
        status, data = _get(
            config.backend_url + BACKEND_PROBE,
            config.http_timeout_s,
            token=config.worker_token,
            limit=CONTROL_LIMIT,
            **({'_opener': _opener} if _opener is not None else {}),
        )
        if 300 <= status < 400:
            fail(backend, 'BACKEND_REDIRECT')
            fail('backend_token', 'NOT_CHECKED')
        else:
            print('OK ' + backend)
            code = _backend_code(data)
            if status == 404 and code == 'NOT_FOUND':
                print('OK backend_token')
            elif (status, code) in (
                (401, 'UNAUTHORIZED'),
                (503, 'MAPPING_REMOTE_UNAVAILABLE'),
                (403, 'TLS_REQUIRED'),
            ):
                fail('backend_token', code)
            else:
                fail('backend_token', 'BACKEND_PROBE_REJECTED')
    except ValueError:
        fail(backend, 'BACKEND_RESPONSE_TOO_LARGE')
    except OSError, URLError, HTTPException:
        fail(backend, 'BACKEND_UNREACHABLE')

    if llm.provider in ('openai', 'anthropic'):
        api = 'api ' + llm.provider
        if llm.provider == 'openai':
            url = 'https://api.openai.com/v1/models'
            headers = {'Authorization': 'Bearer ' + llm.api_key}
        else:
            url = 'https://api.anthropic.com/v1/models'
            headers = {'x-api-key': llm.api_key, 'anthropic-version': '2023-06-01'}
        try:
            status, _ = _get(url, llm.timeout_s, headers=headers, _opener=_opener)
            if status == 200:
                print('OK ' + api)
            else:
                fail(api, f'API_HTTP_{status}')
        except ValueError:
            fail(api, 'API_RESPONSE_TOO_LARGE')
        except OSError, URLError, HTTPException:
            fail(api, 'API_UNREACHABLE')
        print('SKIP model')
        return 2 if failed else 0

    ollama = 'ollama ' + endpoint(llm.base_url)
    try:
        status, data = _get(llm.base_url.rstrip('/') + '/api/tags', llm.timeout_s)
        if status != 200:
            fail(ollama, 'OLLAMA_REDIRECT' if 300 <= status < 400 else 'OLLAMA_HTTP_ERROR')
        else:
            try:
                root = strict_json(data.decode('utf-8'))
                models = root['models']
                if not isinstance(models, list) or any(
                    not isinstance(row, dict) or not isinstance(row.get('name'), str)
                    for row in models
                ):
                    raise ValueError
                names = {row['name'] for row in models}
            except ValueError, UnicodeError, KeyError, TypeError, RecursionError:
                fail(ollama, 'OLLAMA_BAD_RESPONSE')
            else:
                print('OK ' + ollama)
                if llm.model in names or (
                    ':' not in llm.model.rsplit('/', 1)[-1] and llm.model + ':latest' in names
                ):
                    print('OK model')
                else:
                    fail('model', 'MODEL_NOT_FOUND')
    except ValueError:
        fail(ollama, 'OLLAMA_RESPONSE_TOO_LARGE')
    except OSError, URLError, HTTPException:
        fail(ollama, 'OLLAMA_UNREACHABLE')
    return 2 if failed else 0
