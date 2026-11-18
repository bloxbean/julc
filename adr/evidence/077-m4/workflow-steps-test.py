#!/usr/bin/env python3
"""ADR-077 M4: runs the bash of native-image-release.yml's steps that decide the npm gates, the build-once check, the
create-once release assets and their final verification, and julc-wasm/src/ci/npm-run.sh and npm-files.sh, locally, with a fake
`gh` (no GitHub access) and throwaway git repositories. Also checks the job graph's static facts.

    python3 adr/evidence/077-m4/workflow-steps-test.py        (needs PyYAML, git, bash, sha256sum or shasum)

The fake GitHub holds releases (published or draft, with assets in states `uploaded` or `starter`) and attestations
(by content SHA-256, with the source and signer commits and the ref they were made for). A read-only token does not
see drafts, as on GitHub. What GitHub itself does (OIDC, real attestations, the API's real answers, job conditions at
run time) is not exercised here.
"""
import hashlib, json, os, re, shutil, subprocess, sys, tempfile, textwrap
import yaml

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
WORKFLOW_FILE = os.path.join(ROOT, '.github/workflows/native-image-release.yml')
WORKFLOW = yaml.safe_load(open(WORKFLOW_FILE))
NPM_FILES = os.path.join(ROOT, 'julc-wasm/src/ci/npm-files.sh')
NPM_RUN = os.path.join(ROOT, 'julc-wasm/src/ci/npm-run.sh')
V = '0.1.0-pre22'
TAG_SHA = 'a' * 40
failures = 0

FAKE_GH = r'''#!/usr/bin/env python3
import fnmatch, hashlib, json, os, re, sys
state = os.environ['FAKE_GH']
args = sys.argv[1:]
open(os.path.join(state, 'calls'), 'a').write(json.dumps(args) + '\n')
path = os.path.join(state, 'releases.json')
releases = json.load(open(path)) if os.path.exists(path) else []
write = os.environ.get('FAKE_TOKEN') == 'write'
visible = [r for r in releases if write or not r['draft']]
def save(): json.dump(releases, open(path, 'w'))
def opt(name):
    return args[args.index(name) + 1] if name in args else None
if args[:2] == ['release', 'download']:
    matches = [r for r in visible if r['tag'] == args[2]]
    if not matches:
        sys.stderr.write('release not found\n'); sys.exit(1)
    d = opt('--dir'); os.makedirs(d, exist_ok=True)
    patterns = [args[i + 1] for i, a in enumerate(args) if a == '--pattern']
    for a in matches[0]['assets']:
        if a['state'] == 'uploaded' and any(fnmatch.fnmatchcase(a['name'], p) for p in patterns):
            open(os.path.join(d, a['name']), 'wb').write(a['bytes'].encode())
elif args[:2] == ['release', 'upload']:
    matches = [r for r in releases if r['tag'] == args[2]]
    if not matches or matches[0]['draft']:
        sys.stderr.write('release not found\n'); sys.exit(1)
    name = os.path.basename(args[3])
    data = open(args[3]).read()
    if any(a['name'] == name for a in matches[0]['assets']):
        sys.stderr.write(f'asset {name} already exists\n'); sys.exit(1)
    failing = os.environ.get('FAKE_FAIL_UPLOAD', '').split(',')
    new_id = 1000 + sum(len(r['assets']) for r in releases) + len(releases)
    import hashlib as h
    if name in failing:
        matches[0]['assets'].append({'id': new_id, 'name': name, 'state': 'starter', 'bytes': ''})
        save(); sys.stderr.write('HTTP 502: Bad Gateway\n'); sys.exit(1)
    matches[0]['assets'].append({'id': new_id, 'name': name, 'state': 'uploaded', 'bytes': data,
                                 'digest': 'sha256:' + h.sha256(data.encode()).hexdigest()})
    save()
    open(os.path.join(state, 'uploads'), 'a').write(name + '\n')
elif args[:2] == ['attestation', 'verify']:
    data = open(args[2], 'rb').read()
    attested_file = os.path.join(state, 'attested.json')
    att = json.load(open(attested_file)) if os.path.exists(attested_file) else {}
    entry = att.get(hashlib.sha256(data).hexdigest())
    if not entry: sys.stderr.write('no attestation\n'); sys.exit(1)
    for flag, key in [('--source-digest', 'source'), ('--signer-digest', 'signer'), ('--source-ref', 'ref')]:
        if opt(flag) is not None and opt(flag) != entry[key]:
            sys.stderr.write(f'{flag} {opt(flag)} != {entry[key]}\n'); sys.exit(1)
    sys.exit(0)
elif args[0] == 'api':
    if opt('-X') == 'DELETE':
        i = int(args[-1].rsplit('/', 1)[1])
        for r in releases: r['assets'] = [a for a in r['assets'] if a['id'] != i]
        save(); open(os.path.join(state, 'deleted'), 'a').write(f'{i}\n'); sys.exit(0)
    url = [a for a in args[1:] if a.startswith('repos/')][0]
    jq = opt('--jq') or ''
    if opt('-X') == 'POST' and url.endswith('/releases'):
        fields = dict(args[i + 1].split('=', 1) for i, a in enumerate(args) if a in ('-f', '-F'))
        new_id = 100 + len(releases)
        releases.append({'id': new_id, 'tag': fields['tag_name'], 'draft': fields['draft'] == 'true', 'assets': [],
                         'fields': fields})
        save(); print(new_id); sys.exit(0)
    if '/releases/tags/' in url:
        tag = url.split('/releases/tags/')[1]
        matches = [r for r in visible if r['tag'] == tag]
        if not matches:
            sys.stderr.write('gh: Not Found (HTTP 404)\n'); sys.exit(1)
        for a in matches[0]['assets']:
            print(f"{a['name']} {a['state']} {a.get('digest', '')}")
        sys.exit(0)
    if '/actions/workflows/native-image-release.yml/runs?' in url:
        runs_file = os.path.join(state, 'runs.json')
        runs = json.load(open(runs_file)) if os.path.exists(runs_file) else []
        query = dict(part.split('=', 1) for part in url.split('?', 1)[1].split('&'))
        branch = re.search(r'head_branch == "([^"]+)"', jq).group(1)
        for r in runs:
            if r['event'] == query['event'] and r['head_sha'] == query['head_sha'] and r['head_branch'] == branch:
                print(r['id'])
        sys.exit(0)
    if '/actions/runs/' in url:
        runs_file = os.path.join(state, 'runs.json')
        runs = {r['id']: r for r in (json.load(open(runs_file)) if os.path.exists(runs_file) else [])}
        run_id = int(url.split('/actions/runs/')[1].split('/')[0])
        if '/jobs?' in url:
            assert 'filter=all' in url
            for job in runs[run_id]['jobs']:
                if job['name'] == 'Attest the npm tarballs and engine archives' and job['conclusion'] == 'success':
                    print(job['id'])
            sys.exit(0)
        if '/artifacts?name=npm-packages' in url:
            for artifact in runs[run_id].get('artifacts', []):
                if artifact['name'] == 'npm-packages' and not artifact['expired']:
                    print(artifact['id'])
            sys.exit(0)
        sys.exit(2)
    if url.endswith('/releases?per_page=100'):
        tag = re.search(r'tag_name == "([^"]+)"', jq).group(1)
        for r in visible:
            if r['tag'] != tag: continue
            if '.assets[].name' in jq:
                for a in r['assets']: print(a['name'])
            elif '(.assets | length)' in jq:
                print(f"{r['id']} {len(r['assets'])}")
            elif '[.id, .draft]' in jq:
                print(f"{r['id']}\t{'true' if r['draft'] else 'false'}")
            else: sys.exit(3)
        sys.exit(0)
    if '/assets?per_page=100' in url:
        i = int(url.split('/releases/')[1].split('/')[0])
        for r in visible:
            if r['id'] == i:
                for a in r['assets']: print('\t'.join([str(a['id']), a['name'], a['state'], a.get('digest', '')]))
        sys.exit(0)
    if '/releases/assets/' in url:
        i = int(url.rsplit('/', 1)[1])
        for r in visible:
            for a in r['assets']:
                if a['id'] == i: sys.stdout.write(a['bytes']); sys.exit(0)
        sys.exit(1)
    sys.exit(2)
else:
    sys.exit(2)
'''


