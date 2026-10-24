"""Geometry-based native order; ambiguous columns/glyphs remain visible and warned."""
from collections import Counter
import re
from statistics import median


def _rows(words):
    rows = []
    for word in sorted(words, key=lambda w: (w['top'], w['x0'])):
        height = max(1, word['bottom']-word['top'])
        if rows:
            row = rows[-1]
            top, bottom = median(w['top'] for w in row), median(w['bottom'] for w in row)
            overlap = min(bottom, word['bottom'])-max(top, word['top'])
            same = (abs(bottom-word['bottom']) <= max(2, min(height, bottom-top)*.3)
                    and overlap >= min(height, bottom-top)*.5)
        else:
            same = False
        if same:
            rows[-1].append(word)
        else:
            rows.append([word])
    return [sorted(row, key=lambda w: w['x0']) for row in rows]


def _gaps(row):
    height = median(max(1, w['bottom']-w['top']) for w in row)
    return [(a['x1'], b['x0']) for a, b in zip(row, row[1:])
            if b['x0']-a['x1'] >= max(18, height*2)]


def _column_cut(rows):
    # Repeated actual whitespace gutter anywhere on the page, never width/2.
    candidates = sorted({(left+right)/2 for row in rows for left, right in _gaps(row)})
    # Unaligned columns can have no shared baseline. Add gaps in the x projection.
    intervals = sorted((w['x0'], w['x1']) for row in rows for w in row)
    end = intervals[0][1]
    for start, stop in intervals[1:]:
        if start-end >= 24:
            candidates.append((start+end)/2)
        end = max(end, stop)
    best = None
    for cut in candidates:
        supporting = [row for row in rows if any(a < cut < b for a, b in _gaps(row))]
        separate_left = [row for row in rows if all(w['x1'] < cut for w in row)]
        separate_right = [row for row in rows if all(w['x0'] > cut for w in row)]
        if len(supporting) < 2 and min(len(separate_left), len(separate_right)) < 2:
            continue
        left = [w for row in supporting+separate_left for w in row if w['x1'] < cut]
        right = [w for row in supporting+separate_right for w in row if w['x0'] > cut]
        right_text = ''.join(w['text'] for w in right)
        # Repeated right-aligned dates/numbers are annotations, not another article.
        if not right_text or sum(c.isdigit() for c in right_text)/len(right_text) > .25:
            continue
        if min(sum(len(w['text']) for w in side) for side in (left, right)) < 12:
            continue
        gap = min(w['x0'] for w in right)-max(w['x1'] for w in left)
        if gap < 18:
            continue
        score = (len(supporting)+min(len(separate_left), len(separate_right)))*gap
        if best is None or score > best[0]:
            best = score, cut
    return best[1] if best else None


def _line(row):
    return ' '.join(w['text'] for w in row).strip()


def normalize_native(text):
    # Only discretionary soft hyphens prove a word was broken. Keep ambiguous hard hyphens.
    text = re.sub(r'(?<=[^\W\d_])\u00ad\n(?=[^\W\d_])', '', text)
    return '\n'.join(re.sub(r'[^\S\n]+', ' ', line).strip() for line in text.split('\n')).strip()


def native_order(page):
    # Dynamic glyph gaps recover word boundaries; object/text-flow order is not reading order.
    words = page.extract_words(use_text_flow=False, x_tolerance_ratio=.15, y_tolerance=2,
                               expand_ligatures=True)
    if not words:
        return '', False
    rows = _rows(words)
    cut = _column_cut(rows)
    ordered, blocks = [], []
    if cut is None:
        ordered = rows
        blocks = [rows]
    else:
        left, right = [], []
        for row in rows:
            if any(a < cut < b for a, b in _gaps(row)):
                left.append([w for w in row if w['x1'] < cut])
                right.append([w for w in row if w['x0'] > cut])
            elif all(w['x1'] < cut for w in row):
                left.append(row)
            elif all(w['x0'] > cut for w in row):
                right.append(row)
            else:
                # Spanning heading is a band boundary, never split across columns.
                blocks.extend([left, right, [row]])
                ordered.extend(left+right+[row]); left, right = [], []
        ordered.extend(left+right)
        blocks.extend([left, right])
    if Counter(id(w) for row in ordered for w in row) != Counter(id(w) for w in words):
        return normalize_native('\n'.join(_line(row) for row in rows)), True
    # Do not join a soft-hyphen word across a column/heading boundary.
    text = '\n'.join(normalize_native('\n'.join(_line(row) for row in block)) for block in blocks if block)
    uncertain = cut is not None or bool(re.search(r'\(cid:\d+\)|[\ufffd\ue000-\uf8ff]', text))
    uncertain |= any(not w.get('upright', True) for w in words)
    return text, uncertain


def usable(text):
    visible = [c for c in text if not c.isspace()]
    if not visible:
        return False
    alnum = sum(c.isalnum() for c in visible)
    broken = sum(c == '\ufffd' or '\ue000' <= c <= '\uf8ff' for c in visible)
    printable = sum(c.isprintable() for c in visible)
    return (alnum >= 40 and len(text.split()) >= 5 and broken/len(visible) <= .01
            and printable/len(visible) >= .98)
