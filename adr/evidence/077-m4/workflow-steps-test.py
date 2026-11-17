#!/usr/bin/env python3
"""ADR-077 M4: runs the bash of native-image-release.yml's steps that decide the npm gates, the build-once check, the
create-once release assets and their final verification, and julc-wasm/src/ci/release-assets.sh, locally, with a fake
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
RELEASE_ASSETS = os.path.join(ROOT, 'julc-wasm/src/ci/release-assets.sh')
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
    if url.endswith('/releases?per_page=100'):
        tag = re.search(r'tag_name == "([^"]+)"', jq).group(1)
        for r in visible:
            if r['tag'] != tag: continue
            if '.assets[].name' in jq:
                for a in r['assets']: print(a['name'])
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
    case('a release candidate with npm_publish = true', f'refs/tags/v{V}-rc1', props(f'{V}-rc1'), 'dry-run')
    case('a 1.0 release candidate', 'refs/tags/v1.0.0-rc.1', props('1.0.0-rc.1'), 'dry-run')
    case('"source" is not a candidate', 'refs/tags/v0.1.0-source1', props('0.1.0-source1'), 'publish')
    case('github_release = false', f'refs/tags/v{V}', props(V, gh='false'), 'none')
    case('a dispatch from a branch', 'refs/heads/main', props(V), expect_error='runs on a release tag, not refs/heads/main',
         event='workflow_dispatch')
    case('a tag that does not match the version', f'refs/tags/v{V}', props('0.1.0-pre23'),
         expect_error='does not match version 0.1.0-pre23')
    case('a snapshot', 'refs/tags/v0.1.0-SNAPSHOT', props('0.1.0-SNAPSHOT'), expect_error='snapshot version is never published')


def build_once(tmp):
    def case(label, releases, attested, expect_ok, expect_text):
        state = github(tmp, releases, attested)
        work = tempfile.mkdtemp(dir=tmp)
        result = run(step('wasm', 'Refuse to build a released version again'),
                     {'FAKE_GH': state, 'FAKE_TOKEN': 'read', 'VERSION': V, 'GITHUB_REPOSITORY': 'bloxbean/julc',
                      'GITHUB_SHA': TAG_SHA, 'GITHUB_WORKFLOW_SHA': TAG_SHA, 'RUNNER_TEMP': work}, work)
        check(f'wasm build-once: {label}',
              (result.returncode == 0) == expect_ok and expect_text in result.stdout + result.stderr,
              result.stdout + result.stderr)
    tgzs = [asset(i, FILES[i], 'x' + str(i)) for i in range(3)]
    good = {sha(a['bytes']): {'source': TAG_SHA, 'signer': TAG_SHA, 'ref': f'refs/tags/v{V}'} for a in tgzs}
    case('no release yet', [], None, True, 'building')
    case('a release without npm files (before M4)',
         [{'id': 1, 'tag': f'v{V}', 'draft': False, 'assets': [asset(9, f'julc-{V}-linux-x86_64.zip', 'z')]}], None,
         True, 'building')
    case('a release with attested npm tarballs', [{'id': 1, 'tag': f'v{V}', 'draft': False, 'assets': tgzs}], good,
         False, 'attestation: verified')
    case('a release with unattested npm tarballs', [{'id': 1, 'tag': f'v{V}', 'draft': False, 'assets': tgzs[:1]}], {},
         False, 'attestation: NOT verified')
    case('tarballs attested at another commit are not verified',
         [{'id': 1, 'tag': f'v{V}', 'draft': False, 'assets': tgzs}],
         {k: {**v, 'source': 'b' * 40} for k, v in good.items()}, False, 'attestation: NOT verified')
    case('another version\'s similarly named asset does not count',
         [{'id': 1, 'tag': f'v{V}', 'draft': False, 'assets': [asset(3, f'julclang-wasm-client-{V}0.tgz', 'q')]}], None,
         True, 'building')
    case('a draft is invisible to the read-only token (github-release, which sees it, refuses differing bytes)',
         [{'id': 1, 'tag': f'v{V}', 'draft': True, 'assets': tgzs}], good, True, 'building')


def create_once(tmp):
    def case(label, local, releases, expect_ok, expect_text, expect_deleted=()):
        state = github(tmp, releases)
        work = tempfile.mkdtemp(dir=tmp)
        os.makedirs(os.path.join(work, 'dist'))
        for name, data in local.items():
            open(os.path.join(work, 'dist', name), 'w').write(data)
        result = run(step('github-release', 'Keep completed assets, clear failed uploads'),
                     {'FAKE_GH': state, 'FAKE_TOKEN': 'write', 'VERSION': V, 'GITHUB_REPOSITORY': 'bloxbean/julc',
                      'RUNNER_TEMP': work}, work)
        deleted_file = os.path.join(state, 'deleted')
        deleted = open(deleted_file).read().split() if os.path.exists(deleted_file) else []
        check(f'github-release create-once: {label}',
              (result.returncode == 0) == expect_ok and expect_text in result.stdout + result.stderr
              and deleted == [str(d) for d in expect_deleted], result.stdout + result.stderr + repr(deleted))
    name = FILES[2]
    rel = lambda assets, draft=False, i=7: {'id': i, 'tag': f'v{V}', 'draft': draft, 'assets': assets}
    case('no release yet', {name: 'A'}, [], True, f'No release (published or draft) for v{V} yet')
    case('the same bytes already attached (digest)', {name: 'A'}, [rel([asset(5, name, 'A')])], True, 'Keeping')
    case('the same bytes already attached (no digest field: downloaded)', {name: 'A'},
         [rel([asset(5, name, 'A', digest=False)])], True, 'Keeping')
    case('other bytes already attached', {name: 'B'}, [rel([asset(5, name, 'A')])], False,
         'already attached with other bytes')
    case('a failed upload placeholder (HTTP 502)', {name: 'A'}, [rel([asset(6, name, '', state='starter', digest=False)])],
         True, 'failed upload', expect_deleted=[6])
    case('a placeholder and a differing asset', {name: 'A', 'other.zip': 'C'},
         [rel([asset(6, name, '', state='starter', digest=False), asset(8, 'other.zip', 'D')])], False, 'other bytes',
         expect_deleted=[6])
    case('a draft left by a failed run, with a placeholder and the same bytes', {name: 'A', 'other.zip': 'C'},
         [rel([asset(6, name, '', state='starter', digest=False), asset(8, 'other.zip', 'C')], draft=True)], True,
         'draft: true', expect_deleted=[6])
    case('a draft with other bytes (GET /releases/tags would 404 and miss it)', {name: 'B'},
         [rel([asset(5, name, 'A')], draft=True)], False, 'other bytes')
    case('two releases (a draft too) with the tag', {name: 'A'}, [rel([], i=7), rel([], draft=True, i=8)], False,
         '2 releases')
    case('another tag\'s release is not this one', {name: 'B'},
         [{'id': 3, 'tag': f'v{V}0', 'draft': False, 'assets': [asset(5, name, 'A')]}], True, 'No release')


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


def release_assets(tmp):
    data = {f: f'bytes of {f}' for f in FILES + [SUMS]}
    good = {sha(data[f]): {'source': TAG_SHA, 'signer': TAG_SHA, 'ref': f'refs/tags/v{V}'} for f in FILES}
    def case(label, assets, attested, expect_ok, expect_text, draft=False):
        state = github(tmp, [{'id': 7, 'tag': f'v{V}', 'draft': draft, 'assets': assets}], attested)
        work = tempfile.mkdtemp(dir=tmp)
        result = subprocess.run(['bash', RELEASE_ASSETS, V, os.path.join(work, 'assets')], text=True, capture_output=True,
                                env={**os.environ, 'FAKE_GH': state, 'FAKE_TOKEN': 'read', 'GH_TOKEN': 'x',
                                     'GITHUB_REPOSITORY': 'bloxbean/julc', 'SOURCE_DIGEST': TAG_SHA,
                                     'SIGNER_DIGEST': TAG_SHA})
        calls_file = os.path.join(state, 'calls')
        calls = [json.loads(line) for line in open(calls_file)] if os.path.exists(calls_file) else []
        verified = [c for c in calls if c[:2] == ['attestation', 'verify']]
        flags_ok = all('--source-digest' in c and '--signer-digest' in c and '--deny-self-hosted-runners' in c
                       and c[c.index('--signer-workflow') + 1] == 'bloxbean/julc/.github/workflows/native-image-release.yml'
                       and c[c.index('--source-ref') + 1] == f'refs/tags/v{V}' for c in verified)
        check(f'release-assets.sh: {label}',
              (result.returncode == 0) == expect_ok and expect_text in result.stdout + result.stderr and flags_ok,
              result.stdout + result.stderr + json.dumps(verified[:1]))
        return verified
    all_assets = [asset(i, f, data[f]) for i, f in enumerate(FILES + [SUMS])]
    verified = case('all five files and the checksum file, attested', all_assets, good, True,
                    f'attested: julc-wasm-{V}.tar.gz')
    check('release-assets.sh verifies the attestation of all five files',
          sorted(os.path.basename(c[2]) for c in verified) == sorted(FILES), repr(verified))
    for missing in FILES + [SUMS]:
        case(f'{missing} deleted from the release', [a for a in all_assets if a['name'] != missing], good, False,
             f'has no asset {missing}')
    for replaced in FILES:
        case(f'{replaced} replaced in the release (fails its attestation)',
             [asset(i, f, data[f] + (' edited' if f == replaced else '')) for i, f in enumerate(FILES + [SUMS])], good,
             False, 'no attestation')
    case('an attestation of the same tag at another commit (--source-digest)', all_assets,
         {k: {**v, 'source': 'b' * 40} for k, v in good.items()}, False, '--source-digest')
    case('an attestation by another commit\'s workflow (--signer-digest)', all_assets,
         {k: {**v, 'signer': 'c' * 40} for k, v in good.items()}, False, '--signer-digest')
    case('an attestation for another ref', all_assets, {k: {**v, 'ref': 'refs/heads/main'} for k, v in good.items()},
         False, '--source-ref')
    case('a draft release is not found by the read-only token', all_assets, good, False, 'release not found', draft=True)


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
    check('npm-publish runs in the npm-publish environment', jobs['npm-publish'].get('environment') == 'npm-publish')
    check('npm-publish runs only in publish mode', "outputs.mode == 'publish'" in jobs['npm-publish']['if'])
    dry = jobs['npm-dry-run']
    check('npm-dry-run: no environment, no id-token, read-only, only in dry-run mode',
          'environment' not in dry and dry['permissions'] == {'contents': 'read', 'attestations': 'read'}
          and "outputs.mode == 'dry-run'" in dry['if'])
    check('npm-verify: no environment, no id-token', 'environment' not in jobs['npm-verify']
          and jobs['npm-verify']['permissions'] == {'contents': 'read', 'attestations': 'read'})
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
    release_verified(tmp)
    release_assets(tmp)
    graph()
    shutil.rmtree(tmp)
    print(f'{failures} failure(s)')
    sys.exit(1 if failures else 0)


main()
