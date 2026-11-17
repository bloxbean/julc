#!/usr/bin/env python3
"""ADR-077 M4: runs the bash steps of native-image-release.yml that decide the npm gates, the build-once check and the
create-once release assets, locally, with a fake `gh` (no GitHub access) and throwaway git repositories.

    python3 adr/evidence/077-m4/workflow-steps-test.py        (needs PyYAML, git, bash, sha256sum or shasum)

What GitHub itself does (OIDC, attestations, the release API's real answers, job conditions) is not exercised here.
"""
import os, re, shutil, subprocess, sys, tempfile, textwrap
import yaml

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
WORKFLOW = yaml.safe_load(open(os.path.join(ROOT, '.github/workflows/native-image-release.yml')))
failures = 0


def step(job, name):
    for s in WORKFLOW['jobs'][job]['steps']:
        if s.get('name') == name:
            return s['run']
    raise KeyError(f'{job}: {name}')


def run(script, env, cwd):
    full = {**os.environ, **env}
    return subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', script], env=full, cwd=cwd, text=True,
                          capture_output=True)


def check(label, ok, detail=''):
    global failures
    print(('ok    ' if ok else 'FAIL  ') + label)
    if not ok:
        failures += 1
        print(textwrap.indent(detail, '      '))


def tool_dir(tmp):
    """sha256sum for macOS, and a fake gh driven by files in $FAKE_GH."""
    bin_dir = os.path.join(tmp, 'bin')
    os.makedirs(bin_dir, exist_ok=True)
    if not shutil.which('sha256sum'):
        with open(os.path.join(bin_dir, 'sha256sum'), 'w') as f:
            f.write('#!/bin/sh\nexec shasum -a 256 "$@"\n')
    with open(os.path.join(bin_dir, 'gh'), 'w') as f:
        f.write(textwrap.dedent('''\
            #!/usr/bin/env python3
            import json, os, shutil, sys
            state = os.environ['FAKE_GH']
            args = sys.argv[1:]
            open(os.path.join(state, 'calls'), 'a').write(' '.join(args) + '\\n')
            def assets():
                path = os.path.join(state, 'assets.json')
                return json.load(open(path)) if os.path.exists(path) else None
            if args[:2] == ['release', 'view']:
                if assets() is None:
                    sys.stderr.write('release not found\\n'); sys.exit(1)
                for a in assets(): print(a['name'])
            elif args[:2] == ['release', 'download']:
                d = args[args.index('--dir') + 1]
                os.makedirs(d, exist_ok=True)
                for a in assets():
                    if a['name'].endswith('.tgz'): open(os.path.join(d, a['name']), 'wb').write(a['bytes'].encode())
            elif args[:2] == ['attestation', 'verify']:
                sys.exit(0 if os.path.exists(os.path.join(state, 'attested')) else 1)
            elif args[0] == 'api':
                if '-X' in args and args[args.index('-X') + 1] == 'DELETE':
                    open(os.path.join(state, 'deleted'), 'a').write(args[-1].rsplit('/', 1)[1] + '\\n'); sys.exit(0)
                url = [a for a in args[1:] if a.startswith('repos/')][0]
                if '/releases/tags/' in url:
                    if assets() is None:
                        sys.stderr.write('gh: Not Found (HTTP 404)\\n'); sys.exit(1)
                    print(json.dumps({'id': 7})); sys.exit(0)
                if url.endswith('per_page=100'):
                    for a in assets():
                        print('\\t'.join([str(a['id']), a['name'], a['state'], a.get('digest', '')]))
                    sys.exit(0)
                if '/releases/assets/' in url:
                    i = int(url.rsplit('/', 1)[1])
                    sys.stdout.write([a for a in assets() if a['id'] == i][0]['bytes']); sys.exit(0)
                sys.exit(2)
            else:
                sys.exit(2)
            '''))
    for name in os.listdir(bin_dir):
        os.chmod(os.path.join(bin_dir, name), 0o755)
    return bin_dir


