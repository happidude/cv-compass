"""Exact and whitespace-only quote location, never fuzzy matching."""

import re

from .schema import Evidence
from .segmenter import Block, Span


def normalize(text: str) -> str:
    """Collapse whitespace without changing non-whitespace characters."""
    return ' '.join(text.split())


def locate(quote: str | None, block: Block) -> Evidence | None:
    """Locate a quote exactly or with whitespace-only normalization."""
    if not isinstance(quote, str) or len(normalize(quote)) < 2:
        return None
    groups: list[Span] = []
    for span in block.spans:
        if groups and groups[-1].page == span.page and groups[-1].end == span.start:
            previous = groups.pop()
            groups.append(Span(span.page, previous.start, span.end, previous.text + span.text))
        else:
            groups.append(span)
    for span in groups:
        start = span.text.find(quote)
        if start >= 0:
            return Evidence(span.page, span.start + start, span.start + start + len(quote), quote)
    target = normalize(quote)
    for span in groups:
        normalized = ''
        offsets: list[tuple[int, int]] = []
        for match in re.finditer(r'\S+', span.text):
            if normalized:
                normalized += ' '
                offsets.append((offsets[-1][1], match.start()))
            normalized += match.group()
            offsets.extend((index, index + 1) for index in range(match.start(), match.end()))
        start = normalized.find(target)
        if start >= 0:
            left, right = offsets[start][0], offsets[start + len(target) - 1][1]
            return Evidence(span.page, span.start + left, span.start + right, span.text[left:right])
    return None
