import copy
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock,patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from release_distribution import Budget,DeliveryError,REPO
from reuse_test_artifact import validate_origin
from sync_gitee_test import sync_test
from test_release_distribution import payload
from promote_release import verify_run

class RecoveryTests(unittest.TestCase):
    def setUp(self):
        self.run=dict(event='workflow_dispatch',status='completed',conclusion='failure',path='.github/workflows/release.yml',repository={'full_name':REPO},head_repository={'full_name':REPO})
    def test_failed_mirror_can_reuse_successful_build(self): validate_origin(self.run,[dict(name='build',conclusion='success')])
    def test_failed_or_skipped_build_cannot_reuse(self):
        for status in ['failure','skipped','cancelled']:
            with self.assertRaises(DeliveryError): validate_origin(self.run,[dict(name='build',conclusion=status)])
    def test_fork_or_non_test_origin_is_rejected(self):
        for edit in [dict(head_repository={'full_name':'other/repo'}),dict(path='other.yml'),dict(status='in_progress')]:
            with self.assertRaises(DeliveryError): validate_origin(dict(self.run,**edit),[dict(name='build',conclusion='success')])
    def test_completed_repaired_delivery_retains_original_build_binding(self):
        run=dict(self.run,conclusion='success',head_sha='c'*40,id=456)
        receipt=dict(sourceSha='b'*40,buildId='123',deliverySourceSha='c'*40,deliveryRunId='456',tests=dict(tests=1,failures=0,errors=0))
        verify_run(run,receipt)
        with self.assertRaises(DeliveryError): verify_run(dict(run,id=789),receipt)

class TestGiteeTests(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory();self.addCleanup(self.directory.cleanup)
        self.apk=Path(self.directory.name)/'apk';self.apk.write_bytes(b'apk')
        self.p=payload();self.calls=[];self.assets=[];self.lost=False;self.stored=False;self.published=False
    def client(self,method,path,body=None,upload=None,**kwargs):
        self.calls.append((method,path,body))
        if 'contents/' in path: raise AssertionError('Test upload must not touch stable metadata')
        if path.startswith('releases/tags/'): return dict(id=123,prerelease=True) if self.published else None
        if path=='releases':
            self.assertTrue(body['prerelease']);self.assertTrue(body['tag_name'].startswith('test-v1.0.98-'))
            return dict(id=123,prerelease=True)
        if method=='GET': return self.assets
        self.assets=[dict(name='app-release.apk')] if self.stored or not self.lost else []
        if self.lost: raise RuntimeError('response lost')
    def run_sync(self):
        c=Mock();c.request.side_effect=self.client
        with patch('sync_gitee_test.fetch') as fetch:
            report=sync_test(c,self.apk,self.p,'test notes',Budget())
            fetch.assert_called_once();return report
    def test_isolated_prerelease_does_not_change_stable_metadata(self):
        result=self.run_sync();self.assertFalse(result['stableMetadataChanged']);self.assertEqual(result['tag'],'test-v1.0.98-123')
    def test_lost_response_checks_remote_without_second_upload(self):
        self.lost=True;self.stored=True;self.run_sync()
        self.assertEqual(sum(m=='POST' and p.endswith('/attach_files') for m,p,_ in self.calls),1)
    def test_failed_upload_stops_and_does_not_write_metadata(self):
        self.lost=True
        with self.assertRaises(RuntimeError):self.run_sync()
    def test_existing_attachment_is_only_verified(self):
        self.published=True;self.assets=[dict(name='app-release.apk')];self.run_sync()
        self.assertFalse(any(m=='POST' for m,_,_ in self.calls))
    def test_existing_stable_release_cannot_be_repurposed(self):
        c=Mock();c.request.return_value=dict(id=123,prerelease=False)
        with self.assertRaises(DeliveryError):sync_test(c,self.apk,self.p,'notes',Budget())

if __name__=='__main__':unittest.main()
