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
    # Attestations are statements: {subjects: {name: sha256}, source, signer, ref, run, attempt}.
    digest = hashlib.sha256(open(args[2], 'rb').read()).hexdigest()
    attested_file = os.path.join(state, 'attested.json')
    statements = json.load(open(attested_file)) if os.path.exists(attested_file) else []
    covering = [st for st in statements if digest in st['subjects'].values()]
    if not covering: sys.stderr.write('no attestation\n'); sys.exit(1)
    verified = []
    for st in covering:
        wrong = [f'{flag} {opt(flag)} != {st[key]}' for flag, key in [('--source-digest', 'source'),
                 ('--signer-digest', 'signer'), ('--source-ref', 'ref')] if opt(flag) is not None and opt(flag) != st[key]]
        if not wrong: verified.append(st)
    if not verified: sys.stderr.write(wrong[0] + '\n'); sys.exit(1)
    if opt('--format') == 'json':
        print(json.dumps([{'verificationResult': {
            'statement': {'subject': [{'name': n, 'digest': {'sha256': d}} for n, d in st['subjects'].items()]},
            'signature': {'certificate': {'runInvocationURI':
                f"https://github.com/bloxbean/julc/actions/runs/{st['run']}/attempts/{st['attempt']}"}}}}
            for st in verified]))
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
        if not jq:
            print(json.dumps({'draft': matches[0]['draft'], 'assets': [{'name': a['name'], 'state': a['state'],
                'digest': a.get('digest')} for a in matches[0]['assets']]})); sys.exit(0)
        for a in matches[0]['assets']:
            print(f"{a['name']} {a['state']} {a.get('digest', '')}")
        sys.exit(0)
    faults_file = os.path.join(state, 'faults.json')
    faults = json.load(open(faults_file)) if os.path.exists(faults_file) else {}
    if '/actions/workflows/native-image-release.yml/runs?' in url:
        if faults.get('runs'):
            sys.stderr.write('gh: Server Error (HTTP 502)\n'); sys.exit(1)
        runs_file = os.path.join(state, 'runs.json')
        runs = json.load(open(runs_file)) if os.path.exists(runs_file) else []
        query = dict(part.split('=', 1) for part in url.split('?', 1)[1].split('&'))
        matching = [r for r in runs if r['event'] == query['event'] and r['head_sha'] == query['head_sha']]
        if jq.startswith('.total_count'):
            print(faults.get('total_count', len(matching))); sys.exit(0)
        def row(r):  # the jq classification: a positive integer id and a non-blank string branch, or INVALID
            ok = (isinstance(r['id'], int) and not isinstance(r['id'], bool) and r['id'] > 0
                  and isinstance(r['head_branch'], str) and re.fullmatch(r'\S+', r['head_branch']))
            return f"{r['id']} {r['head_branch']}" if ok else 'INVALID'
        rows = [row(r) for r in matching[:faults.get('listed', len(matching))]]
        if 'duplicate' in faults:
            rows += [row(r) for r in matching if r['id'] == faults['duplicate']]
        if faults.get('malformed'):
            rows = ['abc ' + r.split(' ')[1] for r in rows]
        print('\n'.join(rows))
        sys.exit(0)
    if '/actions/artifacts/' in url and url.endswith('/zip'):
        import io, zipfile
        artifact_id = int(url.split('/actions/artifacts/')[1].split('/')[0])
        runs_file = os.path.join(state, 'runs.json')
        for r in (json.load(open(runs_file)) if os.path.exists(runs_file) else []):
            for artifact in r.get('artifacts', []):
                if artifact['id'] == artifact_id:
                    buffer = io.BytesIO()
                    with zipfile.ZipFile(buffer, 'w') as z:
                        for name, content in artifact.get('files', {}).items(): z.writestr(name, content)
                    sys.stdout.buffer.write(buffer.getvalue()); sys.exit(0)
        sys.stderr.write('gh: Not Found (HTTP 404)\n'); sys.exit(1)
    if '/actions/runs/' in url:
        runs_file = os.path.join(state, 'runs.json')
        runs = {r['id']: r for r in (json.load(open(runs_file)) if os.path.exists(runs_file) else [])}
        run_id = int(url.split('/actions/runs/')[1].split('/')[0])
        if '/jobs?' in url:
            assert 'filter=all' in url
            if run_id in faults.get('jobs', []):
                sys.stderr.write('gh: Forbidden (HTTP 403)\n'); sys.exit(1)
            for job in runs[run_id]['jobs']:
                if job['name'] == 'Attest the npm tarballs and engine archives' and job['conclusion'] == 'success':
                    print(job['id'])
            sys.exit(0)
        if url.endswith('/artifacts?per_page=100'):
            for artifact in runs[run_id].get('artifacts', []):
                if artifact['name'].startswith('release-'):
                    print(f"{artifact['id']} {artifact['name']} {'true' if artifact['expired'] else 'false'}")
            sys.exit(0)
        if '/artifacts?name=npm-packages' in url:
            if faults.get('artifacts'):
                sys.stderr.write('gh: Server Error (HTTP 500)\n'); sys.exit(1)
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
    json.dump(attested or [], open(os.path.join(state, 'attested.json'), 'w'))
    return state


