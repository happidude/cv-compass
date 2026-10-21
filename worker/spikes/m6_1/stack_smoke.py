"""Tiny M6.1 DIAGNOSTIC: trusted synthetic fixtures only, never a worker pipeline."""
from __future__ import annotations
import argparse
import csv
import hashlib
import importlib.metadata
import io
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import tempfile
import time

MODEL_REV = '87416418657359cb625c412a48b6e1d6d41c29bd'
MODEL_HASHES = {
    'eng': '7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2',
    'osd': '9cf5d576fcc47564f11265841e5ca839001e7e6f38ff7f7aacf46d15a96b00ff',
    'vie': '79df64caf7bcfb2a27df5042ecb6121e196eada34da774956995747636d5bfa1',
}


def digest(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def native_fixture(path: Path) -> None:
    content = b'BT /F1 16 Tf 60 760 Td (Synthetic native PDF for local compatibility checks.) Tj ET'
    objects = [b'<< /Type /Catalog /Pages 2 0 R >>',
               b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
               b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
               b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
               b'<< /Length ' + str(len(content)).encode() + b' >>\nstream\n' + content + b'\nendstream']
    data = bytearray(b'%PDF-1.7\n'); offsets = [0]
    for index, obj in enumerate(objects, 1):
        offsets.append(len(data)); data.extend(f'{index} 0 obj\n'.encode() + obj + b'\nendobj\n')
    start = len(data)
    data.extend(b'xref\n0 6\n0000000000 65535 f \n')
    for offset in offsets[1:]: data.extend(f'{offset:010d} 00000 n \n'.encode())
    data.extend(f'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n'.encode())
    path.write_bytes(data)


def tesseract_semver(header: str) -> tuple[int, int, int] | None:
    match = re.fullmatch(r'tesseract\s+v?(\d+)\.(\d+)\.(\d+)(?:\.\d+)?', header.strip())
    return tuple(map(int, match.groups())) if match else None


def safe_engine_env() -> dict[str, str]:
    allowed = {'SYSTEMROOT', 'WINDIR', 'TEMP', 'TMP', 'PATH', 'LANG', 'LC_ALL'}
    env = {key: value for key, value in os.environ.items() if key.upper() in allowed}
    env['OMP_THREAD_LIMIT'] = '1'
    return env

def run(args, report):
    # This executable deliberately does not implement a production isolation adapter.
    if args.profile == 'production':
        report.update(status='blocked', error='HARD_ISOLATION_REQUIRED', checks=[])
        return 3
    import pdfplumber
    import pypdfium2 as pdfium
    from PIL import Image, ImageDraw, ImageFont
    checks = report['checks']
    def check(name, condition):
        checks.append({'name': name, 'passed': bool(condition)})
        if not condition: raise AssertionError(name)
    env = safe_engine_env()
    def command(*parts, timeout=40):
        process = subprocess.run([args.tesseract, *map(str, parts)], capture_output=True,
                                 timeout=timeout, env=env, shell=False)
        if process.returncode:
            raise RuntimeError('ENGINE_NONZERO')
        return process
    version = command('--version')
    version_lines = (version.stdout + version.stderr).decode('utf-8', errors='replace').splitlines()
    report['versions'] = {name: importlib.metadata.version(name) for name in
                          ('pdfplumber', 'pdfminer.six', 'pypdfium2', 'Pillow', 'jsonschema')}
    report['versions'].update(python=platform.python_version(), tesseract=version_lines[0],
                              leptonica=next((x.strip() for x in version_lines if 'leptonica-' in x), None),
                              pdfium=str(pdfium.PDFIUM_INFO))
    check('python_3_14_8', platform.python_version() == '3.14.8')
    check('tesseract_5_5_3', tesseract_semver(version_lines[0]) == (5, 5, 3))
    report['models'] = []
    for name, expected in MODEL_HASHES.items():
        actual = digest(Path(args.models) / f'{name}.traineddata')
        report['models'].append({'name': name, 'revision': MODEL_REV, 'sha256': actual})
        check('model_hash_' + name, actual == expected)
    report['font_sha256'] = digest(Path(args.font))
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix='cv-m61-ordinary-') as tmp:
        root = Path(tmp); native = root/'native.pdf'; scan = root/'scan.pdf'
        native_fixture(native)
        with pdfplumber.open(native) as pdf:
            check('native_one_page', len(pdf.pages) == 1)
            check('native_text', 'Synthetic native PDF' in (pdf.pages[0].extract_text() or ''))
        image = Image.new('RGB', (2480, 3508), 'white')
        draw = ImageDraw.Draw(image); font = ImageFont.truetype(args.font, 56)
        lines = ['Hồ sơ minh họa tiếng Việt', 'Kỹ năng lập trình và kiểm thử',
                 'Synthetic English engine compatibility check', 'contact@example.com 0900000000'] * 4
        for i, line in enumerate(lines): draw.text((180, 160+i*170), line, font=font, fill='black')
        image.save(scan, 'PDF', resolution=300, creationDate='D:20260101000000Z', modDate='D:20260101000000Z')
        image.close()
        hashes = {p.name: digest(p) for p in (native, scan)}
        with pdfplumber.open(scan) as pdf:
            check('scan_has_no_native_text', not (pdf.pages[0].extract_text() or '').strip())
        render_start = time.monotonic()
        rendered = root/'page.png'
        with pdfium.PdfDocument(scan) as doc:
            page = doc[0]
            try:
                bitmap = page.render(scale=300/72)
                try:
                    check('bounded_fixture_pixels', bitmap.width*bitmap.height <= 16000000)
                    pil = bitmap.to_pil()
                    try: pil.save(rendered, dpi=(300, 300))
                    finally: pil.close()
                finally: bitmap.close()
            finally: page.close()
        report['timings_ms']['render'] = round((time.monotonic()-render_start)*1000, 3)
        ocr_start = time.monotonic()
        base = root/'ocr'
        recognition = command(rendered, base, '--tessdata-dir', args.models, '-l', 'vie+eng',
                              '--oem', '1', '--psm', '3', '-c', 'tessedit_create_txt=1', '-c', 'tessedit_create_tsv=1')
        report['timings_ms']['ocr'] = round((time.monotonic()-ocr_start)*1000, 3)
        report['diagnostics']['recognition_stderr_lines'] = len(recognition.stderr.decode(errors='replace').splitlines())
        text = base.with_suffix('.txt').read_text(encoding='utf-8')
        check('ocr_english', 'Synthetic English' in text)
        check('ocr_vietnamese_diacritics', 'tiếng Việt' in text and 'kiểm thử' in text)
        check('ocr_synthetic_contact', 'contact@example.com' in text and '0900000000' in text)
        tsv_bytes = base.with_suffix('.tsv').read_bytes()
        report['diagnostics']['tsv_utf8_bom'] = tsv_bytes.startswith(b'\xef\xbb\xbf')
        rows = list(csv.DictReader(io.StringIO(tsv_bytes.decode('utf-8-sig')), delimiter='\t'))
        scores = [float(x['conf']) for x in rows if x['level']=='5' and x['text'].strip() and float(x['conf'])>=0]
        check('tsv_engine_scores', bool(scores) and all(0 <= x <= 100 for x in scores))
        report['confidence_sample_count'] = len(scores)  # no text or invented quality score
        osd = command(rendered, 'stdout', '--tessdata-dir', args.models, '-l', 'osd', '--psm', '0')
        check('osd_invocation', b'Orientation in degrees:' in osd.stdout)
        report['diagnostics']['osd_stderr_lines'] = len(osd.stderr.decode(errors='replace').splitlines())
        check('source_pdfs_unchanged', hashes == {p.name:digest(p) for p in (native, scan)})
        report['fixture_hashes'] = hashes
    check('fixture_cleanup', not root.exists())
    report['timings_ms']['fixture_run'] = round((time.monotonic()-started)*1000, 3)
    report['status'] = 'passed'
    return 0


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tesseract', required=True); parser.add_argument('--models', required=True)
    parser.add_argument('--font', required=True); parser.add_argument('--report', required=True)
    parser.add_argument('--profile', choices=('diagnostic', 'production'), default='diagnostic')
    args=parser.parse_args()
    report={'kind':'verification_report_not_ocr_v1', 'os':platform.platform(), 'profile':args.profile,
            'isolation':{'adapter':'none', 'status':'not_enforced',
                         'requested_limits':{'memory_bytes':1073741824,'cpus':2,'pids':64},
                         'applied_limits':{}},
            'hardware':{'architecture':platform.machine(),'logical_cpus':os.cpu_count()},
            'fixture_scope':'ordinary_synthetic_only_no_resource_bombs_no_real_CVs',
            'resource_safety_verified':False,'checks':[], 'timings_ms':{}, 'diagnostics':{}}
    if sys.platform == 'linux':
        report['distribution'] = platform.freedesktop_os_release().get('PRETTY_NAME')
        mem=Path('/sys/fs/cgroup/memory.max'); cpu=Path('/sys/fs/cgroup/cpu.max'); pids=Path('/sys/fs/cgroup/pids.max')
        if mem.is_file() and cpu.is_file() and pids.is_file():
            applied={'memory_max':mem.read_text().strip(),'cpu_max':cpu.read_text().strip(),'pids_max':pids.read_text().strip()}
            report['isolation']['applied_limits']=applied
            if applied['memory_max']=='1073741824' and applied['cpu_max']=='200000 100000' and applied['pids_max']=='64':
                report['isolation'].update(adapter='docker_cgroup_v2_observed',status='enforced')
    try: code=run(args,report)
    except Exception as exc:
        report.update(status='failed', error_type=type(exc).__name__)
        code=1
    Path(args.report).write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'status':report['status'],'checks':len(report['checks']),
                      'failures':sum(not x['passed'] for x in report['checks']),
                      'errors':int(report['status']=='failed'),
                      'isolation':report['isolation']['status'],'resource_safety_verified':False}))
    return code

if __name__=='__main__':
    sys.exit(main())
