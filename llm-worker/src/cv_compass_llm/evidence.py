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
    if fold and (
        not _safe_case(original) or len(unicodedata.normalize('NFC', original)) != len(original)
    ):
        return None
    return left, right


def source_value(value: str, text: str, *, quote_match: str = 'strict') -> str | None:
    """Return the literal source substring supporting a model's text value."""
    if not normalize(value):
        return None
    start = text.find(value)
    if start >= 0 and (
        quote_match != 'tolerant' or _whole_clusters(text, start, start + len(value))
    ):
        return text[start : start + len(value)]
    for fold in (False, True):
        match = _find(value, text, fold=fold)
        if match is not None and (quote_match != 'tolerant' or _whole_clusters(text, *match)):
            return text[match[0] : match[1]]
    if quote_match == 'tolerant':
        for left, right in _quote_matches(value, text):
            return text[left:right]
    return None


def locate(
    quote: str | None,
    block: Block,
    *,
    ignore_case: bool = True,
    quote_match: str = 'strict',
) -> Evidence | None:
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
        if start >= 0 and (
            quote_match != 'tolerant' or _whole_clusters(span.text, start, start + len(quote))
        ):
            return Evidence(span.page, span.start + start, span.start + start + len(quote), quote)
    for fold in (False, True) if ignore_case else (False,):
        for span in groups:
            match = _find(quote, span.text, fold=fold)
            if match is not None and (
                quote_match != 'tolerant' or _whole_clusters(span.text, *match)
            ):
                left, right = match
                return Evidence(
                    span.page, span.start + left, span.start + right, span.text[left:right]
                )
    if quote_match == 'tolerant':
        for span in groups:
            for left, right in _quote_matches(quote, span.text, ignore_case=ignore_case):
                return Evidence(
                    span.page, span.start + left, span.start + right, span.text[left:right]
                )
    return None


def _quote_clusters(text: str):
    """Use the personal matcher's cluster boundaries without changing its behavior."""
    index = 0
    while index < len(text):
        end = index + 1
        while end < len(text) and (
            unicodedata.combining(text[end])
            or len(unicodedata.normalize('NFC', text[index : end + 1]))
            < len(unicodedata.normalize('NFC', text[index:end]))
            + len(unicodedata.normalize('NFC', text[end]))
        ):
            end += 1
        yield unicodedata.normalize('NFC', text[index:end]), index, end
        index = end


def _whole_clusters(text: str, left: int, right: int) -> bool:
    return not any(
        start < left < end or start < right < end for _, start, end in _quote_clusters(text)
    )


def _quote_text(text: str, *, fold: bool) -> tuple[str, list[tuple[int, int]]]:
    """Keep mandatory word spaces and map whole NFC clusters back to raw ranges."""
    chars, offsets = [], []
    for cluster, index, end in _quote_clusters(text):
        cluster = cluster.translate(_PERSONAL_PUNCTUATION)
        for char in cluster:
            if char.isspace():
                if chars and chars[-1] == ' ':
                    offsets[-1] = (offsets[-1][0], end)
                elif chars:
                    chars.append(' ')
                    offsets.append((index, end))
            else:
                chars.append(char.casefold() if fold and _safe_case(char) else char)
                offsets.append((index, end))
    if chars and chars[-1] == ' ':
        chars.pop()
        offsets.pop()
    return ''.join(chars), offsets


def _quote_matches(quote: str, text: str, *, ignore_case: bool = True):
    """Yield literal spans; expanding case folds and partial clusters cannot match."""
    for fold in (False, True) if ignore_case else (False,):
        target, _ = _quote_text(quote, fold=False)
        if not target:
            return
        if fold:
            if not _safe_case(target):
                continue
            target = target.casefold()
        normalized, offsets = _quote_text(text, fold=fold)
        start = normalized.find(target)
        while start >= 0:
            end = start + len(target)
            if (start == 0 or offsets[start] != offsets[start - 1]) and (
                end == len(offsets) or offsets[end - 1] != offsets[end]
            ):
                left, right = offsets[start][0], offsets[end - 1][1]
                if not fold or _safe_case(text[left:right]):
                    yield left, right
            start = normalized.find(target, start + 1)


def _value_boundaries(span: Span, left: int, right: int) -> bool:
    """B3 cannot take part of a word, including a preceding base with raw marks."""
    text = span.raw_text if span.raw_text is not None else span.text
    if span.raw_text is not None:
        left, right = span.start + left, span.start + right
    before = left - 1
    while before >= 0 and unicodedata.combining(text[before]):
        before -= 1
    return (before < 0 or not text[before].isalnum()) and (
        right == len(text) or (not text[right].isalnum() and not unicodedata.combining(text[right]))
    )


def locate_value(value: str, block: Block, near: Evidence | None) -> Evidence | None:
    """Choose a B3 literal occurrence, preferring the period's page then offset."""
    candidates = [
        Evidence(span.page, span.start + left, span.start + right, span.text[left:right])
        for span in personal_groups(block)
        for left, right in _quote_matches(value, span.text)
        if _value_boundaries(span, left, right)
    ]
    if not candidates:
        return None
    if near is None:
        return min(candidates, key=lambda item: (item.page, item.start))
    return min(
        candidates,
        key=lambda item: (
            item.page != near.page,
            abs(item.start - near.start) if item.page == near.page else abs(item.page - near.page),
            item.page,
            item.start,
        ),
    )