def step(job, name):
    for s in WORKFLOW['jobs'][job]['steps']:
        if s.get('name') == name:
            return s['run']
    raise KeyError(f'{job}: {name}')


def run(script, env, cwd):
    return subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', script], env={**os.environ, **env}, cwd=cwd, text=True,
                          capture_output=True)


def check(label, ok, detail=''):
    global failures
    print(('ok    ' if ok else 'FAIL  ') + label)
    if not ok:
        failures += 1
        print(textwrap.indent(detail, '      '))


def tool_dir(tmp):
    bin_dir = os.path.join(tmp, 'bin')
    os.makedirs(bin_dir, exist_ok=True)
    if not shutil.which('sha256sum'):
        open(os.path.join(bin_dir, 'sha256sum'), 'w').write('#!/bin/sh\nexec shasum -a 256 "$@"\n')
    open(os.path.join(bin_dir, 'gh'), 'w').write(FAKE_GH)
    for name in os.listdir(bin_dir):
        os.chmod(os.path.join(bin_dir, name), 0o755)
    return bin_dir


def github(tmp, releases, attested=None):
    state = tempfile.mkdtemp(dir=tmp)
    json.dump(releases, open(os.path.join(state, 'releases.json'), 'w'))
    json.dump(attested or {}, open(os.path.join(state, 'attested.json'), 'w'))
    return state


sha = lambda text: hashlib.sha256(text.encode()).hexdigest()
FILES = [f'julclang-wasm-client-{V}.tgz', f'julclang-julc-vm-wasm-{V}.tgz', f'julclang-julc-wasm-{V}.tgz',
         f'julc-vm-wasm-{V}.tar.gz', f'julc-wasm-{V}.tar.gz']
SUMS = f'julc-npm-{V}-SHA256SUMS.txt'


def asset(i, name, data, state='uploaded', digest=True):
    return {'id': i, 'name': name, 'state': state, 'bytes': data, **({'digest': 'sha256:' + sha(data)} if digest else {})}