ARTIFACT_RUN = 777


LABELS = ['macos-aarch64', 'linux-x86_64', 'linux-arm64', 'windows-x86_64']


def inventory(prefix=''):
    """The 14 files github-release attaches (R23-C16), with distinct contents."""
    names = ([f'julc-{V}-{l}.zip' for l in LABELS] + [f'julc-playground-{V}-{l}.zip' for l in LABELS]
             + [f'julc-compiler-all-{V}-all.jar', f'julc-vm-java-all-{V}-all.jar', FILES[3], FILES[4],
                f'julc-playground-static-{V}.tar.gz', f'julc-wasm-{V}-SHA256SUMS.txt'])
    return {name: f'{prefix}bytes of {name}' for name in names}


def statement(files, run=ARTIFACT_RUN, attempt=1, **identity):
    """One attest statement over the five npm files (as attest-build-provenance makes it: five subjects)."""
    return {'subjects': {f: sha(files[f]) for f in FILES}, 'source': TAG_SHA, 'signer': TAG_SHA,
            'ref': f'refs/tags/v{V}', 'run': run, 'attempt': attempt, **identity}


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
        shutil.copytree(os.path.join(ROOT, 'julc-wasm', 'src', 'ci'), os.path.join(repo, 'julc-wasm', 'src', 'ci'))
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
    # R23-C3: the gate and the publisher classify with one function, so they agree on every input, including those a
    # bash pattern would have let through.
    for form in ['0.1.0+x', '01.1.0', '0.1.0-', '0.1.9007199254740993', f"0.1.0-pre{'1' * 250}", '0.1.0-pre1+build']:
        case(f'{form[:40]}: refused like the publisher refuses it', f'refs/tags/v{form}', props(form),
             expect_error='npm has no rule for')
    for form, mode in [('0.1.0', 'publish'), ('0.1.0-pre22', 'publish'), ('0.1.0-beta1', 'publish'),
                       ('0.1.0-rc1', 'publish'), ('0.1.0-pre22-rc1', 'dry-run')]:
        decided = subprocess.run(['node', '--input-type=module', '-e',
                                  f"import {{versionKind}} from '{os.path.join(ROOT, 'julc-wasm/src/ci/npm-release.mjs')}';"
                                  f"console.log(versionKind('{form}'))"], text=True, capture_output=True).stdout.strip()
        check(f'npm-gates and the publisher agree on {form}: {decided} -> {mode}',
              (decided == 'pre-rc') == (mode == 'dry-run'))
        case(f'{form} (the publisher says {decided})', f'refs/tags/v{form}', props(form), mode)
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
        result = run(step('release-guard', 'Refuse to build a released version again'),
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
    full = inventory()
    state = case('no release yet: created published, every file of the inventory attached once', full, [], True,
                 'Created release', expect_uploads=sorted(full))
    created = releases_of(state)[0]
    check('github-release create-once: the release is created published, prerelease, make_latest legacy',
          created['draft'] is False and created['fields'] == {'tag_name': f'v{V}', 'name': V, 'prerelease': 'true',
                                                                'draft': 'false', 'make_latest': 'legacy'},
          repr(created))
    rest = sorted(n for n in full if n != name)
    case('the same bytes already attached (digest)', full, [rel([asset(5, name, full[name])])], True, 'Keeping',
         expect_uploads=rest)
    case('the same bytes already attached (no digest field: downloaded)', full,
         [rel([asset(5, name, full[name], digest=False)])], True, 'Keeping', expect_uploads=rest)
    case('other bytes already attached: stops before uploading anything', full, [rel([asset(5, name, 'A')])], False,
         'already attached with other bytes', expect_uploads=[])
    case('a failed upload placeholder (HTTP 502) is deleted and uploaded again', full,
         [rel([asset(6, name, '', state='starter', digest=False)])], True, 'failed upload', expect_deleted=[6],
         expect_uploads=sorted(full))
    case('a placeholder and a differing asset', full,
         [rel([asset(6, name, '', state='starter', digest=False), asset(8, FILES[3], 'D')])], False, 'other bytes',
         expect_deleted=[6], expect_uploads=[])
    case('a draft with the tag (this workflow never makes one): refused', full, [rel([], draft=True)], False,
         'is a draft')
    case('two releases (a draft too) with the tag', full, [rel([], i=7), rel([], draft=True, i=8)], False, '2 releases')
    case('another tag\'s release is not this one', full,
         [{'id': 3, 'tag': f'v{V}0', 'draft': False, 'assets': [asset(5, name, 'A')]}], True, 'Created release')
    # R23-C16: the inventory, before anything is created or changed.
    lacking = {n: c for n, c in full.items() if 'windows' not in n or 'playground' in n}
    state = case('a CLI zip missing from the download: nothing created', lacking, [], False,
                 f'> julc-{V}-windows-x86_64.zip', expect_uploads=[])
    check('github-release create-once: no release is created without the inventory', releases_of(state) == [],
          repr(releases_of(state)))
    case('a file outside the inventory (an npm tarball): nothing created', {**full, FILES[0]: 'npm'}, [], False,
         f'< {FILES[0]}', expect_uploads=[])


def failed_upload_recovery(tmp):
    """M4-C9: an upload fails (HTTP 502), a whole-workflow re-run is refused, and the release is still finished from the
    original bytes: by re-running the failed job, or by hand from the original run's artifacts. Then the npm side (a
    dispatch) finds the run, and its npm files check against their attestation and the finished release's archives."""
    npm = {f: f'bytes of {f}' for f in FILES + [SUMS]}
    data = {**inventory(), FILES[3]: npm[FILES[3]], FILES[4]: npm[FILES[4]]}
    attested = [statement(npm)]
    for recovery in ['re-run failed jobs', 'by hand after a refused full re-run']:
        state = github(tmp, [], attested)
        result, _ = create_step(tmp, state, data, fail_upload=FILES[3])
        partial = releases_of(state)[0]
        check(f'C9 ({recovery}): the upload fails and leaves a published release with a starter placeholder',
              result.returncode != 0 and not partial['draft']
              and any(a['state'] == 'starter' and a['name'] == FILES[3] for a in partial['assets']),
              result.stdout + result.stderr + repr(partial))
        work = tempfile.mkdtemp(dir=tmp)
        rerun = run(step('release-guard', 'Refuse to build a released version again'),
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
                               'GITHUB_REPOSITORY': 'bloxbean/julc', 'SOURCE_DIGEST': TAG_SHA, 'SIGNER_DIGEST': TAG_SHA,
                               'NPM_FILES_RUN': str(ARTIFACT_RUN)})


