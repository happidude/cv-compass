"""Verification cleanup authority is an inspected per-run owner, never a delta."""
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from cv_compass_worker import docker_sandbox as ds
from cv_compass_worker.sandbox import ChildFailure
from test_pipeline import ROOT

sys.path.insert(0,str(ROOT/'spikes/m6_2_hardening'))
import verify_baseline as verifier


def state(run_id='a'*32):
    return {'version':1,'run_id':run_id,'endpoint':'unix:///test.sock'}


class VerificationOwnershipTests(unittest.TestCase):
    def setUp(self):
        token=ds._target.set(('unix:///test.sock',{}))
        self.addCleanup(ds._target.reset,token)
        self.calls=[]
        self.owner=state()
        self.resources={
            'c-own':('container','ocr-child','a'*32),
            'c-assets':('container','ocr-assets','a'*32),
            'c-writer':('container','ocr-writer','a'*32),
            'c-other':('container','ocr-child','b'*32),
            'c-legacy':('container','ocr-child',None),
            'v-own':('volume','ocr-input','a'*32),
            'v-other':('volume','ocr-input','b'*32)}

    def command(self,args,**kwargs):
        self.calls.append(args)
        if args[0]=='ps' or args[:2]==['volume','ls']:
            # Deliberately return foreign/legacy IDs too: cleanup must inspect.
            kind='container' if args[0]=='ps' else 'volume'
            return ' '.join(k for k,v in self.resources.items() if v[0]==kind).encode()
        if args[0]=='inspect' or args[:2]==['volume','inspect']:
            identity=args[-1]; kind,scope,owner=self.resources[identity]
            labels={'cv-compass.scope':scope}
            if owner is not None:
                labels[ds.RUN_LABEL]=owner
            info=({'Id':identity,'Config':{'Labels':labels},'State':{'Running':False,'Status':'created'}}
                  if kind=='container' else {'Name':identity,'Labels':labels})
            return json.dumps([info]).encode()
        if args[0]=='rm' or args[:2]==['volume','rm']:
            del self.resources[args[-1]]
            return b''
        self.fail('Unexpected command '+str(args))

    def test_only_inspected_run_owner_removed_in_container_before_volume_order(self):
        with patch.object(ds,'command',side_effect=self.command):
            self.assertEqual(verifier.cleanup(self.owner),[])
        self.assertEqual(set(self.resources),{'c-other','c-legacy','v-other'})
        discoveries=[c for c in self.calls if c[0]=='ps' or c[:2]==['volume','ls']]
        self.assertTrue(all('label='+ds.RUN_LABEL+'='+'a'*32 in c for c in discoveries))
        removals=[c[-1] for c in self.calls if c[0]=='rm' or c[:2]==['volume','rm']]
        self.assertEqual(removals,['c-own','c-assets','c-writer','v-own'])

    def test_remove_failure_reported_and_other_owner_never_removed(self):
        def command(args,**kwargs):
            if args[0]=='rm' and args[-1]=='c-own':
                raise ChildFailure('INTERNAL_ERROR')
            return self.command(args,**kwargs)
        with patch.object(ds,'command',side_effect=command):
            self.assertEqual(verifier.cleanup(self.owner),['c-own'])
        self.assertEqual(set(self.resources),{'c-own','c-other','c-legacy','v-other'})

    def test_inventory_and_inspect_failure_are_not_empty_success(self):
        with patch.object(ds,'command',side_effect=OSError('synthetic daemon unavailable')):
            self.assertEqual(verifier.cleanup(self.owner),['container:inventory-unverified','volume:inventory-unverified'])
        def command(args,**kwargs):
            if args==['inspect','c-own']:
                raise ChildFailure('INTERNAL_ERROR')
            return self.command(args,**kwargs)
        with patch.object(ds,'command',side_effect=command):
            self.assertIn('c-own',verifier.cleanup(self.owner))
        self.assertIn('c-own',self.resources)

    def test_invalid_or_legacy_state_and_wrong_endpoint_fail_closed(self):
        for invalid in ({'asset':'cvcompass-assets-old','ocr-child':[]},state(''),state('other')):
            with patch.object(ds,'command') as command,self.assertRaises(ValueError):
                verifier.cleanup(invalid)
            command.assert_not_called()
        with patch.object(ds,'command') as command,self.assertRaises(ValueError):
            verifier.cleanup(self.owner | {'endpoint':'unix:///another.sock'})
        command.assert_not_called()

    def test_cleanup_only_uses_persisted_owner_and_exit_reports_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp).resolve(); out=root/'build/gate'; out.mkdir(parents=True)
            (out/'resources.owner.json').write_text(json.dumps(self.owner))
            for fail in (False,True):
                def command(args,**kwargs):
                    if fail and args[0]=='rm':
                        raise OSError('synthetic remove failure')
                    return self.command(args,**kwargs)
                # Recreate own container between attempts; preserve foreign run.
                self.resources['c-own']=('container','ocr-child','a'*32)
                with patch.object(verifier,'ROOT',root),patch.object(sys,'argv',['verify','--build',str(out),'--cleanup-only']),patch.object(ds,'command',side_effect=command):
                    with self.assertRaises(SystemExit) as result:
                        verifier.main()
                self.assertEqual(bool(result.exception.code),fail)
                report=json.loads((out/'verification/cleanup.json').read_text())
                self.assertEqual(report['run_id'],'a'*32)
                self.assertEqual(bool(report['leftovers']),fail)
                self.assertIn('c-other',self.resources)
                self.assertIn('v-other',self.resources)

    def test_input_owner_label_and_invalid_owner_fail_before_creation(self):
        from test_pipeline import config
        with patch.dict(os.environ,{ds.RUN_ENV:'a'*32}):
            self.assertEqual(ds.resource_labels(),['--label',ds.RUN_LABEL+'='+'a'*32])
            with patch.object(ds,'command',side_effect=ChildFailure('INTERNAL_ERROR')) as cmd:
                with self.assertRaises(ChildFailure):
                    ds._prepare_control({},None,config(),None)
            self.assertIn(ds.RUN_LABEL+'='+'a'*32,cmd.call_args_list[0].args[0])
        with patch.dict(os.environ,{ds.RUN_ENV:'invalid'}),patch.object(ds,'command') as cmd:
            with self.assertRaises(ChildFailure):
                ds._prepare_control({},None,config(),None)
            cmd.assert_not_called()

    def test_cleanup_evidence_failure_is_non_success_even_when_resources_removed(self):
        with tempfile.TemporaryDirectory() as temp,patch.object(ds,'command',side_effect=self.command),patch.object(Path,'write_text',side_effect=OSError('synthetic disk full')):
            with patch.object(sys,'stderr') as err:
                self.assertEqual(verifier.cleanup_report(self.owner,Path(temp)),['cleanup-report:write-failed'])
            self.assertTrue(err.write.called)
            self.assertEqual(set(self.resources),{'c-other','c-legacy','v-other'})

    def test_cleanup_only_missing_owner_with_evidence_refuses_legacy_cleanup(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp).resolve(); out=root/'build/gate'; (out/'verification').mkdir(parents=True)
            (out/'resources.before.json').write_text(json.dumps({'ocr-child':[]}))
            with patch.object(verifier,'ROOT',root),patch.object(sys,'argv',['verify','--build',str(out),'--cleanup-only']),patch.object(ds,'command') as command:
                with self.assertRaises(SystemExit) as result:
                    verifier.main()
                self.assertTrue(result.exception.code)
            command.assert_not_called()


