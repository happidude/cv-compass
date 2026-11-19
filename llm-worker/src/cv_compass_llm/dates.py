"""Pure, conservative parsing of explicit date tokens without missing-part inference."""

import re
from datetime import date

MONTHS = (
    ('january', 'jan'),
    ('february', 'feb'),
    ('march', 'mar'),
    ('april', 'apr'),
    ('may',),
    ('june', 'jun'),
    ('july', 'jul'),
    ('august', 'aug'),
    ('september', 'sep', 'sept'),
    ('october', 'oct'),
    ('november', 'nov'),
    ('december', 'dec'),
)
MONTH_NUMBERS = {name: number for number, names in enumerate(MONTHS, 1) for name in names}
ENGLISH_MONTH = r'(?:' + '|'.join(MONTH_NUMBERS) + r')'
ENGLISH = '(' + ENGLISH_MONTH + r')\.?[,]?(?:\s*[-–—]\s*|\s+)([0-9]{4})'
ENGLISH_MONTH_DAY = (
    '(' + ENGLISH_MONTH + r')\.?\s+([0-9]{1,2})(?:st|nd|rd|th)?(?:,\s*|\s+)([0-9]{4})'
)
ENGLISH_DAY_MONTH = r'([0-9]{1,2})(?:st|nd|rd|th)?\s+(' + ENGLISH_MONTH + r')\.?[,]?\s+([0-9]{4})'
VIETNAMESE = r'(?:tháng\s+|t)[0-9]{1,2}(?:[/.-]|\s+năm\s+)[0-9]{4}'
NUMERIC = (
    r'(?:[0-9]{4}[-/.][0-9]{1,2}[-/.][0-9]{1,2}'
    r'|[0-9]{1,2}[-/.][0-9]{1,2}[-/.][0-9]{4}'
    r'|[0-9]{4}[-/.][0-9]{1,2}|[0-9]{1,2}[-/.][0-9]{4}|[0-9]{4})'
)
# Delimiters next to digits belong to the numeric token, not a nested date.
DATE_TOKEN = re.compile(
    r'(?<![\w/])(?<![0-9][.,:])(?:'
    + VIETNAMESE
    + '|'
    + ENGLISH_MONTH_DAY
    + '|'
    + ENGLISH_DAY_MONTH
    + '|'
    + ENGLISH
    + '|'
    + NUMERIC
    + r')(?!\w|[/.][0-9]|,[0-9])',
    re.IGNORECASE,
)
PRESENT = (
    'present',
    'now',
    'current',
    'today',
    'to date',
    'hiện tại',
    'hiện nay',
    'nay',
    'đến nay',
)
PRESENT_TOKEN = re.compile(
    r'\b(?:' + '|'.join(word.replace(' ', r'\s+') for word in PRESENT) + r')\b',
    re.IGNORECASE,
)
PHONE = re.compile(r'(?<![\w+])\+?\(?[0-9]+(?:[ \t().-]+[0-9]+)*\)?(?!\w)')
NUMERIC_RANGE = re.compile(r'(' + NUMERIC + r')\s*[-–—]\s*(' + NUMERIC + r')')


def _parts(text: str) -> tuple[int, int | None, int | None, bool] | None:
    text = text.strip().casefold()
    year, month, day, day_first = 0, None, None, False
    if re.fullmatch(r'[0-9]{4}', text):
        year = int(text)
    elif match := re.fullmatch(r'([0-9]{4})([-/.])([0-9]{2})', text):
        year, month = int(match[1]), int(match[3])
    elif match := re.fullmatch(r'([0-9]{1,2})([-/.])([0-9]{4})', text):
        month, year = int(match[1]), int(match[3])
    elif match := re.fullmatch(r'([0-9]{4})-([0-9]{2})-([0-9]{2})', text):
        year, month, day = map(int, match.groups())
    elif match := re.fullmatch(r'([0-9]{1,2})([-/.])([0-9]{1,2})\2([0-9]{4})', text):
        day, month, year = int(match[1]), int(match[3]), int(match[4])
        day_first = True
    elif match := re.fullmatch(r'(?:tháng\s+|t)([0-9]{1,2})(?:[/.-]|\s+năm\s+)([0-9]{4})', text):
        month, year = map(int, match.groups())
    elif match := re.fullmatch(ENGLISH_MONTH_DAY, text):
        month, day, year = MONTH_NUMBERS[match[1]], int(match[2]), int(match[3])
    elif match := re.fullmatch(ENGLISH_DAY_MONTH, text):
        day, month, year = int(match[1]), MONTH_NUMBERS[match[2]], int(match[3])
    elif match := re.fullmatch(ENGLISH, text):
        month, year = MONTH_NUMBERS[match[1]], int(match[2])
    if not 1950 <= year <= 2100 or (month is not None and not 1 <= month <= 12):
        return None
    if day is not None:
        try:
            date(year, month, day)
        except ValueError:
            return None
    return year, month, day, day_first


def parse_birth_date(text: str) -> tuple[str | None, bool]:
    """Preserve explicit DOB precision and flag ambiguous day-first input."""
    parts = _parts(text)
    if parts is None:
        return None, False
    year, month, day, day_first = parts
    value = f'{year:04d}'
    if month is not None:
        value += f'-{month:02d}'
    if day is not None:
        value += f'-{day:02d}'
    return value, bool(day_first and day <= 12 and month <= 12)


def parse_date(text: str) -> str | None:
    """Parse one explicit date, retaining only year or year-month precision."""
    value, _ = parse_birth_date(text)
    return value[:7] if value is not None else None


def _tokens(quote: str) -> list[str]:
    excluded = []
    for match in PHONE.finditer(quote):
        digits = sum(char.isdigit() for char in match.group())
        date_range = NUMERIC_RANGE.fullmatch(match.group())
        if 9 <= digits <= 15 and not (
            date_range and parse_date(date_range[1]) and parse_date(date_range[2])
        ):
            excluded.append((match.start(), match.end()))
    return [
        match.group()
        for match in DATE_TOKEN.finditer(quote)
        if not any(left < match.end() and match.start() < right for left, right in excluded)
    ]


def find_dates(quote: str) -> list[str]:
    """Collect normalized dates in source order, never nested numeric fragments."""
    return [value for token in _tokens(quote) if (value := parse_date(token)) is not None]


def find_birth_dates(quote: str) -> list[tuple[str, bool]]:
    """Collect DOB tokens at their exact precision, including source ambiguity."""
    return [
        parsed for token in _tokens(quote) if (parsed := parse_birth_date(token))[0] is not None
    ]


def has_present(quote: str) -> bool:
    """Detect a standalone ongoing marker without inferring current employment."""
    return PRESENT_TOKEN.search(quote) is not None


def normalize_date(value: str) -> tuple[str | None, bool]:
    """Compatibility wrapper for validators using the previous raising interface."""
    if PRESENT_TOKEN.fullmatch(value.strip()):
        return None, True
    normalized = parse_date(value)
    if normalized is None:
        raise ValueError('INVALID_DATE')
    return normalized, False
