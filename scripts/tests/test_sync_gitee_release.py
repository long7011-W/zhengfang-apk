import base64
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from sync_gitee_release import sync_release


class FakeGitee:
    def __init__(self, assets=False, lost_upload=False, stored_after_error=False, code=95):
        self.assets = assets
        self.lost_upload = lost_upload
        self.stored_after_error = stored_after_error
        self.calls = []
        self.code = code

    def request(self, method, path, payload=None, upload=None, allow_missing=False):
        self.calls.append((method, path))
        if method == 'GET' and path.startswith('contents/version.json'):
            return {'sha': 'old', 'content': base64.b64encode(json.dumps({'versionCode': self.code}).encode()).decode()}
        if path.startswith('releases/tags/'):
            return {'id': 123}
        if path.endswith('/attach_files'):
            if method == 'GET':
                return [{'name': 'app-release.apk'}] if self.assets else []
            if self.lost_upload:
                self.assets = self.stored_after_error
                raise RuntimeError('upload response lost')
            self.assets = True
            return {'name': 'app-release.apk'}
        return {}


class GiteeSyncTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / 'app-release.apk'
        self.apk.write_bytes(b'signed-test-fixture')

    def run_sync(self, client, error=None):
        with patch('sync_gitee_release.verify_apk', side_effect=error, return_value='https://gitee.example/app.apk'):
            sync_release(client, 'v1.0.97', self.apk, 'Release notes')

    def test_matching_existing_asset_is_not_uploaded_again(self):
        client = FakeGitee(assets=True)
        self.run_sync(client)
        self.assertNotIn(('POST', 'releases/123/attach_files'), client.calls)
        self.assertEqual(client.calls[-1], ('PUT', 'contents/version.json'))

    def test_different_existing_asset_cannot_update_metadata(self):
        client = FakeGitee(assets=True)
        with self.assertRaises(RuntimeError):
            self.run_sync(client, RuntimeError('digest mismatch'))
        self.assertFalse(any(method == 'PUT' for method, _ in client.calls))

    def test_new_upload_is_verified_before_metadata(self):
        client = FakeGitee()
        self.run_sync(client)
        self.assertEqual(client.calls.count(('POST', 'releases/123/attach_files')), 1)
        self.assertEqual(client.calls[-1], ('PUT', 'contents/version.json'))

    def test_lost_response_does_not_repeat_successfully_stored_upload(self):
        client = FakeGitee(lost_upload=True, stored_after_error=True)
        self.run_sync(client)
        self.assertEqual(client.calls.count(('POST', 'releases/123/attach_files')), 1)

    def test_failed_upload_leaves_update_metadata_unchanged(self):
        client = FakeGitee(lost_upload=True)
        with self.assertRaises(RuntimeError):
            self.run_sync(client)
        self.assertFalse(any(method == 'PUT' for method, _ in client.calls))

    def test_newer_published_version_is_never_downgraded(self):
        client = FakeGitee(code=98)
        with self.assertRaises(ValueError):
            self.run_sync(client)
        self.assertTrue(all(method == 'GET' for method, _ in client.calls))


if __name__ == '__main__':
    unittest.main()
