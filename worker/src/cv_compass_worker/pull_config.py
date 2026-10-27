"""Operator-owned pull configuration. No credentials, paths or profile supplied by a job."""

from dataclasses import dataclass, fields
import ipaddress
import math
from pathlib import Path
import re
import tomllib
from urllib.parse import urlsplit
from .diagnostics import WorkerFailure, Reason, failure_scope


@dataclass(frozen=True)
class PullConfig:
    origin: str
    credential_env: str
    spool_root: str
    pipeline_config: str
    profile_revision: str
    worker_commit: str
    image_id: str
    config_fingerprint: str
    enabled: bool = False
    synthetic_only: bool = True
    local_only: bool = False
    heartbeat_seconds: float = 30
    http_timeout_seconds: float = 10
    download_timeout_seconds: float = 60
    retry_backoff_seconds: float = 1
    poll_seconds: float = 5
    max_http_attempts: int = 3
    max_file_bytes: int = 10485760
    max_result_bytes: int = 8388608
    max_envelope_bytes: int = 8454144
    max_spool_bytes: int = 67108864

    @classmethod
    def load(cls, path: Path):
        with failure_scope(Reason.CONFIG_READ_FAILED):
            with path.open('rb') as stream:
                with failure_scope(Reason.CONFIG_PARSE_FAILED):
                    values = tomllib.load(stream)
        with failure_scope(Reason.PULL_CONFIGURATION):
            return cls.parse(values)

    @classmethod
    def parse(cls, values):
        if not isinstance(values, dict) or set(values) - {f.name for f in fields(cls)}:
            raise WorkerFailure(Reason.PULL_CONFIGURATION)
        config = cls(**values)
        for name in (
            'origin',
            'credential_env',
            'spool_root',
            'pipeline_config',
            'profile_revision',
            'worker_commit',
            'image_id',
            'config_fingerprint',
        ):
            if not isinstance(getattr(config, name), str):
                raise WorkerFailure(Reason.PULL_CONFIGURATION)
        origin = urlsplit(config.origin)
        if (
            origin.scheme not in ('https', 'http')
            or not origin.hostname
            or origin.username is not None
            or origin.password is not None
            or origin.path
            or origin.query
            or origin.fragment
        ):
            raise WorkerFailure(Reason.PULL_ORIGIN)
        if origin.scheme == 'http':
            try:
                if not ipaddress.ip_address(origin.hostname).is_loopback:
                    raise WorkerFailure(Reason.PULL_ORIGIN)
            except ValueError:
                raise WorkerFailure(Reason.PULL_ORIGIN) from None
        origin.port  # Validate malformed ports before any request.
        if (
            type(config.enabled) is not bool
            or not config.enabled
            or type(config.synthetic_only) is not bool
            or type(config.local_only) is not bool
            or config.synthetic_only == config.local_only
        ):
            raise WorkerFailure(Reason.PULL_DISABLED)
        # Reserved prefix is excluded by M6 child safe_env/docker_env allowlists.
        # An operator must not bind a credential to LANG/PATH/HOME and leak it to a child.
        if (
            not re.fullmatch(r'CV_COMPASS_OCR_[A-Z][A-Z0-9_]{0,63}', config.credential_env)
            or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}', config.profile_revision)
            or not re.fullmatch(r'[0-9a-f]{40}', config.worker_commit)
            or not re.fullmatch(r'sha256:[0-9a-f]{64}', config.image_id)
            or not re.fullmatch(r'[0-9a-f]{64}', config.config_fingerprint)
        ):
            raise WorkerFailure(Reason.PULL_CONFIGURATION)
        if any(
            not Path(getattr(config, name)).is_absolute()
            for name in ('spool_root', 'pipeline_config')
        ):
            raise WorkerFailure(Reason.PULL_CONFIGURATION)
        for name in (
            'heartbeat_seconds',
            'http_timeout_seconds',
            'download_timeout_seconds',
            'retry_backoff_seconds',
            'poll_seconds',
        ):
            value = getattr(config, name)
            if type(value) not in (int, float) or not math.isfinite(value) or not 0 < value <= 3600:
                raise WorkerFailure(Reason.PULL_CONFIGURATION)
        for name, cap in (
            ('max_http_attempts', 10),
            ('max_file_bytes', 10485760),
            ('max_result_bytes', 8388608),
            ('max_envelope_bytes', 8454144),
            ('max_spool_bytes', 67108864),
        ):
            value = getattr(config, name)
            if type(value) is not int or not 1 <= value <= cap:
                raise WorkerFailure(Reason.PULL_CONFIGURATION)
        if (
            config.max_envelope_bytes < config.max_result_bytes
            or config.max_spool_bytes < config.max_file_bytes + config.max_envelope_bytes
        ):
            raise WorkerFailure(Reason.PULL_CONFIGURATION)
        return config
