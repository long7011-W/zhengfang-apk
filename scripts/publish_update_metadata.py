#!/usr/bin/env python3
"""Publish small legacy metadata independently of Gitee APK attachments."""
import argparse
import base64
import json
import os
from pathlib import Path
import tempfile
from release_distribution import Budget, DeliveryError, verify_manifest, gh_api, fetch, REPO, ensure_forward, release_announcement
from sync_gitee_release import Gitee
from publish_announcement import publish_release

class MetadataClient(Gitee):
    def read_json_file(self, name, allow_missing=False):
        metadata = self.request('GET', f'contents/{name}?ref=main', allow_missing=allow_missing)
        # Gitee returns HTTP 200 with [] for some missing content paths.
        if metadata is None or metadata == []:
            if not allow_missing:
                raise DeliveryError('Required Gitee metadata is missing: '+name)
            return None, None
        if not isinstance(metadata, dict) or not isinstance(metadata.get('content'), str) or not isinstance(metadata.get('sha'), str):
            raise DeliveryError('Unexpected Gitee metadata response: '+name)
        return metadata, json.loads(base64.b64decode(metadata['content']))


_UNSET = object()

def write_json(client, name, data, metadata=_UNSET):
    if metadata is _UNSET:
        metadata, _ = client.read_json_file(name, allow_missing=True)
    body = dict(message='update delivery [skip ci]', branch='main', content=base64.b64encode(json.dumps(data, ensure_ascii=False).encode()).decode())
    if metadata:
        body['sha'] = metadata['sha']
    client.request('PUT' if metadata else 'POST', 'contents/'+name, body)


def publish_metadata(client, envelope, budget, announcement=None, signed_only=False):
    p = verify_manifest(envelope, 'stable')
    tag = 'v'+p['versionName']
    release = gh_api(f'repos/{REPO}/releases/tags/{tag}', budget)
    if release.get('draft') or release.get('prerelease'):
        raise DeliveryError('Official GitHub release is required')
    asset = next((a for a in release.get('assets', []) if a.get('name') == 'app-release.apk'), None)
    if not asset or asset.get('size') != p['size'] or asset.get('digest') != 'sha256:'+p['sha256']:
        raise DeliveryError('GitHub release does not match signed metadata')
    candidates = [m for m in p['mirrors'] if m['id'] != 'github']
    verified = None
    with tempfile.TemporaryDirectory() as d:
        for candidate in candidates:
            try:
                fetch(candidate['url'], Path(d)/'apk', budget, expected=p)
                verified = candidate; break
            except DeliveryError:
                continue
    if verified is None:
        raise DeliveryError('No verified domestic candidate; legacy metadata retained')
    if signed_only:
        # The legacy migration prompt can legitimately point to a newer test APK.
        # Repair the default signed channel without rolling that prompt back.
        metadata, current = client.read_json_file('app-update-stable.json', allow_missing=True)
        if current:
            ensure_forward(verify_manifest(current, 'stable'), p)
        if current != envelope:
            write_json(client, 'app-update-stable.json', envelope, metadata)
        _, stored = client.read_json_file('app-update-stable.json')
        if stored != envelope:
            raise DeliveryError('Signed metadata verification failed')
        return p
    metadata, current = client.read_json_file('version.json', allow_missing=True)
    if current and (current.get('versionCode', 0) > p['versionCode'] or
        (current.get('versionCode') == p['versionCode'] and current.get('sha256') not in (None, p['sha256']))):
        raise DeliveryError('Refusing legacy metadata rollback or replacement')
    signed_metadata, old_signed = client.read_json_file('app-update-stable.json', allow_missing=True)
    if old_signed:
        ensure_forward(verify_manifest(old_signed, 'stable'), p)
    write_json(client, 'app-update-stable.json', envelope, signed_metadata)
    legacy = {key: p[key] for key in ['versionCode', 'versionName', 'releaseNotes', 'forceUpdate', 'sha256', 'size']}
    legacy['downloadUrl'] = verified['url']
    write_json(client, 'version.json', legacy, metadata)
    _, stored = client.read_json_file('version.json')
    if stored != legacy:
        raise DeliveryError('Legacy metadata verification failed')
    announcement = announcement or release_announcement(p)
    if announcement:
        publish_release(client, tag, announcement, p['releaseNotes'], envelope=envelope)
    return legacy


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--manifest', type=Path, required=True); parser.add_argument('--announcement', type=Path)
    parser.add_argument('--signed-only', action='store_true', help='Preserve the legacy version.json migration entry')
    args = parser.parse_args(); budget = Budget(180)
    envelope = json.loads(args.manifest.read_text())
    # A deployment report can be supplied directly by the publisher.
    if 'manifest' in envelope:
        envelope = envelope['manifest']
    publish_metadata(MetadataClient(os.environ.get('GITEE_TOKEN', ''), budget), envelope, budget,
        json.loads(args.announcement.read_text()) if args.announcement else None, signed_only=args.signed_only)

if __name__ == '__main__':
    main()
