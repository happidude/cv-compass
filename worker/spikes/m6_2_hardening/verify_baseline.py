"""Ubuntu host full gate, fresh hash-locked venv, new source-built Docker child."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import uuid
from build_baseline import ROOT, HERE, digest, source_manifest, run


def ids(kind,label):
    args=['docker',*(['ps','-aq'] if kind=='container' else ['volume','ls','-q']),
          '--filter','label=cv-compass.scope='+label]
    return subprocess.check_output(args,text=True).split()


def cleanup(state):
    failed=[]
    asset=state.get('asset')
    if asset:
        if not asset.startswith('cvcompass-assets-'):
            raise ValueError('Invalid asset ownership identity')
        found=subprocess.check_output(['docker','ps','-aq','--filter','name=^/'+asset+'$'],text=True).strip()
        if found and subprocess.run(['docker','rm','-f',asset],stdout=subprocess.DEVNULL).returncode:
            failed.append(asset)
    for kind,label in (('container','ocr-writer'),('container','ocr-child'),('volume','ocr-input')):
        for identity in sorted(set(ids(kind,label))-set(state[label])):
            args=['docker',*(['rm','-f'] if kind=='container' else ['volume','rm']),identity]
            if subprocess.run(args,stdout=subprocess.DEVNULL).returncode:
                failed.append(identity)
    return failed


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--build',type=Path,default=ROOT/'build/baseline')
    parser.add_argument('--cleanup-only',action='store_true')
    args=parser.parse_args()
    out=args.build.resolve()
    if not out.is_relative_to(ROOT/'build') or out == ROOT/'build':
        raise SystemExit('Build directory must be under worker/build')
    statefile=out/'resources.before.json'
    if args.cleanup_only:
        if statefile.is_file():
            raise SystemExit(bool(cleanup(json.loads(statefile.read_text()))))
        return
    expected=json.loads((out/'source-manifest.json').read_text())
    if source_manifest()!=expected:
        raise SystemExit('Source differs from image build manifest; rebuild first')
    state={label:ids(kind,label) for kind,label in (
        ('container','ocr-writer'),('container','ocr-child'),('volume','ocr-input'))}
    container='cvcompass-assets-'+uuid.uuid4().hex
    state['asset']=container
    statefile.write_text(json.dumps(state,indent=2)+'\n')
    report=out/'verification'
    if report.exists():
        raise SystemExit('Fresh verification directory required')
    report.mkdir()
    image=json.loads((out/'images.json').read_text())['worker']
    exit_code=1
    try:
        # Copy trusted provisioning artifacts only. The host runs the supervisor;
        # PDF children never receive the daemon socket or host paths.
        subprocess.run(['docker','create','--name',container,image],check=True,stdout=subprocess.DEVNULL)
        for path,name in (('/usr/local/bin/tesseract','tesseract'),('/models','models'),
                          ('/fixture-font.ttf','font.ttf'),('/provenance','provenance')):
            subprocess.run(['docker','cp',container+':'+path,str(report/name)],check=True)
        subprocess.run(['docker','rm',container],check=True,stdout=subprocess.DEVNULL)
        spec=json.loads((HERE/'provisioning.json').read_text())
        if digest(report/'font.ttf') != spec['ubuntu_font_sha256']:
            raise ValueError('Font checksum mismatch')
        for name,sha in spec['models'].items():
            if digest(report/'models'/(name+'.traineddata')) != sha:
                raise ValueError('Model checksum mismatch')
        venv=Path(tempfile.mkdtemp(prefix='host-venv-',dir=out))
        subprocess.run([sys.executable,'-m','venv',str(venv)],check=True)
        python=str(venv/'bin/python')
        run([python,'-m','pip','install','--no-index','--no-cache-dir','--find-links',str(out/'dependency-context/wheels'),
             '--only-binary=:all:','--require-hashes','-r',str(ROOT/'requirements.lock'),
             '-r',str(ROOT/'requirements-test.lock')],report/'fresh-install.log')
        run([python,'-m','pip','check'],report/'pip-check.log')
        env=os.environ.copy()
        env.update(PYTHONNOUSERSITE='1',PYTHONDONTWRITEBYTECODE='1',
                   OCR_TEST_TESSERACT=str(report/'tesseract'),OCR_TEST_MODELS=str(report/'models'),
                   OCR_TEST_FONT=str(report/'font.ttf'),OCR_TEST_REPORT_DIR=str(report),OCR_TEST_DOCKER_IMAGE=image)
        with (report/'environment.json').open('w') as stream:
            subprocess.run([python,str(HERE/'record_environment.py')],env=env,stdout=stream,check=True)
        run([python,'-W','error','-m','compileall','-f','-q',str(ROOT/'src'),str(ROOT/'tests'),str(ROOT/'spikes')],report/'syntax.log')
        with (report/'full-check.log').open('w') as stream:
            exit_code=subprocess.run([python,'-W','error',str(ROOT/'tests/run_checks.py'),
                                      '--xml',str(report/'tests.xml')],env=env,stdout=stream,stderr=subprocess.STDOUT).returncode
        if source_manifest()!=expected:
            raise ValueError('Source changed during verification')
    finally:
        # Asset extraction identity is known even if create/cp fails.
        subprocess.run(['docker','rm','-f',container],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        leftovers=cleanup(state)
        (report/'cleanup.json').write_text(json.dumps({'leftovers':leftovers},indent=2)+'\n')
        if leftovers:
            exit_code=1
    (report/'evidence.sha256').write_text('\n'.join(digest(p)+'  '+p.name for p in sorted(report.iterdir()) if p.is_file())+'\n')
    print('Full gate exit:',exit_code,'Evidence:',report)
    raise SystemExit(exit_code)


if __name__=='__main__':
    main()
