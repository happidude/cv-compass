"""Read local configuration without mutating the process environment."""

import math
import re
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

ENV_KEYS = {
    'provider': 'CVC_LLM_PROVIDER',
    'model': 'CVC_LLM_MODEL',
    'base_url': 'CVC_LLM_BASE_URL',
    'timeout': 'CVC_LLM_TIMEOUT_S',
    'max_blocks': 'CVC_LLM_MAX_BLOCKS',
    'temperature': 'CVC_LLM_TEMPERATURE',
    'think': 'CVC_LLM_THINK',
    'num_ctx': 'CVC_LLM_NUM_CTX',
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
                if key not in ENV_KEYS.values() or key in values:
                    raise ValueError
                values[key] = value
        return values
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
) -> LlmConfig:
    """Resolve each CLI destination name over environment and dotenv values."""
    file_values = _read_env(
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
    num_ctx = value('num_ctx')
    return LlmConfig(
        provider=provider, model=model,
        base_url=value('base_url', 'http://127.0.0.1:11434'), timeout_s=timeout,
        max_blocks=max_blocks, temperature=temperature, think=think == 'true',
        num_ctx=(_integer(num_ctx, 'INVALID_NUM_CTX', minimum=1, maximum=1048576)
                 if num_ctx else None),
    )