def gates(tmp):
    def case(label, ref, properties, expect_mode=None, expect_error=None, event='push'):
        repo = tempfile.mkdtemp(dir=tmp)
        subprocess.run(['git', 'init', '-q', repo], check=True)
        open(os.path.join(repo, 'gradle.properties'), 'w').write(properties)
        subprocess.run(['git', '-C', repo, 'add', '.'], check=True)
        subprocess.run(['git', '-C', repo, '-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-qm', 'x'], check=True)
        head = subprocess.run(['git', '-C', repo, 'rev-parse', 'HEAD'], capture_output=True, text=True).stdout.strip()
        out = os.path.join(tmp, 'out'); open(out, 'w').close()
        result = run(step('npm-gates', 'Check the ref and read the gates'),
                     {'GITHUB_REF': ref, 'GITHUB_SHA': head, 'GITHUB_OUTPUT': out, 'GITHUB_STEP_SUMMARY': os.devnull,
                      'GITHUB_EVENT_NAME': event}, repo)
        outputs = dict(line.split('=', 1) for line in open(out).read().splitlines() if '=' in line)
        if expect_error:
            check(f'npm-gates: {label}: refused', result.returncode != 0 and expect_error in result.stderr,
                  result.stdout + result.stderr)
        else:
            check(f'npm-gates: {label}: mode {expect_mode}', result.returncode == 0 and outputs.get('mode') == expect_mode,
                  result.stdout + result.stderr + repr(outputs))
    props = lambda version, gh='true', npm='true': (
        f'group = org.julclang\nversion = {version}\ngithub_release = {gh}\n' + (f'npm_publish = {npm}\n' if npm else ''))
    case('a release with npm_publish = true', f'refs/tags/v{V}', props(V), 'publish')
    case('the same, dispatched on the tag', f'refs/tags/v{V}', props(V), 'publish', event='workflow_dispatch')
    case('npm_publish = false', f'refs/tags/v{V}', props(V, npm='false'), 'dry-run')
    case('npm_publish missing', f'refs/tags/v{V}', props(V, npm=None), 'dry-run')
    case('npm_publish=true without spaces', 'refs/tags/v0.1.0', 'version=0.1.0\ngithub_release=true\nnpm_publish=true\n',
         'publish')
    case('npm_publish_extra does not count', 'refs/tags/v0.1.0',
         'version = 0.1.0\ngithub_release = true\nnpm_publish_extra = true\n', 'dry-run')
    case('a release candidate of a prerelease (-preN-rcN) with npm_publish = true', f'refs/tags/v{V}-rc1',
         props(f'{V}-rc1'), 'dry-run')
    case('a release candidate of a final version (-rcN) is published', 'refs/tags/v0.1.0-rc1', props('0.1.0-rc1'),
         'publish')
    case('a 1.0 release candidate', 'refs/tags/v1.0.0-rc2', props('1.0.0-rc2'), 'publish')
    case('a -betaN', 'refs/tags/v0.2.0-beta1', props('0.2.0-beta1'), 'publish')
    case('a 1.x -preN', 'refs/tags/v1.1.0-pre1', props('1.1.0-pre1'), 'publish')
    case('a -preN-rcN with npm_publish = false', f'refs/tags/v{V}-rc2', props(f'{V}-rc2', npm='false'), 'dry-run')
    for form in ['0.1.0-rc.1', '0.1.0-RC1', '0.1.0-beta1-rc1', '0.1.0-source1', '0.1.0-alpha1', '0.1.0-pre.22',
                 '0.1.0-rc0', '0.1.0-preview1']:
        case(f'{form}: no npm rule', f'refs/tags/v{form}', props(form), expect_error='npm has no rule for')
    case('an unsupported form without a GitHub release needs no rule', 'refs/tags/v0.1.0-rc.1',
         props('0.1.0-rc.1', gh='false'), 'none')
    case('github_release = false', f'refs/tags/v{V}', props(V, gh='false'), 'none')
    case('a dispatch from a branch', 'refs/heads/main', props(V), expect_error='runs on a release tag, not refs/heads/main',
         event='workflow_dispatch')
    case('a tag that does not match the version', f'refs/tags/v{V}', props('0.1.0-pre23'),
         expect_error='does not match version 0.1.0-pre23')
    case('a snapshot', 'refs/tags/v0.1.0-SNAPSHOT', props('0.1.0-SNAPSHOT'), expect_error='snapshot version is never published')


def build_once(tmp):
    def case(label, releases, expect_ok, expect_text):
        state = github(tmp, releases)
        work = tempfile.mkdtemp(dir=tmp)
        result = run(step('wasm', 'Refuse to build a released version again'),
                     {'FAKE_GH': state, 'FAKE_TOKEN': 'read', 'VERSION': V, 'GITHUB_REPOSITORY': 'bloxbean/julc',
                      'RUNNER_TEMP': work}, work)
        check(f'wasm build-once: {label}',
              (result.returncode == 0) == expect_ok and expect_text in result.stdout + result.stderr,
              result.stdout + result.stderr)
        return result
    tgzs = [asset(i, FILES[i], 'x' + str(i)) for i in range(3)]
    case('no release yet', [], True, 'building')
    case('another tag\'s release does not count', [{'id': 1, 'tag': f'v{V}0', 'draft': False, 'assets': tgzs}], True,
         'building')
    case('a release with npm tarballs', [{'id': 1, 'tag': f'v{V}', 'draft': False, 'assets': tgzs}], False,
         'already has a GitHub release')
    case('a release with only other assets (a partial upload)',
         [{'id': 1, 'tag': f'v{V}', 'draft': False, 'assets': [asset(9, f'julc-{V}-linux-x86_64.zip', 'z')]}], False,
         'already has a GitHub release')
    case('an empty release (created, then every upload failed)', [{'id': 1, 'tag': f'v{V}', 'draft': False, 'assets': []}],
         False, 'already has a GitHub release')


