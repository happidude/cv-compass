"""Read local configuration without mutating the process environment."""

import math
import re
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit

ENV_KEYS = {
    'provider': 'CVC_LLM_PROVIDER',
    'model': 'CVC_LLM_MODEL',
    'base_url': 'CVC_LLM_BASE_URL',
    'timeout': 'CVC_LLM_TIMEOUT_S',
    'max_blocks': 'CVC_LLM_MAX_BLOCKS',
    'temperature': 'CVC_LLM_TEMPERATURE',
    'think': 'CVC_LLM_THINK',
    'num_ctx': 'CVC_LLM_NUM_CTX',
    'backend_url': 'CVC_LLM_BACKEND_URL',
    'worker_token': 'CVC_LLM_WORKER_TOKEN',
    'worker_version': 'CVC_LLM_WORKER_VERSION',
    'poll_s': 'CVC_LLM_POLL_S',
    'http_timeout_s': 'CVC_LLM_HTTP_TIMEOUT_S',
    'max_http_attempts': 'CVC_LLM_MAX_HTTP_ATTEMPTS',
    'log_level': 'CVC_LLM_LOG_LEVEL',
}
MAX_ENV_BYTES = 64 * 1024


@dataclass(frozen=True)
class LlmConfig:
    provider: str
    model: str
    base_url: str
    timeout_s: float
    max_blocks: int
    temperature: float
    think: bool
    num_ctx: int | None


def default_env_file() -> Path:
    """Resolve the worker's dotenv file independently of the current directory."""
    return Path(__file__).resolve().parents[2] / '.env'


def _read_env(path: Path, explicit: bool) -> dict[str, str]:
    try:
        with path.open('rb') as stream:
            data = stream.read(MAX_ENV_BYTES + 1)
    except FileNotFoundError:
        if explicit:
            raise ValueError('ENV_FILE_NOT_FOUND') from None
        return {}
    except (OSError, ValueError):
        raise ValueError('INVALID_ENV_FILE') from None
    try:
        if len(data) > MAX_ENV_BYTES:
            raise ValueError
        text = data.decode('utf-8', errors='strict')
        values = {}
        for line in text.splitlines():
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            key, separator, value = line.partition('=')
            key, value = key.strip(), value.strip()
            if not separator or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', key):
                raise ValueError
            if value.startswith(('"', "'")):
                if len(value) < 2 or value[-1] != value[0]:
                    raise ValueError
                value = value[1:-1].strip()
            if key.startswith('CVC_LLM_'):
                if key == 'CVC_LLM_WORKER_TOKEN':
                    raise PullConfigError(key, 'TOKEN_IN_ENV_FILE')
                if key not in ENV_KEYS.values() or key in values:
                    raise ValueError
                values[key] = value
        return values
    except PullConfigError:
        raise
    except (ValueError, UnicodeError):
        raise ValueError('INVALID_ENV_FILE') from None


def _number(value: str, code: str, *, minimum: float, maximum: float) -> float:
    try:
        number = float(value)
        if not math.isfinite(number) or not minimum <= number <= maximum:
            raise ValueError
        return number
    except ValueError:
        raise ValueError(code) from None


def _integer(value: str, code: str, *, minimum: int, maximum: int | None = None) -> int:
    try:
        number = int(value)
        if number < minimum or (maximum is not None and number > maximum):
            raise ValueError
        return number
    except ValueError:
        raise ValueError(code) from None


def load_config(
    cli: Mapping[str, str | None], environ: Mapping[str, str], env_file: Path | None,
    *, env_file_explicit: bool,
    _file_values: Mapping[str, str] | None = None,
) -> LlmConfig:
    """Resolve each CLI destination name over environment and dotenv values."""
    file_values = _file_values if _file_values is not None else _read_env(
        env_file if env_file is not None else default_env_file(), env_file_explicit,
    )

    def value(name: str, default: str = '') -> str:
        for candidate in (cli.get(name), environ.get(ENV_KEYS[name]),
                          file_values.get(ENV_KEYS[name])):
            if candidate is not None and candidate.strip():
                return candidate.strip()
        return default

    provider, model = value('provider'), value('model')
    if not provider or not model:
        raise ValueError('MISSING_CONFIG')
    if provider != 'ollama':
        raise ValueError('INVALID_PROVIDER')
    timeout = _number(value('timeout', '120'), 'INVALID_TIMEOUT',
                      minimum=0, maximum=math.inf)
    if timeout <= 0:
        raise ValueError('INVALID_TIMEOUT')
    max_blocks = _integer(value('max_blocks', '20'), 'INVALID_MAX_BLOCKS', minimum=0)
    temperature = _number(value('temperature', '0'), 'INVALID_TEMPERATURE',
                          minimum=0, maximum=2)
    think = value('think', 'false').lower()
    if think not in ('true', 'false'):
        raise ValueError('INVALID_THINK')
    num_ctx = value('num_ctx', '8192')
    return LlmConfig(
        provider=provider, model=model,
        base_url=value('base_url', 'http://127.0.0.1:11434'), timeout_s=timeout,
        max_blocks=max_blocks, temperature=temperature, think=think == 'true',
        num_ctx=_integer(num_ctx, 'INVALID_NUM_CTX', minimum=1, maximum=1048576),
    )


