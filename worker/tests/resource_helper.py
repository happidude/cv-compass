"""Bounded, synthetic enforcement helpers; invoked only by the isolation tests."""
import errno
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time


def respond(value=None,error=None):
    Path('/work/response.json').write_text(json.dumps({'error':error} if error else {'value':value}))


def main(action):
    if action=='oom':
        chunks=[bytearray(1024*1024) for _ in range(256)]
        respond({'unexpected_survival':len(chunks)})
    elif action=='pids':
        children=[]
        try:
            for _ in range(20):
                children.append(subprocess.Popen([sys.executable,'-c','import time; time.sleep(3)']))
        except OSError as exc:
            if exc.errno != errno.EAGAIN: raise
            respond({'pids_exhausted':True})
        finally:
            for process in children:
                process.kill(); process.wait()
    elif action=='temp':
        path=Path('/work/fill.bin')
        try:
            with path.open('wb') as stream:
                for _ in range(16): stream.write(b'x'*(1024*1024))
            respond({'unexpected_survival':True})
        except OSError as exc:
            if exc.errno != errno.ENOSPC: raise
            path.unlink()
            respond(error='TEMP_LIMIT')
    elif action=='output_quota':
        try:
            with Path('/work/engine/fill.txt').open('wb') as stream:
                for _ in range(32): stream.write(b'x'*65536)
            respond({'unexpected_survival':True})
        except OSError as exc:
            if exc.errno != errno.ENOSPC: raise
            respond(error='OUTPUT_LIMIT')
    elif action=='output':
        for _ in range(64):
            sys.stdout.write('x'*16384)
        sys.stdout.flush(); respond({'done':True})
    elif action in ('descendant','crash_descendant'):
        subprocess.Popen([sys.executable,'-c','import time; time.sleep(20)'],start_new_session=True)
        if action=='crash_descendant': os._exit(17)
        time.sleep(20)
    elif action=='cpu':
        children=[subprocess.Popen([sys.executable,'-c','import time; end=time.monotonic()+1.5\nwhile time.monotonic()<end: pass']) for _ in range(4)]
        for process in children: process.wait()
        respond({'cpu_done':True})
    elif action=='permissions':
        refused=[]
        for target in ('/request/input.pdf','/rootfs-write-test'):
            try:
                with open(target,'wb') as stream: stream.write(b'forbidden')
                refused.append(False)
            except OSError:
                refused.append(True)
        try:
            socket.create_connection(('192.0.2.1',9),timeout=.1).close()
            network=False
        except OSError:
            network=True
        respond({'writes_refused':refused,'network_refused':network,
                 'credentials_absent':not any(k in os.environ for k in ('DB_PASSWORD','AWS_SECRET_ACCESS_KEY','DOCKER_HOST'))})
    else:
        raise ValueError('Unknown bounded probe')


if __name__=='__main__':
    main(sys.argv[1])
