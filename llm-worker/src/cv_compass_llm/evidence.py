"""Source-preserving quote location with conservative case-insensitive fallback."""

import re
import unicodedata

from .schema import Evidence
from .segmenter import Block, Span


def normalize(text: str) -> str:
    """Collapse whitespace without changing non-whitespace characters."""
    return ' '.join(text.split())


def _fold(text: str) -> str:
    normalized = unicodedata.normalize('NFC', text)
    if len(normalized) == len(text):
        text = normalized
    # Leave expanding characters literal so they cannot match a safe folded target.
    return ''.join(char.casefold() if _safe_case(char) else char for char in text)


def _safe_case(text: str) -> bool:
    return all(len(char.casefold()) == len(char.lower()) == 1 for char in text)


def _find(quote: str, text: str, *, fold: bool = False) -> tuple[int, int] | None:
    target = normalize(quote)
    normalized = ''
    offsets: list[tuple[int, int]] = []
    for match in re.finditer(r'\S+', text):
        if normalized:
            normalized += ' '
            offsets.append((offsets[-1][1], match.start()))
        normalized += match.group()
        offsets.extend((index, index + 1) for index in range(match.start(), match.end()))
    if fold:
        if not _safe_case(target) or len(unicodedata.normalize('NFC', target)) != len(target):
            return None
        target, normalized = _fold(target), _fold(normalized)
    start = normalized.find(target)
    if start < 0:
        return None
    left, right = offsets[start][0], offsets[start + len(target) - 1][1]
    original = text[left:right]
    if fold and (not _safe_case(original)
                 or len(unicodedata.normalize('NFC', original)) != len(original)):
        return None
    return left, right


def source_value(value: str, text: str) -> str | None:
    """Return the literal source substring supporting a model's text value."""
    if not normalize(value):
        return None
    start = text.find(value)
    if start >= 0:
        return text[start:start + len(value)]
    for fold in (False, True):
        match = _find(value, text, fold=fold)
        if match is not None:
            return text[match[0]:match[1]]
    return None


def locate(quote: str | None, block: Block, *, ignore_case: bool = True) -> Evidence | None:
    """Locate exact, whitespace-normalized, then length-preserving casefold quotes."""
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
    for fold in ((False, True) if ignore_case else (False,)):
        for span in groups:
            match = _find(quote, span.text, fold=fold)
            if match is not None:
                left, right = match
                return Evidence(span.page, span.start + left, span.start + right, span.text[left:right])
    return None
