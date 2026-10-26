"""Fresh allowlisted context: runtime imports design/, probes live in tests/spikes."""

from pathlib import Path
import shutil


def prepare(root, context):
    root, context = Path(root).resolve(), Path(context).resolve()
    if not context.is_relative_to(root / 'build') or context == root / 'build':
        raise ValueError('Context must be a dedicated directory inside worker/build')
    if context.exists():
        shutil.rmtree(context)
    context.mkdir(parents=True)
    sources = []
    for folder in ('src', 'design', 'tests', 'spikes'):
        sources.extend(
            p
            for p in (root / folder).rglob('*')
            if p.is_file() and p.suffix in ('.py', '.json') and '__pycache__' not in p.parts
        )
    sources.append(root / 'run_worker.py')
    for source in sources:
        target = context / source.relative_to(root)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    shutil.copyfile(root / 'spikes/m6_2_hardening/Dockerfile.worker', context / 'Dockerfile')
    return context


if __name__ == '__main__':
    root = Path(__file__).resolve().parents[2]
    prepare(root, root / 'build/m6-2-hardening/worker-context')
    print('Prepared fresh explicit source-only runtime context')