def gates_case(tmp, label, ref, properties, expect_mode=None, expect_error=None, event='push'):
    repo = tempfile.mkdtemp(dir=tmp)
    subprocess.run(['git', 'init', '-q', repo], check=True)
    open(os.path.join(repo, 'gradle.properties'), 'w').write(properties)
    subprocess.run(['git', '-C', repo, 'add', '.'], check=True)
    subprocess.run(['git', '-C', repo, '-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-qm', 'x'], check=True)
    sha = subprocess.run(['git', '-C', repo, 'rev-parse', 'HEAD'], capture_output=True, text=True).stdout.strip()
    out = os.path.join(tmp, 'out'); open(out, 'w').close()
    result = run(step('npm-gates', 'Check the ref and read the gates'),
                 {'GITHUB_REF': ref, 'GITHUB_SHA': sha, 'GITHUB_OUTPUT': out, 'GITHUB_STEP_SUMMARY': os.devnull,
                  'GITHUB_EVENT_NAME': event}, repo)
    outputs = dict(line.split('=', 1) for line in open(out).read().splitlines() if '=' in line)
    if expect_error:
        check(f'npm-gates: {label}: refused', result.returncode != 0 and expect_error in result.stderr,
              result.stdout + result.stderr)
    else:
        check(f'npm-gates: {label}: mode {expect_mode}', result.returncode == 0 and outputs.get('mode') == expect_mode,
              result.stdout + result.stderr + repr(outputs))


