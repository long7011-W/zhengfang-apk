#!/usr/bin/env python3
"""Render explicit per-target outcomes, including optional mirror failures."""
import json
import os
from pathlib import Path
import sys

def summary(path):
    path=Path(path)
    if not path.exists(): return 'Delivery report unavailable; inspect the failed step.\n'
    report=json.loads(path.read_text())
    lines=['| Target | Result |','| --- | --- |']
    for stage in report.get('stages',[]):
        name=stage['target']+((' round '+str(stage['round'])) if 'round' in stage else '')
        lines.append('| '+name+' | '+stage['status']+' |')
    lines.append('\nDelivery result: '+report.get('result','incomplete')+'.')
    if any(s.get('status') in ['failed','skipped'] for s in report.get('stages',[])):
        lines.append('Some mirrors or metadata remain incomplete. Review the report and use independent repair; do not rebuild the APK.')
    return '\n'.join(lines)+'\n'

if __name__=='__main__':
    text=summary(sys.argv[1])
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'],'a') as out: out.write(text)
    else: print(text)
