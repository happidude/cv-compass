"""Deterministic name cuts expressed as literal, contiguous source spans."""

import re

COUNTRIES = (
    'USA',
    'US',
    'UK',
    'Vietnam',
    'Viet Nam',
    'Japan',
    'Korea',
    'South Korea',
    'Singapore',
    'Germany',
    'Norway',
    'Denmark',
    'Australia',
    'Canada',
    'France',
    'China',
    'Switzerland',
    'Sweden',
    'Finland',
    'Netherlands',
    'Belgium',
    'Italy',
    'Spain',
    'India',
    'Thailand',
    'Malaysia',
    'Indonesia',
    'Philippines',
    'New Zealand',
)
LEGAL_SUFFIXES = (
    r'CO\.?\s*,?\s*LTD\.?',
    r'LTD\.?',
    r'JSC',
    r'CORPORATION',
    r'CORP\.?',
    r'COMPANY',
    r'GROUP',
    r'INC\.?',
    r'LLC',
    r'PLC',
    r'AG',
    r'GMBH',
)
_COUNTRY = re.compile(r'^\s*(?:' + '|'.join(map(re.escape, COUNTRIES)) + r'),\s+', re.I)
# Fixed uppercase suffixes exclude proper-name words such as "Marketing Group VN".
_LEGAL = re.compile(r'(?<!\w)(?:' + '|'.join(LEGAL_SUFFIXES) + r')(?!\w)')
_LEGAL_EXTENSION = re.compile(
    r'\s+(?:JOINT STOCK COMPANY|JOINT STOCK|LIMITED|' + '|'.join(LEGAL_SUFFIXES) + r'|CO\.)(?!\w)',
    re.I,
)
_VIETNAMESE_LEGAL = re.compile(r'^\s*Công ty\s+(?:TNHH|Cổ phần)\b', re.I)
_PAREN = re.compile(r'\s*\(([^()]*)\)\s*$')
_EDGES = ',;:-–—|/'


class _Name:
    """Match collapsed whitespace while retaining code-point source offsets."""

    def __init__(self, value: str) -> None:
        parts = list(re.finditer(r'\s+|\S', value))
        self.view = ''.join(' ' if part.group().isspace() else part.group() for part in parts)
        self.offsets = [(part.start(), part.end()) for part in parts]
        self.start, self.end = 0, len(self.view)
        self.length = len(value)

    @property
    def text(self) -> str:
        return self.view[self.start : self.end]

    def keep(self, start: int = 0, end: int | None = None) -> None:
        text = self.text
        end = len(text) if end is None else end
        if start > 0:
            while start < end and (text[start].isspace() or text[start] in _EDGES):
                start += 1
        if end < len(text):
            while end > start and (text[end - 1].isspace() or text[end - 1] in _EDGES):
                end -= 1
        # A failed cut must not erase a supported name.
        if start < end and text[start:end].strip():
            self.end = self.start + end
            self.start += start

    def span(self) -> tuple[int, int]:
        if not self.offsets:
            return 0, self.length
        return self.offsets[self.start][0], self.offsets[self.end - 1][1]


def _label(name: _Name, pattern: str) -> None:
    match = re.match(r'^\s*(?:' + pattern + r'):\s*', name.text, re.I)
    if match is not None:
        name.keep(match.end())


def _parenthesis(name: _Name, limit: int, *, company: bool = False) -> None:
    match = _PAREN.search(name.text)
    if match is not None and (
        len(match[1].split()) > limit
        or (company and re.match(r'^(?:incl\b|including\b|bao gồm\b)', match[1], re.I))
    ):
        name.keep(end=match.start())


def _legal_run(name: _Name) -> None:
    if _VIETNAMESE_LEGAL.match(name.text):
        return
    matches = list(_LEGAL.finditer(name.text))
    index = 0
    while index < len(matches):
        end = matches[index].end()
        index += 1
        # Only whitespace joins legal forms; commas belong inside CO., LTD or end the run.
        while continuation := _LEGAL_EXTENSION.match(name.text, end):
            end = continuation.end()
        while index < len(matches) and matches[index].start() < end:
            index += 1
        parenthesis = re.match(r'\s*\(([^()]*)\)', name.text[end:])
        if (
            parenthesis is not None
            and len(parenthesis[1].split()) <= 6
            and not re.match(r'^(?:incl\b|including\b|bao gồm\b)', parenthesis[1], re.I)
        ):
            end += parenthesis.end()
        tail = name.text[end:]
        if tail.strip() == '.':
            continue
        separator = re.match(r'(?:\s*,| -|\s*–|\s*- )', tail)
        words = re.match(r'\s+([^\W\d_]+)\b', tail)
        if separator is not None or (words is not None and words[1].isupper()):
            name.keep(end=end)
            return


def trim_company(value: str) -> tuple[int, int]:
    """Return the source span retained by company rules 1–5, then 15."""
    name = _Name(value)
    _label(name, r'Company|Công ty')
    separator = name.text.find(' | ')
    if separator >= 0:
        name.keep(end=separator)
    vietnamese_legal = _VIETNAMESE_LEGAL.match(name.text) is not None
    if not vietnamese_legal:
        _parenthesis(name, 6, company=True)
        country = _COUNTRY.match(name.text)
        if country is not None:
            name.keep(country.end())
        # The approved interpretation selects the first eligible suffix run.
        _legal_run(name)
    belonging = re.search(r'(?:\s*\(\s*|\s+)belong to\s+', name.text, re.I)
    if belonging is not None:
        name.keep(end=belonging.start())
    if vietnamese_legal:
        return name.span()
    for separator in re.finditer(r' [–-] ', name.text):
        tail = name.text[separator.end() :]
        words = re.findall(r'[^\W\d_]+', tail)
        if any(len(word) >= 3 and word.islower() for word in words):
            name.keep(end=separator.start())
            break
    return name.span()


def trim_title(value: str, company: str | None) -> tuple[int, int]:
    """Return the source span retained by rules 6–9, 12, 10, 11, 13, 14."""
    name = _Name(value)
    _label(name, r'Position|Chức vụ|Vị trí')
    worked = re.match(r'^\s*Work(?:ed)? as (.+?) (?:in|at) ', name.text, re.I)
    if worked is not None:
        name.keep(worked.start(1), worked.end(1))
    for match in re.finditer(r' consults for | for | cho ', name.text, re.I):
        tail = name.text[match.end() :]
        if ':' in tail or len(tail.split()) > 5:
            name.keep(end=match.start())
            break
    _parenthesis(name, 3)
    if company and company.strip():
        company_view = ' '.join(company.split())
        escaped = re.escape(company_view)
        # Rule 12 consumes its connector before rule 10 can leave a dangling "at".
        suffix = re.search(r'(?: / | – | - | \| | at )' + escaped + r'\s*$', name.text, re.I)
        if suffix is not None:
            name.keep(end=suffix.start())
        suffix = re.search(r'(?<!\w)' + escaped + r'\s*$', name.text, re.I)
        if suffix is not None:
            name.keep(end=suffix.start())
    separator = re.search(r' / | \| ', name.text)
    if separator is not None:
        name.keep(end=separator.start())
    for match in re.finditer(r' at ', name.text, re.I):
        if len(name.text[match.end() :].split()) >= 2:
            name.keep(end=match.start())
            break
    department = re.search(r',\s*(?:[^,]*\bDepartment\b[^,]*|Phòng\b[^,]*)$', name.text, re.I)
    if department is not None:
        name.keep(end=department.start())
    return name.span()
