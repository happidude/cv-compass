"""A fresh child handles native libraries. Supervisor never parses a PDF."""
import json
import math
from pathlib import Path
import sys
import time
from .config import Config
from .contracts import SCHEMA, page
from .layout import usable


def _process_page(path, number, config, root, result):
    import pdfplumber
    import pypdfium2 as pdfium
    from .ocr import recognize, rotation
    started = time.monotonic()
    timings = result['timings_ms']
    tick = time.monotonic()
    raw = ''
    image_fraction = 1
    try:
        with pdfplumber.open(path) as pdf:
            p = pdf.pages[number-1]
            raw = p.extract_text(layout=False) or ''
            area = max(1, p.width*p.height)
            image_area = sum(max(0, i['x1']-i['x0'])*max(0, i['bottom']-i['top']) for i in p.images)
            char_area = sum(max(0, c['x1']-c['x0'])*max(0, c['bottom']-c['top']) for c in p.chars)
            image_fraction = max(0, image_area-char_area)/area
    except Exception:
        result['warnings'].append('NATIVE_EXTRACTION_FAILED')
    timings['native'] = (time.monotonic()-tick)*1000
    if usable(raw) and image_fraction < .25:
        result.update(status='ok', method='native', raw_text=raw)
    else:
        result['warnings'].append('NATIVE_TEXT_UNUSABLE')
        if raw.strip() and not usable(raw):
            result['warnings'].append('SPARSE_TEXT')
        if image_fraction >= .25:
            result['warnings'].append('NATIVE_TEXT_WITH_IMAGES')
        tick = time.monotonic()
        try:
            with pdfium.PdfDocument(path) as doc:
                p = doc[number-1]
                try:
                    width, height = p.get_size()
                    w, h = math.ceil(width*config.render_dpi/72), math.ceil(height*config.render_dpi/72)
                    if min(w, h) < 1 or max(w, h) > config.max_image_dimension or w*h > config.max_image_pixels:
                        raise ValueError('IMAGE_LIMIT')
                    bitmap = p.render(scale=config.render_dpi/72)
                    try:
                        view = bitmap.to_pil()
                        try:
                            image = view.copy()
                        finally:
                            view.close()
                    finally:
                        bitmap.close()
                finally:
                    p.close()
        except ValueError:
            raise
        except Exception:
            raise ValueError('RENDER_FAILED') from None
        try:
            timings['render'] = (time.monotonic()-tick)*1000
            tick = time.monotonic()
            gray = image.convert('L')
            try:
                blank = sum(gray.histogram()[:255]) == 0
            finally:
                gray.close()
            if blank:
                result.update(status='blank', method='none', raw_text='', error=None)
                result['warnings'].append('BLANK_PAGE')
            else:
                target = root / 'page.png'
                image.save(target, dpi=(config.render_dpi, config.render_dpi))
                angle = rotation(config, root, target)
                if angle is None:
                    result['warnings'].append('ROTATION_UNCERTAIN')
                elif angle:
                    rotated = image.rotate(-angle, expand=True)
                    try:
                        rotated.save(target, dpi=(config.render_dpi, config.render_dpi))
                    finally:
                        rotated.close()
                timings['preprocess'] = (time.monotonic()-tick)*1000
                tick = time.monotonic()
                raw, confidence = recognize(config, root, target)
                timings['ocr'] = (time.monotonic()-tick)*1000
                if not usable(raw):
                    raise ValueError('NO_USABLE_TEXT')
                result.update(status='ok', method='ocr', raw_text=raw, confidence=confidence)
                # PSM3 proposes layout; this is not a guarantee of column order.
                result['warnings'].append('READING_ORDER_UNCERTAIN')
                if confidence and confidence['value'] < 60:
                    result['warnings'].append('LOW_OCR_CONFIDENCE')
        finally:
            image.close()
    if len(result['raw_text']) > config.max_text_code_points_per_field:
        result = page(number, status='failed', code='OUTPUT_LIMIT', stage='output')
        result['timings_ms'] = timings
    result['warnings'] = list(dict.fromkeys(result['warnings']))
    timings['total'] = (time.monotonic()-started)*1000
    return result



def process_page(path, number, config, root):
    started = time.monotonic()
    result = page(number)
    try:
        result = _process_page(path, number, config, root, result)
    except Exception as exc:
        known = SCHEMA['$defs']['error']['properties']['code']['enum']
        code = ('DEPENDENCY_MISSING' if isinstance(exc, ImportError) else
                str(exc) if isinstance(exc, ValueError) and str(exc) in known else 'INTERNAL_ERROR')
        stage = ('render' if code in ('RENDER_FAILED', 'IMAGE_LIMIT') else
                 'ocr' if code in ('OCR_FAILED', 'NO_USABLE_TEXT', 'PAGE_TIMEOUT') else
                 'output' if code == 'OUTPUT_LIMIT' else 'supervisor')
        timings = result['timings_ms']
        total = (time.monotonic()-started)*1000
        if stage in timings and timings[stage] is None:
            timings[stage] = max(0, total-sum(v for k, v in timings.items() if k != 'total' and v is not None))
        result.update(status='failed', method='none', raw_text='', confidence=None,
                      error={'code': code, 'stage': stage})
    result['warnings'] = list(dict.fromkeys(result['warnings']))
    result['timings_ms']['total'] = (time.monotonic()-started)*1000
    return result


def main():
    from .observability import configure_child_diagnostics
    configure_child_diagnostics()
    spec, output = map(Path, sys.argv[1:])
    task = json.loads(spec.read_text(encoding='utf-8'))
    try:
        config = Config.parse(task['config'])
        source = Path(task['source'])
        if task['action'] == 'preflight':
            from .pdf import preflight
            from .ocr import metadata
            value = {'engines': metadata(config, spec.parent), 'page_count': preflight(source, config.max_pages)}
        elif task['action'] == 'page':
            value = process_page(source, task['number'], config, spec.parent)
        else:
            raise ValueError('INTERNAL_ERROR')
        response = {'value': value}
    except ImportError:
        response = {'error': 'DEPENDENCY_MISSING'}
    except Exception as exc:
        known = SCHEMA['$defs']['error']['properties']['code']['enum']
        response = {'error': str(exc) if isinstance(exc, ValueError) and str(exc) in known else 'INTERNAL_ERROR'}
    output.write_text(json.dumps(response, ensure_ascii=False, allow_nan=False), encoding='utf-8')


if __name__ == '__main__':
    main()
