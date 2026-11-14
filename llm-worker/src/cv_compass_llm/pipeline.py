"""Pure extraction orchestration; no I/O, logging or persistence."""

from .contacts import extract_contacts
from .ocr_input import Document
from .prompts import PROMPT_VERSION, build_experience_prompt, build_personal_prompt
from .providers.base import LlmProvider, ProviderError
from .schema import EXPERIENCE_SCHEMA, PERSONAL_SCHEMA, ExtractionResult, Issue
from .segmenter import segment
from .validate import validate_experiences, validate_personal


def extract(document: Document, provider: LlmProvider, *, max_blocks: int = 20) -> ExtractionResult:
    """Extract supported CV values while isolating provider block failures."""
    if type(max_blocks) is not int or max_blocks < 0:
        raise ValueError('INVALID_MAX_BLOCKS')
    header, blocks, segment_issues = segment(document)
    fields = list(extract_contacts(document))
    issues = list(segment_issues)
    experiences = []
    if header.spans:
        try:
            response = provider.generate(build_personal_prompt(header.text), PERSONAL_SCHEMA)
        except ProviderError:
            issues.append(Issue('PROVIDER_ERROR', 'personal'))
        else:
            values, errors = validate_personal(response, header)
            fields.extend(values)
            issues.extend(errors)
    if len(blocks) > max_blocks:
        issues.append(Issue('BLOCK_LIMIT_REACHED'))
    for block in blocks[:max_blocks]:
        try:
            response = provider.generate(build_experience_prompt(block.text), EXPERIENCE_SCHEMA)
        except ProviderError:
            issues.append(Issue('PROVIDER_ERROR', 'experiences'))
            continue
        values, errors = validate_experiences(response, block)
        experiences.extend(values)
        issues.extend(errors)
    unique = {}
    for experience in experiences:
        key = (experience.title, experience.company, experience.start, experience.end)
        if key not in unique:
            unique[key] = experience
    return ExtractionResult(
        tuple(fields), tuple(unique.values()), tuple(issues), PROMPT_VERSION, provider.name,
    )
