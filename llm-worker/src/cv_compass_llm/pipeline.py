"""Pure extraction orchestration; no I/O, logging or persistence."""

from collections.abc import Callable
from math import ceil
from time import perf_counter

from .contacts import extract_contacts
from .ocr_input import Document
from .prompts import (
    PROMPT_VERSION,
    WHOLE_PROMPT_VERSION,
    build_experience_prompt,
    build_personal_prompt,
    build_whole_prompt,
)
from .providers.base import LlmProvider, ProviderError
from .schema import EXPERIENCE_SCHEMA, PERSONAL_SCHEMA, WHOLE_SCHEMA, ExtractionResult, Issue
from .segmenter import Block, segment, split_lines
from .validate import validate_experiences, validate_personal, validate_whole


def extract(
    document: Document, provider: LlmProvider, *, max_blocks: int = 20,
    on_block: Callable[[int, int, float], None] | None = None,
    mode: str = 'blocks', whole_timeout: float = 900,
) -> ExtractionResult:
    """Extract supported CV values while isolating provider block failures."""
    if type(max_blocks) is not int or max_blocks < 0:
        raise ValueError('INVALID_MAX_BLOCKS')
    if mode not in ('blocks', 'whole'):
        raise ValueError('INVALID_MODE')
    fields = list(extract_contacts(document))
    issues = []
    experiences = []
    if mode == 'whole':
        block = Block(tuple(split_lines(tuple(p for p in document.pages if p.status == 'ok'))))
        prompt = build_whole_prompt(block.text)
        estimate = ceil(len(prompt) / 2.5) + 4096
        num_ctx = next((size for size in (8192, 16384, 24576, 32768)
                        if size >= estimate), None)
        if num_ctx is None:
            issues.append(Issue('WHOLE_TOO_LONG'))
        else:
            started = perf_counter() if on_block is not None else 0
            try:
                response = provider.generate(prompt, WHOLE_SCHEMA,
                                             num_ctx=num_ctx, timeout_s=whole_timeout)
            except ProviderError:
                issues.append(Issue('PROVIDER_ERROR'))
            else:
                values, experiences, errors = validate_whole(response, block)
                fields.extend(values)
                issues.extend(errors)
            if on_block is not None:
                on_block(1, 1, perf_counter() - started)
    if mode == 'blocks' or any(issue.code == 'WHOLE_TOO_LONG' for issue in issues):
        header, blocks, segment_issues = segment(document)
        issues.extend(segment_issues)
    else:
        header, blocks = Block(()), ()
    total = bool(header.spans) + min(len(blocks), max_blocks)
    index = 0

    def completed(started: float) -> None:
        nonlocal index
        index += 1
        if on_block is not None:
            on_block(index, total, perf_counter() - started)

    if header.spans:
        started = perf_counter() if on_block is not None else 0
        try:
            response = provider.generate(build_personal_prompt(header.text), PERSONAL_SCHEMA)
        except ProviderError:
            issues.append(Issue('PROVIDER_ERROR', 'personal'))
        else:
            values, errors = validate_personal(response, header)
            fields.extend(values)
            issues.extend(errors)
        completed(started)
    if len(blocks) > max_blocks:
        issues.append(Issue('BLOCK_LIMIT_REACHED'))
    for block in blocks[:max_blocks]:
        started = perf_counter() if on_block is not None else 0
        try:
            response = provider.generate(build_experience_prompt(block.text), EXPERIENCE_SCHEMA)
        except ProviderError:
            issues.append(Issue('PROVIDER_ERROR', 'experiences'))
            completed(started)
            continue
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
        tuple(fields), tuple(unique.values()), tuple(issues),
        WHOLE_PROMPT_VERSION if mode == 'whole' else PROMPT_VERSION, provider.name,
    )
