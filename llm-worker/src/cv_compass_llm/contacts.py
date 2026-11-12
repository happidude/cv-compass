"""Deterministic contact extraction from successful OCR pages."""

import re

from .ocr_input import Document
from .schema import Evidence, FieldValue

PATTERNS = {
    'email': r'(?<![\w.+-])[\w.+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+',
    'phone': r'(?<![\w+])(?:\+84|0)(?:[ .-]?\d){9}(?![ .-]?\d|\w)',
    'url': r'(?i)\b(?:https?://|(?:www\.)?(?:linkedin\.com/|github\.com/))[^\s<>]+',
}


def extract_contacts(document: Document) -> tuple[FieldValue, ...]:
    """Extract deterministic contacts with exact source evidence."""
    fields = []
    for page in document.pages:
        if page.status != 'ok':
            continue
        for key, pattern in PATTERNS.items():
            for match in re.finditer(pattern, page.raw_text):
                value = match.group().rstrip('.,;') if key == 'url' else match.group()
                fields.append(FieldValue(key, value, Evidence(
                    page.number, match.start(), match.start() + len(value), value,
                )))
    return tuple(fields)
