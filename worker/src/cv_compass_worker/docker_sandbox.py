"""Docker child scope, shared Windows/Linux implementation; never mounts host data.

Trusted host Docker client can access its local daemon; no Docker socket/config is
passed into the untrusted PDF scope. Production binary gate is independent.
"""
from dataclasses import asdict, replace
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import uuid
from .sandbox import ChildFailure


def docker_env():
    allowed = {'SYSTEMROOT','WINDIR','PATH','HOME','USERPROFILE','DOCKER_CONFIG','DOCKER_HOST','DOCKER_CONTEXT'}
    return {k:v for k,v in os.environ.items() if k.upper() in allowed}


def command(args, timeout=20):
    try:
        p = subprocess.run(['docker',*args],capture_output=True,env=docker_env(),timeout=timeout,shell=False)
    except (OSError,subprocess.TimeoutExpired):
        raise ChildFailure('DEPENDENCY_MISSING') from None
    if p.returncode:
        raise ChildFailure('INTERNAL_ERROR')
    return p.stdout


def check_local_daemon():
    if not shutil.which('docker'):
        raise ChildFailure('DEPENDENCY_MISSING')
    override = os.environ.get('DOCKER_HOST')
    endpoint = override or json.loads(command(['context','inspect']))[0]['Endpoints']['docker']['Host']
    if not endpoint.startswith(('npipe://','unix://')):
        raise ChildFailure('INVALID_CONFIGURATION')
    if command(['info','--format','{{.OSType}} {{.CgroupVersion}}']).strip() != b'linux 2':
        raise ChildFailure('INVALID_CONFIGURATION')


