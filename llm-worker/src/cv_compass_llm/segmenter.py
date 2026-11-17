"""Line-based segmentation retaining original page ranges."""

import re
from dataclasses import dataclass

from . import contacts
from .dates import find_dates, has_present
from .ocr_input import Document, Page
from .schema import Issue

EXPERIENCE_HEADINGS = (
    'experience',
    'work experience',
    'professional experience',
    'employment history',
    'kinh nghiệm',
    'kinh nghiệm làm việc',
    'quá trình làm việc',
    'quá trình công tác',
    'work history',
    'career history',
    'employment',
    'experiences',
    'kinh nghiệm công tác',
    'lịch sử làm việc',
    'lịch sử công tác',
)
OTHER_HEADINGS = (
    'education',
    'học vấn',
    'skills',
    'kỹ năng',
    'projects',
    'dự án',
    'certifications',
    'chứng chỉ',
    'summary',
    'profile',
    'references',
    'interests',
    'languages',
    'giới thiệu',
    'mục tiêu nghề nghiệp',
    'ngoại ngữ',
    'sở thích',
    'awards',
    'giải thưởng',
    'certificates',
    'certificate',
    'core skills',
    'key skills',
    'personal information',
    'thông tin cá nhân',
    'contact',
    'liên hệ',
    'objective',
    'career objective',
    'hobbies',
    'activities',
    'hoạt động',
    'training',
    'đào tạo',
    'memberships',
    'publications',
)
PERSONAL_LABELS = re.compile(
    r'\b(?:'
    + '|'.join(
        re.escape(label)
        for label in (
            'name',
            'full name',
            'họ tên',
            'họ và tên',
            'date of birth',
            'day of birth',
            'ngày sinh',
            'gender',
            'giới tính',
            'personal information',
            'personal details',
            'thông tin cá nhân',
        )
    )
    + r')\b',
)


@dataclass(frozen=True)
class Span:
    page: int
    start: int
    end: int
    text: str
    raw_text: str | None = None


@dataclass(frozen=True)
class Block:
    spans: tuple[Span, ...]

    @property
    def text(self) -> str:
        """Join source spans with separators across gaps and pages."""
        return ''.join(
            (
                '\n'
                if i
                and (self.spans[i - 1].page != span.page or self.spans[i - 1].end != span.start)
                else ''
            )
            + span.text
            for i, span in enumerate(self.spans)
        )


def _is_job_boundary(text: str) -> bool:
    """Recognize explicit period lines or job introduction labels."""
    return bool(
        re.search(r'^(?:from|từ)\b', text.strip(), re.IGNORECASE)
        or has_present(text)
        or (find_dates(text) and re.search(r'[-–—]|\b(?:to|đến)\b', text, re.IGNORECASE))
    )


def split_spans(spans: list[Span], limit: int = 6000) -> tuple[Block, ...]:
    """Group lines within the limit, preferring the last preceding job boundary."""
    blocks: list[Block] = []
    current: list[Span] = []
    size = 0
    for span in spans:
        while (
            current
            and size
            + len(span.text)
            + (current[-1].page != span.page or current[-1].end != span.start)
            > limit
        ):
            cut = next(
                (
                    index
                    for index in range(len(current) - 1, 0, -1)
                    if _is_job_boundary(current[index].text)
                ),
                len(current),
            )
            blocks.append(Block(tuple(current[:cut])))
            current = current[cut:]
            # Carrying a job changes which page/gap separators belong to this block.
            size = len(Block(tuple(current)).text)
        separator = bool(
            current and (current[-1].page != span.page or current[-1].end != span.start)
        )
        size += len(span.text) + separator
        current.append(span)
    if current:
        blocks.append(Block(tuple(current)))
    return tuple(blocks)


