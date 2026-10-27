"""Rebuild source against an existing immutable baseline; full verifier is still required."""

import argparse
import json
from pathlib import Path
import platform
import re
import shutil
import subprocess
from build_baseline import ROOT, source_manifest, digest, run
from prepare_worker_image import prepare


def source_git(*arguments):
    root = ROOT.parent
    marker = root / '.git'
    command = ['git', '-C', str(root)]
    if marker.is_file():
        target = marker.read_text().strip().removeprefix('gitdir: ')
        if re.match(r'^[A-Za-z]:[/\\]', target):
            # Windows-created worktrees retain Windows gitdir paths when mounted in WSL.
            target = subprocess.check_output(['wslpath', '-u', target], text=True).strip()
            command = ['git', '--git-dir=' + target, '--work-tree=' + str(root)]
    return subprocess.check_output([*command, *arguments], text=True).strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--baseline', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if platform.system() != 'Linux' or platform.python_version() != '3.14.8':
        raise SystemExit('Requires provisioned Linux CPython 3.14.8')
    out = args.output.resolve()
    if not out.is_relative_to(ROOT / 'build') or out == ROOT / 'build' or out.exists():
        raise SystemExit('Use a new dedicated directory under worker/build')
    images = json.loads((args.baseline / 'images.json').read_text())
    # Resolve both pinned prerequisites before writing/building anything.
    for name in ('runtime', 'dependencies'):
        observed = subprocess.check_output(
            ['docker', 'image', 'inspect', images[name], '--format', '{{.Id}}'], text=True
        ).strip()
        if observed != images[name]:
            raise SystemExit('Baseline image identity mismatch')
    out.mkdir(parents=True)
    manifest = source_manifest()
    (out / 'source-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    commit = source_git('rev-parse', 'HEAD')
    dirty = bool(source_git('status', '--porcelain'))
    (out / 'source-version.json').write_text(
        json.dumps(
            dict(
                commit=commit,
                dirty=dirty,
                manifest_sha256=digest(out / 'source-manifest.json'),
                production='BLOCKED_GCC_WARNING_UNRESOLVED',
            ),
            indent=2,
        )
        + '\n'
    )
    wheels = out / 'dependency-context/wheels'
    shutil.copytree(args.baseline / 'dependency-context/wheels', wheels)
    context = prepare(ROOT, out / 'worker-context')
    # Dockerfile FROM needs a repository reference, not the bare local image ID.
    dependency_ref = 'cvcompass-m6-deps:' + images['dependencies'].split(':', 1)[1]
    subprocess.run(['docker', 'tag', images['dependencies'], dependency_ref], check=True)
    run(
        [
            'docker',
            'build',
            '--network',
            'none',
            '--progress=plain',
            '--build-arg',
            'DEPENDENCY_IMAGE=' + dependency_ref,
            '-t',
            'cvcompass-logging-quality:build',
            str(context),
        ],
        out / 'worker-build.log',
    )
    images['worker'] = subprocess.check_output(
        ['docker', 'image', 'inspect', 'cvcompass-logging-quality:build', '--format', '{{.Id}}'],
        text=True,
    ).strip()
    (out / 'images.json').write_text(json.dumps(images, indent=2) + '\n')
    if source_manifest() != manifest:
        raise SystemExit('Source changed during build; rebuild before verification')
    print('Measured worker image:', images['worker'])
    print('Run verify_baseline.py --build against this output before deployment')


if __name__ == '__main__':
    main()
