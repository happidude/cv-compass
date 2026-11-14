"""Command-scoped progress logging; callers supply only approved metadata."""

import logging
import re
from contextlib import contextmanager
from urllib.parse import urlsplit

LOGGER = logging.getLogger('cv_compass_llm.pull')
LOGGER.addHandler(logging.NullHandler())


def endpoint(value: str) -> str:
    """Render only host and effective port, including bracketed IPv6."""
    url = urlsplit(value)
    host = url.hostname or ''
    if not re.fullmatch(r'[A-Za-z0-9.:-]+', host):
        return 'unknown:0'
    host = f'[{host}]' if ':' in host else host
    return f'{host}:{url.port or (443 if url.scheme == "https" else 80)}'


def model_name(value: str) -> str:
    """Model provenance allows slashes; never render an absolute path or URL."""
    if (not re.fullmatch(r'[A-Za-z0-9._:/@+-]{1,128}', value)
            or '://' in value or value.startswith(('/', '.'))
            or re.match(r'^[A-Za-z]:/', value)):
        return 'REDACTED'
    return value


def event(name: str, *, level: int = logging.INFO, **fields) -> None:
    # Build the record from metadata only: no exception object, body or source locals.
    LOGGER.log(level, ' '.join([f'event={name}', *(f'{key}={value}'
                                                for key, value in fields.items())]))


@contextmanager
def command_logging(level: str):
    """StreamHandler flushes each line; never modify root or urllib loggers."""
    previous = LOGGER.handlers[:], LOGGER.level, LOGGER.propagate, LOGGER.disabled
    handler = logging.StreamHandler()
    handler.setFormatter(logging.Formatter('%(asctime)s %(levelname)s %(message)s',
                                           datefmt='%Y-%m-%d %H:%M:%S'))
    LOGGER.handlers = [handler]
    LOGGER.setLevel(level)
    LOGGER.propagate = False
    LOGGER.disabled = False
    try:
        yield
    finally:
        handler.close()
        LOGGER.handlers, previous_level, LOGGER.propagate, LOGGER.disabled = previous
        LOGGER.setLevel(previous_level)
