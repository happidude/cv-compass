"""Line-based segmentation retaining original page ranges."""

from dataclasses import dataclass

from .ocr_input import Document, Page
from .schema import Issue

EXPERIENCE_HEADINGS = (
    'experience', 'work experience', 'professional experience', 'employment history',
    'kinh nghiệm', 'kinh nghiệm làm việc', 'quá trình làm việc', 'quá trình công tác',
)
OTHER_HEADINGS = (
    'education', 'học vấn', 'skills', 'kỹ năng', 'projects', 'dự án', 'certifications',
    'chứng chỉ', 'summary', 'profile', 'references', 'interests', 'languages',
    'giới thiệu', 'mục tiêu nghề nghiệp', 'ngoại ngữ', 'sở thích', 'awards', 'giải thưởng',
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
            ('\n' if i and (self.spans[i - 1].page != span.page
                           or self.spans[i - 1].end != span.start) else '') + span.text
            for i, span in enumerate(self.spans)
        )


def split_spans(spans: list[Span], limit: int = 6000) -> tuple[Block, ...]:
    """Group complete lines into blocks within the character limit."""
    blocks: list[Block] = []
    current: list[Span] = []
    size = 0
    for span in spans:
        separator = bool(current and (
            current[-1].page != span.page or current[-1].end != span.start
        ))
        cost = len(span.text) + separator
        if current and size + cost > limit:
            blocks.append(Block(tuple(current)))
            current, size = [], 0
        size += len(span.text) + (separator if current else 0)
        current.append(span)
    if current:
        blocks.append(Block(tuple(current)))
    return tuple(blocks)


def build_header(pages: tuple[Page, ...]) -> Block:
    """Take at most 3000 characters from the first successful page."""
    if not pages:
        return Block(())
    page = pages[0]
    text = page.raw_text[:3000]
    return Block((Span(page.number, 0, len(text), text, page.raw_text),))


def split_lines(pages: tuple[Page, ...]) -> list[Span]:
    """Split successful pages into lines with original offsets."""
    lines = []
    for page in pages:
        offset = 0
        for line in page.raw_text.splitlines(keepends=True):
            lines.append(Span(page.number, offset, offset + len(line), line, page.raw_text))
            offset += len(line)
    return lines


def scan_headings(
    lines: list[Span], experience_headings: tuple[str, ...], other_headings: tuple[str, ...],
) -> tuple[tuple[Block, ...], bool]:
    """Collect experience sections delimited by complete heading lines."""
    starts = {heading.casefold() for heading in experience_headings}
    stops = {heading.casefold() for heading in other_headings}
    found, active = False, False
    selected: list[Span] = []
    blocks: list[Block] = []
    for line in lines:
        heading = line.text.strip().casefold()
        if heading in starts or heading in stops:
            if selected:
                blocks.extend(split_spans(selected))
                selected = []
            active = heading in starts
            found |= active
        elif active:
            selected.append(line)
    blocks.extend(split_spans(selected))
    return tuple(blocks), found


def segment(
    document: Document, *,
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
        return header, split_spans(lines), (
            Issue('EXPERIENCE_HEADING_NOT_FOUND'), *issues,
        )
    return header, blocks, tuple(issues)