def create_step(tmp, state, local, fail_upload=''):
    work = tempfile.mkdtemp(dir=tmp)
    os.makedirs(os.path.join(work, 'dist'))
    for name, data in local.items():
        open(os.path.join(work, 'dist', name), 'w').write(data)
    return run(step('github-release', 'Create the release once and attach its assets'),
               {'FAKE_GH': state, 'FAKE_TOKEN': 'write', 'VERSION': V, 'GITHUB_REPOSITORY': 'bloxbean/julc',
                'RUNNER_TEMP': work, 'PRERELEASE': 'true', 'FAKE_FAIL_UPLOAD': fail_upload}, work), work


def releases_of(state):
    return json.load(open(os.path.join(state, 'releases.json')))


def create_once(tmp):
    def case(label, local, releases, expect_ok, expect_text, expect_deleted=(), expect_uploads=None):
        state = github(tmp, releases)
        result, _ = create_step(tmp, state, local)
        deleted_file = os.path.join(state, 'deleted')
        deleted = open(deleted_file).read().split() if os.path.exists(deleted_file) else []
        uploads_file = os.path.join(state, 'uploads')
        uploads = open(uploads_file).read().split() if os.path.exists(uploads_file) else []
        ok = ((result.returncode == 0) == expect_ok and expect_text in result.stdout + result.stderr
              and deleted == [str(d) for d in expect_deleted]
              and (expect_uploads is None or uploads == expect_uploads))
        check(f'github-release create-once: {label}', ok, result.stdout + result.stderr + repr((deleted, uploads)))
        return state
    name = FILES[4]
    rel = lambda assets, draft=False, i=7: {'id': i, 'tag': f'v{V}', 'draft': draft, 'assets': assets}
    everything = {f'julc-{V}-linux-x86_64.zip': 'Z', FILES[3]: 'D', FILES[4]: 'E', f'julc-wasm-{V}-SHA256SUMS.txt': 'S'}
    state = case('no release yet: created published, every file attached once', everything, [], True, 'Created release',
                 expect_uploads=sorted(everything))
    created = releases_of(state)[0]
    check('github-release create-once: the release is created published, prerelease, make_latest legacy',
          created['draft'] is False and created['fields'] == {'tag_name': f'v{V}', 'name': V, 'prerelease': 'true',
                                                                'draft': 'false', 'make_latest': 'legacy'},
          repr(created))
    case('the same bytes already attached (digest)', {name: 'A'}, [rel([asset(5, name, 'A')])], True, 'Keeping',
         expect_uploads=[])
    case('the same bytes already attached (no digest field: downloaded)', {name: 'A'},
         [rel([asset(5, name, 'A', digest=False)])], True, 'Keeping', expect_uploads=[])
    case('other bytes already attached: stops before uploading anything', {name: 'B', 'other.zip': 'C'},
         [rel([asset(5, name, 'A')])], False, 'already attached with other bytes', expect_uploads=[])
    case('a failed upload placeholder (HTTP 502) is deleted and uploaded again', {name: 'A'},
         [rel([asset(6, name, '', state='starter', digest=False)])], True, 'failed upload', expect_deleted=[6],
         expect_uploads=[name])
    case('a placeholder and a differing asset', {name: 'A', 'other.zip': 'C'},
         [rel([asset(6, name, '', state='starter', digest=False), asset(8, 'other.zip', 'D')])], False, 'other bytes',
         expect_deleted=[6], expect_uploads=[])
    case('a draft with the tag (this workflow never makes one): refused', {name: 'A'}, [rel([], draft=True)], False,
         'is a draft')
    case('two releases (a draft too) with the tag', {name: 'A'}, [rel([], i=7), rel([], draft=True, i=8)], False,
         '2 releases')
    case('another tag\'s release is not this one', {name: 'B'},
         [{'id': 3, 'tag': f'v{V}0', 'draft': False, 'assets': [asset(5, name, 'A')]}], True, 'Created release')


