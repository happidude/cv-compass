"""Build every image from a clean Linux checkout; stdlib + provisioned pip only."""

import argparse
import hashlib
import json
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import urllib.request
from prepare_worker_image import prepare

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def fetch(url, expected, path):
    with urllib.request.urlopen(url, timeout=60) as response, path.open('wb') as target:
        shutil.copyfileobj(response, target)
    if digest(path) != expected:
        raise ValueError('Provisioning checksum mismatch: ' + path.name)


def run(args, log):
    with log.open('w') as stream:
        subprocess.run(args, stdout=stream, stderr=subprocess.STDOUT, check=True)


def source_manifest():
    files = []
    paths = [
        *(
            ROOT / name
            for name in (
                'run_worker.py',
                'requirements.lock',
                'requirements-test.lock',
                'config.example.toml',
                '.gitattributes',
            )
        )
    ]
    for folder in ('src', 'design', 'tests', 'spikes'):
        paths.extend((ROOT / folder).rglob('*'))
    for path in sorted(paths):
        if path.is_file() and '__pycache__' not in path.parts:
            if (
                path.suffix in ('.py', '.json', '.lock', '.toml', '.sh', '.ps1', '.cpp')
                or path.name.startswith('Dockerfile')
                or path.name in ('.gitattributes', '.dockerignore')
            ):
                files.append(
                    {'path': 'worker/' + path.relative_to(ROOT).as_posix(), 'sha256': digest(path)}
                )
    workflow = ROOT.parent / '.github/workflows/worker-ci.yml'
    if workflow.is_file():
        files.append({'path': '.github/workflows/worker-ci.yml', 'sha256': digest(workflow)})
    return files


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=ROOT / 'build/baseline')
    args = parser.parse_args()
    out = args.output.resolve()
    if platform.system() != 'Linux' or platform.python_version() != '3.14.8':
        raise SystemExit('Build requires Linux CPython 3.14.8 (use Ubuntu WSL on Windows)')
    if not out.is_relative_to(ROOT / 'build') or out == ROOT / 'build':
        raise SystemExit('Output must be a dedicated worker/build subdirectory')
    out.mkdir(parents=True, exist_ok=True)
    manifest = source_manifest()
    (out / 'source-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    version = subprocess.run(
        ['git', '-C', str(ROOT.parent), 'rev-parse', 'HEAD'], capture_output=True, text=True
    )
    (out / 'source-version.json').write_text(
        json.dumps(
            {
                'commit': version.stdout.strip() if version.returncode == 0 else None,
                'manifest_sha256': digest(out / 'source-manifest.json'),
                'production': 'BLOCKED_GCC_WARNING_UNRESOLVED',
                'accuracy': 'NOT_EVALUATED',
            },
            indent=2,
        )
        + '\n'
    )
    spec = json.loads((HERE / 'provisioning.json').read_text())
    runtime = out / 'runtime-context'
    if runtime.exists():
        shutil.rmtree(runtime)
    artifacts = runtime / 'artifacts'
    artifacts.mkdir(parents=True)
    checks = []
    for name in ('python', 'tesseract'):
        item = spec[name]
        fetch(item['url'], item['sha256'], artifacts / item['file'])
        checks.append(item['sha256'] + '  ' + item['file'])
    for name, sha in spec['models'].items():
        filename = name + '.traineddata'
        fetch(
            'https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/'
            + spec['model_revision']
            + '/'
            + filename,
            sha,
            artifacts / filename,
        )
        checks.append(sha + '  ' + filename)
    (artifacts / 'SHA256SUMS').write_text('\n'.join(checks) + '\n')
    shutil.copyfile(HERE / 'Dockerfile.runtime', runtime / 'Dockerfile')
    run(
        ['docker', 'build', '--progress=plain', '-t', 'cvcompass-m6-runtime:build', str(runtime)],
        out / 'runtime-build.log',
    )
    runtime_id = subprocess.check_output(
        ['docker', 'image', 'inspect', 'cvcompass-m6-runtime:build', '--format', '{{.Id}}'],
        text=True,
    ).strip()
    runtime_ref = 'cvcompass-m6-runtime:' + runtime_id.split(':')[1]
    subprocess.run(['docker', 'tag', runtime_id, runtime_ref], check=True)
    deps = out / 'dependency-context'
    if deps.exists():
        shutil.rmtree(deps)
    (deps / 'wheels').mkdir(parents=True)
    run(
        [sys.executable, str(HERE / 'download_locked.py'), str(ROOT), str(deps / 'wheels')],
        out / 'wheel-download.log',
    )
    for name in ('requirements.lock', 'requirements-test.lock'):
        shutil.copyfile(ROOT / name, deps / name)
    shutil.copyfile(HERE / 'Dockerfile.dependencies', deps / 'Dockerfile')
    run(
        [
            'docker',
            'build',
            '--network',
            'none',
            '--progress=plain',
            '--build-arg',
            'RUNTIME_IMAGE=' + runtime_ref,
            '-t',
            'cvcompass-m6-deps:build',
            str(deps),
        ],
        out / 'dependency-build.log',
    )
    deps_id = subprocess.check_output(
        ['docker', 'image', 'inspect', 'cvcompass-m6-deps:build', '--format', '{{.Id}}'], text=True
    ).strip()
    deps_ref = 'cvcompass-m6-deps:' + deps_id.split(':')[1]
    subprocess.run(['docker', 'tag', deps_id, deps_ref], check=True)
    context = prepare(ROOT, out / 'worker-context')
    run(
        [
            'docker',
            'build',
            '--network',
            'none',
            '--progress=plain',
            '--build-arg',
            'DEPENDENCY_IMAGE=' + deps_ref,
            '-t',
            'cvcompass-m6-worker:build',
            str(context),
        ],
        out / 'worker-build.log',
    )
    image = subprocess.check_output(
        ['docker', 'image', 'inspect', 'cvcompass-m6-worker:build', '--format', '{{.Id}}'],
        text=True,
    ).strip()
    (out / 'images.json').write_text(
        json.dumps({'runtime': runtime_id, 'dependencies': deps_id, 'worker': image}, indent=2)
        + '\n'
    )
    if source_manifest() != manifest:
        raise SystemExit('Source changed during build; do not test stale image')
    print(image)


if __name__ == '__main__':
    main()
