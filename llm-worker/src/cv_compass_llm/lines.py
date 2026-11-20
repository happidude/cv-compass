"""Global line IDs and source-preserving views of numbered CV text."""

from collections.abc import Iterable

from .schema import Evidence
from .segmenter import Block, Span
from .trim import compact_line


def _render(table: dict[int, Span], *, trim: bool = False) -> str:
    rows = []
    page = None
    for number, span in table.items():
        if span.page != page:
            page = span.page
            rows.append(f'=== page {page} ===')
        text = ' '.join(span.text.strip().splitlines())
        if trim:
            text = compact_line(text)
        text = text.replace('[L', '[ L').replace('=== page', '= = = page')
        rows.append(f'[L{number}] ' + text)
    return '\n'.join(rows)


def number_lines(spans: Iterable[Span]) -> tuple[str, dict[int, Span]]:
    """Number nonempty source lines once, retaining their original spans."""
    table = {number: span for number, span in enumerate((s for s in spans if s.text.strip()), 1)}
    return _render(table), table


def block_lines(
    block: Block, table: dict[int, Span], *, trim: bool = False
) -> tuple[str, dict[int, Span]]:
    """Restrict IDs and validation to complete lines actually supplied to a call."""
    ranges: list[tuple[int, int, int]] = []
    for span in block.spans:
        if ranges and ranges[-1][0] == span.page and ranges[-1][2] == span.start:
            ranges[-1] = (span.page, ranges[-1][1], span.end)
        else:
            ranges.append((span.page, span.start, span.end))
    selected = {
        number: span
        for number, span in table.items()
        if any(
            page == span.page and start <= span.start < span.end <= end
            for page, start, end in ranges
        )
    }
    return _render(selected, trim=trim), selected


def line_summary(items: list[dict], table: dict[int, Span]) -> tuple[Evidence, ...] | None:
    """Recover ordered line ranges, joining only contiguous spans on each page."""
    evidence: list[Evidence] = []
    previous = 0
    for item in items:
        start, end = item['startLine'], item['endLine']
        if start <= previous or end < start or end - start >= len(table):
            return None
        selected = [table.get(number) for number in range(start, end + 1)]
        if any(span is None for span in selected):
            return None
        groups: list[Evidence] = []
        for span in selected:
            part = Evidence(span.page, span.start, span.end, span.text)
            if groups:
                left = groups[-1]
                if left.page == part.page and left.end == part.start:
                    groups[-1] = Evidence(left.page, left.start, part.end, left.text + part.text)
                    continue
                if part.page != left.page and part.page != left.page + 1:
                    return None
            groups.append(part)
        evidence.extend(groups)
        previous = end
    # Remove line terminators at range edges; interior source whitespace stays literal.
    evidence = [
        Evidence(part.page, part.start, part.start + len(text), text)
        for part in evidence
        if (text := part.text.rstrip('\r\n'))
    ]
    if not 1 <= len(evidence) <= 8 or len('\n'.join(part.text for part in evidence)) > 6000:
        return None
    return tuple(evidence)
