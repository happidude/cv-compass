"""Read local configuration without mutating the process environment."""

import math
import os
import re
import socket
from collections.abc import Mapping
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlsplit

ENV_KEYS = {
    'provider': 'CVC_LLM_PROVIDER',
    'model': 'CVC_LLM_MODEL',
    'fallback_model': 'CVC_LLM_FALLBACK_MODEL',
    'fallback_drop_ratio': 'CVC_LLM_FALLBACK_DROP_RATIO',
    'base_url': 'CVC_LLM_BASE_URL',
    'timeout': 'CVC_LLM_TIMEOUT_S',
    'mode': 'CVC_LLM_MODE',
    'evidence': 'CVC_LLM_EVIDENCE',
    'name_style': 'CVC_LLM_NAME_STYLE',
    'name_trim': 'CVC_LLM_NAME_TRIM',
    'dates': 'CVC_LLM_DATES',
    'trim_input': 'CVC_LLM_TRIM_INPUT',
    'trim_sections': 'CVC_LLM_TRIM_SECTIONS',
    'trim_phrases': 'CVC_LLM_TRIM_PHRASES',
    'whole_timeout': 'CVC_LLM_WHOLE_TIMEOUT_S',
    'max_blocks': 'CVC_LLM_MAX_BLOCKS',
    'temperature': 'CVC_LLM_TEMPERATURE',
    'think': 'CVC_LLM_THINK',
    'num_ctx': 'CVC_LLM_NUM_CTX',
    'prompt_schema_text': 'CVC_LLM_PROMPT_SCHEMA_TEXT',
    'whole_personal': 'CVC_LLM_WHOLE_PERSONAL',
    'max_output_tokens': 'CVC_LLM_MAX_OUTPUT_TOKENS',
    'keep_alive': 'CVC_LLM_KEEP_ALIVE',
    'ctx_chars_per_token': 'CVC_LLM_CTX_CHARS_PER_TOKEN',
    'ctx_fine_tiers': 'CVC_LLM_CTX_FINE_TIERS',
    'ctx_guard': 'CVC_LLM_CTX_GUARD',
    'backend_url': 'CVC_LLM_BACKEND_URL',
    'worker_token': 'CVC_LLM_WORKER_TOKEN',
    'worker_version': 'CVC_LLM_WORKER_VERSION',
    'worker_name': 'CVC_LLM_WORKER_NAME',
    'poll_s': 'CVC_LLM_POLL_S',
    'http_timeout_s': 'CVC_LLM_HTTP_TIMEOUT_S',
    'max_http_attempts': 'CVC_LLM_MAX_HTTP_ATTEMPTS',
    'log_level': 'CVC_LLM_LOG_LEVEL',
}
MAX_ENV_BYTES = 64 * 1024
# Keep benchmark overrides separate from pull credentials and transport settings.
BENCH_ENV_KEYS = frozenset(
    ENV_KEYS[name]
    for name in (
        'provider',
        'model',
        'fallback_model',
        'fallback_drop_ratio',
        'base_url',
        'timeout',
        'mode',
        'evidence',
        'name_style',
        'name_trim',
        'dates',
        'trim_input',
        'trim_sections',
        'trim_phrases',
        'whole_timeout',
        'max_blocks',
        'temperature',
        'think',
        'num_ctx',
        'prompt_schema_text',
        'whole_personal',
        'max_output_tokens',
        'keep_alive',
        'ctx_chars_per_token',
        'ctx_fine_tiers',
        'ctx_guard',
    )
)


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
    mode: str = 'blocks'
    whole_timeout: float = 900
    prompt_schema_text: bool = True
    whole_personal: bool = True
    max_output_tokens: int | None = None
    keep_alive: str | None = None
    ctx_chars_per_token: float = 2.5
    ctx_fine_tiers: bool = False
    ctx_guard: bool = False
    evidence: str = 'quote'
    trim_input: bool = False
    trim_sections: bool = False
    trim_phrases: tuple[str, ...] = ()
    fallback_model: str = ''
    fallback_drop_ratio: float = 0.3
    name_style: str = 'raw'
    dates: str = 'v1'
    name_trim: str = 'off'

    @property
    def provenance_model(self) -> str:
        """Keep claim and submit model identity independent of each CV's route."""
        if not self.fallback_model:
            return self.model
        model = f'{self.model}+fallback:{self.fallback_model}'
        if not re.fullmatch(r'[A-Za-z0-9._:/@+-]{1,128}', model):
            raise ValueError('INVALID_FALLBACK_MODEL')
        return model


