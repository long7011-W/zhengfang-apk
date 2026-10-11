#!/usr/bin/env python3
"""Publish an explicitly requested test attachment, never stable metadata."""
import argparse
import json
import os
from pathlib import Path
import re
from release_distribution import Budget, DeliveryError, inspect_apk, fetch
from sync_gitee_release import Gitee

def sync_test(client,apk,receipt,notes,budget):
    version=receipt['versionName'];build=receipt['buildId']
    if not re.fullmatch(r'1\.0\.\d+',version) or not re.fullmatch(r'\d{1,24}',build):
        raise DeliveryError('Invalid test version identity')
    tag='test-v'+version+'-'+build
    url='https://gitee.com/znj12345/zhengfang/releases/download/'+tag+'/app-release.apk'
    release=client.request('GET','releases/tags/'+tag,allow_missing=True)
    description=('仅供测试，不是正式发布。覆盖安装前请核对版本。\n\n'+notes+'\n\nSHA-256: '+receipt['sha256']+
        '\n源码: '+receipt['sourceSha']+'\n原始构建: https://github.com/znjhahaha/zhengfang-apk/actions/runs/'+build)
    if release is None:
        release=client.request('POST','releases',dict(tag_name=tag,target_commitish='main',name='测试版 '+version+' · '+build,body=description,prerelease=True))
    if not release.get('prerelease'):
        raise DeliveryError('Existing target is not a prerelease; refusing to change it')
    path=f'releases/{int(release["id"])}/attach_files'
    assets=client.request('GET',path)
    if not any(a.get('name')=='app-release.apk' for a in assets):
        try: client.request('POST',path,upload=apk)
        except RuntimeError:
            assets=client.request('GET',path)
            if not any(a.get('name')=='app-release.apk' for a in assets): raise
    # The public attachment must be the same byte-for-byte tested APK.
    import tempfile
    with tempfile.TemporaryDirectory() as root: fetch(url,Path(root)/'verify.apk',budget,expected=receipt)
    return dict(result='verified',tag=tag,url=url,sha256=receipt['sha256'],size=receipt['size'],stableMetadataChanged=False)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--directory',type=Path,default=Path('delivery'))
    args=parser.parse_args();budget=Budget(170);directory=args.directory;report=dict(result='incomplete',stableMetadataChanged=False)
    try:
        receipt=json.loads((directory/'receipt.json').read_text());apk=directory/'app-release.apk'
        actual=inspect_apk(apk,budget)
        if any(receipt.get(k)!=v for k,v in actual.items()): raise DeliveryError('Test APK receipt mismatch')
        report=sync_test(Gitee(os.environ.get('GITEE_TOKEN',''),budget),apk,receipt,(directory/'release-notes.txt').read_text(),budget)
    except Exception as e:
        report['result']='failed';report['reason']=str(e) if isinstance(e,(DeliveryError,RuntimeError)) else type(e).__name__
        raise SystemExit('Gitee test delivery failed: '+report['reason']) from None
    finally:
        report['events']=budget.events
        (directory/'gitee-test.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