def failed_upload_recovery(tmp):
    """M4-C9: an upload fails (HTTP 502), a whole-workflow re-run is refused, and the release is still finished from the
    original bytes: by re-running the failed job, or by hand from the original run's artifacts. Then the npm side (a
    dispatch) finds the run, and its npm files check against their attestation and the finished release's archives."""
    npm = {f: f'bytes of {f}' for f in FILES + [SUMS]}
    data = {FILES[3]: npm[FILES[3]], FILES[4]: npm[FILES[4]], f'julc-wasm-{V}-SHA256SUMS.txt': 'sums',
            f'julc-{V}-linux-x86_64.zip': 'cli'}
    attested = {sha(npm[f]): {'source': TAG_SHA, 'signer': TAG_SHA, 'ref': f'refs/tags/v{V}'} for f in FILES}
    for recovery in ['re-run failed jobs', 'by hand after a refused full re-run']:
        state = github(tmp, [], attested)
        result, _ = create_step(tmp, state, data, fail_upload=FILES[3])
        partial = releases_of(state)[0]
        check(f'C9 ({recovery}): the upload fails and leaves a published release with a starter placeholder',
              result.returncode != 0 and not partial['draft']
              and any(a['state'] == 'starter' and a['name'] == FILES[3] for a in partial['assets']),
              result.stdout + result.stderr + repr(partial))
        work = tempfile.mkdtemp(dir=tmp)
        rerun = run(step('wasm', 'Refuse to build a released version again'),
                    {'FAKE_GH': state, 'FAKE_TOKEN': 'read', 'VERSION': V, 'GITHUB_REPOSITORY': 'bloxbean/julc',
                     'RUNNER_TEMP': work}, work)
        check(f'C9 ({recovery}): a whole-workflow re-run stops in wasm, building nothing',
              rerun.returncode != 0 and 'already has a GitHub release' in rerun.stderr, rerun.stdout + rerun.stderr)
        files_dir = npm_dir(tmp, npm)
        early = npm_files(state, files_dir)
        check(f'C9 ({recovery}): before the release is finished, npm is refused (an archive is not public)',
              early.returncode != 0 and 'does not attach' in early.stderr, early.stdout + early.stderr)
        if recovery == 're-run failed jobs':
            again, _ = create_step(tmp, state, data)
            check('C9 (re-run failed jobs): github-release deletes the placeholder and attaches the original bytes',
                  again.returncode == 0, again.stdout + again.stderr)
        else:
            # RUNBOOK.md: delete the placeholder, attach the missing files from the original run's artifacts, never
            # replacing a completed asset.
            rel = releases_of(state)[0]
            for a in [a for a in rel['assets'] if a['state'] == 'starter']:
                subprocess.run(['gh', 'api', '-X', 'DELETE', f'repos/bloxbean/julc/releases/assets/{a["id"]}'],
                               env={**os.environ, 'FAKE_GH': state, 'FAKE_TOKEN': 'write'}, check=True)
            names = {a['name'] for a in releases_of(state)[0]['assets']}
            artifacts = tempfile.mkdtemp(dir=tmp)
            for f, content in data.items():
                if f not in names:
                    open(os.path.join(artifacts, f), 'w').write(content)
                    subprocess.run(['gh', 'release', 'upload', f'v{V}', os.path.join(artifacts, f), '--repo',
                                    'bloxbean/julc'], env={**os.environ, 'FAKE_GH': state, 'FAKE_TOKEN': 'write'},
                                   check=True)
        final_dir = tempfile.mkdtemp(dir=tmp)
        os.makedirs(os.path.join(final_dir, 'dist'))
        for f, content in data.items():
            open(os.path.join(final_dir, 'dist', f), 'w').write(content)
        final = run(step('github-release', "Verify the release's assets"),
                    {'FAKE_GH': state, 'FAKE_TOKEN': 'write', 'VERSION': V, 'GITHUB_REPOSITORY': 'bloxbean/julc',
                     'RUNNER_TEMP': final_dir}, final_dir)
        check(f'C9 ({recovery}): the release is complete with the original bytes, and no npm file', final.returncode == 0
              and not any(a['name'].startswith('julclang-') or a['name'] == SUMS for a in releases_of(state)[0]['assets']),
              final.stdout + final.stderr)
        later = npm_files(state, files_dir)
        check(f'C9 ({recovery}): the npm jobs (a dispatch) verify the six npm files and the public archives',
              later.returncode == 0, later.stdout + later.stderr)


def npm_dir(tmp, files):
    """A directory holding what actions/download-artifact puts there: the npm-packages artifact's files."""
    directory = tempfile.mkdtemp(dir=tmp)
    for f, content in files.items():
        open(os.path.join(directory, f), 'w').write(content)
    return directory


def npm_files(state, directory):
    return subprocess.run(['bash', NPM_FILES, V, directory], text=True, capture_output=True,
                          env={**os.environ, 'FAKE_GH': state, 'FAKE_TOKEN': 'read', 'GH_TOKEN': 'x',
                               'GITHUB_REPOSITORY': 'bloxbean/julc', 'SOURCE_DIGEST': TAG_SHA, 'SIGNER_DIGEST': TAG_SHA})