def npm_run_lookup(tmp):
    """npm-run.sh: the one artifact a recovery dispatch takes the npm files from (R23-C1, R23-C2)."""
    attest = lambda conclusion, attempt=1, i=1: {'id': i, 'name': 'Attest the npm tarballs and engine archives',
                                                 'conclusion': conclusion, 'run_attempt': attempt}
    live = [{'id': 900, 'name': 'npm-packages', 'expired': False}]
    def run_(i, jobs, artifacts=live, sha_=TAG_SHA, branch=f'v{V}', event='push'):
        return {'id': i, 'event': event, 'head_sha': sha_, 'head_branch': branch, 'jobs': jobs, 'artifacts': artifacts}
    def case(label, runs, expect=None, expect_text='', faults=None):
        state = github(tmp, [])
        json.dump(runs, open(os.path.join(state, 'runs.json'), 'w'))
        json.dump(faults or {}, open(os.path.join(state, 'faults.json'), 'w'))
        out = os.path.join(tempfile.mkdtemp(dir=tmp), 'out')
        open(out, 'w').close()
        result = subprocess.run(['bash', NPM_RUN, V], text=True, capture_output=True,
                                env={**os.environ, 'FAKE_GH': state, 'FAKE_TOKEN': 'read', 'GH_TOKEN': 'x',
                                     'GITHUB_REPOSITORY': 'bloxbean/julc', 'SOURCE_DIGEST': TAG_SHA,
                                     'GITHUB_OUTPUT': out})
        found = open(out).read().strip()
        ok = (result.returncode == 0 and found == f'run_id={expect[0]}\nartifact_id={expect[1]}') if expect else (
            result.returncode != 0 and expect_text in result.stderr and not found)
        check(f'npm-run.sh: {label}', ok, result.stdout + result.stderr + found)
    gone = 'are no longer available (artifacts are kept 14 days)'
    unreadable = 'Cannot read the run history'
    case('the tag\'s push run, attested, artifact live', [run_(11, [attest('success')])], expect=(11, 900))
    case('attested in attempt 1, a later attempt\'s attest skipped (one artifact)',
         [run_(12, [attest('success', 1, 1), attest('skipped', 2, 2)])], expect=(12, 900))
    case('a failed attest in attempt 1, then a second upload attested in attempt 2: two artifacts, refused',
         [run_(23, [attest('failure', 1, 1), attest('success', 2, 2)],
               artifacts=[{'id': 901, 'name': 'npm-packages', 'expired': False},
                          {'id': 902, 'name': 'npm-packages', 'expired': False}])], expect_text='2 npm-packages artifacts')
    case('the same, the first attempt\'s artifact expired: the live one', [run_(24, [attest('failure', 1, 1),
         attest('success', 2, 2)], artifacts=[{'id': 901, 'name': 'npm-packages', 'expired': True},
                                              {'id': 902, 'name': 'npm-packages', 'expired': False}])], expect=(24, 902))
    case('a second run for the tag that stopped in wasm is not chosen',
         [run_(13, [attest('success')]), run_(14, [attest('skipped')], artifacts=[])], expect=(13, 900))
    case('a run of another tag on the same commit is not chosen',
         [run_(15, [attest('success')]), run_(16, [attest('success')], branch=f'v{V}-rc1')], expect=(15, 900))
    no_run = 'No run of native-image-release.yml for the push'
    not_yet = 'has a successful attestation yet'
    case('no run for the tag', [], expect_text=no_run)
    case('a run whose attest failed: not expiry, re-run it', [run_(17, [attest('failure')])], expect_text=not_yet)
    case('a run whose attest has not run yet', [run_(32, [attest('queued')])], expect_text=not_yet)
    case('a dispatch run is not the tag\'s push run', [run_(18, [attest('success')], event='workflow_dispatch')],
         expect_text=no_run)
    case('a run at another commit', [run_(19, [attest('success')], sha_='b' * 40)], expect_text=no_run)
    case('the artifact has expired (14 days)', [run_(20, [attest('success')],
                                                    artifacts=[{'id': 1, 'name': 'npm-packages', 'expired': True}])],
         expect_text=gone)
    case('two attested runs: a maintainer decides', [run_(21, [attest('success')]), run_(22, [attest('success')])],
         expect_text='Several runs')
    case('two attested runs, one\'s jobs unreadable (HTTP 403): nothing is chosen',
         [run_(25, [attest('success')]), run_(26, [attest('success')])], expect_text=unreadable, faults={'jobs': [26]})
    case('the run list unreadable (HTTP 502)', [run_(27, [attest('success')])], expect_text=unreadable,
         faults={'runs': True})
    case('more than 1,000 runs: completeness cannot be established', [run_(28, [attest('success')])],
         expect_text='incomplete', faults={'total_count': 1001})
    case('fewer runs read than the total', [run_(29, [attest('success')]), run_(30, [attest('skipped')])],
         expect_text='1 distinct of 1 rows read, 2 reported', faults={'listed': 1})
    case('the artifact list unreadable (HTTP 500)', [run_(31, [attest('success')])], expect_text=unreadable,
         faults={'artifacts': True})
    case('overlapping pages: a run listed twice, the total met by rows (R23-C8)',
         [run_(33, [attest('skipped')], artifacts=[]), run_(34, [attest('success')]), run_(35, [attest('skipped')])],
         expect_text='inconsistent', faults={'duplicate': 34, 'listed': 2})
    case('a malformed run id', [run_(36, [attest('success')])], expect_text='malformed', faults={'malformed': True})
    case('a valid candidate and an unclassifiable row (id null, R23-C15)',
         [run_(37, [attest('success')]), {**run_(38, [attest('skipped')]), 'id': None}], expect_text='malformed')
    case('a valid candidate and a row with no branch', [run_(39, [attest('success')]),
                                                       {**run_(40, [attest('skipped')]), 'head_branch': None}],
         expect_text='malformed')


