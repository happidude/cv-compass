"""Pure extraction orchestration; no I/O, logging or persistence."""

from collections.abc import Callable
from time import perf_counter

from .contacts import extract_contacts
from .ocr_input import Document
from .prompts import PROMPT_VERSION, build_experience_prompt, build_personal_prompt
from .providers.base import LlmProvider, ProviderError
from .schema import EXPERIENCE_SCHEMA, PERSONAL_SCHEMA, ExtractionResult, Issue
from .segmenter import segment
from .validate import validate_experiences, validate_personal


def extract(
    document: Document, provider: LlmProvider, *, max_blocks: int = 20,
    on_block: Callable[[int, int, float], None] | None = None,
) -> ExtractionResult:
    """Extract supported CV values while isolating provider block failures."""
    if type(max_blocks) is not int or max_blocks < 0:
        raise ValueError('INVALID_MAX_BLOCKS')
    header, blocks, segment_issues = segment(document)
    fields = list(extract_contacts(document))
    issues = list(segment_issues)
    experiences = []
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
        tuple(fields), tuple(unique.values()), tuple(issues), PROMPT_VERSION, provider.name,
    )
