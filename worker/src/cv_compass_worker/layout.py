"""Native text usability only; extraction order remains the PDF library output."""


def usable(text):
    visible = [c for c in text if not c.isspace()]
    if not visible:
        return False
    alnum = sum(c.isalnum() for c in visible)
    broken = sum(c == '\ufffd' or '\ue000' <= c <= '\uf8ff' for c in visible)
    printable = sum(c.isprintable() for c in visible)
    return (alnum >= 40 and len(text.split()) >= 5 and broken/len(visible) <= .01
            and printable/len(visible) >= .98)