def runbook_manual_download(tmp):
    """RUNBOOK.md's download of the original run's release-* artifacts by id (R23-C13)."""
    text = open(os.path.join(ROOT, 'adr/evidence/077-m4/RUNBOOK.md')).read()
    start = text.index('  (\n    set -euo pipefail\n    run=<original run id>')
    block = text[start:text.index('  )\n  ```', start) + 4].replace('\n  ', '\n').strip().replace('<original run id>', '777')
    files = inventory()
    def artifact(i, name, contents, expired=False):
        return {'id': i, 'name': name, 'expired': expired, 'files': contents}
    by_artifact = {**{f'release-cli-{l}': {f'julc-{V}-{l}.zip': files[f'julc-{V}-{l}.zip']} for l in LABELS},
                   **{f'release-playground-{l}': {f'julc-playground-{V}-{l}.zip': files[f'julc-playground-{V}-{l}.zip']}
                      for l in LABELS},
                   'release-shadow-jars': {n: files[n] for n in files if n.endswith('.jar')},
                   'release-wasm': {n: files[n] for n in [FILES[3], FILES[4], f'julc-playground-static-{V}.tar.gz',
                                                          f'julc-wasm-{V}-SHA256SUMS.txt']}}
    ten = [artifact(100 + i, name, contents) for i, (name, contents) in enumerate(sorted(by_artifact.items()))]
    def case(label, artifacts, expect_ok, expect_text):
        state = github(tmp, [])
        json.dump([{'id': 777, 'event': 'push', 'head_sha': TAG_SHA, 'head_branch': f'v{V}', 'jobs': [],
                    'artifacts': artifacts}], open(os.path.join(state, 'runs.json'), 'w'))
        work = tempfile.mkdtemp(dir=tmp)
        result = subprocess.run(['bash', '-c', block], cwd=work, text=True, capture_output=True,
                                env={**os.environ, 'FAKE_GH': state, 'FAKE_TOKEN': 'read'})
        got = sorted(os.path.relpath(os.path.join(d, f), os.path.join(work, 'dist'))
                     for d, _, fs in os.walk(os.path.join(work, 'dist')) for f in fs) if expect_ok else []
        check(f'runbook manual download: {label}', (result.returncode == 0) == expect_ok
              and expect_text in result.stdout + result.stderr
              and (not expect_ok or len(got) == 14), result.stdout + result.stderr + repr(got))
    case('the ten artifacts, each once: 14 files in dist/<artifact>/', ten, True, f'release-wasm/{FILES[4]}')
    rebuilt = ten + [artifact(200, f'release-cli-{LABELS[3]}', {f'julc-{V}-{LABELS[3]}.zip': 'rebuilt bytes'})]
    case('a CLI zip artifact uploaded twice with different bytes (a rebuild): refused', rebuilt, False,
         f'uploaded more than once (a rebuild): release-cli-{LABELS[3]}')
    case('an artifact missing', ten[1:], False, 'not the ten expected')
    case('an artifact expired', ten[:-1] + [{**ten[-1], 'expired': True}], False, 'expired')


