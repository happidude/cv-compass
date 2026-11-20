"""Pure model-input views retaining untouched source lines for evidence."""

import re
from collections import defaultdict
from collections.abc import Iterable

from .segmenter import Block, Span, heading_kind

PAGE_NUMBER = re.compile(
    r'(?:(?:page\s+)?([0-9]{1,3})\s+of\s+([0-9]{1,3})'
    r'|(?:trang\s+)?([0-9]{1,3})\s*/\s*([0-9]{1,3})'
    r'|-\s*([0-9]{1,3})\s*-|([0-9]{1,3}))',
    re.IGNORECASE,
)
DATE_LINE = re.compile(r'(?:[0-9]{4}|[0-9]{1,2}/[0-9]{4}|[0-9]{4}-[0-9]{1,2})')


def _is_page_number(text: str, page_count: int) -> bool:
    match = PAGE_NUMBER.fullmatch(text)
    if match is None:
        return False
    numbers = [int(group) for group in match.groups() if group is not None]
    current = numbers[0]
    total = numbers[1] if len(numbers) == 2 else min(page_count, 999)
    return 1 <= current <= total <= page_count


def compact_line(text: str) -> str:
    """Collapse source whitespace within one line for model display only."""
    return ' '.join(text.split())


def compact_text(spans: Iterable[Span]) -> str:
    """Render lines with at most one consecutive empty line."""
    rows = []
    for span in spans:
        text = compact_line(span.text)
        if text or not rows or rows[-1]:
            rows.append(text)
    return '\n'.join(rows)


def trim_spans(
    spans: Iterable[Span],
    *,
    phrases: tuple[str, ...] = (),
    sections: bool = False,
    page_count: int | None = None,
) -> tuple[tuple[Span, ...], str, int, int]:
    """Filter successful-page lines without changing any source span or offset."""
    spans = tuple(spans)
    pages: dict[int, list[Span]] = defaultdict(list)
    for span in spans:
        pages[span.page].append(span)
    # Empty successful pages have no spans, so the pipeline supplies the full count.
    page_count = len(pages) if page_count is None else page_count
    threshold = 3 if page_count >= 3 else 2
    edges: dict[tuple[int, int, int], set[int]] = defaultdict(set)
    occurrences: dict[tuple[int, str], list[int]] = defaultdict(list)
    for page, lines in pages.items():
        nonempty = [span for span in lines if span.text.strip()]
        for side, candidates in enumerate((nonempty[:3], nonempty[-3:])):
            seen = set()
            for span in candidates:
                edges[(span.page, span.start, span.end)].add(side)
                text = compact_line(span.text).casefold()
                if text not in seen:
                    occurrences[(side, text)].append(page)
                    seen.add(text)
    repeated = {
        key: numbers[0] for key, numbers in occurrences.items() if len(numbers) >= threshold
    }
    phrases_set = {phrase.strip().casefold() for phrase in phrases if phrase.strip()}
    kept = []
    section = None
    for span in spans:
        text = compact_line(span.text)
        sides = edges.get((span.page, span.start, span.end), ())
        if sections:
            kind = heading_kind(span.text, in_experience=section == 'experience')
            if kind is not None:
                section = kind
        if (
            (sections and section == 'other')
            or (text and not any(char.isalnum() for char in text))
            or span.text.strip().casefold() in phrases_set
            or (sides and _is_page_number(text, page_count))
            or (
                not DATE_LINE.fullmatch(text)
                and not PAGE_NUMBER.fullmatch(text)
                and any(
                    (side, text.casefold()) in repeated
                    and span.page != repeated[(side, text.casefold())]
                    for side in sides
                )
            )
        ):
            continue
        kept.append(span)
    retained = tuple(kept)
    text = compact_text(retained)
    return retained, text, len(Block(spans).text), len(text)
