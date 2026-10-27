"""Ubuntu host full gate, fresh hash-locked venv, new source-built Docker child."""

import argparse
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import tempfile
import uuid
from build_baseline import ROOT, HERE, digest, source_manifest, run

sys.path.insert(0, str(ROOT / 'src'))
from cv_compass_worker import docker_sandbox as sandbox


def validate_state(state):
    if (
        set(state) != {'version', 'run_id', 'endpoint'}
        or state['version'] != 1
        or not isinstance(state['run_id'], str)
        or not re.fullmatch('[0-9a-f]{32}', state['run_id'])
        or not isinstance(state['endpoint'], str)
    ):
        raise ValueError('Missing/invalid run ownership state; legacy snapshots are not ownership')


def cleanup(state):
    """Only run-specific labels plus inspect confirmation authorize removal.

    Discovery/inspect errors fail closed and are reported, never interpreted as
    an empty inventory. No pre/post snapshot, common scope label or name prefix
    can establish ownership. Container IDs avoid deleting name replacements.
    """
    validate_state(state)
    failed = []
    with sandbox.docker_session():
        if sandbox.check_local_daemon()[0] != state['endpoint']:
            raise ValueError('Ownership state belongs to a different Docker endpoint')
        for kind in ('container', 'volume'):
            try:
                args = (
                    ['ps', '-aq', '--no-trunc'] if kind == 'container' else ['volume', 'ls', '-q']
                )
                identities = (
                    sandbox.command(
                        [*args, '--filter', 'label=' + sandbox.RUN_LABEL + '=' + state['run_id']]
                    )
                    .decode()
                    .split()
                )
            except Exception:
                failed.append(kind + ':inventory-unverified')
                continue
            for identity in identities:
                try:
                    info = json.loads(
                        sandbox.command(
                            [*(['volume'] if kind == 'volume' else []), 'inspect', identity]
                        )
                    )[0]
                    labels = (
                        info.get('Config', {}).get('Labels')
                        if kind == 'container'
                        else info.get('Labels')
                    ) or {}
                    # Inspect is authoritative, not the discovery filter.
                    if labels.get(sandbox.RUN_LABEL) != state['run_id']:
                        continue
                    scope = labels.get('cv-compass.scope')
                    if scope not in (
                        ('ocr-assets', 'ocr-writer', 'ocr-child')
                        if kind == 'container'
                        else ('ocr-input',)
                    ):
                        failed.append(identity + ':unexpected-owned-scope')
                        continue
                    if kind == 'container':
                        if info['Id'] != identity:
                            raise ValueError('Container identity changed')
                        sandbox.remove_container(identity)
                    else:
                        if info['Name'] != identity:
                            raise ValueError('Volume identity changed')
                        sandbox.command(['volume', 'rm', identity])
                except Exception:
                    failed.append(identity)
    return failed


def cleanup_report(state, report):
    try:
        leftovers = cleanup(state)
    except Exception as exc:
        leftovers = ['cleanup-unverified:' + type(exc).__name__]
    try:
        report.mkdir(parents=True, exist_ok=True)
        (report / 'cleanup.json').write_text(
            json.dumps({'run_id': state.get('run_id'), 'leftovers': leftovers}, indent=2) + '\n'
        )
    except OSError:
        leftovers.append('cleanup-report:write-failed')
        print(
            'Cleanup evidence write failed; run_id=' + str(state.get('run_id')),
            'leftovers=' + repr(leftovers),
            file=sys.stderr,
        )
    return leftovers


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--build', type=Path, default=ROOT / 'build/baseline')
    parser.add_argument('--cleanup-only', action='store_true')
    args = parser.parse_args()
    out = args.build.resolve()
    if not out.is_relative_to(ROOT / 'build') or out == ROOT / 'build':
        raise SystemExit('Build directory must be under worker/build')
    statefile = out / 'resources.owner.json'
    report = out / 'verification'
    if args.cleanup_only:
        if statefile.is_file():
            raise SystemExit(bool(cleanup_report(json.loads(statefile.read_text()), report)))
        if report.exists() or (out / 'resources.before.json').exists():
            raise SystemExit(
                'Missing run ownership; legacy snapshots are not authority. Inspect manually'
            )
        return
    expected = json.loads((out / 'source-manifest.json').read_text())
    if source_manifest() != expected:
        raise SystemExit('Source differs from image build manifest; rebuild first')
    if report.exists():
        raise SystemExit('Fresh verification directory required')
    with sandbox.docker_session():
        return verify(out, statefile, report, expected)


