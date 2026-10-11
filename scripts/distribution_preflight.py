#!/usr/bin/env python3
"""Read-only CI diagnostics. Public APK certificate fingerprints are not secret material."""
from pathlib import Path
import tempfile
from release_distribution import Budget, fetch, android_tool, inspect_apk, cf_project_exists

budget=Budget(180)
with tempfile.TemporaryDirectory() as root:
    apk=Path(root)/'official97.apk'
    fetch('https://github.com/znjhahaha/zhengfang-apk/releases/download/v1.0.97/app-release.apk',apk,budget,
        expected=dict(size=17567266,sha256='a69f1a750f11ea90552320b96b259bd00c12ce70c32eeb09e7ef45b9980d96c6'))
    tool=android_tool('apksigner')
    print('Selected Android tool:',tool,flush=True)
    result=budget.run([tool,'verify','--print-certs',str(apk)],'public-apk-certificate',30).decode()
    for line in result.splitlines():
        if 'certificate SHA-256 digest:' in line:
            print(line,flush=True)
    try:
        inspect_apk(apk,budget)
        print('Existing official APK verifier: passed',flush=True)
    except Exception as e:
        print('Existing official APK verifier:',type(e).__name__,str(e),flush=True)
        raise
print('Cloudflare test project exists:',cf_project_exists('test',budget))
