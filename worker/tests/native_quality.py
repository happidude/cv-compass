"""Generated public synthetic PDF corpus and explicit ground truth; no runtime files."""
from collections import Counter
from difflib import SequenceMatcher
import json
from pathlib import Path
import re
import tempfile
from pypdf import PdfWriter
from pypdf.generic import DictionaryObject, NameObject, DecodedStreamObject, ArrayObject, NumberObject


def corpus():
    # (text, x, y, font size). Stream order is deliberately different from geometric order.
    return {
        'single_column': ([('First ordinary sentence for a synthetic profile.', 40, 760, 12),
                           ('Second independent sentence with reliable spacing.', 40, 735, 12)],
                          ['First ordinary sentence for a synthetic profile.', 'Second independent sentence with reliable spacing.']),
        'tight_word_gaps': ([('As a Software Engineer with 1+ years of practice.', 40, 760, 12),
                            ('Technical work uses Java Python and PostgreSQL.', 40, 735, 12)],
                           ['As a Software Engineer with 1+ years of practice.', 'Technical work uses Java Python and PostgreSQL.']),
        'two_columns': ([('Left first sentence', 40, 730, 12), ('Right first sentence', 355, 730, 12),
                         ('Left second sentence', 40, 705, 12), ('Right second sentence', 355, 705, 12)],
                        ['Left first sentence', 'Left second sentence', 'Right first sentence', 'Right second sentence']),
        'unequal_columns_heading': ([('Independent full width heading across columns', 40, 790, 16),
                                    ('Side skills', 40, 740, 12), ('Main first detailed sentence', 230, 740, 12),
                                    ('Side tools', 40, 715, 12), ('Main second detailed sentence', 230, 715, 12)],
                                   ['Independent full width heading across columns', 'Side skills', 'Side tools',
                                    'Main first detailed sentence', 'Main second detailed sentence']),
        'heading_date': ([('Research Information Service Center', 40, 760, 14), ('2023 - 2024', 460, 760, 10),
                          ('Implemented reliable systems for offline analysis.', 40, 735, 12),
                          ('Documented results and maintained local services.', 40, 710, 12)],
                         ['Research Information Service Center 2023 - 2024',
                          'Implemented reliable systems for offline analysis.', 'Documented results and maintained local services.']),
        'bullets_hyphens': ([('• Built cross-platform systems using scikit-learn.', 40, 760, 12),
                             ('• Maintained C++ C# .NET and UTF-8 support.', 40, 735, 12),
                             ('Conservative soft hyphen exam\u00ad', 40, 710, 12), ('ple remains readable.', 40, 690, 12),
                             ('Preserve hard hyphen cross-', 40, 665, 12), ('platform and ISO-9001.', 40, 645, 12)],
                            ['• Built cross-platform systems using scikit-learn.', '• Maintained C++ C# .NET and UTF-8 support.',
                             'Conservative soft hyphen example remains readable.', 'Preserve hard hyphen cross-', 'platform and ISO-9001.']),
        'vietnamese_english': ([('Hồ sơ minh họa kỹ năng lập trình', 40, 760, 12),
                               ('English and tiếng Việt preserve diacritics.', 40, 735, 12)],
                              ['Hồ sơ minh họa kỹ năng lập trình', 'English and tiếng Việt preserve diacritics.']),
        'unknown_font_icon': ([('Unknown glyph \uffff remains visible for review.', 40, 760, 12),
                              ('Preserve original source without guessing icons.', 40, 735, 12)],
                             ['Unknown glyph (cid:65535) remains visible for review.', 'Preserve original source without guessing icons.']),
    }