def npm_run_lookup(tmp):
    """npm-run.sh: the run a recovery dispatch takes the npm files from."""
    attest = lambda conclusion, attempt=1, i=1: {'id': i, 'name': 'Attest the npm tarballs and engine archives',
                                                 'conclusion': conclusion, 'run_attempt': attempt}
    live = [{'id': 900, 'name': 'npm-packages', 'expired': False}]
    def run_(i, jobs, artifacts=live, sha_=TAG_SHA, branch=f'v{V}', event='push'):
        return {'id': i, 'event': event, 'head_sha': sha_, 'head_branch': branch, 'jobs': jobs, 'artifacts': artifacts}
    def case(label, runs, expect_run=None, expect_text=''):
        state = github(tmp, [])
        json.dump(runs, open(os.path.join(state, 'runs.json'), 'w'))
        out = os.path.join(tempfile.mkdtemp(dir=tmp), 'out')
        open(out, 'w').close()
        result = subprocess.run(['bash', NPM_RUN, V], text=True, capture_output=True,
                                env={**os.environ, 'FAKE_GH': state, 'FAKE_TOKEN': 'read', 'GH_TOKEN': 'x',
                                     'GITHUB_REPOSITORY': 'bloxbean/julc', 'SOURCE_DIGEST': TAG_SHA,
                                     'GITHUB_OUTPUT': out})
        found = open(out).read().strip()
        ok = (result.returncode == 0 and found == f'run_id={expect_run}') if expect_run else (
            result.returncode != 0 and expect_text in result.stderr and not found)
        check(f'npm-run.sh: {label}', ok, result.stdout + result.stderr + found)
    gone = 'are no longer available (artifacts are kept 14 days)'
    case('the tag\'s push run, attested, artifact live', [run_(11, [attest('success')])], expect_run=11)
    case('attested in attempt 1, a later full re-run stopped in wasm (attest skipped)',
         [run_(12, [attest('success', 1, 1), attest('skipped', 2, 2)])], expect_run=12)
    case('a second run for the tag that stopped in wasm is not chosen',
         [run_(13, [attest('success')]), run_(14, [attest('skipped')], artifacts=[])], expect_run=13)
    case('a run of another tag on the same commit is not chosen',
         [run_(15, [attest('success')]), run_(16, [attest('success')], branch=f'v{V}-rc1')], expect_run=15)
    case('no run for the tag', [], expect_text=gone)
    case('a run whose attest failed', [run_(17, [attest('failure')])], expect_text=gone)
    case('a dispatch run is not the tag\'s push run', [run_(18, [attest('success')], event='workflow_dispatch')],
         expect_text=gone)
    case('a run at another commit', [run_(19, [attest('success')], sha_='b' * 40)], expect_text=gone)
    case('the artifact has expired (14 days)', [run_(20, [attest('success')],
                                                    artifacts=[{'id': 1, 'name': 'npm-packages', 'expired': True}])],
         expect_text=gone)
    case('two attested runs: a maintainer decides', [run_(21, [attest('success')]), run_(22, [attest('success')])],
         expect_text='Several runs')


def ref_check(tmp):
    """npm-publish's environment, release-staging, also admits main and release/**: the job's own exact-ref check is the
    control against a dispatch from a branch, and runs before anything else."""
    steps = WORKFLOW['jobs']['npm-publish']['steps']
    check('npm-publish: the ref check is its first step, unconditional',
          steps[0].get('name') == 'Refuse any ref but the release tag' and 'if' not in steps[0], repr(steps[0]))
    def case(label, ref, version, expect_ok):
        work = tempfile.mkdtemp(dir=tmp)
        result = run(step('npm-publish', 'Refuse any ref but the release tag'), {'GITHUB_REF': ref, 'VERSION': version}, work)
        check(f'npm-publish ref check: {label}', (result.returncode == 0) == expect_ok
              and (expect_ok or 'runs only on refs/tags/v' in result.stderr), result.stdout + result.stderr)
    case('the release tag', f'refs/tags/v{V}', V, True)
    case('a dispatch from main', 'refs/heads/main', V, False)
    case('a dispatch from release/**', 'refs/heads/release/0.1', V, False)
    case('another release tag', 'refs/tags/v0.1.0-pre23', V, False)
    case('a branch named like the tag', f'refs/heads/v{V}', V, False)
    case('no version from npm-gates', f'refs/tags/v{V}', '', False)


def release_verified(tmp):
    def case(label, local, releases, expect_ok, expect_text):
        state = github(tmp, releases)
        work = tempfile.mkdtemp(dir=tmp)
        os.makedirs(os.path.join(work, 'dist'))
        for name, data in local.items():
            open(os.path.join(work, 'dist', name), 'w').write(data)
        result = run(step('github-release', "Verify the release's assets"),
                     {'FAKE_GH': state, 'FAKE_TOKEN': 'write', 'VERSION': V, 'GITHUB_REPOSITORY': 'bloxbean/julc',
                      'RUNNER_TEMP': work}, work)
        check(f'github-release final check: {label}',
              (result.returncode == 0) == expect_ok and expect_text in result.stdout + result.stderr,
              result.stdout + result.stderr)
    local = {FILES[0]: 'A', FILES[3]: 'B'}
    rel = lambda assets, draft=False: [{'id': 7, 'tag': f'v{V}', 'draft': draft, 'assets': assets}]
    complete = [asset(1, FILES[0], 'A'), asset(2, FILES[3], 'B')]
    case('published, every file attached with this run\'s bytes', local, rel(complete), True, '')
    case('still a draft (finalising failed)', local, rel(complete, draft=True), False, 'still a draft')
    case('an asset missing', local, rel(complete[:1]), False, 'is not attached')
    case('an asset left as a starter placeholder', local,
         rel([complete[0], asset(2, FILES[3], '', 'starter', False)]), False, 'state starter')
    case('an asset with other bytes', local, rel([complete[0], asset(2, FILES[3], 'X')]), False, FILES[3])
    case('no release', local, [], False, 'Expected exactly one release')


