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
# Keep the v1 token grammar independent: DOB and segmentation still use it.
ENGLISH_V2 = '(' + ENGLISH_MONTH + r')\.?[,]?[\x27’]?(?:\s*[-–—]\s*|\s+)([0-9]{4})'
ENGLISH_SHORT_YEAR = (
    '(' + ENGLISH_MONTH + r')(?:[ \t]+[.\x27’]?[ \t]*|[.\x27’][ \t]*)([0-9]{2})'
    r'(?!\w|[ \t]*[:/]|[ \t]*(?:,[ \t]*)?[0-9]{4}\b)'
)
DOTTED_MONTH_YEAR = r'([0-9]{1,2})[ \t]*\.[ \t]*([0-9]{4})'
DATE_TOKEN_V2 = re.compile(
    r'(?<![\w/])(?<![0-9][.,:])(?:'
    + '|'.join(
        (
            VIETNAMESE,
            ENGLISH_MONTH_DAY,
            ENGLISH_DAY_MONTH,
            ENGLISH_V2,
            ENGLISH_SHORT_YEAR,
            NUMERIC,
            DOTTED_MONTH_YEAR,
        )
    )
    + r')(?!\w|[/.][0-9]|,[0-9])',
    re.IGNORECASE,
)
PAGE_MARKER = re.compile(r'(?<!\w)(?:page|p\.|trang|tr\.)[ \t]*$', re.IGNORECASE)
NUMERIC_CHAIN = re.compile(r'(?<!\w)[0-9]+[ \t]*\.[ \t]*[0-9]{1,2}[ \t]*\.[ \t]*[0-9]{4}(?![0-9])')
FOUR_DIGIT_YEAR = re.compile(r'(?<![0-9])(?:19[5-9][0-9]|20[0-9]{2}|2100)(?![0-9])')
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
NUMERIC_RANGE_V2 = re.compile(
    r'(?P<start>'
    + DOTTED_MONTH_YEAR
    + '|'
    + NUMERIC
    + r')\s*[-–—]\s*(?P<end>'
    + DOTTED_MONTH_YEAR
    + '|'
    + NUMERIC
    + ')'
)


def _tolerant_months() -> dict[str, int]:
    candidates: dict[str, set[int]] = {}
    # Common names and words are too ambiguous to treat as misspelled months.
    ambiguous = {'amy', 'marc', 'par', 'cot', 'esp', 'spe'}
    for number, names in enumerate(MONTHS, 1):
        name = names[0]
        variants = {name[:length] for length in range(3, len(name) + 1)}
        short = name[:3]
        for index in range(2):
            variants.add(short[:index] + short[index + 1] + short[index] + short[index + 2 :])
        for variant in variants:
            candidates.setdefault(variant, set()).add(number)
    return {
        name: next(iter(numbers))
        for name, numbers in candidates.items()
        if len(numbers) == 1 and name not in ambiguous
    }


MONTH_NUMBERS_V3 = _tolerant_months()
ENGLISH_V3 = (
    '('
    + '|'.join(sorted(MONTH_NUMBERS_V3, key=lambda name: (-len(name), name)))
    + r')\.?[,]?[\x27’]?(?:\s*[-–—]\s*|\s+)([0-9]{4})'
)
SPACED_MONTH_YEAR = r'([0-9]{1,2})[ \t]*[/.–—-][ \t]*([0-9]{4})'
VIETNAMESE_V3 = r'(?:tháng\s+|t)' + SPACED_MONTH_YEAR
PHONE_V3 = re.compile(r'(?<![\w+])\+?\(?[0-9]+(?:[ \t()./–—-]+[0-9]+)*\)?(?!\w)')
DATE_TOKEN_V3 = re.compile(
    DATE_TOKEN_V2.pattern
    + r'|(?<![\w/])(?<![0-9][.,:])(?:'
    + '|'.join((VIETNAMESE_V3, ENGLISH_V3, SPACED_MONTH_YEAR))
    + r')(?!\w|[/.][0-9]|,[0-9])',
    re.IGNORECASE,
)
NUMERIC_RANGE_V3 = re.compile(
    r'(?P<start>'
    + SPACED_MONTH_YEAR
    + '|'
    + NUMERIC
    + r')\s*[-–—]\s*(?P<end>'
    + SPACED_MONTH_YEAR
    + '|'
    + NUMERIC
    + ')'
)


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


def parse_date(text: str, *, style: str = 'v1', current_year: int | None = None) -> str | None:
    """Parse one explicit date, retaining only year or year-month precision."""
    if style == 'v3':
        if match := re.fullmatch(ENGLISH_V3, text.strip(), re.IGNORECASE):
            return parse_date(f'{match[2]}-{MONTH_NUMBERS_V3[match[1].casefold()]:02d}')
        if match := re.fullmatch(
            r'(?:tháng\s+|t)?' + SPACED_MONTH_YEAR, text.strip(), re.IGNORECASE
        ):
            return parse_date(f'{match[2]}-{int(match[1]):02d}')
    if style in ('v2', 'v3'):
        if match := re.fullmatch(ENGLISH_SHORT_YEAR, text.strip(), re.IGNORECASE):
            pivot = (date.today().year if current_year is None else current_year) % 100 + 1
            year = int(match[2])
            year += 2000 if year <= pivot else 1900
            return parse_date(f'{year:04d}-{MONTH_NUMBERS[match[1].casefold()]:02d}')
        if match := re.fullmatch(ENGLISH_V2, text.strip(), re.IGNORECASE):
            return parse_date(f'{match[2]}-{MONTH_NUMBERS[match[1].casefold()]:02d}')
        if match := re.fullmatch(DOTTED_MONTH_YEAR, text.strip()):
            return parse_date(f'{match[2]}-{int(match[1]):02d}')
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


