"""Pure extraction orchestration; no I/O, logging or persistence."""

from collections.abc import Callable
from dataclasses import replace
from math import ceil
from time import perf_counter

from .config import LlmConfig
from .contacts import extract_contacts
from .lifecycle import call_metrics
from .lines import block_lines, number_lines
from .ocr_input import Document
from .prompts import (
    build_experience_prompt,
    build_lines_prompt,
    build_personal_prompt,
    build_whole_prompt,
    prompt_version,
)
from .providers.base import LlmProvider, ProviderError
from .schema import (
    EXPERIENCE_SCHEMA,
    LINES_EXPERIENCE_SCHEMA,
    LINES_PERSONAL_SCHEMA,
    PERSONAL_KEYS,
    PERSONAL_SCHEMA,
    WHOLE_SCHEMA,
    ExtractionResult,
    Issue,
)
from .segmenter import (
    EXPERIENCE_HEADINGS,
    OTHER_HEADINGS,
    Block,
    build_header,
    scan_headings,
    segment,
    split_lines,
)
from .trim import compact_text, trim_spans
from .validate import (
    validate_experiences,
    validate_line_experiences,
    validate_line_personal,
    validate_personal,
    validate_whole,
)

FALLBACK_REASONS = ('INVALID_JSON', 'NO_EXPERIENCE', 'NO_FULLNAME', 'DROP_RATIO')


def fallback_reasons(
    result: ExtractionResult, document: Document, *, drop_ratio: float = 0.3
) -> tuple[str, ...]:
    """Assess quality using fixed codes, without rerouting infrastructure failures."""
    if any(issue.code == 'PROVIDER_ERROR' for issue in result.issues):
        return ()
    reasons = []
    if any(issue.code == 'LLM_INVALID_JSON' for issue in result.issues):
        reasons.append('INVALID_JSON')
    _, found = scan_headings(
        split_lines(tuple(page for page in document.pages if page.status == 'ok')),
        EXPERIENCE_HEADINGS,
        OTHER_HEADINGS,
    )
    if not result.experiences and found:
        reasons.append('NO_EXPERIENCE')
    if not any(field.key == 'fullName' for field in result.fields):
        reasons.append('NO_FULLNAME')
    # Validators emit this once per rejected item, after any quote errors. A quote
    # error alone can leave a valid role/company, so it must not count as a drop.
    dropped = sum(issue.code == 'EXPERIENCE_WITHOUT_ROLE' for issue in result.issues)
    total = len(result.experiences) + dropped
    if total and dropped / total >= drop_ratio:
        reasons.append('DROP_RATIO')
    return tuple(reasons)