def runbook_release_check(tmp):
    """RUNBOOK.md's check of a release completed by hand (R23-C5, R23-C9, R23-F5), on gh run download's nested layout."""
    text = open(os.path.join(ROOT, 'adr/evidence/077-m4/RUNBOOK.md')).read()
    start = text.index('  (\n    set -euo pipefail\n    version=<version>')
    block = text[start:text.index('  )\n  ```', start) + 4].replace('\n  ', '\n').strip().replace('<version>', V)
    labels = ['macos-aarch64', 'linux-x86_64', 'linux-arm64', 'windows-x86_64']
    inventory = {**{f'release-cli-{l}/julc-{V}-{l}.zip': f'cli {l}' for l in labels},
                 **{f'release-playground-{l}/julc-playground-{V}-{l}.zip': f'pg {l}' for l in labels},
                 f'release-shadow-jars/julc-compiler-all-{V}-all.jar': 'compiler',
                 f'release-shadow-jars/julc-vm-java-all-{V}-all.jar': 'vm', f'release-wasm/{FILES[3]}': 'vm archive',
                 f'release-wasm/{FILES[4]}': 'full archive', f'release-wasm/julc-playground-static-{V}.tar.gz': 'static',
                 f'release-wasm/julc-wasm-{V}-SHA256SUMS.txt': 'sums'}
    def case(label, files, assets, expect_ok, expect_text, draft=False, path_extra=None):
        state = github(tmp, [{'id': 7, 'tag': f'v{V}', 'draft': draft, 'assets': assets}])
        work = tempfile.mkdtemp(dir=tmp)
        os.makedirs(os.path.join(work, 'dist'))
        for rel, content in files.items():
            os.makedirs(os.path.dirname(os.path.join(work, 'dist', rel)), exist_ok=True)
            open(os.path.join(work, 'dist', rel), 'w').write(content)
        env = {**os.environ, 'FAKE_GH': state, 'FAKE_TOKEN': 'write'}
        if path_extra is not None: env['PATH'] = path_extra
        result = subprocess.run(['bash', '-c', block], cwd=work, env=env, text=True, capture_output=True)
        check(f'runbook release check: {label}', (result.returncode == 0) == expect_ok
              and expect_text in result.stdout + result.stderr, result.stdout + result.stderr)
    released = lambda files: [asset(i, os.path.basename(r), c) for i, (r, c) in enumerate(files.items())]
    case('the 14 files, nested as gh run download leaves them, all attached', inventory, released(inventory), True,
         f'ok julc-{V}-windows-x86_64.zip')
    missing = {r: c for r, c in inventory.items() if 'windows' not in r or 'playground' in r}
    case('the same CLI zip absent from the artifacts and the release', missing, released(missing), False,
         f'> julc-{V}-windows-x86_64.zip')
    case('an empty download and an empty release', {}, [], False, 'differ from the inventory')
    changed = {**inventory, f'release-wasm/{FILES[4]}': 'another archive'}
    case('a differing asset', changed, released(inventory), False, f'DIFFERENT {FILES[4]}')
    case('an asset not in the inventory (an npm tarball)', inventory,
         released(inventory) + [asset(99, FILES[0], 'npm')], False, f'< {FILES[0]}')
    case('a file found twice under dist', {**inventory, f'extra/julc-{V}-linux-arm64.zip': 'cli linux-arm64'},
         released(inventory), False, 'found twice')
    case('a draft', inventory, released(inventory), False, 'is a draft', draft=True)
    tools = os.path.join(tmp, 'no-sha-tools')
    os.makedirs(tools, exist_ok=True)
    for tool in ['bash', 'gh', 'jq', 'sort', 'cut', 'awk', 'diff', 'find', 'mktemp', 'uniq', 'cat', 'basename', 'python3']:
        found = shutil.which(tool)
        if found and not os.path.exists(os.path.join(tools, tool)): os.symlink(found, os.path.join(tools, tool))
    case('no sha256sum and no shasum: refused up front', inventory, released(inventory), False,
         'needs sha256sum or shasum', path_extra=tools)


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
    case('an asset that is not this run\'s file (R23-C16)', local, rel(complete + [asset(9, FILES[0], 'npm')]), False,
         f'< {FILES[0]}')