def npm_files_check(tmp):
    data = {f: f'bytes of {f}' for f in FILES + [SUMS]}
    good = {sha(data[f]): {'source': TAG_SHA, 'signer': TAG_SHA, 'ref': f'refs/tags/v{V}'} for f in FILES}
    released = [asset(1, FILES[3], data[FILES[3]]), asset(2, FILES[4], data[FILES[4]]), asset(3, 'other.zip', 'z')]
    def case(label, files, attested, expect_ok, expect_text, releases=None):
        state = github(tmp, releases if releases is not None
                       else [{'id': 7, 'tag': f'v{V}', 'draft': False, 'assets': released}], attested)
        result = npm_files(state, npm_dir(tmp, files))
        calls_file = os.path.join(state, 'calls')
        calls = [json.loads(line) for line in open(calls_file)] if os.path.exists(calls_file) else []
        verified = [c for c in calls if c[:2] == ['attestation', 'verify']]
        flags_ok = all('--source-digest' in c and '--signer-digest' in c and '--deny-self-hosted-runners' in c
                       and c[c.index('--signer-workflow') + 1] == 'bloxbean/julc/.github/workflows/native-image-release.yml'
                       and c[c.index('--source-ref') + 1] == f'refs/tags/v{V}' for c in verified)
        check(f'npm-files.sh: {label}',
              (result.returncode == 0) == expect_ok and expect_text in result.stdout + result.stderr and flags_ok,
              result.stdout + result.stderr + json.dumps(verified[:1]))
        return verified
    verified = case('the five files and the checksum file, attested; the archives public', data, good, True,
                    f'public: julc-wasm-{V}.tar.gz')
    check('npm-files.sh verifies the attestation of all five files',
          sorted(os.path.basename(c[2]) for c in verified) == sorted(FILES), repr(verified))
    for missing in FILES + [SUMS]:
        case(f'{missing} missing from the artifact', {f: c for f, c in data.items() if f != missing}, good, False,
             f'have no {missing}')
    for replaced in FILES:
        case(f'{replaced} replaced (fails its attestation)',
             {f: c + (' edited' if f == replaced else '') for f, c in data.items()}, good, False, 'no attestation')
    case('an attestation of the same tag at another commit (--source-digest)', data,
         {k: {**v, 'source': 'b' * 40} for k, v in good.items()}, False, '--source-digest')
    case('an attestation by another commit\'s workflow (--signer-digest)', data,
         {k: {**v, 'signer': 'c' * 40} for k, v in good.items()}, False, '--signer-digest')
    case('an attestation for another ref', data, {k: {**v, 'ref': 'refs/heads/main'} for k, v in good.items()},
         False, '--source-ref')
    case('no published release (npm gets only versions whose archives are public)', data, good, False,
         'has no published GitHub release', releases=[])
    case('a draft release is invisible to the read-only token', data, good, False, 'has no published GitHub release',
         releases=[{'id': 7, 'tag': f'v{V}', 'draft': True, 'assets': released}])
    case('the release attaches another engine archive', data, good, False, f'does not attach {FILES[4]}',
         releases=[{'id': 7, 'tag': f'v{V}', 'draft': False,
                    'assets': [released[0], asset(2, FILES[4], 'rebuilt'), released[2]]}])
    case('the release lacks an engine archive', data, good, False, f'does not attach {FILES[3]}',
         releases=[{'id': 7, 'tag': f'v{V}', 'draft': False, 'assets': released[1:]}])


