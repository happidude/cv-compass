"""Pure extraction orchestration; no I/O, logging or persistence."""

from collections.abc import Callable
from math import ceil
from time import perf_counter

from .config import LlmConfig
from .contacts import extract_contacts
from .ocr_input import Document
from .prompts import (
    build_experience_prompt,
    build_personal_prompt,
    build_whole_prompt,
    prompt_version,
)
from .providers.base import LlmProvider, ProviderError
from .schema import (
    EXPERIENCE_SCHEMA,
    PERSONAL_KEYS,
    PERSONAL_SCHEMA,
    WHOLE_SCHEMA,
    ExtractionResult,
    Issue,
)
from .segmenter import Block, build_header, segment, split_lines
from .validate import validate_experiences, validate_personal, validate_whole


def extract(
    document: Document,
    provider: LlmProvider,
    *,
    max_blocks: int = 20,
    on_block: Callable[[int, int, float], None] | None = None,
    mode: str = 'blocks',
    whole_timeout: float = 900,
    config: LlmConfig | None = None,
) -> ExtractionResult:
    """Extract supported CV values while isolating provider block failures."""
    if type(max_blocks) is not int or max_blocks < 0:
        raise ValueError('INVALID_MAX_BLOCKS')
    if mode not in ('blocks', 'whole'):
        raise ValueError('INVALID_MODE')
    schema_text = config.prompt_schema_text if config is not None else True
    whole_personal = config.whole_personal if config is not None else True
    fields = list(extract_contacts(document))
    issues = []
    experiences = []

    def check_truncated(field: str) -> None:
        metrics = getattr(provider, 'last_metrics', None)
        if isinstance(metrics, dict) and metrics.get('done_reason') == 'length':
            issues.append(Issue('OUTPUT_TRUNCATED', field))

    if mode == 'whole':
        header = build_header(tuple(p for p in document.pages if p.status == 'ok'))
        block = Block(tuple(split_lines(tuple(p for p in document.pages if p.status == 'ok'))))
        prompt = build_whole_prompt(
            block.text, schema_text=schema_text, whole_personal=whole_personal
        )
        ratio = config.ctx_chars_per_token if config is not None else 2.5
        tiers = (
            (8192, 12288, 16384, 24576, 32768)
            if config is not None and config.ctx_fine_tiers
            else (8192, 16384, 24576, 32768)
        )
        estimate = ceil(len(prompt) / ratio) + 4096
        num_ctx = next((size for size in tiers if size >= estimate), None)
        if num_ctx is None:
            issues.append(Issue('WHOLE_TOO_LONG'))
        else:
            started = perf_counter() if on_block is not None else 0
            try:
                response = provider.generate(
                    prompt,
                    WHOLE_SCHEMA if whole_personal else EXPERIENCE_SCHEMA,
                    num_ctx=num_ctx,
                    timeout_s=whole_timeout,
                )
            except ProviderError:
                issues.append(Issue('PROVIDER_ERROR'))
            else:
                check_truncated('experiences')
                _, experiences, errors = validate_whole(
                    response, block, whole_personal=whole_personal
                )
                issues.extend(error for error in errors if error.field not in PERSONAL_KEYS)
            if on_block is not None:
                on_block(1, 1 + bool(header.spans), perf_counter() - started)
    if mode == 'blocks' or any(issue.code == 'WHOLE_TOO_LONG' for issue in issues):
        header, blocks, segment_issues = segment(document)
        issues.extend(segment_issues)
    else:
        blocks = ()
    whole_called = mode == 'whole' and not any(i.code == 'WHOLE_TOO_LONG' for i in issues)
    total = int(whole_called) + bool(header.spans) + min(len(blocks), max_blocks)
    index = int(whole_called)

    def completed(started: float) -> None:
        nonlocal index
        index += 1
        if on_block is not None:
            on_block(index, total, perf_counter() - started)

    if header.spans:
        started = perf_counter() if on_block is not None else 0
        try:
            response = provider.generate(
                build_personal_prompt(header.text, schema_text=schema_text), PERSONAL_SCHEMA
            )
        except ProviderError:
            issues.append(Issue('PROVIDER_ERROR', 'personal'))
        else:
            check_truncated('personal')
            values, errors = validate_personal(response, header)
            fields.extend(values)
            issues.extend(errors)
        completed(started)
    if len(blocks) > max_blocks:
        issues.append(Issue('BLOCK_LIMIT_REACHED'))
    for block in blocks[:max_blocks]:
        started = perf_counter() if on_block is not None else 0
        try:
            response = provider.generate(
                build_experience_prompt(block.text, schema_text=schema_text), EXPERIENCE_SCHEMA
            )
        except ProviderError:
            issues.append(Issue('PROVIDER_ERROR', 'experiences'))
            completed(started)
            continue
        check_truncated('experiences')
        values, errors = validate_experiences(response, block)
        experiences.extend(values)
        issues.extend(errors)
        completed(started)
    unique = {}
    for experience in experiences:
        key = (experience.title, experience.company, experience.start, experience.end)
        if key not in unique:
            unique[key] = experience
    return ExtractionResult(
        tuple(fields),
        tuple(unique.values()),
        tuple(issues),
        prompt_version(mode, config),
        provider.name,
    )
