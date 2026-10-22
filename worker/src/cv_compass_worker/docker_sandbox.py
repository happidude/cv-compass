"""Docker child scope, shared Windows/Linux implementation; never mounts host data.

Trusted host Docker client can access its local daemon; no Docker socket/config is
passed into the untrusted PDF scope. Production binary gate is independent.
"""
from dataclasses import asdict, replace
from contextlib import contextmanager
from contextvars import ContextVar
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import time
import uuid
import threading
from .sandbox import ChildFailure


_target = ContextVar('docker_target', default=None)
RUN_ENV = 'CV_COMPASS_VERIFICATION_RUN_ID'
RUN_LABEL = 'cv-compass.verification-run'


def resource_labels():
    """Trusted supervisor ownership only; never forwarded to the PDF child env."""
    run_id = os.environ.get(RUN_ENV)
    if run_id is None:
        return []
    if not re.fullmatch('[0-9a-f]{32}', run_id):
        raise ChildFailure('INVALID_CONFIGURATION')
    return ['--label', RUN_LABEL+'='+run_id]


def docker_env():
    # Snapshot routing configuration only while resolving. Commands use explicit
    # --host and never inherit context/host/TLS overrides after validation.
    allowed = {'SYSTEMROOT','WINDIR','PATH','HOME','USERPROFILE','DOCKER_CONFIG'}
    return {k:v for k,v in os.environ.items() if k.upper() in allowed}


def resolve_target():
    if not shutil.which('docker'):
        raise ChildFailure('DEPENDENCY_MISSING')
    env = docker_env()
    context = os.environ.get('DOCKER_CONTEXT')
    endpoint = None if context else os.environ.get('DOCKER_HOST')
    if not endpoint:
        args = ['docker','context','inspect',*([context] if context else [])]
        resolution_env=env.copy()
        # The special in-memory "default" context derives its endpoint from
        # DOCKER_HOST, even when DOCKER_CONTEXT=default. Let CLI metadata resolve
        # that rule; named contexts still select their stored endpoint.
        if os.environ.get('DOCKER_HOST'):
            resolution_env['DOCKER_HOST']=os.environ['DOCKER_HOST']
        try:
            result = subprocess.run(args,capture_output=True,env=resolution_env,timeout=20,shell=False)
            if result.returncode:
                raise ValueError()
            endpoint = json.loads(result.stdout)[0]['Endpoints']['docker']['Host']
        except (OSError,ValueError,KeyError,IndexError,TypeError,subprocess.TimeoutExpired):
            raise ChildFailure('INVALID_CONFIGURATION') from None
    if not isinstance(endpoint,str) or not (
            endpoint.startswith('unix:///') or endpoint.startswith('npipe:////./pipe/')):
        raise ChildFailure('INVALID_CONFIGURATION')
    target = (endpoint,env)
    if _invoke(['info','--format','{{.OSType}} {{.CgroupVersion}}'],target).strip() != b'linux 2':
        raise ChildFailure('INVALID_CONFIGURATION')
    return target


@contextmanager
def docker_session():
    token = _target.set(_target.get() or resolve_target())
    try:
        yield
    finally:
        _target.reset(token)


def docker_cli(args):
    endpoint, env = _target.get() or resolve_target()
    return ['docker','--host',endpoint,*args],env


def _invoke(args,target,timeout=20):
    endpoint,env=target
    try:
        p=subprocess.run(['docker','--host',endpoint,*args],capture_output=True,
                         env=env,timeout=timeout,shell=False)
    except (OSError,subprocess.TimeoutExpired):
        raise ChildFailure('DEPENDENCY_MISSING') from None
    if p.returncode:
        raise ChildFailure('INTERNAL_ERROR')
    return p.stdout


def command(args, timeout=20):
    return _invoke(args,_target.get() or resolve_target(),timeout)


def check_local_daemon():
    return _target.get() or resolve_target()