def build_header(pages: tuple[Page, ...]) -> Block:
    """Take the personal page and its successor, retaining source offsets."""
    if not pages:
        return Block(())
    page = next(
        (
            page
            for page in pages[:3]
            if PERSONAL_LABELS.search(page.raw_text.casefold())
            or any(
                field.key in ('email', 'phone')
                for field in contacts.extract_contacts(Document((page,)))
            )
        ),
        pages[0],
    )
    selected = (page,) + tuple(p for p in pages if p.number == page.number + 1)
    spans = []
    remaining = 8000
    for selected_page in selected:
        if spans:
            remaining -= 1  # Block.text inserts a page separator.
        text = selected_page.raw_text[:remaining]
        if text:
            spans.append(Span(selected_page.number, 0, len(text), text, selected_page.raw_text))
        remaining -= len(text)
        if remaining <= 1:
            break
    return Block(tuple(spans))


def split_lines(pages: tuple[Page, ...]) -> list[Span]:
    """Split successful pages into lines with original offsets."""
    lines = []
    for page in pages:
        offset = 0
        for line in page.raw_text.splitlines(keepends=True):
            lines.append(Span(page.number, offset, offset + len(line), line, page.raw_text))
            offset += len(line)
    return lines


def _strip_heading_prefix(text: str) -> str:
    """Remove numeric, symbolic and Roman numeral heading prefixes."""
    text = text.strip()
    while text and not text[0].isalpha():
        text = text[1:]
    text = re.sub(
        r'^(?=[IVX])(?:XX|X?(?:IX|IV|V?I{0,3}))(?:[.)]\s*|\s*[-–]\s+)',
        '',
        text,
        flags=re.IGNORECASE,
    )
    while text and not text[0].isalpha():
        text = text[1:]
    return text


def _is_heading_candidate(text: str) -> bool:
    """Check source casing and punctuation before normalization loses them."""
    text = text.strip()
    if text.endswith('.'):
        return False
    text = _strip_heading_prefix(text)
    return bool(text and text[0].isupper())


def normalize_heading(text: str) -> str:
    """Remove heading decorations without accepting prose by prefix."""
    text = _strip_heading_prefix(text)
    return ' '.join(text.rstrip(': -–\t\r\n').split()).casefold()


def scan_headings(
    lines: list[Span],
    experience_headings: tuple[str, ...],
    other_headings: tuple[str, ...],
) -> tuple[tuple[Block, ...], bool]:
    """Collect experience sections delimited by complete heading lines."""
    starts = {normalize_heading(heading) for heading in experience_headings}
    stops = {normalize_heading(heading) for heading in other_headings}
    found, active = False, False
    selected: list[Span] = []
    blocks: list[Block] = []
    for line in lines:
        if not _is_heading_candidate(line.text):
            if active:
                selected.append(line)
            continue
        heading = normalize_heading(line.text)
        parts = re.split(r'\s*(?:[&/,]|\band\b|\bvà\b)\s*', heading)
        known = len(heading) <= 60 and all(part in starts | stops for part in parts)
        if active and heading in ('projects', 'dự án'):
            known = False
        if known:
            if selected:
                blocks.extend(split_spans(selected))
                selected = []
            active = parts[0] in starts
            found |= active
        elif active:
            selected.append(line)
    blocks.extend(split_spans(selected))
    return tuple(blocks), found


def segment(
    document: Document,
    *,
    experience_headings: tuple[str, ...] = EXPERIENCE_HEADINGS,
    other_headings: tuple[str, ...] = OTHER_HEADINGS,
) -> tuple[Block, tuple[Block, ...], tuple[Issue, ...]]:
    """Build the personal header and evidence-preserving experience blocks."""
    pages = tuple(page for page in document.pages if page.status == 'ok')
    header = build_header(pages)
    lines = split_lines(pages)
    issues: list[Issue] = []
    if any(len(line.text) > 6000 for line in lines):
        issues.append(Issue('BLOCK_LINE_TOO_LONG'))
        lines = [line for line in lines if len(line.text) <= 6000]
    blocks, found = scan_headings(lines, experience_headings, other_headings)
    if not found:
        return (
            header,
            split_spans(lines),
            (
                Issue('EXPERIENCE_HEADING_NOT_FOUND'),
                *issues,
            ),
        )
    return header, blocks, tuple(issues)
