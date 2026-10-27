"""PID1 watchdog for the hard-limited Linux container. Stdlib only until child."""

import json
import os
import signal
import subprocess
import sys
import time
from pathlib import Path

CGROUP = Path('/sys/fs/cgroup')


def measurements():
    result = {}
    for name in (
        'memory.max',
        'memory.swap.max',
        'memory.peak',
        'cpu.max',
        'cpu.stat',
        'pids.max',
        'pids.peak',
        'pids.events',
        'memory.events',
    ):
        path = CGROUP / name
        result[name] = path.read_text().strip() if path.is_file() else None
    return result


def count(metrics, field, counter):
    return dict(line.split() for line in (metrics.get(field) or '').splitlines()).get(counter, '0')


def main():
    task = json.loads(Path('/request/task.json').read_text())
    limit = task['wall_timeout']
    cap = task['config']['max_engine_output_bytes']
    root = Path('/work')
    started = time.monotonic()
    (root / 'task.json').write_text(json.dumps(task))
    args = task.get('probe_command') or [
        sys.executable,
        '-m',
        'cv_compass_worker.child',
        '/work/task.json',
        '/work/response.json',
    ]
    failure = None
    with (
        (root / 'child.stdout.log').open('wb') as out,
        (root / 'child.stderr.log').open('wb') as err,
    ):
        child = subprocess.Popen(
            args,
            stdin=subprocess.DEVNULL,
            stdout=out,
            stderr=err,
            cwd=root,
            start_new_session=True,
            env={
                'PATH': '/opt/cv-venv/bin:/usr/local/bin:/usr/bin:/bin',
                'PYTHONPATH': '/opt/worker/src',
                'PYTHONNOUSERSITE': '1',
                'PYTHONDONTWRITEBYTECODE': '1',
                'OMP_THREAD_LIMIT': '1',
                'TMPDIR': '/work',
            },
        )
        try:
            while True:
                sizes = [(p, p.stat().st_size) for p in root.rglob('*') if p.is_file()]
                if sum(n for p, n in sizes if p.suffix in ('.txt', '.tsv', '.log')) > cap:
                    failure = 'OUTPUT_LIMIT'
                elif time.monotonic() - started >= limit:
                    failure = 'PAGE_TIMEOUT' if task['action'] == 'page' else 'DOCUMENT_TIMEOUT'
                if failure or child.poll() is not None:
                    break
                time.sleep(0.02)
        finally:
            try:
                os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            child.wait(timeout=3)
    metrics = measurements()
    metrics['engine_stderr_lines'] = sum(
        len(p.read_bytes().splitlines())
        for p in root.glob('*.stderr.log')
        if p.stat().st_size <= cap
    )
    if (
        int(count(metrics, 'memory.events', 'oom_kill')) > 0
        or int(count(metrics, 'pids.events', 'max')) > 0
    ):
        failure = 'RESOURCE_LIMIT'
    metrics['work_free_bytes'] = os.statvfs('/work').f_bavail * os.statvfs('/work').f_frsize
    metrics['engine_free_bytes'] = (
        os.statvfs('/work/engine').f_bavail * os.statvfs('/work/engine').f_frsize
    )
    if not metrics['engine_free_bytes']:
        failure = 'OUTPUT_LIMIT'
    elif not metrics['work_free_bytes']:
        failure = 'TEMP_LIMIT'
    response = root / 'response.json'
    if failure:
        result = {'error': failure}
    elif child.returncode or not response.is_file():
        result = {'error': 'ENGINE_CRASH'}
    elif response.stat().st_size > cap:
        result = {'error': 'OUTPUT_LIMIT'}
    else:
        try:
            result = json.loads(response.read_text(encoding='utf-8'))
        except ValueError, UnicodeError:
            result = {'error': 'ENGINE_CRASH'}
    print(
        json.dumps({'result': result, 'metrics': metrics}, ensure_ascii=False, allow_nan=False),
        flush=True,
    )


if __name__ == '__main__':
    main()
