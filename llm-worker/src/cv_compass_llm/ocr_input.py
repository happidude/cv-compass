"""Strict OCR boundary; errors never contain source data."""

from dataclasses import dataclass
from pathlib import Path

from .json_strict import strict_json

MAX_BYTES = 8 * 1024 * 1024


@dataclass(frozen=True)
class Page:
    number: int
    status: str
    raw_text: str


@dataclass(frozen=True)
class Document:
    pages: tuple[Page, ...]


def parse_document(text: str) -> Document:
    """Parse a strict OCR document with consecutive page numbers."""
    if not isinstance(text, str):
        raise ValueError('INVALID_OCR')
    try:
        if len(text.encode('utf-8')) > MAX_BYTES:
            raise ValueError('OCR_TOO_LARGE')
    except UnicodeError:
        raise ValueError('INVALID_UTF8') from None
    root = strict_json(text)
    if isinstance(root, dict) and 'ocr_result' in root:
        root = root['ocr_result']
    if not isinstance(root, dict) or not isinstance(root.get('pages'), list):
        raise ValueError('INVALID_OCR')
    pages = []
    for number, item in enumerate(root['pages'], 1):
        if not isinstance(item, dict):
            raise ValueError('INVALID_PAGE')
        index, status, raw = item.get('page_number'), item.get('status'), item.get('raw_text')
        if type(index) is not int or index != number:
            raise ValueError('INVALID_PAGE_NUMBER')
        if status not in ('ok', 'blank', 'failed', 'not_processed'):
            raise ValueError('INVALID_PAGE_STATUS')
        if (status == 'ok' or raw is not None) and not isinstance(raw, str):
            raise ValueError('INVALID_PAGE_TEXT')
        pages.append(Page(number, status, raw or ''))
    return Document(tuple(pages))


def load_document(path: str | Path) -> Document:
    """Read bounded UTF-8 OCR input without changing the source file."""
    try:
        with Path(path).open('rb') as stream:
            data = stream.read(MAX_BYTES + 1)
    except OSError:
        raise ValueError('OCR_READ_ERROR') from None
    if len(data) > MAX_BYTES:
        raise ValueError('OCR_TOO_LARGE')
    try:
        text = data.decode('utf-8', errors='strict')
    except UnicodeError:
        raise ValueError('INVALID_UTF8') from None
    return parse_document(text)