class PullConfigError(ValueError):
    """Configuration diagnostics contain only a known key and fixed code."""

    def __init__(self, key: str, code: str) -> None:
        super().__init__(f'{key} {code}')


@dataclass(frozen=True, repr=False)
class PullConfig:
    llm: LlmConfig
    backend_url: str
    worker_token: str
    worker_version: str
    poll_s: int
    http_timeout_s: int
    max_http_attempts: int
    log_level: str = 'INFO'


def validate_backend_url(value: str) -> str:
    """Allow plaintext only for the three explicitly approved loopback hosts."""
    try:
        url = urlsplit(value)
        valid = (
            url.scheme in ('http', 'https') and bool(url.hostname)
            and (url.scheme == 'https' or url.hostname in ('127.0.0.1', 'localhost', '::1'))
            and url.username is None and url.password is None and url.path == ''
            and '?' not in value and '#' not in value and '\\' not in value
            and not url.netloc.endswith(':')
            and (url.port is None or 0 < url.port <= 65535)
            and not any(ord(char) <= 32 or ord(char) == 127 for char in value)
        )
    except (ValueError, TypeError):
        valid = False
    if not valid:
        raise PullConfigError(ENV_KEYS['backend_url'], 'BACKEND_URL_NOT_ALLOWED')
    return value


def load_pull_config(
    environ: Mapping[str, str], env_file: Path | None, *, env_file_explicit: bool,
    cli: Mapping[str, str | None] | None = None,
) -> PullConfig:
    """Extend the shared dotenv parser; credentials come only from the process."""
    path = env_file if env_file is not None else default_env_file()
    values = _read_env(path, env_file_explicit)

    def value(name: str, default: str = '') -> str:
        key = ENV_KEYS[name]
        return (((cli or {}).get(name) or '').strip() or environ.get(key, '').strip()
                or values.get(key, '').strip() or default)

    backend = value('backend_url')
    if not backend:
        raise PullConfigError(ENV_KEYS['backend_url'], 'MISSING_CONFIG')
    backend = validate_backend_url(backend)
    token = environ.get(ENV_KEYS['worker_token'], '')
    if not token:
        raise PullConfigError(ENV_KEYS['worker_token'], 'MISSING_CONFIG')
    if not re.fullmatch(r'[A-Za-z0-9_-]{43,256}', token):
        raise PullConfigError(ENV_KEYS['worker_token'], 'INVALID_WORKER_TOKEN')
    version = value('worker_version', 'dev')
    if not re.fullmatch(r'[A-Za-z0-9._:/@+-]{1,128}', version):
        raise PullConfigError(ENV_KEYS['worker_version'], 'INVALID_PROVENANCE')
    numbers = {}
    for name, default, maximum in (
        ('poll_s', '5', 300), ('http_timeout_s', '10', 120),
        ('max_http_attempts', '3', 10),
    ):
        try:
            numbers[name] = _integer(value(name, default), 'INVALID_CONFIG',
                                     minimum=1, maximum=maximum)
        except ValueError:
            raise PullConfigError(ENV_KEYS[name], 'INVALID_CONFIG') from None
    # Reuse the same parsed snapshot so dotenv cannot change between the two loads.
    merged = dict(values)
    merged.update(environ)
    try:
        llm = load_config({}, merged, path, env_file_explicit=env_file_explicit,
                          _file_values=values)
    except ValueError as error:
        code = str(error)
        names = {
            'INVALID_PROVIDER': 'provider', 'INVALID_TIMEOUT': 'timeout',
            'INVALID_MAX_BLOCKS': 'max_blocks', 'INVALID_TEMPERATURE': 'temperature',
            'INVALID_THINK': 'think', 'INVALID_NUM_CTX': 'num_ctx',
        }
        name = names.get(code, 'provider' if not value('provider') else 'model')
        raise PullConfigError(ENV_KEYS[name], code) from None
    if not re.fullmatch(r'[A-Za-z0-9._:/@+-]{1,128}', llm.model):
        raise PullConfigError(ENV_KEYS['model'], 'INVALID_PROVENANCE')
    log_level = value('log_level', 'INFO')
    if log_level not in ('DEBUG', 'INFO', 'WARNING', 'ERROR'):
        raise PullConfigError(ENV_KEYS['log_level'], 'INVALID_LOG_LEVEL')
    return PullConfig(llm, backend, token, version, **numbers, log_level=log_level)