def create_scope(config, volume=None):
    check_local_daemon()
    name = 'cvcompass-ocr-'+uuid.uuid4().hex
    engine_quota=min(config.max_engine_output_bytes,max(4096,config.max_temp_bytes//2))
    work_quota=config.max_temp_bytes-engine_quota
    if work_quota < 4096:
        raise ChildFailure('INVALID_CONFIGURATION')
    args=['create','--name',name,'--pull','never','--network','none','--read-only',
          '--memory',str(config.container_memory_bytes),'--memory-swap',str(config.container_memory_bytes),
          '--cpus',str(config.container_cpu_millicores/1000),'--pids-limit',str(config.container_pids),
          '--tmpfs',f'/work:rw,noexec,nosuid,nodev,size={work_quota},uid=65532,gid=65532,mode=0700',
          '--tmpfs',f'/work/engine:rw,noexec,nosuid,nodev,size={engine_quota},uid=65532,gid=65532,mode=0700',
          '--shm-size','1m','--cap-drop','ALL','--security-opt','no-new-privileges',
          '--ulimit','core=0','--log-driver','none','--user','65532:65532',
          '--label','cv-compass.scope=ocr-child',config.docker_image,
          '/opt/cv-venv/bin/python','-m','cv_compass_worker.container_entry']
    if volume:
        args[-4:-4] = ['--mount',f'type=volume,source={volume},target=/request,readonly']
    command(args)
    try:
        info=json.loads(command(['inspect',name]))[0]
        host=info['HostConfig']
        if (host['Memory']!=config.container_memory_bytes or host['MemorySwap']!=config.container_memory_bytes
                or host['NanoCpus']!=config.container_cpu_millicores*1000000
                or host['PidsLimit']!=config.container_pids or host['NetworkMode']!='none'
                or not host['ReadonlyRootfs'] or host['LogConfig']['Type']!='none'
                or host['Binds']
                or (volume and (len(info['Mounts']) != 1 or info['Mounts'][0]['Type'] != 'volume'
                    or info['Mounts'][0]['Name'] != volume or info['Mounts'][0]['RW']))
                or (not volume and info['Mounts'])):
            raise ChildFailure('INVALID_CONFIGURATION')
        return name
    except Exception:
        command(['rm','-f',name])
        raise


def prepare_control(task, source, config):
    check_local_daemon()  # Before staging, volume creation or reading source.
    volume='cvcompass-input-'+uuid.uuid4().hex
    command(['volume','create','--label','cv-compass.scope=ocr-input',volume])
    writer = """import json,sys,pathlib,os
line=sys.stdin.buffer.readline(65537)
if len(line)>65536: raise SystemExit(3)
root=pathlib.Path('/control')
(root/'task.json').write_bytes(line)
with (root/'input.pdf').open('wb') as out:
 size=0
 while data:=sys.stdin.buffer.read(65536):
  size+=len(data)
  if size>10485760: raise SystemExit(3)
  out.write(data)
for p in root.iterdir(): p.chmod(0o444)
"""
    args=['docker','run','--rm','-i','--pull','never','--network','none','--read-only',
          '--memory','64m','--memory-swap','64m','--cpus','1','--pids-limit','8',
          '--cap-drop','ALL','--security-opt','no-new-privileges','--log-driver','none',
          '--mount',f'type=volume,source={volume},target=/control',
          '--user','0:0',config.docker_image,'/opt/cv-venv/bin/python','-c',writer]
    process=subprocess.Popen(args,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=docker_env())
    try:
        process.stdin.write(json.dumps(task).encode()+b'\n')
        if source is not None:
            with Path(source).open('rb') as stream:
                shutil.copyfileobj(stream,process.stdin,length=65536)
        process.stdin.close(); process.stdin=None
        process.communicate(timeout=20)
        if process.returncode:
            raise ChildFailure('INTERNAL_ERROR')
        return volume
    except Exception:
        command(['volume','rm',volume])
        raise
    finally:
        if process.poll() is None:
            process.kill(); process.wait(timeout=5)


def execute_scope(name, root, config, timeout):
    started=time.monotonic()
    stdout=root/'container.response.json'; stderr=root/'container.stderr.log'
    failure=None
    with stdout.open('wb') as out, stderr.open('wb') as err:
        attached=subprocess.Popen(['docker','start','--attach',name],stdout=out,stderr=err,stdin=subprocess.DEVNULL,env=docker_env())
        try:
            while True:
                if stdout.stat().st_size+stderr.stat().st_size > config.max_engine_output_bytes+32768:
                    failure='OUTPUT_LIMIT'
                elif time.monotonic()-started > timeout+5:
                    failure='PAGE_TIMEOUT'
                if failure or attached.poll() is not None:
                    break
                time.sleep(.02)
            if failure:
                command(['kill',name])
            attached.wait(timeout=10)
        finally:
            if attached.poll() is None:
                command(['kill',name]); attached.kill(); attached.wait(timeout=5)
    state=json.loads(command(['inspect',name]))[0]['State']
    if state['Running']:
        raise ChildFailure('INTERNAL_ERROR')
    if state['OOMKilled']:
        try:
            observed=json.loads(stdout.read_text(encoding='utf-8'))['metrics']
        except (ValueError,KeyError,TypeError):
            observed={}
        return {'result':{'error':'RESOURCE_LIMIT'},'metrics':observed | {'oom_killed':True}}
    if failure:
        return {'result':{'error':failure},'metrics':{}}
    if state['ExitCode'] != 0:
        raise ChildFailure('ENGINE_CRASH')
    try:
        envelope=json.loads(stdout.read_text(encoding='utf-8'))
        if set(envelope)!= {'result','metrics'} or not isinstance(envelope['metrics'],dict):
            raise ValueError()
        return envelope
    except (ValueError,TypeError):
        raise ChildFailure('ENGINE_CRASH') from None


def run_scope(task, root, config, timeout, source=None):
    root.mkdir(mode=0o700)
    child_config=replace(config,adapter='native',tesseract='/usr/local/bin/tesseract',models='/models')
    control=task | {'config':asdict(child_config),'source':'/request/input.pdf','wall_timeout':timeout}
    volume=prepare_control(control,source,config)
    name=None
    envelope=None
    try:
        name=create_scope(config,volume)
        envelope=execute_scope(name,root,config,timeout)
        return envelope
    finally:
        leftovers=[]
        for args in ([['rm','-f',name]] if name else []) + [['volume','rm',volume]]:
            try:
                command(args)
            except ChildFailure:
                leftovers.append(args[-1])
        if leftovers:
            primary=sys.exception()
            if primary is not None:
                primary.cleanup_failed=True
            # Safe opaque ownership ledger, private to the supervisor. No
            # automatic retry/recovery; preserve the primary result/exception.
            try:
                (root/'scope-cleanup.json').write_text(json.dumps(leftovers))
            except OSError:
                # A full host disk must not replace the original processing error.
                pass
            if envelope is not None:
                envelope['metrics']['cleanup_failed']=True


def run_docker_child(task, root, config, timeout):
    envelope=run_scope(task,root,config,timeout,source=task['source'])
    (root/'isolation.json').write_text(json.dumps(envelope['metrics']),encoding='utf-8')
    response=envelope['result']
    if not isinstance(response,dict) or set(response) not in ({'value'},{'error'}):
        raise ChildFailure('ENGINE_CRASH')
    if 'error' in response:
        from .contracts import SCHEMA
        if response['error'] not in SCHEMA['$defs']['error']['properties']['code']['enum']:
            raise ChildFailure('ENGINE_CRASH')
        raise ChildFailure(response['error'])
    return response['value']
