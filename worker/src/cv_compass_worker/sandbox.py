"""Diagnostic process supervision, bounded private IPC, separate isolation report.

This adapter is NOT a production sandbox. Windows lacks Job Object containment;
Linux process groups are lifecycle control, not memory/CPU security boundaries.
"""
from dataclasses import asdict
import json
import os
from pathlib import Path
import platform
import signal
import subprocess
import sys
import time


class ChildFailure(Exception):
    def __init__(self, code):
        self.code = code


def safe_env():
    allowed = {'SYSTEMROOT', 'WINDIR', 'LANG', 'LC_ALL'}
    env = {k: v for k, v in os.environ.items() if k.upper() in allowed}
    env.update(OMP_THREAD_LIMIT='1', PYTHONDONTWRITEBYTECODE='1',
               PYTHONPATH=str(Path(__file__).resolve().parents[1]))
    return env


def isolation_report(config, diagnostics=None):
    report = dict(kind='verification_report_not_ocr_v1', os=platform.platform(),
                  adapter='diagnostic_process_group' if os.name != 'nt' else 'diagnostic_taskkill',
                  status='not_enforced', requested_limits={'memory_bytes': 1073741824,
                  'cpus': 2, 'pids': 64, 'temp_bytes': config.max_temp_bytes}, applied_limits={},
                  resource_safety_verified=False, production_binary='BLOCKED_GCC_WARNING_UNRESOLVED',
                  fixture_scope='ordinary_synthetic_only', quality_evaluation='NOT_PERFORMED')
    if sys.platform == 'linux':
        for name in ('memory.max', 'memory.swap.max', 'cpu.max', 'pids.max'):
            path = Path('/sys/fs/cgroup') / name
            if path.is_file():
                report['applied_limits'][name] = path.read_text().strip()
        report['adapter'] = 'diagnostic_process_group_with_observed_cgroup'
    # Observing aggregate cgroup settings does not prove a separate child budget
    # or filesystem quota. Do not advertise full requested isolation as enforced.
    if config.adapter == 'docker':
        metrics = [d['isolation'] for d in (diagnostics or []) if isinstance(d.get('isolation'), dict)]
        report.update(adapter='docker_linux_cgroup_v2', requested_limits={
            'child_memory_bytes': config.container_memory_bytes,
            'cpu_millicores': config.container_cpu_millicores,
            'pids': config.container_pids, 'work_tmpfs_bytes': config.max_temp_bytes,
            'shm_bytes': 1048576}, applied_limits=metrics,
            supervisor='outside_child_container_no_claim_of_host_memory_cap')
        expected_cpu=f'{config.container_cpu_millicores*100} 100000'
        if metrics and all(m.get('memory.max') == str(config.container_memory_bytes)
                           and m.get('cpu.max') == expected_cpu and m.get('pids.max') == str(config.container_pids)
                           and m.get('memory.swap.max') == '0' for m in metrics):
            report['status']='enforced'
        report['resource_safety_verified']=False  # enforcement != universal parser safety
    return report


def stop_tree(process):
    if os.name == 'nt':
        if process.poll() is None:
            tool = Path(os.environ.get('SystemRoot', 'C:/Windows')) / 'System32/taskkill.exe'
            result = subprocess.run([str(tool), '/PID', str(process.pid), '/T', '/F'],
                                    capture_output=True, timeout=5, shell=False)
            if result.returncode and process.poll() is None:
                process.kill()
    else:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    process.wait(timeout=5)


def run_child(task, root, config, timeout):
    """One native child at a time; no pickle from an engine process."""
    if config.adapter == 'docker':
        from .docker_sandbox import run_docker_child
        return run_docker_child(task, root, config, timeout)
    root.mkdir(mode=0o700)
    spec = root / 'task.json'
    output = root / 'response.json'
    spec.write_text(json.dumps(task | {'config': asdict(config)}), encoding='utf-8')
    started = time.monotonic()
    with (root / 'stdout.log').open('wb') as stdout, (root / 'stderr.log').open('wb') as stderr:
        process = subprocess.Popen([sys.executable, '-m', 'cv_compass_worker.child', str(spec), str(output)],
                                   stdin=subprocess.DEVNULL, stdout=stdout, stderr=stderr,
                                   env=safe_env(), cwd=root, start_new_session=os.name != 'nt',
                                   shell=False)
        failure = None
        try:
            while True:
                files = list(root.rglob('*'))
                sizes = [(p, p.stat().st_size) for p in files if p.is_file()]
                document_bytes = sum(p.stat().st_size for p in root.parent.rglob('*') if p.is_file())
                engine_bytes = sum(n for p, n in sizes if p.suffix in ('.txt', '.tsv', '.log'))
                if document_bytes > config.max_temp_bytes:
                    failure = 'TEMP_LIMIT'
                elif engine_bytes > config.max_engine_output_bytes:
                    failure = 'OUTPUT_LIMIT'
                elif time.monotonic() - started >= timeout:
                    failure = 'PAGE_TIMEOUT' if task['action'] == 'page' else 'DOCUMENT_TIMEOUT'
                if failure or process.poll() is not None:
                    break
                time.sleep(0.02)
        finally:
            stop_tree(process)
        if failure:
            raise ChildFailure(failure)
        if process.returncode != 0 or not output.is_file():
            raise ChildFailure('ENGINE_CRASH')
        if output.stat().st_size > config.max_engine_output_bytes:
            raise ChildFailure('OUTPUT_LIMIT')
        try:
            response = json.loads(output.read_text(encoding='utf-8'))
            if not isinstance(response, dict) or set(response) - {'value', 'error'}:
                raise ValueError()
            if 'error' in response:
                from .contracts import SCHEMA
                if response['error'] not in SCHEMA['$defs']['error']['properties']['code']['enum']:
                    raise ValueError()
                raise ChildFailure(response['error'])
            return response['value']
        except (ValueError, KeyError, UnicodeError):
            raise ChildFailure('ENGINE_CRASH') from None
