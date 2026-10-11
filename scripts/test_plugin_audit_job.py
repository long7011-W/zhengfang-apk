import importlib.util,io,json,os,pathlib,tempfile,unittest,zipfile
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('audit_job',pathlib.Path(__file__).with_name('plugin_audit_job.py'));job=importlib.util.module_from_spec(spec);spec.loader.exec_module(job)
class AuditBootstrapTest(unittest.TestCase):
 def archive(self,name='sdk/index.d.ts',symlink=False):
  data=io.BytesIO()
  with zipfile.ZipFile(data,'w') as z:
   info=zipfile.ZipInfo(name)
   if symlink:info.external_attr=(0o120777<<16)
   z.writestr(info,'synthetic')
  return data.getvalue()
 def test_extract_rejects_path_escape_and_symlinks(self):
  with tempfile.TemporaryDirectory() as tmp:
   for name,link in [('../escape',False),('/escape',False),('sdk/link',True)]:
    with self.subTest(name=name),self.assertRaises(RuntimeError):job.extract_kit(self.archive(name,link),pathlib.Path(tmp))
 def test_safe_toolchain_extracts(self):
  with tempfile.TemporaryDirectory() as tmp:
   job.extract_kit(self.archive(),pathlib.Path(tmp));self.assertEqual((pathlib.Path(tmp)/'sdk/index.d.ts').read_text(),'synthetic')
 def test_no_execution_before_expected_digest(self):
  meta={'url':job.ORIGIN+'/downloads/plugin-starter-v3.zip','sha256':'0'*64}
  with patch.dict(os.environ,{'AUDIT_SELF_TEST':'true'}),patch.object(job,'http',side_effect=[json.dumps(meta).encode(),b'wrong']),patch.object(job.subprocess,'run') as run:
   with self.assertRaisesRegex(RuntimeError,'TOOLCHAIN_DIGEST'):job.main()
   run.assert_not_called()
 def test_server_cannot_redirect_toolchain_to_arbitrary_host(self):
  with patch.dict(os.environ,{'AUDIT_SELF_TEST':'true'}),patch.object(job,'http',return_value=json.dumps({'url':'https://example.test/evil','sha256':'0'*64}).encode()):
   with self.assertRaisesRegex(RuntimeError,'TOOLCHAIN_METADATA'):job.main()
 def test_task_fields_are_opaque_validated_ids(self):
  with patch.dict(os.environ,{'AUDIT_DRAFT_ID':'$(echo unsafe)','AUDIT_NONCE':'x'*43}):
   with self.assertRaisesRegex(RuntimeError,'TASK_INPUT_INVALID'):job.task()
if __name__=='__main__':unittest.main()