def verify(out, statefile, report, expected):
    state = {'version': 1, 'run_id': uuid.uuid4().hex, 'endpoint': sandbox.check_local_daemon()[0]}
    # Exclusive durable ownership state precedes *all* resource creation. Never
    # replace a previous run's cleanup authority, including failed/cancelled runs.
    with statefile.open('x') as stream:
        stream.write(json.dumps(state, indent=2) + '\n')
        stream.flush()
        os.fsync(stream.fileno())
    report.mkdir()
    container = 'cvcompass-assets-' + state['run_id']
    image = json.loads((out / 'images.json').read_text())['worker']
    exit_code = 1
    try:
        # Copy trusted provisioning artifacts only. The host runs the supervisor;
        # PDF children never receive the daemon socket or host paths.
        sandbox.command(
            [
                'create',
                '--name',
                container,
                '--label',
                'cv-compass.scope=ocr-assets',
                '--label',
                sandbox.RUN_LABEL + '=' + state['run_id'],
                image,
            ]
        )
        for path, name in (
            ('/usr/local/bin/tesseract', 'tesseract'),
            ('/models', 'models'),
            ('/fixture-font.ttf', 'font.ttf'),
            ('/provenance', 'provenance'),
        ):
            sandbox.command(['cp', container + ':' + path, str(report / name)])
        if cleanup(state):
            raise RuntimeError('Asset cleanup failed; see final cleanup report')
        spec = json.loads((HERE / 'provisioning.json').read_text())
        if digest(report / 'font.ttf') != spec['ubuntu_font_sha256']:
            raise ValueError('Font checksum mismatch')
        for name, sha in spec['models'].items():
            if digest(report / 'models' / (name + '.traineddata')) != sha:
                raise ValueError('Model checksum mismatch')
        venv = Path(tempfile.mkdtemp(prefix='host-venv-', dir=out))
        subprocess.run([sys.executable, '-m', 'venv', str(venv)], check=True)
        python = str(venv / 'bin/python')
        run(
            [
                python,
                '-m',
                'pip',
                'install',
                '--no-index',
                '--no-cache-dir',
                '--find-links',
                str(out / 'dependency-context/wheels'),
                '--only-binary=:all:',
                '--require-hashes',
                '-r',
                str(ROOT / 'requirements.lock'),
                '-r',
                str(ROOT / 'requirements-test.lock'),
            ],
            report / 'fresh-install.log',
        )
        run([python, '-m', 'pip', 'check'], report / 'pip-check.log')
        env = os.environ.copy()
        # The test supervisor and its CLI subprocesses inherit the same local
        # endpoint and unique run owner; the PDF child env remains allowlisted.
        env.pop('DOCKER_CONTEXT', None)
        env['DOCKER_HOST'] = state['endpoint']
        env[sandbox.RUN_ENV] = state['run_id']
        env.update(
            PYTHONNOUSERSITE='1',
            PYTHONDONTWRITEBYTECODE='1',
            OCR_TEST_TESSERACT=str(report / 'tesseract'),
            OCR_TEST_MODELS=str(report / 'models'),
            OCR_TEST_FONT=str(report / 'font.ttf'),
            OCR_TEST_REPORT_DIR=str(report),
            OCR_TEST_DOCKER_IMAGE=image,
        )
        with (report / 'environment.json').open('w') as stream:
            subprocess.run(
                [python, str(HERE / 'record_environment.py')], env=env, stdout=stream, check=True
            )
        run(
            [
                python,
                '-W',
                'error',
                '-m',
                'compileall',
                '-f',
                '-q',
                str(ROOT / 'src'),
                str(ROOT / 'tests'),
                str(ROOT / 'spikes'),
            ],
            report / 'syntax.log',
        )
        with (report / 'full-check.log').open('w') as stream:
            exit_code = subprocess.run(
                [
                    python,
                    '-W',
                    'error',
                    str(ROOT / 'tests/run_checks.py'),
                    '--xml',
                    str(report / 'tests.xml'),
                ],
                env=env,
                stdout=stream,
                stderr=subprocess.STDOUT,
            ).returncode
        if source_manifest() != expected:
            raise ValueError('Source changed during verification')
    finally:
        leftovers = cleanup_report(state, report)
        if leftovers:
            exit_code = 1
    (report / 'evidence.sha256').write_text(
        '\n'.join(digest(p) + '  ' + p.name for p in sorted(report.iterdir()) if p.is_file()) + '\n'
    )
    print('Full gate exit:', exit_code, 'Evidence:', report)
    raise SystemExit(exit_code)


if __name__ == '__main__':
    main()