def record_cleanup(root, leftovers, primary=None, envelope=None):
    if not leftovers:
        return
    if primary is not None:
        primary.cleanup_failed=True
        primary.leftovers=leftovers
    if envelope is not None:
        envelope['metrics']['cleanup_failed']=True
        envelope['metrics']['leftovers']=leftovers
    if root is not None:
        try:
            (root/'scope-cleanup.json').write_text(json.dumps(leftovers))
        except OSError:
            pass


def remove_container(name):
    """Kill/wait the daemon-side tree before removing its resource identity."""
    info=json.loads(command(['inspect',name]))[0]['State']
    if info['Running']:
        command(['kill',name])
    if info.get('Status') != 'created':
        command(['wait',name],timeout=10)
    command(['rm','-f',name])


def create_scope(config, volume=None):
    with docker_session():
        return _create_scope(config,volume)


def _create_scope(config, volume):
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
          '--label','cv-compass.scope=ocr-child',*resource_labels(),config.docker_image,
          '/opt/cv-venv/bin/python','-m','cv_compass_worker.container_entry']
    if volume:
        args[-4:-4] = ['--mount',f'type=volume,source={volume},target=/request,readonly']
    try:
        command(args)
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
    except Exception as exc:
        try:
            remove_container(name)
        except Exception:
            record_cleanup(None,[name],primary=exc)
        raise


def prepare_control(task, source, config, root=None):
    with docker_session():
        return _prepare_control(task,source,config,root)


