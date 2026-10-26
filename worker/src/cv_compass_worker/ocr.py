"""Offline Tesseract adapter. Outputs remain inside the private child directory."""

import csv
import hashlib
import io
from pathlib import Path
import re
import subprocess
from .sandbox import safe_env

MODEL_REVISION = '87416418657359cb625c412a48b6e1d6d41c29bd'
MODEL_HASHES = {
    'eng': '7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2',
    'vie': '79df64caf7bcfb2a27df5042ecb6121e196eada34da774956995747636d5bfa1',
    'osd': '9cf5d576fcc47564f11265841e5ca839001e7e6f38ff7f7aacf46d15a96b00ff',
}


def command(config, root, label, args, timeout):
    root = root / 'engine'
    root.mkdir(exist_ok=True)
    with (
        (root / (label + '.stdout.log')).open('wb') as stdout,
        (root / (label + '.stderr.log')).open('wb') as stderr,
    ):
        process = subprocess.Popen(
            [config.tesseract, *map(str, args)],
            stdin=subprocess.DEVNULL,
            stdout=stdout,
            stderr=stderr,
            shell=False,
            env=safe_env(),
            cwd=root,
        )
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
            raise ValueError('PAGE_TIMEOUT') from None
    if code:
        raise ValueError('OCR_FAILED')
    path = root / (label + '.stdout.log')
    if path.stat().st_size > config.max_engine_output_bytes:
        raise ValueError('OUTPUT_LIMIT')
    return path.read_text(encoding='utf-8', errors='replace')


def metadata(config, root):
    import importlib.metadata
    import platform
    import pypdfium2 as pdfium

    pins = {
        'pdfplumber': '0.11.10',
        'pdfminer.six': '20260107',
        'pypdfium2': '5.14.0',
        'Pillow': '13.0.0',
    }
    versions = {k: importlib.metadata.version(k) for k in pins}
    if versions != pins or platform.python_version() != '3.14.8':
        raise ValueError('ENGINE_VERSION_UNSUPPORTED')
    if not Path(config.tesseract).is_file():
        raise ValueError('DEPENDENCY_MISSING')
    text = command(config, root, 'version', ['--version'], min(config.preflight_timeout, 5))
    header = text.splitlines()[0] if text else ''
    if not re.fullmatch(r'tesseract\s+v?5\.5\.3(?:\.\d+)?', header.strip()):
        raise ValueError('ENGINE_VERSION_UNSUPPORTED')
    models = []
    for name, expected in MODEL_HASHES.items():
        path = Path(config.models) / (name + '.traineddata')
        if not path.is_file():
            raise ValueError('MODEL_MISSING')
        with path.open('rb') as stream:
            actual = hashlib.file_digest(stream, 'sha256').hexdigest()
        if actual != expected:
            raise ValueError('MODEL_MISSING')
        models.append(dict(name=name, revision=MODEL_REVISION, sha256=actual))
    return dict(
        python=platform.python_version(),
        pdfplumber=versions['pdfplumber'],
        pdfminer_six=versions['pdfminer.six'],
        pypdfium2=versions['pypdfium2'],
        pdfium=str(pdfium.PDFIUM_INFO),
        pillow=versions['Pillow'],
        tesseract=header.strip(),
        worker_version='0.2.0',
        models=models,
        leptonica=next((s.strip() for s in text.splitlines() if 'leptonica-' in s), None),
    )


def recognize(config, root, image):
    engine_root = root / 'engine'
    engine_root.mkdir(exist_ok=True)
    base = engine_root / 'ocr'
    command(
        config,
        root,
        'recognize',
        [
            image,
            base,
            '--tessdata-dir',
            config.models,
            '-l',
            'vie+eng',
            '--oem',
            '1',
            '--psm',
            '3',
            '-c',
            'tessedit_create_txt=1',
            '-c',
            'tessedit_create_tsv=1',
        ],
        config.tesseract_timeout,
    )
    txt, tsv = base.with_suffix('.txt'), base.with_suffix('.tsv')
    if not txt.is_file() or not tsv.is_file():
        raise ValueError('OCR_FAILED')
    if txt.stat().st_size + tsv.stat().st_size > config.max_engine_output_bytes:
        raise ValueError('OUTPUT_LIMIT')
    with txt.open(encoding='utf-8', newline='') as stream:
        raw = stream.read()
    rows = csv.DictReader(io.StringIO(tsv.read_text(encoding='utf-8-sig')), delimiter='\t')
    try:
        scores = [
            float(r['conf'])
            for r in rows
            if r['level'] == '5' and r['text'].strip() and float(r['conf']) >= 0
        ]
        if any(not 0 <= x <= 100 for x in scores):
            raise ValueError()
    except ValueError, KeyError, TypeError:
        raise ValueError('OCR_FAILED') from None
    confidence = (
        dict(
            engine='tesseract',
            metric='mean_word_confidence',
            value=sum(scores) / len(scores),
            sample_count=len(scores),
        )
        if scores
        else None
    )
    return raw, confidence


def rotation(config, root, image):
    try:
        text = command(
            config,
            root,
            'osd',
            [image, 'stdout', '--tessdata-dir', config.models, '-l', 'osd', '--psm', '0'],
            config.osd_timeout,
        )
    except ValueError as exc:
        if str(exc) == 'OUTPUT_LIMIT':
            raise
        return None
    angle = re.search(r'^Rotate: (0|90|180|270)$', text, re.M)
    confidence = re.search(r'^Orientation confidence: ([0-9.]+)$', text, re.M)
    return int(angle[1]) if angle and confidence and float(confidence[1]) >= 15 else None
