"""Copy only explicit non-sensitive source files into a dedicated Docker context."""
from pathlib import Path
import shutil
root=Path(__file__).resolve().parents[2]
ctx=root/'build/m6-2-hardening/worker-context'
for folder in ('src','design','tests'):
    for source in (root/folder).rglob('*'):
        if source.is_file() and source.suffix in ('.py','.json') and '__pycache__' not in source.parts:
            target=ctx/source.relative_to(root)
            target.parent.mkdir(parents=True,exist_ok=True)
            shutil.copyfile(source,target)
for source in [*(root/'spikes/m6_1').glob('*.py'), *(root/'spikes/m6_2_hardening').glob('*.py')]:
    target=ctx/source.relative_to(root)
    target.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(source,target)
shutil.copyfile(root/'spikes/m6_2_hardening/Dockerfile.worker',ctx/'Dockerfile')
shutil.copyfile(root/'run_worker.py',ctx/'run_worker.py')
print('Prepared explicit source-only runtime context')