def default_env_file() -> Path:
    """Resolve the worker's dotenv file independently of the current directory."""
    return Path(__file__).resolve().parents[2] / '.env'


def _read_env(
    path: Path, explicit: bool, *, allowed_keys: frozenset[str] | None = None
) -> dict[str, str]:
    try:
        with path.open('rb') as stream:
            data = stream.read(MAX_ENV_BYTES + 1)
    except FileNotFoundError:
        if explicit:
            raise ValueError('ENV_FILE_NOT_FOUND') from None
        return {}
    except OSError, ValueError:
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
            if allowed_keys is not None and key not in allowed_keys:
                raise ValueError('BENCH_INVALID_CONFIG_KEY')
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
    except ValueError as error:
        if str(error) == 'BENCH_INVALID_CONFIG_KEY':
            raise
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


def validate_keep_alive(value: str | None) -> None:
    """Accept only the approved Ollama duration syntax without echoing values."""
    if value is not None and (
        not isinstance(value, str) or not re.fullmatch(r'(?:[0-9]{1,6}(?:ms|s|m|h)?|-1)', value)
    ):
        raise ValueError('INVALID_KEEP_ALIVE')


def load_config(
    cli: Mapping[str, str | None],
    environ: Mapping[str, str],
    env_file: Path | None,
    *,
    env_file_explicit: bool,
    _file_values: Mapping[str, str] | None = None,
) -> LlmConfig:
    """Resolve each CLI destination name over environment and dotenv values."""
    file_values = (
        _file_values
        if _file_values is not None
        else _read_env(
            env_file if env_file is not None else default_env_file(),
            env_file_explicit,
        )
    )

    def value(name: str, default: str = '') -> str:
        for candidate in (
            cli.get(name),
            environ.get(ENV_KEYS[name]),
            file_values.get(ENV_KEYS[name]),
        ):
            if name == 'fallback_model' and candidate is not None:
                return candidate.strip()
            if candidate is not None and candidate.strip():
                return candidate.strip()
        return default

    provider, model = value('provider'), value('model')
    if not provider or not model:
        raise ValueError('MISSING_CONFIG')
    if provider != 'ollama':
        raise ValueError('INVALID_PROVIDER')
    timeout = _number(value('timeout', '120'), 'INVALID_TIMEOUT', minimum=0, maximum=math.inf)
    if timeout <= 0:
        raise ValueError('INVALID_TIMEOUT')
    mode = value('mode', 'blocks')
    if mode not in ('blocks', 'whole'):
        raise ValueError('INVALID_MODE')
    evidence = value('evidence', 'quote')
    if evidence not in ('quote', 'lines'):
        raise ValueError('INVALID_EVIDENCE')
    name_style = value('name_style', 'raw')
    if name_style not in ('raw', 'clean'):
        raise ValueError('INVALID_NAME_STYLE')
    name_trim = value('name_trim', 'off')
    if name_trim not in ('off', 'on'):
        raise ValueError('INVALID_NAME_TRIM')
    dates = value('dates', 'v1')
    if dates not in ('v1', 'v2'):
        raise ValueError('INVALID_DATES')
    whole_timeout = _number(
        value('whole_timeout', '900'), 'INVALID_WHOLE_TIMEOUT', minimum=0, maximum=math.inf
    )
    if whole_timeout <= 0:
        raise ValueError('INVALID_WHOLE_TIMEOUT')
    max_blocks = _integer(value('max_blocks', '20'), 'INVALID_MAX_BLOCKS', minimum=0)
    temperature = _number(value('temperature', '0'), 'INVALID_TEMPERATURE', minimum=0, maximum=2)
    think = value('think', 'false').lower()
    if think not in ('true', 'false'):
        raise ValueError('INVALID_THINK')
    num_ctx = value('num_ctx', '8192')
    flags = {}
    for name, default in (
        ('prompt_schema_text', 'true'),
        ('whole_personal', 'true'),
        ('ctx_fine_tiers', 'false'),
        ('ctx_guard', 'false'),
        ('trim_input', 'false'),
        ('trim_sections', 'false'),
    ):
        flag = value(name, default).lower()
        if flag not in ('true', 'false'):
            raise ValueError('INVALID_' + name.upper())
        flags[name] = flag == 'true'
    max_output_tokens = value('max_output_tokens')
    keep_alive = value('keep_alive') or None
    validate_keep_alive(keep_alive)
    fallback_drop_ratio = _number(
        value('fallback_drop_ratio', '0.3'),
        'INVALID_FALLBACK_DROP_RATIO',
        minimum=0,
        maximum=1,
    )
    if fallback_drop_ratio == 0:
        raise ValueError('INVALID_FALLBACK_DROP_RATIO')
    config = LlmConfig(
        provider=provider,
        model=model,
        fallback_model=value('fallback_model'),
        fallback_drop_ratio=fallback_drop_ratio,
        base_url=value('base_url', 'http://127.0.0.1:11434'),
        timeout_s=timeout,
        max_blocks=max_blocks,
        temperature=temperature,
        think=think == 'true',
        num_ctx=_integer(num_ctx, 'INVALID_NUM_CTX', minimum=1, maximum=1048576),
        mode=mode,
        evidence=evidence,
        name_style=name_style,
        name_trim=name_trim,
        dates=dates,
        trim_phrases=tuple(
            part.strip() for part in value('trim_phrases').split('|') if part.strip()
        ),
        whole_timeout=whole_timeout,
        **flags,
        max_output_tokens=(
            _integer(max_output_tokens, 'INVALID_MAX_OUTPUT_TOKENS', minimum=1)
            if max_output_tokens
            else None
        ),
        keep_alive=keep_alive,
        ctx_chars_per_token=_number(
            value('ctx_chars_per_token', '2.5'),
            'INVALID_CTX_CHARS_PER_TOKEN',
            minimum=0.5,
            maximum=10,
        ),
    )
    _ = config.provenance_model
    return config


