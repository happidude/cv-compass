"""PR9 endpoint, staging-lifecycle, in-memory cleanup and clean-context regressions."""
from contextlib import contextmanager
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
from cv_compass_worker import docker_sandbox as ds
from cv_compass_worker.sandbox import ChildFailure
from cv_compass_worker.pipeline import process
from test_pipeline import config, request, ROOT
from fixtures import native


class EndpointTests(unittest.TestCase):
    def resolve(self, env, context_host):
        calls=[]
        def run(args,**kwargs):
            calls.append((args,kwargs['env']))
            if args[1:3] == ['context','inspect']:
                return SimpleNamespace(returncode=0,stdout=json.dumps([{'Endpoints':{'docker':{'Host':context_host}}}]).encode())
            return SimpleNamespace(returncode=0,stdout=b'linux 2\n')
        with patch.dict(os.environ,env,clear=True),patch('shutil.which',return_value='docker'),patch.object(ds.subprocess,'run',side_effect=run):
            target=ds.resolve_target()
        return target,calls

    def test_remote_context_overrides_local_host(self):
        with self.assertRaises(ChildFailure) as err:
            self.resolve({'DOCKER_CONTEXT':'remote','DOCKER_HOST':'unix:///var/run/docker.sock'},'ssh://remote')
        self.assertEqual(err.exception.code,'INVALID_CONFIGURATION')

    def test_local_context_overrides_remote_host_and_is_pinned(self):
        target,calls=self.resolve({'DOCKER_CONTEXT':'local','DOCKER_HOST':'tcp://remote:2375'},'unix:///local.sock')
        self.assertEqual(target[0],'unix:///local.sock')
        self.assertEqual(calls[0][0],['docker','context','inspect','local'])
        token=ds._target.set(target)
        try:
            with patch.dict(os.environ,{'DOCKER_CONTEXT':'remote','DOCKER_HOST':'ssh://other'}):
                cli,env=ds.docker_cli(['start','owned'])
                self.assertEqual(cli,['docker','--host','unix:///local.sock','start','owned'])
                self.assertNotIn('DOCKER_CONTEXT',env); self.assertNotIn('DOCKER_HOST',env)
        finally:
            ds._target.reset(token)

    def test_host_overrides_default_context(self):
        target,calls=self.resolve({'DOCKER_HOST':'unix:///host.sock'},'ssh://default')
        self.assertEqual(target[0],'unix:///host.sock')
        self.assertEqual(len(calls),1)

    def test_remote_host_rejected_before_resources_or_input(self):
        with patch.dict(os.environ,{'DOCKER_HOST':'tcp://remote:2375'},clear=True),patch('shutil.which',return_value='docker'),patch.object(ds,'command') as cmd,patch.object(Path,'open') as opened:
            with self.assertRaises(ChildFailure):
                ds.prepare_control({},Path('/must-not-open'),config())
            cmd.assert_not_called(); opened.assert_not_called()

    def test_default_context_resolution(self):
        target,calls=self.resolve({},'unix:///default.sock')
        self.assertEqual(target[0],'unix:///default.sock')
        self.assertEqual(calls[0][0],['docker','context','inspect'])

    def test_explicit_default_context_keeps_host_for_metadata_only(self):
        def run(args,**kwargs):
            endpoint=kwargs['env'].get('DOCKER_HOST','unix:///default.sock')
            if args[1:3]==['context','inspect']:
                return SimpleNamespace(returncode=0,stdout=json.dumps([{'Endpoints':{'docker':{'Host':endpoint}}}]).encode())
            self.fail('Remote endpoint must be rejected before info/resource commands')
        with patch.dict(os.environ,{'DOCKER_CONTEXT':'default','DOCKER_HOST':'tcp://untrusted.invalid:2375'},clear=True),patch('shutil.which',return_value='docker'),patch.object(ds.subprocess,'run',side_effect=run):
            with self.assertRaises(ChildFailure) as err:
                ds.resolve_target()
        self.assertEqual(err.exception.code,'INVALID_CONFIGURATION')

    def test_real_cli_explicit_default_remote_host_rejected_without_daemon_call(self):
        with patch.dict(os.environ,{'DOCKER_CONTEXT':'default','DOCKER_HOST':'tcp://untrusted.invalid:2375'}),patch.object(ds,'_invoke') as invoke:
            with self.assertRaises(ChildFailure) as err:
                ds.resolve_target()
        self.assertEqual(err.exception.code,'INVALID_CONFIGURATION')
        invoke.assert_not_called()

    def test_pipeline_rejects_remote_before_snapshot_or_lock(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); source=root/('a'*32+'.pdf'); native(source)
            with patch.dict(os.environ,{'DOCKER_HOST':'tcp://remote'},clear=True),patch('shutil.which',return_value='docker'),patch('cv_compass_worker.pipeline.snapshot') as snapshot:
                result=process(request(source),root,config(adapter='docker',docker_image='sha256:'+'a'*64))
            snapshot.assert_not_called()
            self.assertEqual(result['errors'][0]['code'],'INVALID_CONFIGURATION')
            self.assertFalse((root/'.ocr-lock').exists())


class StagingTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        self.commands=[]

    def cmd(self,args,**kwargs):
        self.commands.append(args)
        if args[0]=='inspect':
            return json.dumps([{'State':{'Running':True,'Status':'running'}}]).encode()
        return b''

    @contextmanager
    def staging(self, popen, command=None):
        token=ds._target.set(('unix:///test.sock',{}))
        try:
            with patch.object(ds,'command',side_effect=command or self.cmd),patch.object(ds.subprocess,'Popen',popen):
                yield
        finally:
            ds._target.reset(token)

    def fake_process(self,write_error=None,timeout=False):
        proc=Mock()
        proc.stdin=Mock()
        proc.stdin.write.side_effect=write_error
        proc.returncode=0
        proc.poll.return_value=0
        proc.wait.side_effect=[subprocess.TimeoutExpired('writer',20),0] if timeout else None
        return proc

    def assert_order(self):
        ops=[c[0] for c in self.commands]
        kill,wait,rm,volume_rm=ops.index('kill'),ops.index('wait'),ops.index('rm'),next(i for i,c in enumerate(self.commands) if c[:2]==['volume','rm'])
        self.assertLess(kill,wait); self.assertLess(wait,rm); self.assertLess(rm,volume_rm)

    def test_popen_failure_removes_daemon_writer_before_volume(self):
        with self.staging(Mock(side_effect=OSError('synthetic spawn'))):
            with self.assertRaisesRegex(OSError,'synthetic spawn'):
                ds.prepare_control({},None,config(),self.root)
        self.assert_order()

    def test_stdin_failure_keeps_primary_and_cleans_tree(self):
        with self.staging(Mock(return_value=self.fake_process(BrokenPipeError('synthetic pipe')))):
            with self.assertRaises(BrokenPipeError):
                ds.prepare_control({},None,config(),self.root)
        self.assert_order()

    def test_timeout_kills_waits_removes_writer_before_volume(self):
        with self.staging(Mock(return_value=self.fake_process(timeout=True))):
            with self.assertRaises(ChildFailure) as err:
                ds.prepare_control({},None,config(),self.root)
        self.assertEqual(err.exception.code,'DOCUMENT_TIMEOUT'); self.assert_order()

    def test_cleanup_failure_keeps_primary_flag_without_ledger(self):
        def failing(args,**kwargs):
            if args[0]=='kill':
                raise ChildFailure('INTERNAL_ERROR')
            return self.cmd(args,**kwargs)
        with self.staging(Mock(side_effect=OSError('primary')),failing),patch.object(Path,'write_text',side_effect=OSError('disk full')):
            with self.assertRaisesRegex(OSError,'primary') as err:
                ds.prepare_control({},None,config(),self.root)
        self.assertTrue(err.exception.cleanup_failed)
        self.assertTrue(any(x.startswith('cvcompass-input-') for x in err.exception.leftovers))
        self.assertFalse(any(c[:2]==['volume','rm'] for c in self.commands))

    def test_successful_writer_is_removed_volume_retained(self):
        with self.staging(Mock(return_value=self.fake_process())) as _:
            volume=ds.prepare_control({},None,config(),self.root)
        self.assertTrue(volume.startswith('cvcompass-input-'))
        self.assertIn('rm',[c[0] for c in self.commands])
        self.assertFalse(any(c[:2]==['volume','rm'] for c in self.commands))

    def test_volume_creation_failure_is_not_masked_by_cleanup(self):
        primary=ChildFailure('DEPENDENCY_MISSING')
        def failure(args,**kwargs):
            self.commands.append(args)
            raise primary if args[:2]==['volume','create'] else ChildFailure('INTERNAL_ERROR')
        with self.staging(Mock(),failure),patch.object(Path,'write_text',side_effect=OSError('full')):
            with self.assertRaises(ChildFailure) as err:
                ds.prepare_control({},None,config(),self.root)
        self.assertIs(err.exception,primary); self.assertTrue(err.exception.cleanup_failed)

    def test_preparation_failure_reaches_run_scope(self):
        failure=ChildFailure('DOCUMENT_TIMEOUT'); failure.cleanup_failed=True; failure.leftovers=['owned']
        token=ds._target.set(('unix:///test.sock',{}))
        try:
            with patch.object(ds,'prepare_control',side_effect=failure),patch.object(Path,'write_text',side_effect=OSError('full')):
                with self.assertRaises(ChildFailure) as err:
                    ds.run_scope({},self.root/'scope',config(),1)
            self.assertIs(err.exception,failure); self.assertTrue(err.exception.cleanup_failed)
        finally:
            ds._target.reset(token)


