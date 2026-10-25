"""Real Docker enforcement. Missing prerequisites fail tests, never skip."""
from dataclasses import replace
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from cv_compass_worker.config import Config
from cv_compass_worker.docker_sandbox import run_scope, create_scope, prepare_control, execute_scope, command, docker_env, docker_cli
from test_pipeline import config, request
from fixtures import native


def scope_config(**values):
    image=os.environ['OCR_TEST_DOCKER_IMAGE']
    return config(adapter='docker',docker_image=image,**values)


def fields(metrics, name):
    return dict(line.split() for line in metrics[name].splitlines())


class DockerEnforcementTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='cv-ocr-enforcement-')
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)

    def probe(self, action, timeout=5, **limits):
        cfg=scope_config(**limits)
        result=run_scope({'action':'page','probe_command':['/opt/cv-venv/bin/python','/opt/worker/tests/resource_helper.py',action]},self.root/action,cfg,timeout)
        if directory:=os.environ.get('OCR_TEST_REPORT_DIR'):
            target=Path(directory)/('isolation-'+action+'.json')
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_text(json.dumps(result,indent=2),encoding='utf-8')
        return result

    def test_real_staging_source_failure_reaps_writer_and_volume(self):
        before_writers=command(['ps','-aq','--filter','label=cv-compass.scope=ocr-writer'])
        before_volumes=command(['volume','ls','-q','--filter','label=cv-compass.scope=ocr-input'])
        with self.assertRaises(FileNotFoundError):
            prepare_control({},self.root/'missing.pdf',scope_config(),self.root)
        self.assertEqual(command(['ps','-aq','--filter','label=cv-compass.scope=ocr-writer']),before_writers)
        self.assertEqual(command(['volume','ls','-q','--filter','label=cv-compass.scope=ocr-input']),before_volumes)

    def test_limits_readonly_network_and_environment(self):
        result=self.probe('permissions')
        self.assertEqual(result['result']['value'],{'writes_refused':[True,True],'network_refused':True,'credentials_absent':True})
        metrics=result['metrics']
        self.assertEqual(metrics['memory.max'],'805306368')
        self.assertEqual(metrics['memory.swap.max'],'0')
        self.assertEqual(metrics['cpu.max'],'200000 100000')
        self.assertEqual(metrics['pids.max'],'64')
        self.assertGreater(int(metrics['memory.peak']),0)

    def test_oom_child_scope_supervisor_survives(self):
        result=self.probe('oom',container_memory_bytes=67108864)
        self.assertEqual(result['result']['error'],'RESOURCE_LIMIT')
        metrics=result['metrics']
        if not metrics.get('oom_killed'):
            self.assertGreater(int(fields(metrics,'memory.events')['oom_kill']),0)
            self.assertGreater(int(metrics['memory.peak']),0)
        # Test runner/supervisor is still executing outside the OOM scope.
        self.assertTrue(self.root.is_dir())

    def test_pid_limit_is_enforced(self):
        result=self.probe('pids',container_pids=8)
        self.assertEqual(result['result']['error'],'RESOURCE_LIMIT')
        self.assertGreater(int(fields(result['metrics'],'pids.events')['max']),0)

    def test_cpu_quota_throttles_bounded_workers(self):
        result=self.probe('cpu',timeout=12,container_cpu_millicores=500)
        self.assertTrue(result['result']['value']['cpu_done'])
        self.assertEqual(result['metrics']['cpu.max'],'50000 100000')
        self.assertGreater(int(fields(result['metrics'],'cpu.stat')['nr_throttled']),0)

    def test_hard_tmpfs_enospc(self):
        self.assertEqual(self.probe('temp',max_temp_bytes=8*1024*1024)['result']['error'],'TEMP_LIMIT')

    def test_hard_engine_output_quota(self):
        result=self.probe('output_quota',max_engine_output_bytes=4096)
        self.assertEqual(result['result']['error'],'OUTPUT_LIMIT')
        self.assertEqual(result['metrics']['engine_free_bytes'],0)

    def test_output_cap(self):
        self.assertEqual(self.probe('output',max_engine_output_bytes=4096)['result']['error'],'OUTPUT_LIMIT')

    def test_timeout_descendant_scope_is_reaped(self):
        result=self.probe('descendant',timeout=.8)
        self.assertEqual(result['result']['error'],'PAGE_TIMEOUT')
        self.assertGreaterEqual(int(result['metrics']['pids.peak']),3)

    def test_crash_with_detached_descendant(self):
        result=self.probe('crash_descendant')
        self.assertEqual(result['result']['error'],'ENGINE_CRASH')
        self.assertGreaterEqual(int(result['metrics']['pids.peak']),3)

    def test_supervisor_kill_watchdog_bounds_container_lifetime(self):
        cfg=scope_config()
        # Trusted parent prepares the scope, then a separate supervisor owns
        # attach. Killing that supervisor cannot remove the in-container timer.
        task={'action':'page','probe_command':['/opt/cv-venv/bin/python','/opt/worker/tests/resource_helper.py','descendant'],
              'config':dict(max_engine_output_bytes=cfg.max_engine_output_bytes),'wall_timeout':1.5}
        volume=prepare_control(task,None,cfg)
        name=create_scope(cfg,volume)
        cli,env=docker_cli(['start','--attach',name])
        script='import subprocess,sys; subprocess.run(sys.argv[1:],stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)'
        supervisor=subprocess.Popen([sys.executable,'-c',script,*cli],env=env)
        try:
            deadline=time.monotonic()+8
            while time.monotonic()<deadline:
                info=json.loads(command(['inspect',name]))[0]
                if info['State']['Running']: break
                time.sleep(.05)
            self.assertTrue(info['State']['Running'])
            supervisor.kill(); supervisor.wait(timeout=5)
            command(['wait',name],timeout=10)
            info=json.loads(command(['inspect',name]))[0]
            self.assertFalse(info['State']['Running'])
            self.assertEqual(info['State']['Pid'],0)
            self.assertEqual(info['State']['ExitCode'],0)
            if directory:=os.environ.get('OCR_TEST_REPORT_DIR'):
                (Path(directory)/'isolation-supervisor-death.json').write_text(json.dumps({
                    'running':info['State']['Running'],'pid':info['State']['Pid'],
                    'exit_code':info['State']['ExitCode'],'watchdog_seconds':1.5,
                    'stopped_container_and_volume_retained_until_test_owner_cleanup':True},indent=2))
            # Stopped scope/readonly input volume remain after hard supervisor
            # death; test owner cleans them. No crash-recovery guarantee.
        finally:
            if supervisor.poll() is None: supervisor.kill(); supervisor.wait()
            command(['rm','-f',name]); command(['volume','rm',volume])

    def test_real_pdf_pipeline_uses_isolated_container(self):
        from cv_compass_worker.pipeline import process
        from cv_compass_worker.contracts import validate_result
        source=self.root/('d'*32+'.pdf'); native(source)
        before=source.read_bytes(); diagnostics=[]
        cfg=scope_config(preflight_timeout=20,page_timeout=20)
        result=process(request(source),self.root,cfg,diagnostics)
        validate_result(result,cfg)
        self.assertEqual(result['status'],'success',result['errors'])
        self.assertEqual(result['pages'][0]['method'],'native')
        self.assertEqual(source.read_bytes(),before)
        self.assertEqual(len(diagnostics),2)
        self.assertTrue(all(d.get('isolation') for d in diagnostics))
        packet_root=self.root/'private-ipc'
        packet=run_scope({'action':'page','number':1},packet_root,cfg,20,source=source)
        self.assertIn('Synthetic native',packet['result']['value']['raw_text'])
        self.assertTrue((packet_root/'container.response.json').exists())
        for log in packet_root.glob('*.log'):
            data=log.read_text(encoding='utf-8')
            for forbidden in ('Synthetic native',source.name,str(source),'DB_PASSWORD'):
                self.assertNotIn(forbidden,data)


if __name__=='__main__': unittest.main()