def load_bench_config(env_file: Path, environ: Mapping[str, str]) -> LlmConfig:
    """Overlay extraction-only benchmark settings without reading the default dotenv."""
    values = _read_env(env_file, True, allowed_keys=BENCH_ENV_KEYS)
    return load_config(
        {name: values.get(key) for name, key in ENV_KEYS.items() if key in BENCH_ENV_KEYS},
        environ,
        env_file,
        env_file_explicit=True,
        _file_values={},
    )


class PullConfigError(ValueError):
    """Configuration diagnostics contain only a known key and fixed code."""

    def __init__(self, key: str, code: str) -> None:
        super().__init__(f'{key} {code}')


def default_worker_name() -> str:
    """Keep the PID suffix when shortening a sanitized hostname."""
    suffix = f'-{os.getpid()}'
    host = re.sub(r'[^A-Za-z0-9-]', '', socket.gethostname().split('.')[0]).strip('-')
    return (host or 'worker')[: 40 - len(suffix)] + suffix


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
    worker_name: str = field(default_factory=default_worker_name)


def validate_backend_url(value: str) -> str:
    """Allow plaintext only for the three explicitly approved loopback hosts."""
    try:
        url = urlsplit(value)
        valid = (
            url.scheme in ('http', 'https')
            and bool(url.hostname)
            and (url.scheme == 'https' or url.hostname in ('127.0.0.1', 'localhost', '::1'))
            and url.username is None
            and url.password is None
            and url.path == ''
            and '?' not in value
            and '#' not in value
            and '\\' not in value
            and not url.netloc.endswith(':')
            and (url.port is None or 0 < url.port <= 65535)
            and not any(ord(char) <= 32 or ord(char) == 127 for char in value)
        )
    except ValueError, TypeError:
        valid = False
    if not valid:
        raise PullConfigError(ENV_KEYS['backend_url'], 'BACKEND_URL_NOT_ALLOWED')
    return value


