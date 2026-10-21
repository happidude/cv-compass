"""Deterministic native block order with a conservative two-column split."""


def native_order(page):
    words = page.extract_words(use_text_flow=False)
    if not words:
        return '', False
    # Candidate central whitespace gutter; words crossing it are full-width
    # bands, whose boundaries divide the page before column ordering.
    width = page.width
    gutter = width / 2
    crossing = [w for w in words if w['x0'] < gutter-8 and w['x1'] > gutter+8]
    left = [w for w in words if w['x1'] <= gutter+8]
    right = [w for w in words if w['x0'] >= gutter-8]
    columns = len(left) >= 5 and len(right) >= 5 and len(crossing) <= max(3, len(words)//10)
    if not columns:
        return page.extract_text(layout=False) or '', False
    cuts = sorted({round(w['top']/4)*4 for w in crossing})
    def lines(items):
        rows = []
        for w in sorted(items, key=lambda x: (x['top'], x['x0'])):
            if not rows or abs(rows[-1][0]-w['top']) > 4:
                rows.append((w['top'], [w]))
            else:
                rows[-1][1].append(w)
        return [' '.join(w['text'] for w in sorted(row, key=lambda x: x['x0'])) for _, row in rows]
    output = []
    low = float('-inf')
    for cut in cuts + [float('inf')]:
        band = [w for w in words if low < w['top'] < cut-2]
        output.extend(lines([w for w in band if w['x1'] <= gutter+8]))
        output.extend(lines([w for w in band if w['x0'] >= gutter-8 and w['x1'] > gutter+8]))
        if cut != float('inf'):
            output.extend(lines([w for w in words if abs(w['top']-cut) <= 2]))
        low = cut+2
    ordered = '\n'.join(output)
    # Geometry heuristics may be uncertain, but must never lose/duplicate words.
    if sorted(ordered.split()) != sorted(w['text'] for w in words):
        return page.extract_text(layout=False) or '', True
    return ordered, True


def usable(text):
    visible = [c for c in text if not c.isspace()]
    if not visible:
        return False
    alnum = sum(c.isalnum() for c in visible)
    broken = sum(c == '\ufffd' or '\ue000' <= c <= '\uf8ff' for c in visible)
    printable = sum(c.isprintable() for c in visible)
    return (alnum >= 40 and len(text.split()) >= 5 and broken/len(visible) <= .01
            and printable/len(visible) >= .98)