def npm_files_check(tmp):
    data = {f: f'bytes of {f}' for f in FILES + [SUMS]}
    good = [statement(data)]
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
                       and c[c.index('--source-ref') + 1] == f'refs/tags/v{V}' and c[c.index('--format') + 1] == 'json'
                       for c in verified)
        texts = expect_text if isinstance(expect_text, tuple) else (expect_text,)
        check(f'npm-files.sh: {label}',
              (result.returncode == 0) == expect_ok and any(t in result.stdout + result.stderr for t in texts)
              and flags_ok, result.stdout + result.stderr + json.dumps(verified[:1]))
        return verified
    verified = case('the five files and the checksum file, one attested set of this run; the archives public', data,
                    good, True, f'public: julc-wasm-{V}.tar.gz')
    check('npm-files.sh verifies one attestation, the first file\'s, and reads its statement',
          [os.path.basename(c[2]) for c in verified] == [FILES[0]], repr(verified))
    for missing in FILES + [SUMS]:
        case(f'{missing} missing from the artifact', {f: c for f, c in data.items() if f != missing}, good, False,
             f'have no {missing}')
    for replaced in FILES:
        case(f'{replaced} replaced (not the attested set)',
             {f: c + (' edited' if f == replaced else '') for f, c in data.items()}, good, False,
             ('no attestation', 'not one attested set'))
    case('an attestation of the same tag at another commit (--source-digest)', data, [statement(data, source='b' * 40)],
         False, '--source-digest')
    case('an attestation by another commit\'s workflow (--signer-digest)', data, [statement(data, signer='c' * 40)],
         False, '--signer-digest')
    case('an attestation for another ref', data, [statement(data, ref='refs/heads/main')], False, '--source-ref')
    case('an attestation of another run', data, [statement(data, run=999)], False, 'not one attested set')
    case('a run invocation with attempt 0 (R23-C14)', data, [statement(data, attempt=0)], False, 'not one attested set')
    case('a run invocation with a trailing path', data, [statement(data, attempt='1/extra')], False,
         'not one attested set')
    case('a run invocation of a run whose id starts with this one\'s', data, [statement(data, run=7770)], False,
         'not one attested set')
    # R23-C7: two attempts that signed builds with identical archives but different client and wrapper bytes.
    other = {**data, FILES[0]: 'client of attempt 2', FILES[1]: 'vm package of attempt 2', FILES[2]: 'full of attempt 2'}
    both = [statement(data, attempt=1), statement(other, attempt=2)]
    mixed = {**other, FILES[0]: data[FILES[0]]}
    case('a set mixed from two attempts\' attested files is refused', mixed, both, False, 'not one attested set')
    case('a set mixed the other way is refused', {**data, FILES[1]: other[FILES[1]]}, both, False, 'not one attested set')
    case('the original artifact expired and the later attempt\'s remains: one set, accepted', other, both, True,
         'the five files are one set')
    case('a statement with a sixth subject is not this set', data,
         [{**statement(data), 'subjects': {**statement(data)['subjects'], 'extra.tgz': sha('x')}}], False,
         'not one attested set')
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
    # This workflow and every local reusable workflow it calls (R23-C11), followed transitively.
    files, seen = [WORKFLOW_FILE], set()
    unpinned = []
    while files:
        current = files.pop()
        if current in seen: continue
        seen.add(current)
        for line in open(current).read().splitlines():
            if not re.match(r'\s*(- )?uses: ', line): continue
            local = re.search(r'uses: (\./\.github/workflows/[^\s@]+)', line)
            if local:
                files.append(os.path.join(ROOT, local.group(1)))
            elif not re.search(r'@[0-9a-f]{40} # v\d', line):
                unpinned.append(f'{os.path.relpath(current, ROOT)}: {line.strip()}')
    check(f'every action is pinned to a full commit SHA with its version, in {len(seen)} workflow files',
          not unpinned and len(seen) >= 2, '\n'.join(unpinned))
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
              and steps_by_name["Download the npm files of the tag's run"]['with'].get('artifact-ids')
              == '${{ steps.run.outputs.artifact_id }}'
              and steps_by_name["Download the npm files of the tag's run"]['with'].get('merge-multiple') is True
              and 'name' not in steps_by_name["Download the npm files of the tag's run"]['with']
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
    producers = [j for j, spec in jobs.items() if any('upload-artifact' in str(st.get('uses', '')) and (
        str(st['with']['name']).startswith('release-') or st['with']['name'] == 'npm-packages')
        for st in spec.get('steps', []))]
    check(f'every job that uploads a release file needs release-guard ({", ".join(sorted(producers))}, R23-C13)',
          sorted(producers) == ['native-image', 'native-image-playground', 'shadow-jars', 'wasm']
          and all('release-guard' in jobs[j]['needs'] for j in producers), repr(producers))
    guard = jobs['release-guard']
    check('release-guard: build-free, read-only, push only',
          guard['permissions'] == {'contents': 'read'} and "github.event_name == 'push'" in guard['if']
          and not any('uses' in st for st in guard['steps']))
    check('wasm no longer carries its own release check',
          not any(st.get('name') == 'Refuse to build a released version again' for st in jobs['wasm']['steps']))
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
    runbook_manual_download(tmp)
    runbook_release_check(tmp)
    graph()
    shutil.rmtree(tmp)
    print(f'{failures} failure(s)')
    sys.exit(1 if failures else 0)


main()