class CleanupPropagationTests(unittest.TestCase):
    def test_prepare_failure_flag_keeps_pipeline_lock_when_ledger_unwritable(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); source=root/('a'*32+'.pdf'); native(source)
            failure=ChildFailure('DOCUMENT_TIMEOUT')
            failure.cleanup_failed=True; failure.leftovers=['owned-writer','owned-volume']
            with patch.object(ds,'resolve_target',return_value=('unix:///test.sock',{})),patch.object(ds,'prepare_control',side_effect=failure),patch.object(Path,'write_text',side_effect=OSError('full')):
                result=process(request(source),root,config(adapter='docker',docker_image='sha256:'+'a'*64))
            self.assertEqual(result['errors'][0]['code'],'DOCUMENT_TIMEOUT')
            self.assertIn('CLEANUP_FAILED',result['warnings'])
            self.assertTrue((root/'.ocr-lock').exists())

    def test_successful_page_cleanup_failure_halts_without_ledger(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); source=root/('a'*32+'.pdf'); native(source)
            example=json.loads((ROOT/'design/examples/result-success.json').read_text(encoding='utf-8'))
            def child(task,directory,cfg,timeout):
                directory.mkdir()
                value=ds.ChildValue({'page_count':2,'engines':example['engines']} if task['action']=='preflight' else copy.deepcopy(example['pages'][0]))
                value.cleanup_failed=task['action']=='page'
                return value
            with patch('cv_compass_worker.pipeline.run_child',side_effect=child) as calls:
                result=process(request(source),root,config())
            self.assertEqual(calls.call_count,2)
            self.assertEqual(result['pages'][0]['status'],'ok')
            self.assertEqual(result['pages'][1]['status'],'not_processed')
            self.assertIn('CLEANUP_FAILED',result['warnings']); self.assertTrue((root/'.ocr-lock').exists())

    def test_error_and_success_flags_cross_docker_child(self):
        for response in ({'error':'OCR_FAILED'},{'value':{'x':1}}):
            with self.subTest(response=response),tempfile.TemporaryDirectory() as tmp:
                envelope={'result':response,'metrics':{'cleanup_failed':True,'leftovers':['owned']}}
                with patch.object(ds,'run_scope',return_value=envelope):
                    if 'error' in response:
                        with self.assertRaises(ChildFailure) as err:
                            ds.run_docker_child({'source':'unused'},Path(tmp),config(),1)
                        self.assertTrue(err.exception.cleanup_failed)
                    else:
                        value=ds.run_docker_child({'source':'unused'},Path(tmp),config(),1)
                        self.assertTrue(value.cleanup_failed)

    def test_ledger_and_diagnostics_disk_full_keep_engine_outcome_and_flag(self):
        for response in ({'error':'OCR_FAILED'},{'value':{'x':1}}):
            with self.subTest(response=response),tempfile.TemporaryDirectory() as tmp:
                envelope={'result':response,'metrics':{'cleanup_failed':True,'leftovers':['owned']}}
                with patch.object(ds,'run_scope',return_value=envelope),patch.object(Path,'write_text',side_effect=OSError('full')):
                    if 'error' in response:
                        with self.assertRaises(ChildFailure) as err:
                            ds.run_docker_child({'source':'unused'},Path(tmp),config(),1)
                        self.assertEqual(err.exception.code,'OCR_FAILED')
                        self.assertTrue(err.exception.cleanup_failed)
                    else:
                        self.assertTrue(ds.run_docker_child({'source':'unused'},Path(tmp),config(),1).cleanup_failed)


class BuildContextTests(unittest.TestCase):
    def test_provisioning_checksum_rejects_corrupt_download(self):
        import sys
        with patch.object(sys,'path',[str(ROOT/'spikes/m6_2_hardening'),*sys.path]):
            import build_baseline
        with tempfile.TemporaryDirectory() as tmp,patch.object(build_baseline.urllib.request,'urlopen',return_value=io.BytesIO(b'corrupt')):
            with self.assertRaisesRegex(ValueError,'checksum mismatch'):
                build_baseline.fetch('https://example.invalid/artifact','0'*64,Path(tmp)/'artifact')

    def test_provisioning_model_hashes_match_runtime(self):
        from cv_compass_worker.ocr import MODEL_HASHES,MODEL_REVISION
        spec=json.loads((ROOT/'spikes/m6_2_hardening/provisioning.json').read_text())
        self.assertEqual(spec['models'],MODEL_HASHES)
        self.assertEqual(spec['model_revision'],MODEL_REVISION)

    def test_context_replaces_deleted_source_and_excludes_private_artifacts(self):
        script=ROOT/'spikes/m6_2_hardening/prepare_worker_image.py'
        spec=importlib.util.spec_from_file_location('context_builder',script)
        module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            for folder in ('src','design','tests','spikes/m6_2_hardening'):
                (root/folder).mkdir(parents=True)
            (root/'run_worker.py').write_text('')
            (root/'spikes/m6_2_hardening/Dockerfile.worker').write_text('FROM scratch')
            source=root/'src/removed.py'; source.write_text('old')
            (root/'src/private.pdf').write_bytes(b'not a fixture')
            context=root/'build/context'
            module.prepare(root,context); source.unlink(); module.prepare(root,context)
            self.assertFalse((context/'src/removed.py').exists())
            self.assertFalse((context/'src/private.pdf').exists())


if __name__=='__main__':
    unittest.main()
