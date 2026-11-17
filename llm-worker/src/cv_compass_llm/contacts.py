"""Deterministic contact extraction from successful OCR pages."""

import re

from .ocr_input import Document
from .schema import Evidence, FieldValue

PATTERNS = {
    'email': r'(?<![\w.+-])[\w.+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+',
    'phone': (
        r'(?<![\w+(])(?:'
        r'(?:\([ \t]{0,3}\+[ \t]{0,3}8[ \t]{0,3}4[ \t]{0,3}\)|\+[ \t]{0,3}8[ \t]{0,3}4)'
        r'[ \t.\-]{0,3}[1-9](?:[ \t.\-]{0,3}[0-9]){8,9}'
        r'|0(?:[ \t.\-]{0,3}[0-9]){9,10})(?![ \t.\-]*[0-9]|\w)'
    ),
    'url': r'(?i)\b(?:https?://|(?:www\.)?(?:linkedin\.com/|github\.com/))[^\s<>]+',
}


def extract_contacts(document: Document) -> tuple[FieldValue, ...]:
    """Extract deterministic contacts with exact source evidence."""
    fields = []
    for page in document.pages:
        if page.status != 'ok':
            continue
        for key, pattern in PATTERNS.items():
            last_phone_end = -1
            for match in re.finditer(pattern, page.raw_text):
                if key == 'phone':
                    line_start = (
                        max(
                            page.raw_text.rfind('\n', 0, match.start()),
                            page.raw_text.rfind('\r', 0, match.start()),
                        )
                        + 1
                    )
                    # Digits right before the match block it (account-number tail), unless they
                    # are the tail of a phone already accepted on this line.
                    after_phone = last_phone_end >= line_start and re.fullmatch(
                        r'[ \t.\-]*', page.raw_text[last_phone_end : match.start()]
                    )
                    if not after_phone and re.search(
                        r'[0-9][ \t.\-]+$', page.raw_text[line_start : match.start()]
                    ):
                        continue
                if key == 'phone' and re.search(
                    r'\b(?:19|20)[0-9]{2}\b|\b[0-3]?[0-9][.-][01]?[0-9][.-](?:19|20)[0-9]{2}\b',
                    match.group(),
                ):
                    continue
                if key == 'phone':
                    last_phone_end = match.end()
                value = match.group().rstrip('.,;') if key == 'url' else match.group()
                fields.append(
                    FieldValue(
                        key,
                        value,
                        Evidence(
                            page.number,
                            match.start(),
                            match.start() + len(value),
                            value,
                        ),
                    )
                )
    return tuple(fields)