def is_weak_date_token(text: str) -> bool:
    """Identify bare numeric month/year tokens with whitespace beside the separator."""
    return re.fullmatch(SPACED_MONTH_YEAR, text) is not None and bool(re.search(r'[ \t]', text))


def _numeric_date_context(quote: str, start: int) -> bool:
    prefix = quote[:start]
    if not prefix.strip():
        return True
    return re.search(r'(?:[([{:,]|\b(?:from|từ|since))\s*\Z', prefix, re.IGNORECASE) is not None


def _token_matches_v2(quote: str, *, style: str = 'v2') -> list[re.Match[str]]:
    excluded = []
    explicit_year = FOUR_DIGIT_YEAR.search(quote) is not None
    numeric_range = NUMERIC_RANGE_V3 if style == 'v3' else NUMERIC_RANGE_V2
    date_token = DATE_TOKEN_V3 if style == 'v3' else DATE_TOKEN_V2
    phone = PHONE_V3 if style == 'v3' else PHONE
    for match in phone.finditer(quote):
        digits = sum(char.isdigit() for char in match.group())
        date_range = numeric_range.fullmatch(match.group())
        if (
            9 <= digits
            and (digits <= 15 or style == 'v3')
            and not (
                date_range
                and parse_date(date_range['start'], style=style)
                and parse_date(date_range['end'], style=style)
            )
        ):
            excluded.append((match.start(), match.end()))
    for match in NUMERIC_CHAIN.finditer(quote):
        # Consume malformed day.month.year runs without swallowing sentence dots after years.
        if (
            re.search(DOTTED_MONTH_YEAR, match.group())
            and not re.fullmatch(DOTTED_MONTH_YEAR, match.group())
            and parse_date(match.group()) is None
        ):
            excluded.append((match.start(), match.end()))
    return [
        match
        for match in date_token.finditer(quote)
        if not (
            re.fullmatch(DOTTED_MONTH_YEAR, match.group())
            and PAGE_MARKER.search(quote[: match.start()])
        )
        and not (explicit_year and re.fullmatch(ENGLISH_SHORT_YEAR, match.group(), re.IGNORECASE))
        and not any(left < match.end() and match.start() < right for left, right in excluded)
        and not (
            style == 'v3'
            and is_weak_date_token(match.group())
            and not _numeric_date_context(quote, match.start())
        )
    ]


def _tokens_v2(quote: str, *, style: str = 'v2') -> list[str]:
    return [match.group() for match in _token_matches_v2(quote, style=style)]


def date_matches_v3(quote: str) -> list[re.Match[str]]:
    """Collect source endpoints, preserving v2 matches when a richer match is rejected."""
    matches = _token_matches_v2(quote, style='v3')
    for legacy in _token_matches_v2(quote):
        if not any(
            match.start() < legacy.end() and legacy.start() < match.end() for match in matches
        ):
            matches.append(legacy)
    return sorted(matches, key=lambda match: match.start())


def find_dates(quote: str, *, style: str = 'v1', current_year: int | None = None) -> list[str]:
    """Collect normalized dates in source order, never nested numeric fragments."""
    if style == 'v3':
        # Keep legacy tokens (including bare years) as well as richer overlapping tokens.
        positioned = {}
        for token_style in ('v3', 'v2'):
            for match in _token_matches_v2(quote, style=token_style):
                value = parse_date(match.group(), style=token_style, current_year=current_year)
                if value is not None:
                    positioned[(match.start(), -match.end(), value)] = value
        return [positioned[key] for key in sorted(positioned)]
    if style == 'v2':
        return [
            value
            for token in _tokens_v2(quote)
            if (value := parse_date(token, style=style, current_year=current_year)) is not None
        ]
    return [value for token in _tokens(quote) if (value := parse_date(token)) is not None]


def find_birth_dates(quote: str) -> list[tuple[str, bool]]:
    """Collect DOB tokens at their exact precision, including source ambiguity."""
    return [
        parsed for token in _tokens(quote) if (parsed := parse_birth_date(token))[0] is not None
    ]


def has_present(quote: str) -> bool:
    """Detect a standalone ongoing marker without inferring current employment."""
    return PRESENT_TOKEN.search(quote) is not None


def has_open_period(quote: str) -> bool:
    """Require a valid source date immediately before the final range separator."""
    separator = re.search(r'[-–—~→]\s*\Z', quote)
    if separator is None:
        return False
    prefix = quote[: separator.start()].rstrip()
    matches = date_matches_v3(prefix)
    if len(matches) != 1:
        return False
    return any(
        match.end() == len(prefix) and parse_date(match.group(), style='v3') is not None
        for match in matches
    )


def normalize_date(
    value: str, *, style: str = 'v1', current_year: int | None = None
) -> tuple[str | None, bool]:
    """Compatibility wrapper for validators using the previous raising interface."""
    if PRESENT_TOKEN.fullmatch(value.strip()):
        return None, True
    normalized = parse_date(value, style=style, current_year=current_year)
    if normalized is None:
        raise ValueError('INVALID_DATE')
    return normalized, False