def graph():
    jobs = WORKFLOW['jobs']
    text = open(WORKFLOW_FILE).read()
    unpinned = [line.strip() for line in text.splitlines()
                if re.match(r'\s*(- )?uses: ', line) and not re.search(r'@[0-9a-f]{40} # v\d', line)
                and './.github/workflows/' not in line]
    check('every action is pinned to a full commit SHA with its version', not unpinned, '\n'.join(unpinned))
    with_id_token = sorted(j for j, s in jobs.items() if (s.get('permissions') or {}).get('id-token') == 'write')
    check('only attest and npm-publish hold id-token: write', with_id_token == ['attest', 'npm-publish'],
          repr(with_id_token))
    check('npm-publish runs in release-ops\' release-staging environment',
          jobs['npm-publish'].get('environment') == 'release-staging')
    check('npm-publish runs only in publish mode', "outputs.mode == 'publish'" in jobs['npm-publish']['if'])
    dry = jobs['npm-dry-run']
    check('npm-dry-run: no environment, no id-token, read-only, only in dry-run mode',
          'environment' not in dry and dry['permissions'] == {'contents': 'read', 'attestations': 'read', 'actions': 'read'}
          and "outputs.mode == 'dry-run'" in dry['if'])
    check('npm-verify: no environment, no id-token', 'environment' not in jobs['npm-verify']
          and jobs['npm-verify']['permissions'] == {'contents': 'read', 'attestations': 'read', 'actions': 'read'})
    uploads = {s['with']['name']: s['with'] for s in jobs['wasm']['steps'] if 'upload-artifact' in str(s.get('uses', ''))}
    npm_paths = uploads['npm-packages']['path'].split('\n')[:-1]
    check('the npm files are the npm-packages artifact, kept 14 days, and not release assets',
          uploads['npm-packages']['retention-days'] == 14 and len(npm_paths) == 6
          and not any('julclang-' in p or 'julc-npm-' in p for p in uploads['release-wasm']['path'].split('\n')),
          repr(uploads))
    release_uploads = [s['with'] for j in jobs.values() for s in j.get('steps', [])
                       if 'upload-artifact' in str(s.get('uses', '')) and s['with']['name'].startswith('release-')]
    check('every release-* artifact is kept 14 days (manual completion of the release)',
          len(release_uploads) == 4 and all(u['retention-days'] == 14 for u in release_uploads), repr(release_uploads))
    attest_download = [s for s in jobs['attest']['steps'] if 'download-artifact' in str(s.get('uses', ''))][0]
    check('attest attests the npm-packages artifact', attest_download['with']['name'] == 'npm-packages')
    for job in ['npm-dry-run', 'npm-publish', 'npm-verify']:
        names = [s.get('name') for s in jobs[job]['steps']]
        steps_by_name = {s.get('name'): s for s in jobs[job]['steps']}
        check(f'{job}: npm files from this run (push) or the tag\'s run (dispatch), then verified',
              steps_by_name["Download this run's npm files"]['if'] == "github.event_name == 'push'"
              and steps_by_name["Download the npm files of the tag's run"]['with'].get('run-id')
              == '${{ steps.run.outputs.run_id }}'
              and steps_by_name['Find the run that built the npm files']['if'] == "github.event_name == 'workflow_dispatch'"
              and names.index('Verify the npm files against their attestation and the release\'s archives')
              > names.index("Download the npm files of the tag's run"), repr(names))
    for job in ['npm-dry-run', 'npm-publish']:
        condition = jobs[job]['if']
        check(f'{job}: a status function, no bare always(), dispatch or both prerequisites',
              '!cancelled()' in condition and 'always()' not in condition
              and "github.event_name == 'workflow_dispatch'" in condition
              and "needs.github-release.result == 'success' && needs.attest.result == 'success'" in condition)
    pushed_only = ['uplc-conformance', 'validate', 'native-image', 'wasm', 'wasm-package-checks', 'native-image-playground',
                   'shadow-jars', 'attest', 'github-release', 'update-homebrew']
    check('every build job, attest, github-release and update-homebrew run only on push',
          all("github.event_name == 'push'" in str(jobs[j].get('if', '')) for j in pushed_only))
    check('github-release needs attest', 'attest' in jobs['github-release']['needs'])
    check('no draft-first release action', 'softprops/action-gh-release' not in text)
    check('one concurrency group for every npm publication, never cancelling a running one',
          jobs['npm-publish']['concurrency'] == {'group': 'npm-publish-julclang', 'cancel-in-progress': False},
          repr(jobs['npm-publish'].get('concurrency')))
    check('npm-gates holds contents: read only, and reads no environment',
          jobs['npm-gates']['permissions'] == {'contents': 'read'}
          and not any('environments' in str(s.get('run', '')) for s in jobs['npm-gates']['steps']))
    check('only npm-publish and update-homebrew use an environment, both release-staging',
          {j: s['environment'] for j, s in jobs.items() if 'environment' in s}
          == {'npm-publish': 'release-staging', 'update-homebrew': 'release-staging'})
    check('npm-verify gets the tags npm-publish chose',
          jobs['npm-publish']['outputs'].get('tags') == '${{ steps.publish.outputs.tags }}'
          and '--published-tags' in [s for s in jobs['npm-verify']['steps'] if s.get('name', '').startswith('Compare')][0]['run'])
    check('wasm holds contents: read only', jobs['wasm']['permissions'] == {'contents': 'read'})
    for job in ['npm-dry-run', 'npm-publish', 'npm-verify']:
        checkout = [s for s in jobs[job]['steps'] if str(s.get('uses', '')).startswith('actions/checkout@')][0]
        check(f'{job} checks out only julc-wasm/src/ci without credentials',
              checkout['with'] == {'persist-credentials': False, 'sparse-checkout': 'julc-wasm/src/ci',
                                   'sparse-checkout-cone-mode': False})
    owners = open(os.path.join(ROOT, '.github/CODEOWNERS')).read()
    check('CODEOWNERS: release owners own julc-wasm/src/ci',
          re.search(r'^/julc-wasm/src/ci/\s+@bloxbean/release-owners$', owners, re.M) is not None)


def main():
    tmp = tempfile.mkdtemp(prefix='julc-m4-steps-')
    os.environ['PATH'] = tool_dir(tmp) + os.pathsep + os.environ['PATH']
    gates(tmp)
    build_once(tmp)
    create_once(tmp)
    failed_upload_recovery(tmp)
    ref_check(tmp)
    release_verified(tmp)
    npm_run_lookup(tmp)
    npm_files_check(tmp)
    graph()
    shutil.rmtree(tmp)
    print(f'{failures} failure(s)')
    sys.exit(1 if failures else 0)


main()
