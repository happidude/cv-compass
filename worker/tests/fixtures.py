"""Ordinary generated fixtures only; never import real CVs."""

from pathlib import Path
from pypdf import PdfReader, PdfWriter


def native(path, columns=False, padding_bytes=0, content=None):
    text = (
        b'BT /F1 12 Tf 50 760 Td (Synthetic native page with enough ordinary words for extraction.) Tj '
        b'0 -24 Td (Second line preserves reading order and tests the offline pipeline.) Tj ET'
    )
    if columns:
        text = (
            b'BT /F1 12 Tf 50 760 Td (Full width heading for synthetic columns) Tj ET '
            b'BT /F1 12 Tf 50 700 Td (Left column first ordinary line) Tj 0 -20 Td (Left column second line) Tj ET '
            b'BT /F1 12 Tf 340 700 Td (Right column first ordinary line) Tj 0 -20 Td (Right column second line) Tj ET'
        )
    if content is not None:
        text = content
    objects = [
        b'<< /Type /Catalog /Pages 2 0 R >>',
        b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
        b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
        b'<< /Length ' + str(len(text)).encode() + b' >>\nstream\n' + text + b'\nendstream',
    ]
    data = bytearray(b'%PDF-1.7\n')
    if padding_bytes:
        data.extend(b'%' + b' ' * (padding_bytes - 2) + b'\n')
    offsets = []
    for i, obj in enumerate(objects, 1):
        offsets.append(len(data))
        data.extend(f'{i} 0 obj\n'.encode() + obj + b'\nendobj\n')
    start = len(data)
    data.extend(b'xref\n0 6\n0000000000 65535 f \n')
    for offset in offsets:
        data.extend(f'{offset:010d} 00000 n \n'.encode())
    data.extend(f'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n'.encode())
    path.write_bytes(data)


def scan(path, font_path, rotation=0):
    from PIL import Image, ImageDraw, ImageFont

    image = Image.new('RGB', (1240, 1754), 'white')
    try:
        draw = ImageDraw.Draw(image)
        font = ImageFont.truetype(str(font_path), 28)
        lines = [
            'Hồ sơ minh họa tiếng Việt',
            'Kỹ năng lập trình và kiểm thử',
            'Synthetic English offline page extraction',
            'contact@example.com 0900000000',
        ] * 4
        for i, line in enumerate(lines):
            draw.text((90, 80 + i * 85), line, font=font, fill='black')
        if rotation:
            rotated = image.rotate(rotation, expand=True)
            try:
                rotated.save(path, 'PDF', resolution=150)
            finally:
                rotated.close()
        else:
            image.save(path, 'PDF', resolution=150)
    finally:
        image.close()


def combine(path, inputs, blanks=0):
    writer = PdfWriter()
    for source in inputs:
        writer.append(source)
    for _ in range(blanks):
        writer.add_blank_page(width=595, height=842)
    writer.write(path)