def _prepare_control(task, source, config, root):
    labels=resource_labels()
    volume='cvcompass-input-'+uuid.uuid4().hex
    name='cvcompass-writer-'+uuid.uuid4().hex
    writer = """import json,sys,pathlib,os,threading
timer=threading.Timer(20,lambda:os._exit(3)); timer.daemon=True; timer.start()
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
    args=['create','--name',name,'-i','--pull','never','--network','none','--read-only',
          '--memory','64m','--memory-swap','64m','--cpus','1','--pids-limit','8',
          '--cap-drop','ALL','--security-opt','no-new-privileges','--log-driver','none',
          '--label','cv-compass.scope=ocr-writer',*labels,
          '--mount',f'type=volume,source={volume},target=/control',
          '--user','0:0',config.docker_image,'/opt/cv-venv/bin/python','-c',writer]
    process=None
    feeder=None
    feed_errors=[]
    writer_attempted=False
    primary=None
    leftovers=[]
    try:
        command(['volume','create','--label','cv-compass.scope=ocr-input',*labels,volume])
        writer_attempted=True
        command(args)
        cli,env=docker_cli(['start','--attach','--interactive',name])
        process=subprocess.Popen(cli,stdin=subprocess.PIPE,stdout=subprocess.DEVNULL,
                                 stderr=subprocess.DEVNULL,env=env)
        def feed():
            try:
                process.stdin.write(json.dumps(task).encode()+b'\n')
                if source is not None:
                    with Path(source).open('rb') as stream:
                        shutil.copyfileobj(stream,process.stdin,length=65536)
            except Exception as exc:
                feed_errors.append(exc)
            finally:
                try:
                    process.stdin.close()
                except Exception as exc:
                    if not feed_errors:
                        feed_errors.append(exc)
        # Bound pipe writes too, not only wait/communicate after a potentially
        # blocking write. Killing the writer closes its pipe on failure.
        feeder=threading.Thread(target=feed,daemon=True)
        feeder.start()
        process.wait(timeout=20)
        feeder.join(timeout=1)
        if feeder.is_alive():
            raise ChildFailure('DOCUMENT_TIMEOUT')
        if feed_errors:
            raise feed_errors[0]
        if process.returncode:
            raise ChildFailure('INTERNAL_ERROR')
    except Exception as exc:
        primary=(feed_errors[0] if feed_errors else
                 ChildFailure('DOCUMENT_TIMEOUT') if isinstance(exc,subprocess.TimeoutExpired) else exc)
    finally:
        if writer_attempted:
            try:
                remove_container(name)
            except Exception:
                leftovers.append(name)
        if process is not None:
            try:
                if process.poll() is None:
                    process.kill()
                process.wait(timeout=5)
                if feeder is not None:
                    feeder.join(timeout=5)
                    if feeder.is_alive():
                        leftovers.append('writer-feeder')
                if process.stdin is not None and not (feeder and feeder.is_alive()):
                    process.stdin.close()
            except Exception:
                leftovers.append(name+'-cli')
        if primary is not None or leftovers:
            # Never remove an input volume still owned by an unremoved writer.
            if leftovers:
                leftovers.append(volume)
            else:
                try:
                    command(['volume','rm',volume])
                except Exception:
                    leftovers.append(volume)
            primary=primary or ChildFailure('INTERNAL_ERROR')
            record_cleanup(root,leftovers,primary=primary)
    if primary is not None:
        raise primary
    return volume


def execute_scope(name, root, config, timeout):
    with docker_session():
        return _execute_scope(name,root,config,timeout)


def _execute_scope(name, root, config, timeout):
    started=time.monotonic()
    stdout=root/'container.response.json'; stderr=root/'container.stderr.log'
    failure=None
    with stdout.open('wb') as out, stderr.open('wb') as err:
        cli,env=docker_cli(['start','--attach',name])
        attached=subprocess.Popen(cli,stdout=out,stderr=err,stdin=subprocess.DEVNULL,env=env)
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
    with docker_session():
        return _run_scope(task,root,config,timeout,source)


def _run_scope(task, root, config, timeout, source):
    root.mkdir(mode=0o700)
    child_config=replace(config,adapter='native',tesseract='/usr/local/bin/tesseract',models='/models')
    control=task | {'config':asdict(child_config),'source':'/request/input.pdf','wall_timeout':timeout}
    volume=None
    name=None
    envelope=None
    try:
        volume=prepare_control(control,source,config,root)
        name=create_scope(config,volume)
        envelope=execute_scope(name,root,config,timeout)
        return envelope
    finally:
        leftovers=[]
        primary=sys.exception()
        if primary is not None and getattr(primary,'cleanup_failed',False):
            leftovers.extend(getattr(primary,'leftovers',[]))
        if name:
            try:
                remove_container(name)
            except Exception:
                leftovers.append(name)
        if volume and not leftovers:
            try:
                command(['volume','rm',volume])
            except Exception:
                leftovers.append(volume)
        elif volume:
            leftovers.append(volume)
        record_cleanup(root,leftovers,primary=primary,envelope=envelope)


class ChildValue(dict):
    cleanup_failed=False


def run_docker_child(task, root, config, timeout):
    envelope=run_scope(task,root,config,timeout,source=task['source'])
    cleanup_failed=envelope['metrics'].get('cleanup_failed',False)
    try:
        try:
            (root/'isolation.json').write_text(json.dumps(envelope['metrics']),encoding='utf-8')
        except OSError:
            # Diagnostic storage cannot replace the engine outcome or erase
            # the in-memory cleanup flag (including when ledger writing fails).
            pass
        response=envelope['result']
        if not isinstance(response,dict) or set(response) not in ({'value'},{'error'}):
            raise ChildFailure('ENGINE_CRASH')
        if 'error' in response:
            from .contracts import SCHEMA
            if response['error'] not in SCHEMA['$defs']['error']['properties']['code']['enum']:
                raise ChildFailure('ENGINE_CRASH')
            raise ChildFailure(response['error'])
        value=ChildValue(response['value'])
        value.cleanup_failed=cleanup_failed
        return value
    except Exception as exc:
        if cleanup_failed:
            exc.cleanup_failed=True
            exc.leftovers=envelope['metrics'].get('leftovers',[])
        raise
