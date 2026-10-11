#!/usr/bin/env python3
"""Repair the default channel from an existing official APK, preserving legacy rollout."""
import argparse
import base64
import json
import os
from pathlib import Path
from release_distribution import Budget, DeliveryError, deploy_release, gh_api, verify_manifest, REPO
from repair_delivery import existing_release
from publish_update_metadata import MetadataClient, publish_metadata


def repair(tag, directory, budget, metadata_only=False):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    if metadata_only:
        remote = gh_api(f'repos/{REPO}/contents/stable.json?ref=updates', budget)
        envelope = json.loads(base64.b64decode(remote['content']))
        payload = verify_manifest(envelope, 'stable')
        if tag != 'v'+payload['versionName']:
            raise DeliveryError('Requested version is no longer current; refusing old metadata repair')
    else:
        apk, receipt, notes = existing_release(tag, directory, budget)
        envelope = deploy_release(apk, receipt, 'stable', notes, directory/'stable-mirror.json',
                                  budget, publish_announcements=False)
    payload = publish_metadata(MetadataClient(os.environ.get('GITEE_TOKEN', ''), budget),
                               envelope, budget, signed_only=True)
    return dict(result='verified', versionCode=payload['versionCode'], sha256=payload['sha256'],
                legacyPromptChanged=False, apkRebuilt=False, formalReleaseCreated=False)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--tag', required=True)
    parser.add_argument('--directory', type=Path, default=Path('repair'))
    parser.add_argument('--metadata-only', action='store_true')
    args = parser.parse_args()
    budget = Budget(420)
    report = dict(result='incomplete')
    try:
        report = repair(args.tag, args.directory, budget, args.metadata_only)
    except Exception as e:
        report.update(result='failed', reason=str(e) if isinstance(e, (DeliveryError, RuntimeError)) else type(e).__name__)
        raise SystemExit('Default update channel repair failed: '+report['reason']) from None
    finally:
        args.directory.mkdir(parents=True, exist_ok=True)
        report['events'] = budget.events
        (args.directory/'stable-bootstrap.json').write_text(json.dumps(report, indent=2)+'\n')
