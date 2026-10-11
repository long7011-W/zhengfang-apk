#!/usr/bin/env python3
"""Fast-forward GitHub main into an independent Gitee source branch; preserve metadata main."""
import os
from pathlib import Path
import tempfile
from release_distribution import Budget, DeliveryError, REPO, gh_api


def main():
    token = os.environ.get('GITEE_TOKEN')
    if not token:
        raise DeliveryError('GITEE_TOKEN is required')
    budget = Budget(150)
    expected = gh_api(f'repos/{REPO}/git/ref/heads/main', budget)['object']['sha']
    with tempfile.TemporaryDirectory() as directory:
        root=Path(directory); ask=root/'askpass.py'
        ask.write_text('#!/usr/bin/env python3\nimport os,sys\nprint("znj12345" if "username" in sys.argv[1].lower() else os.environ["GITEE_TOKEN"])\n')
        ask.chmod(0o700)
        env=dict(os.environ, GIT_ASKPASS=str(ask), GIT_TERMINAL_PROMPT='0')
        def git(*args):
            return budget.run(['git','-c','http.connectTimeout=10','-c','http.lowSpeedLimit=1','-c','http.lowSpeedTime=20',*args], 'gitee-source',60,env=env,cwd=root)
        git('init','--bare','source.git')
        root=root/'source.git'
        git('fetch','--no-tags',f'https://github.com/{REPO}.git','main')
        if git('rev-parse','FETCH_HEAD').decode().strip()!=expected:
            raise DeliveryError('Main changed during fetch; rerun source sync')
        # Never overwrite Gitee metadata commits or force-push divergent history.
        git('push','--no-follow-tags','https://gitee.com/znj12345/zhengfang.git','FETCH_HEAD:refs/heads/github-source')
        actual=git('ls-remote','https://gitee.com/znj12345/zhengfang.git','refs/heads/github-source').decode().split()[0]
        if actual != expected:
            raise DeliveryError('Source synchronization SHA mismatch')
        print('Gitee github-source verified:',actual)

if __name__ == '__main__':
    main()
