"""Regression tests for hardening review1. All Docker failure paths here injected."""
import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from cv_compass_worker.docker_sandbox import prepare_control,run_scope
from cv_compass_worker.sandbox import ChildFailure
from cv_compass_worker.pipeline import process
from cv_compass_worker.contracts import validate_result
from fixtures import native
from test_pipeline import config,request,ROOT


class HardeningReviewTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)

    def test_remote_endpoint_refused_before_staging(self):
        with patch.dict(os.environ,{'DOCKER_HOST':'tcp://untrusted.invalid:2375'}),patch('shutil.which',return_value='docker'),patch('cv_compass_worker.docker_sandbox.command') as command:
            with self.assertRaises(ChildFailure) as caught:
                prepare_control({},self.root/'not-opened.pdf',config())
        self.assertEqual(caught.exception.code,'INVALID_CONFIGURATION')
        command.assert_not_called()

    def test_cleanup_does_not_mask_primary_failure(self):
        with patch('cv_compass_worker.docker_sandbox.prepare_control',return_value='owned-volume'),patch('cv_compass_worker.docker_sandbox.create_scope',return_value='owned-scope'),patch('cv_compass_worker.docker_sandbox.execute_scope',side_effect=ChildFailure('OCR_FAILED')),patch('cv_compass_worker.docker_sandbox.command',side_effect=ChildFailure('INTERNAL_ERROR')):
            with self.assertRaises(ChildFailure) as caught:
                run_scope({'action':'page'},self.root/'child',config(),1)
        self.assertEqual(caught.exception.code,'OCR_FAILED')
        self.assertTrue((self.root/'child/scope-cleanup.json').is_file())

    def test_ledger_write_failure_does_not_replace_primary(self):
        with patch('cv_compass_worker.docker_sandbox.prepare_control',return_value='owned-volume'),patch('cv_compass_worker.docker_sandbox.create_scope',return_value='owned-scope'),patch('cv_compass_worker.docker_sandbox.execute_scope',side_effect=ChildFailure('OCR_FAILED')),patch('cv_compass_worker.docker_sandbox.command',side_effect=ChildFailure('INTERNAL_ERROR')),patch.object(Path,'write_text',side_effect=OSError('synthetic disk full')):
            with self.assertRaises(ChildFailure) as caught:
                run_scope({'action':'page'},self.root/'child',config(),1)
        self.assertEqual(caught.exception.code,'OCR_FAILED')
        self.assertTrue(caught.exception.cleanup_failed)

    def test_cleanup_preserves_successful_value(self):
        envelope={'result':{'value':{'unchanged':'synthetic'}},'metrics':{}}
        with patch('cv_compass_worker.docker_sandbox.prepare_control',return_value='owned-volume'),patch('cv_compass_worker.docker_sandbox.create_scope',return_value='owned-scope'),patch('cv_compass_worker.docker_sandbox.execute_scope',return_value=envelope),patch('cv_compass_worker.docker_sandbox.command',side_effect=ChildFailure('INTERNAL_ERROR')):
            result=run_scope({'action':'page'},self.root/'child',config(),1)
        self.assertEqual(result['result'],{'value':{'unchanged':'synthetic'}})
        self.assertTrue(result['metrics']['cleanup_failed'])

    def test_scope_cleanup_halts_without_changing_completed_page(self):
        source=self.root/('1'*32+'.pdf');native(source)
        example=json.loads((ROOT/'design/examples-v2/result-success.json').read_text(encoding='utf-8'))
        good=example['pages'][0]
        def child(task,directory,cfg,timeout):
            directory.mkdir()
            if task['action']=='preflight': return {'page_count':2,'engines':example['engines']}
            (directory/'scope-cleanup.json').write_text('["owned-scope"]')
            return copy.deepcopy(good)
        with patch('cv_compass_worker.pipeline.run_child',side_effect=child) as calls:
            result=process(request(source),self.root,config())
        validate_result(result,config())
        self.assertEqual(calls.call_count,2)
        self.assertEqual(result['pages'][0],good)
        self.assertEqual(result['pages'][1]['status'],'not_processed')
        self.assertEqual(result['status'],'partial')
        self.assertIn('CLEANUP_FAILED',result['warnings'])
        self.assertTrue((self.root/'.ocr-lock').is_file())


if __name__=='__main__': unittest.main()