def extract(
    document: Document,
    provider: LlmProvider,
    *,
    max_blocks: int = 20,
    on_block: Callable[[int, int, float], None] | None = None,
    mode: str = 'blocks',
    whole_timeout: float = 900,
    config: LlmConfig | None = None,
    on_trim: Callable[[int, int], None] | None = None,
    fallback_provider: LlmProvider | None = None,
    on_fallback: Callable[[tuple[str, ...]], None] | None = None,
    on_context: Callable[[dict], None] | None = None,
) -> ExtractionResult:
    """Extract supported CV values while isolating provider block failures."""
    if config is not None and config.fallback_model:
        if fallback_provider is None:
            raise ValueError('INVALID_FALLBACK_MODEL')
        primary_config = replace(config, fallback_model='')
        options = {
            'max_blocks': max_blocks,
            'on_block': on_block,
            'mode': mode,
            'whole_timeout': whole_timeout,
            'on_trim': on_trim,
            'on_context': on_context,
        }
        result = extract(document, provider, config=primary_config, **options)
        reasons = fallback_reasons(result, document, drop_ratio=config.fallback_drop_ratio)
        if on_fallback is not None:
            on_fallback(reasons)
        if reasons:
            return extract(
                document,
                fallback_provider,
                config=replace(primary_config, model=config.fallback_model),
                **options,
            )
        return result
    if type(max_blocks) is not int or max_blocks < 0:
        raise ValueError('INVALID_MAX_BLOCKS')
    if mode not in ('blocks', 'whole'):
        raise ValueError('INVALID_MODE')
    schema_text = config.prompt_schema_text if config is not None else True
    whole_personal = config.whole_personal if config is not None else True
    use_lines = config is not None and config.evidence == 'lines'
    trim_input = config is not None and config.trim_input
    retained = set()
    if trim_input:
        kept, _, _, _ = trim_spans(
            split_lines(tuple(p for p in document.pages if p.status == 'ok')),
            phrases=config.trim_phrases,
            sections=config.trim_sections,
            page_count=sum(p.status == 'ok' for p in document.pages),
        )
        retained = {(span.page, span.start, span.end) for span in kept}
    line_table = (
        number_lines(split_lines(tuple(p for p in document.pages if p.status == 'ok')))[1]
        if use_lines
        else {}
    )
    fields = list(extract_contacts(document))
    issues = []
    experiences = []
    guard = config is not None and config.ctx_guard
    tiers = (
        (8192, 12288, 16384, 24576, 32768)
        if config is not None and config.ctx_fine_tiers
        else (8192, 16384, 24576, 32768)
    )

    def generate(prompt: str, schema: dict, **options) -> str:
        effective = options.get('num_ctx', config.num_ctx if config is not None else None)
        telemetry = {'discarded': []}
        try:
            if not guard:
                if effective is not None:
                    telemetry['num_ctx'] = effective
                return provider.generate(prompt, schema, **options)
            for attempt in range(2):
                if effective is not None:
                    telemetry['num_ctx'] = effective
                started = perf_counter()
                try:
                    response = provider.generate(prompt, schema, **options)
                except ProviderError as error:
                    if str(error) != 'OLLAMA_CONTEXT_OVERFLOW':
                        raise
                else:
                    metrics = getattr(provider, 'last_metrics', None)
                    metrics = metrics if isinstance(metrics, dict) else {}
                    prompt_tokens = metrics.get('prompt_tokens')
                    output_tokens = metrics.get('output_tokens')
                    full = (
                        effective is not None
                        and type(prompt_tokens) is int
                        and type(output_tokens) is int
                        and prompt_tokens + output_tokens >= effective - 32
                    )
                    if not (
                        full
                        or metrics.get('done_reason') == 'length'
                        and config.max_output_tokens is None
                    ):
                        return response
                telemetry['discarded'].append(
                    {
                        **({'num_ctx': effective} if effective is not None else {}),
                        'seconds': perf_counter() - started,
                        **call_metrics(getattr(provider, 'last_metrics', None)),
                    }
                )
                next_ctx = next((size for size in tiers if size > (effective or 8192)), None)
                if attempt or next_ctx is None:
                    raise ProviderError('OLLAMA_CONTEXT_OVERFLOW')
                effective = next_ctx
                options = {**options, 'num_ctx': next_ctx}
                telemetry['ctx_retry'] = 1
        finally:
            if on_context is not None:
                on_context(telemetry)

    def failure(error: ProviderError, field: str | None = None) -> None:
        if guard and str(error) == 'OLLAMA_CONTEXT_OVERFLOW':
            issues.append(
                Issue('WHOLE_TOO_LONG') if field is None else Issue('CONTEXT_OVERFLOW', field)
            )
        else:
            issues.append(Issue('PROVIDER_ERROR', field))

    def experience_input(block: Block) -> tuple[Block, str, dict, int]:
        original = block_lines(block, line_table)[0] if use_lines else block.text
        if trim_input:
            block = Block(tuple(s for s in block.spans if (s.page, s.start, s.end) in retained))
        if use_lines:
            text, table = block_lines(block, line_table, trim=trim_input)
        else:
            text = compact_text(block.spans) if trim_input else block.text
            table = {}
        return block, text, table, len(original)

    def report_chars(before: int, after: int) -> None:
        if trim_input and on_trim is not None:
            on_trim(before, after)

    def check_truncated(field: str) -> None:
        metrics = getattr(provider, 'last_metrics', None)
        if isinstance(metrics, dict) and metrics.get('done_reason') == 'length':
            issues.append(Issue('OUTPUT_TRUNCATED', field))

    whole_attempted = False
    if mode == 'whole':
        header = build_header(tuple(p for p in document.pages if p.status == 'ok'))
        block = Block(tuple(split_lines(tuple(p for p in document.pages if p.status == 'ok'))))
        block, block_text, block_table, chars_before = experience_input(block)
        prompt = (
            build_lines_prompt(block_text, whole=True, schema_text=schema_text)
            if use_lines
            else build_whole_prompt(
                block_text, schema_text=schema_text, whole_personal=whole_personal
            )
        )
        ratio = config.ctx_chars_per_token if config is not None else 2.5
        estimate = ceil(len(prompt) / ratio) + 4096
        num_ctx = next((size for size in tiers if size >= estimate), None)
        if trim_input and not block_text.strip():
            if on_context is not None:
                on_context({'discarded': []})
            if on_block is not None:
                on_block(1, 1 + bool(header.spans), 0.0)
        elif num_ctx is None:
            issues.append(Issue('WHOLE_TOO_LONG'))
        else:
            whole_attempted = True
            started = perf_counter() if on_block is not None else 0
            report_chars(chars_before, len(block_text))
            try:
                response = generate(
                    prompt,
                    LINES_EXPERIENCE_SCHEMA
                    if use_lines
                    else WHOLE_SCHEMA
                    if whole_personal
                    else EXPERIENCE_SCHEMA,
                    num_ctx=num_ctx,
                    timeout_s=whole_timeout,
                )
            except ProviderError as error:
                failure(error)
            else:
                check_truncated('experiences')
                if use_lines:
                    experiences, errors = validate_line_experiences(response, block_table)
                else:
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
    whole_called = mode == 'whole' and (
        not any(i.code == 'WHOLE_TOO_LONG' for i in issues) or guard and whole_attempted
    )
    total = int(whole_called) + bool(header.spans) + min(len(blocks), max_blocks)
    index = int(whole_called)

    def completed(started: float) -> None:
        nonlocal index
        index += 1
        if on_block is not None:
            on_block(index, total, perf_counter() - started)

    if header.spans:
        started = perf_counter() if on_block is not None else 0
        if use_lines:
            header_text, header_table = block_lines(header, line_table)
        try:
            response = generate(
                build_lines_prompt(header_text, personal=True, schema_text=schema_text)
                if use_lines
                else build_personal_prompt(header.text, schema_text=schema_text),
                LINES_PERSONAL_SCHEMA if use_lines else PERSONAL_SCHEMA,
            )
        except ProviderError as error:
            failure(error, 'personal')
        else:
            check_truncated('personal')
            values, errors = (
                validate_line_personal(response, header_table)
                if use_lines
                else validate_personal(response, header)
            )
            fields.extend(values)
            issues.extend(errors)
        completed(started)
    if len(blocks) > max_blocks:
        issues.append(Issue('BLOCK_LIMIT_REACHED'))
    for block in blocks[:max_blocks]:
        started = perf_counter() if on_block is not None else 0
        block, block_text, block_table, chars_before = experience_input(block)
        if trim_input and not block_text.strip():
            if on_context is not None:
                on_context({'discarded': []})
            completed(started)
            continue
        report_chars(chars_before, len(block_text))
        try:
            response = generate(
                build_lines_prompt(block_text, schema_text=schema_text)
                if use_lines
                else build_experience_prompt(block_text, schema_text=schema_text),
                LINES_EXPERIENCE_SCHEMA if use_lines else EXPERIENCE_SCHEMA,
            )
        except ProviderError as error:
            failure(error, 'experiences')
            completed(started)
            continue
        check_truncated('experiences')
        values, errors = (
            validate_line_experiences(response, block_table)
            if use_lines
            else validate_experiences(response, block)
        )
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