class RealVerificationOwnershipTests(unittest.TestCase):
    def test_two_real_runs_cleanup_only_does_not_touch_other_run(self):
        from test_docker_enforcement import scope_config
        owners=[]; resources=[]
        with ds.docker_session():
            endpoint=ds.check_local_daemon()[0]
            try:
                for _ in range(2):
                    import uuid
                    owner=state(uuid.uuid4().hex) | {'endpoint':endpoint}
                    owners.append(owner)
                    with patch.dict(os.environ,{ds.RUN_ENV:owner['run_id']}):
                        with patch.object(ds,'command',wraps=ds.command) as commands:
                            volume=ds.prepare_control({},None,scope_config())
                        writer_create=next(call.args[0] for call in commands.call_args_list
                            if call.args[0][0]=='create')
                        self.assertIn(ds.RUN_LABEL+'='+owner['run_id'],writer_create)
                        name=ds.create_scope(scope_config(),volume)
                        asset='cvcompass-assets-'+owner['run_id']
                        ds.command(['create','--name',asset,'--label','cv-compass.scope=ocr-assets',
                            *ds.resource_labels(),scope_config().docker_image])
                    for kind,identity in (('container',name),('container',asset),('volume',volume)):
                        info=json.loads(ds.command([*(['volume'] if kind=='volume' else []),'inspect',identity]))[0]
                        labels=info['Config']['Labels'] if kind=='container' else info['Labels']
                        self.assertEqual(labels[ds.RUN_LABEL],owner['run_id'])
                    resources.append((name,asset,volume))
                with tempfile.TemporaryDirectory() as temp:
                    root=Path(temp).resolve(); out=root/'build/gate'; out.mkdir(parents=True)
                    (out/'resources.owner.json').write_text(json.dumps(owners[0]))
                    with patch.object(verifier,'ROOT',root),patch.object(sys,'argv',['verify','--build',str(out),'--cleanup-only']):
                        with self.assertRaises(SystemExit) as result:
                            verifier.main()
                    self.assertFalse(result.exception.code)
                for kind in ('container','volume'):
                    args=['ps','-aq'] if kind=='container' else ['volume','ls','-q']
                    self.assertEqual(ds.command([*args,'--filter','label='+ds.RUN_LABEL+'='+owners[0]['run_id']]).strip(),b'')
                other=resources[1]
                for identity in other[:2]:
                    self.assertTrue(json.loads(ds.command(['inspect',identity])))
                self.assertTrue(json.loads(ds.command(['volume','inspect',other[2]])))
            finally:
                failures=[]
                for owner in owners:
                    failures.extend(verifier.cleanup(owner))
                self.assertEqual(failures,[])


if __name__=='__main__':
    unittest.main()