def main():
    tmp = tempfile.mkdtemp(prefix='julc-m4-steps-')
    bin_dir = tool_dir(tmp)
    os.environ['PATH'] = bin_dir + os.pathsep + os.environ['PATH']
    props = lambda version, gh='true', npm='true': (
        f'group = org.julclang\nversion = {version}\ngithub_release = {gh}\n' + (f'npm_publish = {npm}\n' if npm else ''))

    gates_case(tmp, 'a release with npm_publish = true', 'refs/tags/v0.1.0-pre22', props('0.1.0-pre22'), 'publish')
    gates_case(tmp, 'the same, dispatched on the tag', 'refs/tags/v0.1.0-pre22', props('0.1.0-pre22'), 'publish',
               event='workflow_dispatch')
    gates_case(tmp, 'npm_publish = false', 'refs/tags/v0.1.0-pre22', props('0.1.0-pre22', npm='false'), 'dry-run')
    gates_case(tmp, 'npm_publish missing', 'refs/tags/v0.1.0-pre22', props('0.1.0-pre22', npm=None), 'dry-run')
    gates_case(tmp, 'npm_publish=true without spaces', 'refs/tags/v0.1.0',
               'version=0.1.0\ngithub_release=true\nnpm_publish=true\n', 'publish')
    gates_case(tmp, 'npm_publish_extra does not count', 'refs/tags/v0.1.0',
               'version = 0.1.0\ngithub_release = true\nnpm_publish_extra = true\n', 'dry-run')
    gates_case(tmp, 'a release candidate with npm_publish = true', 'refs/tags/v0.1.0-pre22-rc1',
               props('0.1.0-pre22-rc1'), 'dry-run')
    gates_case(tmp, 'a 1.0 release candidate', 'refs/tags/v1.0.0-rc.1', props('1.0.0-rc.1'), 'dry-run')
    gates_case(tmp, '"source" is not a candidate', 'refs/tags/v0.1.0-source1', props('0.1.0-source1'), 'publish')
    gates_case(tmp, 'github_release = false', 'refs/tags/v0.1.0-pre22', props('0.1.0-pre22', gh='false'), 'none')
    gates_case(tmp, 'a dispatch from a branch', 'refs/heads/main', props('0.1.0-pre22'),
               expect_error='runs on a release tag, not refs/heads/main', event='workflow_dispatch')
    gates_case(tmp, 'a tag that does not match the version', 'refs/tags/v0.1.0-pre22', props('0.1.0-pre23'),
               expect_error='does not match version 0.1.0-pre23')
    gates_case(tmp, 'a snapshot', 'refs/tags/v0.1.0-SNAPSHOT', props('0.1.0-SNAPSHOT'),
               expect_error='snapshot version is never published')

    # The wasm job's build-once check.
    import json
    def build_once(label, assets, attested, expect_ok, expect_text):
        state = tempfile.mkdtemp(dir=tmp)
        if assets is not None:
            json.dump(assets, open(os.path.join(state, 'assets.json'), 'w'))
        if attested:
            open(os.path.join(state, 'attested'), 'w').close()
        work = tempfile.mkdtemp(dir=tmp)
        result = run(step('wasm', 'Refuse to build a released version again'),
                     {'FAKE_GH': state, 'VERSION': '0.1.0-pre22', 'GITHUB_REPOSITORY': 'bloxbean/julc',
                      'RUNNER_TEMP': work}, work)
        check(f'wasm build-once: {label}', (result.returncode == 0) == expect_ok and expect_text in result.stdout + result.stderr,
              result.stdout + result.stderr)
    tgz = lambda n, b='x': {'id': n, 'name': ['julclang-wasm-client-0.1.0-pre22.tgz', 'julclang-julc-vm-wasm-0.1.0-pre22.tgz',
                                             'julclang-julc-wasm-0.1.0-pre22.tgz'][n], 'state': 'uploaded', 'bytes': b}
    build_once('no release yet', None, False, True, 'building')
    build_once('a release without npm files (before M4)', [{'id': 9, 'name': 'julc-0.1.0-pre22-linux-x86_64.zip',
               'state': 'uploaded', 'bytes': 'z'}], False, True, 'holds no npm tarballs')
    build_once('a release with attested npm tarballs', [tgz(0), tgz(1), tgz(2)], True, False, 'attestation: verified')
    build_once('a release with unattested npm tarballs', [tgz(0)], False, False, 'attestation: NOT verified')
    build_once('a pre-release of another version is not this one', [{'id': 3, 'name': 'julclang-wasm-client-0.1.0-pre220.tgz',
               'state': 'uploaded', 'bytes': 'q'}], False, True, 'holds no npm tarballs')

    # github-release's create-once step.
    import hashlib
    def create_once(label, local, assets, expect_ok, expect_text, expect_deleted=()):
        state = tempfile.mkdtemp(dir=tmp)
        if assets is not None:
            json.dump(assets, open(os.path.join(state, 'assets.json'), 'w'))
        work = tempfile.mkdtemp(dir=tmp)
        os.makedirs(os.path.join(work, 'dist'))
        for name, data in local.items():
            open(os.path.join(work, 'dist', name), 'w').write(data)
        result = run(step('github-release', 'Keep completed assets, clear failed uploads'),
                     {'FAKE_GH': state, 'VERSION': '0.1.0-pre22', 'GITHUB_REPOSITORY': 'bloxbean/julc',
                      'RUNNER_TEMP': work}, work)
        deleted = open(os.path.join(state, 'deleted')).read().split() if os.path.exists(os.path.join(state, 'deleted')) else []
        check(f'github-release create-once: {label}',
              (result.returncode == 0) == expect_ok and expect_text in result.stdout + result.stderr
              and deleted == [str(d) for d in expect_deleted], result.stdout + result.stderr + repr(deleted))
    sha = lambda s: 'sha256:' + hashlib.sha256(s.encode()).hexdigest()
    name = 'julclang-julc-wasm-0.1.0-pre22.tgz'
    create_once('no release yet', {name: 'A'}, None, True, 'No release for v0.1.0-pre22 yet')
    create_once('the same bytes already attached (digest)', {name: 'A'},
                [{'id': 5, 'name': name, 'state': 'uploaded', 'digest': sha('A'), 'bytes': 'A'}], True, 'Keeping')
    create_once('the same bytes already attached (no digest field: downloaded)', {name: 'A'},
                [{'id': 5, 'name': name, 'state': 'uploaded', 'bytes': 'A'}], True, 'Keeping')
    create_once('other bytes already attached', {name: 'B'},
                [{'id': 5, 'name': name, 'state': 'uploaded', 'digest': sha('A'), 'bytes': 'A'}], False,
                'already attached with other bytes')
    create_once('a failed upload placeholder (HTTP 502)', {name: 'A'},
                [{'id': 6, 'name': name, 'state': 'starter', 'bytes': ''}], True, 'failed upload', expect_deleted=[6])
    create_once('a placeholder and a differing asset', {name: 'A', 'other.zip': 'C'},
                [{'id': 6, 'name': name, 'state': 'starter', 'bytes': ''},
                 {'id': 8, 'name': 'other.zip', 'state': 'uploaded', 'digest': sha('D'), 'bytes': 'D'}], False,
                'other bytes', expect_deleted=[6])

    shutil.rmtree(tmp)
    print(f'{failures} failure(s)')
    sys.exit(1 if failures else 0)


main()