def load_pull_config(
    environ: Mapping[str, str],
    env_file: Path | None,
    *,
    env_file_explicit: bool,
    cli: Mapping[str, str | None] | None = None,
) -> PullConfig:
    """Extend the shared dotenv parser; credentials come only from the process."""
    path = env_file if env_file is not None else default_env_file()
    values = _read_env(path, env_file_explicit)

    def value(name: str, default: str = '') -> str:
        key = ENV_KEYS[name]
        return (
            ((cli or {}).get(name) or '').strip()
            or environ.get(key, '').strip()
            or values.get(key, '').strip()
            or default
        )

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
    worker_name = environ.get(ENV_KEYS['worker_name'], values.get(ENV_KEYS['worker_name']))
    if worker_name is None:
        worker_name = default_worker_name()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9-]{0,39}', worker_name):
        raise PullConfigError(ENV_KEYS['worker_name'], 'INVALID_WORKER_NAME')
    numbers = {}
    for name, default, maximum in (
        ('poll_s', '5', 300),
        ('http_timeout_s', '10', 120),
        ('max_http_attempts', '3', 10),
    ):
        try:
            numbers[name] = _integer(
                value(name, default), 'INVALID_CONFIG', minimum=1, maximum=maximum
            )
        except ValueError:
            raise PullConfigError(ENV_KEYS[name], 'INVALID_CONFIG') from None
    # Reuse the same parsed snapshot so dotenv cannot change between the two loads.
    merged = dict(values)
    merged.update(environ)
    try:
        llm = load_config(
            cli or {}, merged, path, env_file_explicit=env_file_explicit, _file_values=values
        )
    except ValueError as error:
        code = str(error)
        names = {
            'INVALID_FALLBACK_MODEL': 'fallback_model',
            'INVALID_FALLBACK_DROP_RATIO': 'fallback_drop_ratio',
            'INVALID_PROVIDER': 'provider',
            'INVALID_TIMEOUT': 'timeout',
            'INVALID_MAX_BLOCKS': 'max_blocks',
            'INVALID_TEMPERATURE': 'temperature',
            'INVALID_THINK': 'think',
            'INVALID_NUM_CTX': 'num_ctx',
            'INVALID_MODE': 'mode',
            'INVALID_EVIDENCE': 'evidence',
            'INVALID_NAME_STYLE': 'name_style',
            'INVALID_NAME_TRIM': 'name_trim',
            'INVALID_DATES': 'dates',
            'INVALID_TRIM_INPUT': 'trim_input',
            'INVALID_TRIM_SECTIONS': 'trim_sections',
            'INVALID_WHOLE_TIMEOUT': 'whole_timeout',
            'INVALID_PROMPT_SCHEMA_TEXT': 'prompt_schema_text',
            'INVALID_WHOLE_PERSONAL': 'whole_personal',
            'INVALID_MAX_OUTPUT_TOKENS': 'max_output_tokens',
            'INVALID_KEEP_ALIVE': 'keep_alive',
            'INVALID_CTX_CHARS_PER_TOKEN': 'ctx_chars_per_token',
            'INVALID_CTX_FINE_TIERS': 'ctx_fine_tiers',
            'INVALID_CTX_GUARD': 'ctx_guard',
        }
        name = names.get(code, 'provider' if not value('provider') else 'model')
        raise PullConfigError(ENV_KEYS[name], code) from None
    if not re.fullmatch(r'[A-Za-z0-9._:/@+-]{1,128}', llm.model):
        raise PullConfigError(ENV_KEYS['model'], 'INVALID_PROVENANCE')
    log_level = value('log_level', 'INFO')
    if log_level not in ('DEBUG', 'INFO', 'WARNING', 'ERROR'):
        raise PullConfigError(ENV_KEYS['log_level'], 'INVALID_LOG_LEVEL')
    return PullConfig(
        llm, backend, token, version, **numbers, log_level=log_level, worker_name=worker_name
    )
