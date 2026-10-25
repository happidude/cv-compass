"""Strict local configuration. Contract ceilings come from one manifest."""
from dataclasses import asdict, dataclass, fields
import hashlib
import json
import re
from pathlib import Path
import tomllib

DESIGN = Path(__file__).resolve().parents[2] / 'design'
LIMITS = json.loads((DESIGN / 'ocr-v2.limits.json').read_text(encoding='utf-8'))


@dataclass(frozen=True)
class Config:
    tesseract: str = ''
    models: str = ''
    mode: str = 'production'
    adapter: str = 'native'
    docker_image: str = ''
    container_memory_bytes: int = 805306368
    container_cpu_millicores: int = 2000
    container_pids: int = 64
    max_file_bytes: int = 10485760
    max_pages: int = LIMITS['max_pages']
    max_text_code_points_per_field: int = LIMITS['max_text_code_points_per_field']
    max_document_text: int = 500000
    max_result_bytes: int = 8388608
    max_engine_output_bytes: int = 8388608
    max_temp_bytes: int = 268435456
    render_dpi: int = 300
    max_image_pixels: int = 16000000
    max_image_dimension: int = 6000
    preflight_timeout: float = 10
    page_timeout: float = 60
    tesseract_timeout: float = 40
    osd_timeout: float = 5
    document_timeout: float = 600
    concurrency: int = 1

    @classmethod
    def parse(cls, values: dict):
        if set(values) - {f.name for f in fields(cls)}:
            raise ValueError('INVALID_CONFIGURATION')
        config = cls(**values)
        for field in fields(cls):
            value = getattr(config, field.name)
            if field.name in ('tesseract', 'models', 'mode', 'adapter', 'docker_image'):
                if not isinstance(value, str):
                    raise ValueError('INVALID_CONFIGURATION')
            elif field.name.endswith('_timeout'):
                if type(value) not in (int, float) or not 0 < value <= 3600:
                    raise ValueError('INVALID_CONFIGURATION')
            elif type(value) is not int or value < 1:
                raise ValueError('INVALID_CONFIGURATION')
        if (config.mode not in ('production', 'diagnostic') or config.concurrency != 1
                or config.max_pages > LIMITS['max_pages']
                or config.max_text_code_points_per_field > LIMITS['max_text_code_points_per_field']
                or config.max_file_bytes > 10485760
                or not 150 <= config.render_dpi <= 400
                or config.max_image_pixels > 16000000
                or config.max_image_dimension > 6000
                or config.max_temp_bytes > 268435456
                or config.max_result_bytes > 8388608
                or config.max_document_text > 500000
                or config.max_engine_output_bytes > 8388608
                or config.document_timeout <= 7):
            raise ValueError('INVALID_CONFIGURATION')
        if (config.adapter not in ('native', 'docker')
                or not 67108864 <= config.container_memory_bytes <= 805306368
                or not 250 <= config.container_cpu_millicores <= 2000
                or not 8 <= config.container_pids <= 64
                or (config.adapter == 'docker' and re.fullmatch(r'sha256:[0-9a-f]{64}', config.docker_image) is None)):
            raise ValueError('INVALID_CONFIGURATION')
        for value in (config.tesseract, config.models):
            if not value or not Path(value).is_absolute():
                raise ValueError('INVALID_CONFIGURATION')
        return config

    @classmethod
    def load(cls, path: Path):
        with path.open('rb') as stream:
            return cls.parse(tomllib.load(stream))

    def fingerprint(self):
        # No physical paths appear in the result; their hashes are still part
        # of the local configuration fingerprint.
        return hashlib.sha256(json.dumps(asdict(self), sort_keys=True).encode()).hexdigest()