def generate(path, name, regions):
    writer = PdfWriter()
    page = writer.add_blank_page(width=660, height=842)
    chars = sorted(set(''.join(r[0] for r in regions)) - {'\uffff'})
    cmap = ('/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CIDSystemInfo '
            '<< /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def /CMapName /Synthetic def '
            '/CMapType 2 def 1 begincodespacerange <0000> <FFFF> endcodespacerange '
            + f'{len(chars)} beginbfchar ' + ' '.join(f'<{ord(c):04X}> <{ord(c):04X}>' for c in chars)
            + ' endbfchar endcmap CMapName currentdict /CMap defineresource pop end end').encode()
    mapping = DecodedStreamObject(); mapping.set_data(cmap)
    descendant = DictionaryObject({NameObject('/Type'): NameObject('/Font'), NameObject('/Subtype'): NameObject('/CIDFontType2'),
        NameObject('/BaseFont'): NameObject('/Synthetic'), NameObject('/DW'): NumberObject(500),
        NameObject('/CIDSystemInfo'): DictionaryObject(),
        NameObject('/FontDescriptor'): DictionaryObject({NameObject('/Type'): NameObject('/FontDescriptor'),
            NameObject('/FontName'): NameObject('/Synthetic'), NameObject('/Flags'): NumberObject(4),
            NameObject('/FontBBox'): ArrayObject([NumberObject(n) for n in (0, -200, 1000, 800)]),
            NameObject('/Ascent'): NumberObject(800), NameObject('/Descent'): NumberObject(-200),
            NameObject('/CapHeight'): NumberObject(700), NameObject('/ItalicAngle'): NumberObject(0),
            NameObject('/StemV'): NumberObject(80)})})
    font = DictionaryObject({NameObject('/Type'): NameObject('/Font'), NameObject('/Subtype'): NameObject('/Type0'),
        NameObject('/BaseFont'): NameObject('/Synthetic'), NameObject('/Encoding'): NameObject('/Identity-H'),
        NameObject('/DescendantFonts'): ArrayObject([writer._add_object(descendant)]), NameObject('/ToUnicode'): writer._add_object(mapping)})
    page[NameObject('/Resources')] = DictionaryObject({NameObject('/Font'): DictionaryObject({NameObject('/F1'): writer._add_object(font)})})
    operations = []
    # Reverse PDF object order to distinguish layout order from content-stream order.
    for text, x, y, size in reversed(regions):
        if name == 'tight_word_gaps':
            for word in text.split():
                operations.append(f'BT /F1 {size} Tf 1 0 0 1 {x} {y} Tm <{word.encode("utf-16-be").hex()}> Tj ET')
                x += len(word)*size/2 + size*.17
        else:
            operations.append(f'BT /F1 {size} Tf 1 0 0 1 {x} {y} Tm <{text.encode("utf-16-be").hex()}> Tj ET')
    stream = DecodedStreamObject(); stream.set_data('\n'.join(operations).encode())
    page[NameObject('/Contents')] = writer._add_object(stream)
    writer.write(path)


def metrics(actual, expected, units):
    compact = lambda s: re.sub(r'\s', '', s)
    a, b = compact(actual), compact(expected)
    # Content ignores order/whitespace, while order compares the non-whitespace sequence.
    ca, cb = Counter(a), Counter(b)
    content = 2*sum((ca & cb).values())/max(1, len(a)+len(b))
    order = SequenceMatcher(None, a, b, autojunk=False).ratio()
    def boundaries(text):
        indices, offset = set(), 0
        for token in re.findall(r'\S+|\s+', text):
            if token.isspace(): indices.add(offset)
            else: offset += len(token)
        return indices - {0, offset}
    # Align compact character indices before scoring positive whitespace boundaries.
    pairs = SequenceMatcher(None, a, b, autojunk=False).get_matching_blocks()
    mapping = {i+k: j+k for i,j,n in pairs for k in range(n)}
    ab, bb = boundaries(actual), boundaries(expected)
    mapped = {mapping[index] for index in ab if index in mapping}
    spacing = 2*len(mapped & bb)/max(1, len(ab)+len(bb))
    return dict(content=round(content, 6), whitespace=round(spacing, 6), readingOrder=round(order, 6),
                exact=actual == expected)


def evaluate(extractor):
    import pdfplumber
    results = {}
    with tempfile.TemporaryDirectory(prefix='native-quality-') as directory:
        for name, (regions, truth) in corpus().items():
            path = Path(directory)/(name+'.pdf')
            generate(path, name, regions)
            with pdfplumber.open(path) as pdf:
                text, uncertain = extractor(pdf.pages[0])
            results[name] = metrics(text, '\n'.join(truth), truth) | {'uncertain': uncertain}
    return results


if __name__ == '__main__':
    import argparse

    parser = argparse.ArgumentParser(); parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evaluate(lambda page: (page.extract_text(layout=False) or '', False)), indent=2)+'\n')