_PERSONAL_PUNCTUATION = str.maketrans(
    {'‘': "'", '’': "'", '“': '"', '”': '"', '–': '-', '—': '-', '‐': '-'}
)


def personal_text(text: str, *, fold: bool = False) -> tuple[str, list[tuple[int, int]]]:
    """Normalize harmless personal quote differences with code-point source ranges."""
    chars: list[str] = []
    offsets: list[tuple[int, int]] = []
    index = 0
    while index < len(text):
        end = index + 1
        while end < len(text) and (
            unicodedata.combining(text[end])
            or len(unicodedata.normalize('NFC', text[index : end + 1]))
            < len(unicodedata.normalize('NFC', text[index:end]))
            + len(unicodedata.normalize('NFC', text[end]))
        ):
            end += 1
        normalized = unicodedata.normalize('NFC', text[index:end]).translate(_PERSONAL_PUNCTUATION)
        for char in normalized:
            if fold and _safe_case(char):
                char = char.casefold()
            chars.append(char)
            offsets.append((index, end))
        index = end
    result: list[str] = []
    ranges: list[tuple[int, int]] = []
    index = 0
    while index < len(chars):
        char = chars[index]
        if char.isspace():
            end = index + 1
            while end < len(chars) and chars[end].isspace():
                end += 1
            # Spaces remain mandatory between words; only punctuation-adjacent spaces vary.
            if (
                result
                and end < len(chars)
                and not unicodedata.category(result[-1]).startswith('P')
                and not unicodedata.category(chars[end]).startswith('P')
            ):
                result.append(' ')
                ranges.append((offsets[index][0], offsets[end - 1][1]))
            index = end
            continue
        result.append(char)
        ranges.append(offsets[index])
        index += 1
    return ''.join(result), ranges


def find_personal(
    quote: str | None, text: str, *, ignore_case: bool = True
) -> tuple[int, int] | None:
    """Match personal text without edits to letters, digits or punctuation tokens."""
    if not isinstance(quote, str):
        return None
    for fold in (False, True) if ignore_case else (False,):
        target, _ = personal_text(quote, fold=fold)
        if len(target) < 2:
            return None
        normalized, offsets = personal_text(text, fold=fold)
        start = normalized.find(target)
        while start >= 0:
            end = start + len(target)
            # Never accept only part of a canonically normalized source cluster.
            if (start == 0 or offsets[start] != offsets[start - 1]) and (
                end == len(offsets) or offsets[end - 1] != offsets[end]
            ):
                return offsets[start][0], offsets[end - 1][1]
            start = normalized.find(target, start + 1)
    return None


def personal_groups(block: Block) -> tuple[Span, ...]:
    """Join only contiguous source spans on the same page."""
    groups: list[Span] = []
    for span in block.spans:
        if groups and groups[-1].page == span.page and groups[-1].end == span.start:
            previous = groups.pop()
            groups.append(
                Span(
                    span.page,
                    previous.start,
                    span.end,
                    previous.text + span.text,
                    previous.raw_text,
                )
            )
        else:
            groups.append(span)
    return tuple(groups)


def locate_personal(
    quote: str | None, block: Block, *, ignore_case: bool = True
) -> Evidence | None:
    """Locate a personal quote on one page using its original offsets."""
    for span in personal_groups(block):
        match = find_personal(quote, span.text, ignore_case=ignore_case)
        if match is not None:
            start, end = match
            return Evidence(span.page, span.start + start, span.start + end, span.text[start:end])
    return None


def summary_evidence(items: list[dict], block: Block) -> tuple[Evidence, ...] | None:
    """Recover ordered full paragraphs, splitting page crossings into literal evidence."""
    groups = personal_groups(block)
    text = '\n'.join(span.text for span in groups)
    ranges = []
    offset = 0
    for span in groups:
        ranges.append((offset, offset + len(span.text), span))
        offset += len(span.text) + 1
    evidence: list[Evidence] = []
    previous_end = 0
    for item in items:
        start_match = find_personal(item['quoteStart'], text[previous_end:])
        if start_match is None:
            return None
        start = (previous_end + start_match[0], previous_end + start_match[1])
        end_match = find_personal(item['quoteEnd'], text[start[0] :])
        end = (start[0] + end_match[0], start[0] + end_match[1]) if end_match is not None else None
        if end is None or end[1] < start[1]:
            return None
        selected = []
        for left, right, span in ranges:
            begin, finish = max(start[0], left), min(end[1], right)
            if begin < finish:
                selected.append(
                    Evidence(
                        span.page,
                        span.start + begin - left,
                        span.start + finish - left,
                        text[begin:finish],
                    )
                )
        # A crossing must be between adjacent pages, never over missing OCR or source gaps.
        if not selected or any(
            right.page != left.page + 1 for left, right in zip(selected, selected[1:])
        ):
            return None
        evidence.extend(selected)
        previous_end = end[1]
    if not 1 <= len(evidence) <= 8 or len('\n'.join(item.text for item in evidence)) > 6000:
        return None
    if any(
        (right.page, right.start) < (left.page, left.end)
        for left, right in zip(evidence, evidence[1:])
    ):
        return None
    return tuple(evidence)
