// ADR-077 M4 and Verification "Packages": the release workflow's npm-publish and npm-verify scripts
// (julc-wasm/src/ci) run with the npm CLI against a local registry (npm-registry-stub.mjs), for each retry case of
// D8a and I11. Provenance cannot be produced outside GitHub Actions: the scripts run with --no-provenance (accepted only
// for a loopback registry), and the registry attaches an unsigned provenance statement to what it publishes, as npm
// does under trusted publishing, or is seeded with versions with or without one. The publish job reads only that
// statement; signatures are npm-verify's, tested on a real attestation in npm-release.test.mjs.
//   JULC_TEST_NPM=<npm command> selects the npm CLI (default: npm on PATH).
import assert from 'node:assert/strict';
import {spawn, spawnSync} from 'node:child_process';
import {cpSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {startRegistry} from './npm-registry-stub.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const ci = path.join(here, '..', '..', 'ci');
const NPM = process.env.JULC_TEST_NPM ?? 'npm';
const VERSION = '0.1.0-pre22';
const CLIENT = '@julclang/wasm-client';
const VM = '@julclang/julc-vm-wasm';
const FULL = '@julclang/julc-wasm';
const identity = version => ({repository: 'bloxbean/julc', workflow: '.github/workflows/native-image-release.yml',
  ref: `refs/tags/v${version}`});
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const tarballName = (name, version) => `${name.slice(1).replace('/', '-')}-${version}.tgz`;

const root = realpathSync(mkdtempSync(path.join(tmpdir(), 'julc-npm-publish-')));
test.after(() => rmSync(root, {recursive: true, force: true}));
let counter = 0;
const scratch = label => {
  const dir = path.join(root, `${++counter}-${label}`);
  mkdirSync(dir, {recursive: true});
  return dir;
};

/** An npm environment that sees neither the developer's ~/.npmrc nor the CI's GitHub Actions files. */
function npmEnv(registry, work) {
  const env = {...process.env};
  for (const key of Object.keys(env)) if (/^(npm_config_|GITHUB_|ACTIONS_)/i.test(key)) delete env[key];
  const npmrc = path.join(work, 'npmrc');
  // No retries: npm would retry the injected HTTP 500 three times, a minute apart.
  writeFileSync(npmrc, `//${new URL(registry).host}/:_authToken=test-token\nfetch-retries=0\n`);
  env.NPM_CONFIG_USERCONFIG = npmrc;
  env.NPM_CONFIG_CACHE = path.join(work, 'npm-cache');
  env.NPM_CONFIG_UPDATE_NOTIFIER = 'false';
  env.GITHUB_OUTPUT = path.join(work, 'github-output');
  writeFileSync(env.GITHUB_OUTPUT, '');
  return env;
}

/** The three packages of a release, packed by npm as the wasm job packs them, with the checksum file. `salt` changes
 *  the bytes of a package without changing its name or version. */
function release(version, {salt = {}, build = 'A'} = {}) {
  const dir = scratch(`release-${version}`);
  const env = npmEnv('http://127.0.0.1:1/', dir);
  // The engines of one build: the engine packages' engine/ files and the archives' are the same bytes (I1).
  const engines = {};
  for (const [name, variant] of [[VM, 'vm'], [FULL, 'full']]) {
    const launcher = `julc-${variant}-${createHash('sha256').update(variant + build).digest('hex').slice(0, 12)}.js`;
    const bundle = path.join(dir, 'bundle', variant);
    mkdirSync(bundle, {recursive: true});
    writeFileSync(path.join(bundle, 'engine.json'), JSON.stringify({launcher, variant, julcVersion: version}) + '\n');
    writeFileSync(path.join(bundle, launcher), `// launcher ${variant} ${version} ${build}\n`);
    writeFileSync(path.join(bundle, launcher + '.wasm'), `wasm ${variant} ${version} ${build}\n`);
    engines[name] = bundle;
  }
  for (const name of [CLIENT, VM, FULL]) {
    const source = path.join(dir, 'src', name.split('/')[1]);
    mkdirSync(source, {recursive: true});
    const pkg = {name, version, type: 'module', license: 'MIT', repository: {type: 'git', url: 'git+https://github.com/bloxbean/julc.git', directory: 'julc-wasm'},
      files: name === CLIENT ? ['index.js'] : ['index.js', 'engine/'],
      ...(name === CLIENT ? {} : {dependencies: {[CLIENT]: version}})};
    writeFileSync(path.join(source, 'package.json'), JSON.stringify(pkg, null, 2) + '\n');
    writeFileSync(path.join(source, 'index.js'), `export const name = ${JSON.stringify(name)};\n// ${salt[name] ?? ''}\n`);
    if (engines[name]) cpSync(engines[name], path.join(source, 'engine'), {recursive: true});
    const pack = spawnSync(NPM, ['pack', '--ignore-scripts', '--pack-destination', dir], {cwd: source, env, encoding: 'utf8'});
    assert.equal(pack.status, 0, pack.stderr);
  }
  const archives = [`julc-vm-wasm-${version}.tar.gz`, `julc-wasm-${version}.tar.gz`];
  for (const [archive, name] of [[archives[0], VM], [archives[1], FULL]]) {
    const tar = spawnSync('tar', ['czf', path.join(dir, archive), '-C', engines[name], '.'], {encoding: 'utf8'});
    assert.equal(tar.status, 0, tar.stderr);
  }
  const files = [...[CLIENT, VM, FULL].map(name => tarballName(name, version)), ...archives];
  const writeSums = () => writeFileSync(path.join(dir, `julc-npm-${version}-SHA256SUMS.txt`),
    files.map(file => `${sha256(readFileSync(path.join(dir, file)))}  ${file}\n`).join(''));
  writeSums();
  return {dir, version, bytes: name => readFileSync(path.join(dir, tarballName(name, version))), writeSums,
    digests: () => files.map(file => `--digest=${file}=${sha256(readFileSync(path.join(dir, file)))}`)};
}

/** Runs a script as the workflow does. Asynchronously: the registry answers from this process. */
async function run(script, registry, args, {env: extra = {}} = {}) {
  const work = scratch(script);
  const env = {...npmEnv(registry.url ?? registry, work), ...extra};
  const child = spawn(process.execPath, [path.join(ci, script), '--npm', NPM, '--registry', registry.url ?? registry,
    ...args], {env, cwd: work, stdio: ['ignore', 'pipe', 'pipe']});
  let text = '';
  child.stdout.on('data', chunk => { text += chunk; });
  child.stderr.on('data', chunk => { text += chunk; });
  const status = await new Promise((resolve, reject) => { child.on('error', reject); child.on('close', resolve); });
  const outputs = Object.fromEntries(readFileSync(env.GITHUB_OUTPUT, 'utf8').split('\n').filter(Boolean)
    .map(line => line.split(/=(.*)/s).slice(0, 2)));
  return {status, outputs, text, work};
}
const publish = (registry, rel, extra = [], options) => run('npm-publish.mjs', registry,
  ['--dir', rel.dir, '--version', rel.version, '--mode', 'publish', '--no-provenance', '--visibility-timeout', '1',
    ...extra], options);
const verify = (registry, rel, extra = []) =>
  run('npm-verify.mjs', registry, ['--dir', rel.dir, '--version', rel.version, '--no-provenance', ...extra]);
const show = result => `exit ${result.status}\n${result.text}`;

test('a new version: the three packages are published in dependency order, with provenance attached', async () => {
  const registry = await startRegistry({provenanceOnPublish: identity(VERSION)});
  try {
    const rel = release(VERSION);
    const result = await publish(registry, rel, rel.digests());
    assert.equal(result.status, 0, show(result));
    assert.deepEqual(registry.publishes, [CLIENT, VM, FULL].map(name => `${name}@${VERSION}`));
    assert.deepEqual(result.outputs, {published: 'true',
      tags: JSON.stringify({[CLIENT]: 'latest', [VM]: 'latest', [FULL]: 'latest'})});
    assert.equal(result.text.match(/--tag latest /g)?.length, 3);
    for (const name of [CLIENT, VM, FULL]) assert.equal(registry.packages.get(name)['dist-tags'].latest, VERSION);
    const verified = await run('npm-verify.mjs', registry, ['--dir', rel.dir, '--version', VERSION, '--no-provenance',
      '--work', scratch('verify-work'), ...rel.digests()]);
    assert.equal(verified.status, 0, show(verified));
    assert.match(verified.text, /ok: 3 packages at 0\.1\.0-pre22/);
    // Every read of a package's document asks for the registry's own, uncached answer (ADR-077 r24).
    assert.ok(registry.queries.length > 0 && registry.queries.every(query => query === '?write=true'),
      `uncached reads only: ${[...new Set(registry.queries)]}`);
  } finally { await registry.close(); }
});

test('already published and identical, with this workflow\'s provenance: every package is skipped', async () => {
  const registry = await startRegistry();
  try {
    const rel = release(VERSION);
    for (const name of [CLIENT, VM, FULL]) registry.seed(name, VERSION, rel.bytes(name), {provenance: identity(VERSION)});
    const result = await publish(registry, rel, rel.digests());
    assert.equal(result.status, 0, show(result));
    assert.deepEqual(registry.publishes, [], 'nothing is published twice');
    assert.equal(result.text.match(/^skip /gm)?.length, 3, show(result));
    assert.equal(result.outputs.published, 'true');
    assert.equal(result.outputs.tags, '{}', 'skipped packages have no tag chosen by this run');
  } finally { await registry.close(); }
});

test('already published and identical, without provenance: the job fails and publishes nothing', async () => {
  const registry = await startRegistry({provenanceOnPublish: identity(VERSION)});
  try {
    const rel = release(VERSION);
    registry.seed(CLIENT, VERSION, rel.bytes(CLIENT));
    const result = await publish(registry, rel);
    assert.equal(result.status, 1, show(result));
    assert.match(result.text, /wasm-client@0\.1\.0-pre22 is already on the registry[\s\S]*no provenance attestation/);
    assert.deepEqual(registry.publishes, []);
    assert.equal(result.outputs.published, 'false');
  } finally { await registry.close(); }
});

test('already published with provenance from another workflow or tag: the job fails', async () => {
  for (const other of [{...identity(VERSION), workflow: '.github/workflows/release.yml'},
    {...identity(VERSION), ref: 'refs/heads/main'}, {...identity(VERSION), repository: 'someone/julc'}]) {
    const registry = await startRegistry({provenanceOnPublish: identity(VERSION)});
    try {
      const rel = release(VERSION);
      registry.seed(CLIENT, VERSION, rel.bytes(CLIENT), {provenance: identity(VERSION)});
      registry.seed(VM, VERSION, rel.bytes(VM), {provenance: other});
      const result = await publish(registry, rel);
      assert.equal(result.status, 1, show(result));
      assert.match(result.text, /julc-vm-wasm@0\.1\.0-pre22 is already on the registry[\s\S]*workflow/);
      assert.deepEqual(registry.publishes, [], 'the client is skipped, the VM engine stops the job');
    } finally { await registry.close(); }
  }
});

test('already published with different contents: the job fails and publishes nothing more', async () => {
  const registry = await startRegistry({provenanceOnPublish: identity(VERSION)});
  try {
    const rel = release(VERSION);
    const other = release(VERSION, {salt: {[CLIENT]: 'another build'}});
    // Different bytes, even with this workflow's provenance for them (an earlier rebuild, say).
    registry.seed(CLIENT, VERSION, other.bytes(CLIENT), {provenance: identity(VERSION)});
    const result = await publish(registry, rel);
    assert.equal(result.status, 1, show(result));
    assert.match(result.text, /registry integrity sha512-.*, the release's tarball is sha512-/);
    assert.match(result.text, /subject sha512/);
    assert.deepEqual(registry.publishes, []);
  } finally { await registry.close(); }
});

test('"already exists" while the registry did not show it, and it matches: counted as published', async () => {
  const registry = await startRegistry({provenanceOnPublish: identity(VERSION)});
  try {
    const rel = release(VERSION);
    // An earlier attempt published the client, but the registry does not show it yet.
    registry.seed(CLIENT, VERSION, rel.bytes(CLIENT), {provenance: identity(VERSION), hide: true});
    const result = await publish(registry, rel);
    assert.equal(result.status, 0, show(result));
    assert.match(result.text, /cannot publish over the previously published versions/);
    assert.match(result.text, /already published \(a registry delay\), identical @julclang\/wasm-client/);
    assert.deepEqual(registry.publishes, [CLIENT, VM, FULL].map(name => `${name}@${VERSION}`), 'one attempt each');
    assert.equal(result.outputs.published, 'true');
  } finally { await registry.close(); }
});

test('"already exists" while the registry did not show it, and it differs or lacks provenance: the job fails', async () => {
  for (const seeded of ['different', 'unattested']) {
    const registry = await startRegistry({provenanceOnPublish: identity(VERSION)});
    try {
      const rel = release(VERSION);
      const bytes = seeded === 'different' ? release(VERSION, {salt: {[CLIENT]: 'x'}}).bytes(CLIENT) : rel.bytes(CLIENT);
      registry.seed(CLIENT, VERSION, bytes, {provenance: seeded === 'different' ? identity(VERSION) : null, hide: true});
      const result = await publish(registry, rel);
      assert.equal(result.status, 1, seeded + '\n' + show(result));
      assert.match(result.text, seeded === 'different' ? /registry integrity/ : /no provenance attestation/);
      assert.deepEqual(registry.publishes, [`${CLIENT}@${VERSION}`], seeded);
      assert.equal(result.outputs.published, 'false');
    } finally { await registry.close(); }
  }
});

test('a failure after the first package stops the job; re-running it skips the client and publishes the rest', async () => {
  const registry = await startRegistry({provenanceOnPublish: identity(VERSION)});
  try {
    const rel = release(VERSION);
    registry.failPublish.add(VM);
    const first = await publish(registry, rel, rel.digests());
    assert.equal(first.status, 1, show(first));
    assert.match(first.text, /npm publish failed for @julclang\/julc-vm-wasm@0\.1\.0-pre22; nothing more is published/);
    assert.deepEqual(registry.publishes, [`${CLIENT}@${VERSION}`, `${VM}@${VERSION}`]);
    assert.equal(first.outputs.published, 'false');
    assert.equal(registry.packages.get(FULL).versions[VERSION], undefined, 'the full engine was never attempted');

    // "Re-run failed jobs": the same job on the same files.
    registry.failPublish.clear();
    const retry = await publish(registry, rel, rel.digests());
    assert.equal(retry.status, 0, show(retry));
    assert.match(retry.text, /skip @julclang\/wasm-client@0\.1\.0-pre22/);
    assert.deepEqual(registry.publishes, [CLIENT, VM, VM, FULL].map(name => `${name}@${VERSION}`),
      'the client is never published twice');
    assert.equal(retry.outputs.published, 'true');
  } finally { await registry.close(); }
});

test('files that differ from the checksum file or the wasm job\'s digests are refused before the registry is asked', async () => {
  const registry = await startRegistry();
  try {
    const rel = release(VERSION);
    const digests = rel.digests();
    // A digest output that disagrees with the checksum file.
    const wrong = digests.map(arg => arg.includes('julclang-julc-wasm-') ? arg.replace(/=[0-9a-f]{64}$/, '=' + '0'.repeat(64)) : arg);
    let result = await publish(registry, rel, wrong);
    assert.equal(result.status, 1, show(result));
    assert.match(result.text, /the wasm job's digest output says 0{64}/);
    // A tarball replaced after the checksum file was written.
    const tampered = release(VERSION, {salt: {[VM]: 'tampered'}});
    writeFileSync(path.join(rel.dir, tarballName(VM, VERSION)), tampered.bytes(VM));
    result = await publish(registry, rel);
    assert.equal(result.status, 1, show(result));
    assert.match(result.text, /julclang-julc-vm-wasm-0\.1\.0-pre22\.tgz: SHA-256 [0-9a-f]{64}, but julc-npm-0\.1\.0-pre22-SHA256SUMS\.txt says/);
    // A tarball missing from the release.
    rmSync(path.join(rel.dir, tarballName(FULL, VERSION)));
    result = await publish(registry, rel);
    assert.equal(result.status, 1);
    assert.deepEqual(registry.publishes, []);
  } finally { await registry.close(); }
});

test('dry runs publish nothing: a candidate of a prerelease uses its prerelease\'s tag, and is refused for real', async () => {
  const registry = await startRegistry();
  try {
    const rc = release('0.1.0-pre22-rc1');
    const dry = await run('npm-publish.mjs', registry, ['--dir', rc.dir, '--version', rc.version, '--mode', 'dry-run',
      '--no-provenance', ...rc.digests()]);
    assert.equal(dry.status, 0, show(dry));
    assert.match(dry.text, /0\.1\.0-pre22-rc1 is a release candidate of a prerelease: never published \(D8\)\. Dry run with the tags 0\.1\.0-pre22 would get/);
    assert.equal(dry.text.match(/--tag latest --ignore-scripts --dry-run/g)?.length, 3, show(dry));
    assert.match(dry.text, /julclang-julc-wasm-0\.1\.0-pre22-rc1\.tgz|julc-wasm@0\.1\.0-pre22-rc1/);
    assert.deepEqual(dry.outputs, {published: 'false', tags: '{}'}, 'a dry run publishes, so chooses, nothing');
    const real = await publish(registry, rc);
    assert.equal(real.status, 1, show(real));
    assert.match(real.text, /release candidate of a prerelease, which is never published/);
    assert.deepEqual(registry.publishes, []);
  } finally { await registry.close(); }
});

test('local safety: no provenance only for a loopback registry, and no real publication outside GitHub Actions', async () => {
  const rel = release(VERSION);
  // Neither reaches the network: both are refused before the files are read.
  let result = await run('npm-publish.mjs', 'https://registry.npmjs.org/', ['--dir', rel.dir, '--version', VERSION,
    '--mode', 'dry-run', '--no-provenance']);
  assert.equal(result.status, 1);
  assert.match(result.text, /--no-provenance is only for a loopback test registry/);
  result = await run('npm-publish.mjs', 'https://registry.npmjs.org/', ['--dir', rel.dir, '--version', VERSION, '--mode', 'publish']);
  assert.equal(result.status, 1);
  assert.match(result.text, /a real publication runs only in GitHub Actions/);
  result = await run('npm-verify.mjs', 'https://registry.npmjs.org/', ['--dir', rel.dir, '--version', VERSION, '--work', rel.dir,
    '--no-provenance']);
  assert.equal(result.status, 1);
  assert.match(result.text, /--no-provenance is only for a loopback test registry/);
});

test('npm-verify: a registry tarball that differs, a deleted release asset, or a dist-tag behind are reported', async () => {
  const registry = await startRegistry({provenanceOnPublish: identity(VERSION)});
  try {
    const rel = release(VERSION);
    assert.equal((await publish(registry, rel)).status, 0);
    const verifyWork = () => ['--work', scratch('verify-work')];

    // The release's asset no longer matches what npm serves (a recreated release, say).
    const other = release(VERSION, {salt: {[FULL]: 'rebuilt'}});
    let result = await verify(registry, other, verifyWork());
    assert.equal(result.status, 1, show(result));
    assert.match(result.text, /julc-wasm@0\.1\.0-pre22: registry integrity/);
    assert.match(result.text, /the tarball the registry serves differs from the release's julclang-julc-wasm-0\.1\.0-pre22\.tgz/);

    // A dist-tag that names an older version.
    // (A dist-tag always names a version the document lists: seed the versions the tags are moved to.)
    registry.seed(VM, '0.1.0-pre21', release('0.1.0-pre21').bytes(VM), {tag: 'old'});
    registry.seed(VM, '0.1.0-pre23', release('0.1.0-pre23').bytes(VM), {tag: 'new'});
    registry.packages.get(VM)['dist-tags'].latest = '0.1.0-pre21';
    result = await verify(registry, rel, verifyWork());
    assert.equal(result.status, 1, show(result));
    assert.match(result.text, /julc-vm-wasm@0\.1\.0-pre22: none of the dist-tags latest, preview, release-0\.1, release-0\.1\.0-pre22 names 0\.1\.0-pre22 or later/);
    // A later version on the tag is no failure (a recovery run for an older release).
    registry.packages.get(VM)['dist-tags'].latest = '0.1.0-pre23';
    result = await verify(registry, rel, verifyWork());
    assert.equal(result.status, 0, show(result));

    // A release asset deleted after publication.
    rmSync(path.join(rel.dir, tarballName(CLIENT, VERSION)));
    result = await verify(registry, rel, verifyWork());
    assert.equal(result.status, 1, show(result));
    assert.match(result.text, /missing julclang-wasm-client-0\.1\.0-pre22\.tgz/);
  } finally { await registry.close(); }
});

/** The dist-tags of the three packages. */
const tagsOf = registry => Object.fromEntries([CLIENT, VM, FULL].map(name => [name, registry.packages.get(name)?.['dist-tags']]));

test('releases published one after the other in either order never move latest backwards (M4-C8)', async () => {
  // The workflow serialises every npm publication (one concurrency group for all of them), and the tag is chosen
  // inside, per package, from the registry as it is then: whichever order two releases run in, latest ends on the
  // higher one, and the lower one, published second, goes to release-0.1.
  for (const order of [['0.1.0-pre23', VERSION], [VERSION, '0.1.0-pre23']]) {
    const registry = await startRegistry();
    try {
      for (const version of order) {
        registry.provenanceOnPublish = identity(version);
        const rel = release(version);
        const result = await publish(registry, rel);
        assert.equal(result.status, 0, order.join(' then ') + '\n' + show(result));
      }
      for (const name of [CLIENT, VM, FULL]) {
        assert.equal(tagsOf(registry)[name].latest, '0.1.0-pre23', `${order.join(' then ')}: ${name}`);
      }
      assert.equal(tagsOf(registry)[CLIENT]['release-0.1'], order[0] === VERSION ? undefined : VERSION);
    } finally { await registry.close(); }
  }
});

test('a version older than latest goes to release-<major>.<minor>, per package; npm-verify passes', async () => {
  const registry = await startRegistry({provenanceOnPublish: identity(VERSION)});
  try {
    const rel = release(VERSION);
    // 0.1.0-pre23 is already out for the VM engine only (a partial newer publication, say).
    registry.seed(VM, '0.1.0-pre23', release('0.1.0-pre23').bytes(VM), {provenance: identity('0.1.0-pre23')});
    const result = await publish(registry, rel, rel.digests());
    assert.equal(result.status, 0, show(result));
    assert.match(result.text, /@julclang\/julc-vm-wasm: latest is 0\.1\.0-pre23, higher than 0\.1\.0-pre22: publishing as release-0\.1/);
    assert.deepEqual(tagsOf(registry), {
      [CLIENT]: {latest: VERSION}, [VM]: {latest: '0.1.0-pre23', 'release-0.1': VERSION}, [FULL]: {latest: VERSION}});
    const verified = await verify(registry, rel, ['--work', scratch('verify-work')]);
    assert.equal(verified.status, 0, show(verified));
  } finally { await registry.close(); }
});

test('a partial newer publication (pre23 client only), then pre22 over older engines (M4-C11)', async () => {
  const registry = await startRegistry({provenanceOnPublish: identity(VERSION)});
  try {
    const pre21 = release('0.1.0-pre21');
    for (const name of [CLIENT, VM, FULL]) registry.seed(name, '0.1.0-pre21', pre21.bytes(name), {provenance: identity('0.1.0-pre21')});
    registry.seed(CLIENT, '0.1.0-pre23', release('0.1.0-pre23').bytes(CLIENT), {provenance: identity('0.1.0-pre23')});
    const rel = release(VERSION);
    const result = await publish(registry, rel);
    assert.equal(result.status, 0, show(result));
    assert.deepEqual(tagsOf(registry), {
      [CLIENT]: {latest: '0.1.0-pre23', 'release-0.1': VERSION}, [VM]: {latest: VERSION}, [FULL]: {latest: VERSION}});
    const verified = await verify(registry, rel, ['--work', scratch('verify-work')]);
    assert.equal(verified.status, 0, show(verified));
  } finally { await registry.close(); }
});

test('no fallback tag moves backwards either: release-<version> when release-<major>.<minor> is higher (M4-C10)', async () => {
  for (const [label, version, tags, expected] of [
    ['maintenance', '0.1.8', {latest: '0.2.0', 'release-0.1': '0.1.9'}, 'release-0.1.8'],
    ['prerelease', '0.1.0-pre21', {latest: '0.1.0-pre23', 'release-0.1': '0.1.0-pre22'}, 'release-0.1.0-pre21'],
    ['fallback lower', '0.1.0-pre22', {latest: '0.1.0-pre23', 'release-0.1': '0.1.0-pre21'}, 'release-0.1'],
    ['latest lower', '0.2.1', {latest: '0.2.0', 'release-0.1': '0.1.9'}, 'latest'],
  ]) {
    const registry = await startRegistry({provenanceOnPublish: identity(version)});
    try {
      for (const [tag, holder] of Object.entries(tags)) {
        registry.seed(CLIENT, holder, release(holder).bytes(CLIENT), {tag, provenance: identity(holder)});
      }
      const rel = release(version);
      const result = await publish(registry, rel);
      assert.equal(result.status, 0, label + '\n' + show(result));
      const after = tagsOf(registry)[CLIENT];
      assert.equal(after[expected], version, label);
      for (const [tag, holder] of Object.entries(tags)) {
        if (tag !== expected) assert.equal(after[tag], holder, `${label}: ${tag} unchanged`);
      }
      const verified = await verify(registry, rel, ['--work', scratch('verify-work')]);
      assert.equal(verified.status, 0, label + '\n' + show(verified));
    } finally { await registry.close(); }
  }
});

test('npm-verify checks the tag npm-publish chose for each package it published (M4-C12)', async () => {
  const registry = await startRegistry({provenanceOnPublish: identity('0.1.8')});
  try {
    // The client's latest is 0.2.0 and release-0.1 is 0.1.9: 0.1.8 goes to release-0.1.8; the engines to latest.
    registry.seed(CLIENT, '0.2.0', release('0.2.0').bytes(CLIENT), {provenance: identity('0.2.0')});
    registry.seed(CLIENT, '0.1.9', release('0.1.9').bytes(CLIENT), {tag: 'release-0.1', provenance: identity('0.1.9')});
    const rel = release('0.1.8');
    const result = await publish(registry, rel);
    assert.equal(result.status, 0, show(result));
    const chosen = {[CLIENT]: 'release-0.1.8', [VM]: 'latest', [FULL]: 'latest'};
    assert.deepEqual(JSON.parse(result.outputs.tags), chosen);
    const check = async () => verify(registry, rel, ['--work', scratch('verify-work'), '--published-tags', result.outputs.tags]);
    let verified = await check();
    assert.equal(verified.status, 0, show(verified));

    const clientTags = registry.packages.get(CLIENT)['dist-tags'];
    delete clientTags['release-0.1.8'];
    verified = await check();
    assert.equal(verified.status, 1, 'the chosen tag deleted\n' + show(verified));
    assert.match(verified.text, /wasm-client@0\.1\.8: dist-tag release-0\.1\.8, chosen when it was published, names nothing/);
    // Without the run's choices (a package it skipped), a missing fallback is not inferred to be a failure.
    const unchecked = await verify(registry, rel, ['--work', scratch('verify-work')]);
    assert.equal(unchecked.status, 0, show(unchecked));

    registry.seed(CLIENT, '0.1.7', release('0.1.7').bytes(CLIENT), {tag: 'release-0.1.8', provenance: identity('0.1.7')});
    verified = await check();
    assert.equal(verified.status, 1, 'the chosen tag moved back to 0.1.7\n' + show(verified));
    assert.match(verified.text, /dist-tag release-0\.1\.8, chosen when it was published, names 0\.1\.7, expected 0\.1\.8 or later/);

    // A chosen tag that later moves forward is legitimate.
    clientTags['release-0.1.8'] = '0.1.8';
    registry.seed(VM, '0.2.0', release('0.2.0').bytes(VM), {tag: 'later'});
    registry.packages.get(VM)['dist-tags'].latest = '0.2.0';
    verified = await check();
    assert.equal(verified.status, 0, 'latest advanced\n' + show(verified));

    const malformed = await verify(registry, rel, ['--work', scratch('verify-work'), '--published-tags', '["latest"]']);
    assert.equal(malformed.status, 1);
    assert.match(malformed.text, /--published-tags must map package names to dist-tags/);
  } finally { await registry.close(); }
});

/** Publishes a version on the registry and verifies it with the tags the run chose; returns the client's tags. */
let lastVerifyText = '';
const publishStep = registry => async (version, expected) => {
  registry.provenanceOnPublish = identity(version);
  const rel = release(version);
  const result = await publish(registry, rel);
  assert.equal(result.status, 0, version + '\n' + show(result));
  assert.deepEqual(JSON.parse(result.outputs.tags), {[CLIENT]: expected, [VM]: expected, [FULL]: expected}, version);
  const verified = await verify(registry, rel, ['--work', scratch('verify-work'), '--published-tags', result.outputs.tags]);
  assert.equal(verified.status, 0, version + '\n' + show(verified));
  lastVerifyText = verified.text;
  return tagsOf(registry)[CLIENT];
};

test('D8: -preN on latest until a final version exists, then on preview; finals on latest; nothing moves back', async () => {
  const registry = await startRegistry();
  const step = publishStep(registry);
  try {
    assert.deepEqual(await step('0.1.0-pre22', 'latest'), {latest: '0.1.0-pre22'}, 'no final yet');
    assert.deepEqual(await step('0.1.0', 'latest'), {latest: '0.1.0'});
    assert.deepEqual(await step('0.2.0-pre1', 'preview'), {latest: '0.1.0', preview: '0.2.0-pre1'}, 'latest stays');
    assert.deepEqual(await step('0.2.0-pre2', 'preview'), {latest: '0.1.0', preview: '0.2.0-pre2'});
    // A prerelease below latest never takes preview (a channel is what comes after latest): the fallback instead.
    assert.deepEqual(await step('0.1.0-pre23', 'release-0.1'),
      {latest: '0.1.0', preview: '0.2.0-pre2', 'release-0.1': '0.1.0-pre23'});
    // preview never moves backwards either: semver orders pre10 below pre2 (open question 8).
    assert.deepEqual(await step('0.2.0-pre10', 'release-0.2'),
      {latest: '0.1.0', preview: '0.2.0-pre2', 'release-0.1': '0.1.0-pre23', 'release-0.2': '0.2.0-pre10'});
    assert.deepEqual(await step('0.2.0', 'latest'),
      {latest: '0.2.0', preview: '0.2.0-pre2', 'release-0.1': '0.1.0-pre23', 'release-0.2': '0.2.0-pre10'});
    assert.match(lastVerifyText, /note @julclang\/wasm-client: dist-tag preview names 0\.2\.0-pre2, behind latest 0\.2\.0/,
      'a channel behind latest after a final release is noted');
    // A final below latest (a maintenance release) goes to its fallback.
    assert.equal((await step('0.1.1', 'release-0.1'))['release-0.1'], '0.1.1');
  } finally { await registry.close(); }
});

test('D8: -betaN on beta, X.Y.Z-rcN on next, for any major; neither moves back, nor stays behind latest', async () => {
  const registry = await startRegistry();
  const step = publishStep(registry);
  try {
    assert.deepEqual(await step('1.0.0', 'latest'), {latest: '1.0.0'});
    assert.deepEqual(await step('1.1.0-beta2', 'beta'), {latest: '1.0.0', beta: '1.1.0-beta2'});
    assert.deepEqual(await step('1.1.0-rc2', 'next'), {latest: '1.0.0', beta: '1.1.0-beta2', next: '1.1.0-rc2'});
    assert.deepEqual(await step('1.1.0-pre1', 'preview'),
      {latest: '1.0.0', beta: '1.1.0-beta2', next: '1.1.0-rc2', preview: '1.1.0-pre1'}, 'a 1.x -preN, after a final');
    // Lower than the channel's holder: the fallback.
    assert.equal((await step('1.1.0-beta1', 'release-1.1'))['release-1.1'], '1.1.0-beta1');
    assert.equal((await step('1.1.0-rc1', 'release-1.1'))['release-1.1'], '1.1.0-rc1', 'release-1.1 moves forward');
    // Below beta and release-1.1 too (semver orders beta10 below beta2): release-<version>.
    assert.equal((await step('1.1.0-beta10', 'release-1.1.0-beta10'))['release-1.1.0-beta10'], '1.1.0-beta10');
    // A candidate of a final already on latest never takes next.
    assert.equal((await step('1.1.0', 'latest')).latest, '1.1.0');
    assert.deepEqual(await step('1.1.0-rc3', 'release-1.1'), {latest: '1.1.0', beta: '1.1.0-beta2', next: '1.1.0-rc2',
      preview: '1.1.0-pre1', 'release-1.1': '1.1.0-rc3', 'release-1.1.0-beta10': '1.1.0-beta10'});
  } finally { await registry.close(); }
});

test('D8: a first final below a -preN on latest goes to a fallback; a same-core beta after a -preN too', async () => {
  const registry = await startRegistry();
  const step = publishStep(registry);
  try {
    assert.deepEqual(await step('0.2.0-pre1', 'latest'), {latest: '0.2.0-pre1'}, 'no final yet');
    // A backfilled first final: 0.1.0 < 0.2.0-pre1, so latest does not move back.
    assert.deepEqual(await step('0.1.0', 'release-0.1'), {latest: '0.2.0-pre1', 'release-0.1': '0.1.0'});
    // Within one X.Y.Z the order is beta < pre < rc: 0.2.0-beta1 is behind latest (0.2.0-pre1), so not beta.
    assert.deepEqual(await step('0.2.0-beta1', 'release-0.2'),
      {latest: '0.2.0-pre1', 'release-0.1': '0.1.0', 'release-0.2': '0.2.0-beta1'});
  } finally { await registry.close(); }
});

test('npm-verify: a final version must be on latest or behind a later one; a prerelease on one of its tags', async () => {
  const registry = await startRegistry({provenanceOnPublish: identity('0.2.0')});
  try {
    const rel = release('0.2.0');
    assert.equal((await publish(registry, rel)).status, 0);
    registry.seed(VM, '0.1.0', release('0.1.0').bytes(VM), {provenance: identity('0.1.0')});
    registry.packages.get(VM)['dist-tags'].latest = '0.1.0';
    const result = await verify(registry, rel, ['--work', scratch('verify-work')]);
    assert.equal(result.status, 1, show(result));
    assert.match(result.text, /julc-vm-wasm@0\.2\.0: dist-tag latest names 0\.1\.0, expected 0\.2\.0 or later/);
  } finally { await registry.close(); }
});

test('npm files of two builds are refused: an engine tarball must carry its archive\'s engine (I1, R23-C2)', async () => {
  const registry = await startRegistry({provenanceOnPublish: identity(VERSION)});
  try {
    const a = release(VERSION);
    const b = release(VERSION, {build: 'B'});
    // A's tarballs with B's VM archive, under a checksum file over exactly these bytes.
    writeFileSync(path.join(a.dir, `julc-vm-wasm-${VERSION}.tar.gz`), readFileSync(path.join(b.dir, `julc-vm-wasm-${VERSION}.tar.gz`)));
    a.writeSums();
    const result = await publish(registry, a);
    assert.equal(result.status, 1, show(result));
    assert.match(result.text, /@julclang\/julc-vm-wasm: engine\/engine\.json differs from julc-vm-wasm-0\.1\.0-pre22\.tar\.gz's \(I1\)/);
    assert.deepEqual(registry.publishes, []);
  } finally { await registry.close(); }
});

test('an incomplete registry answer with HTTP 200 stops publication and verification, never "unpublished"', async () => {
  for (const [label, body] of [
    ['dist-tags without versions', JSON.stringify({name: CLIENT, 'dist-tags': {latest: '1.0.0'}})],
    ['null', 'null'], ['an empty object', '{}'], ['an error body', JSON.stringify({error: 'internal'})],
    ['not JSON', '<html>busy</html>'],
    // At the packageTag/hasFinalVersion boundary: none of these may yield "no final yet" or "latest".
    ['empty versions and dist-tags', JSON.stringify({name: CLIENT, versions: {}, 'dist-tags': {}})],
    ['an array dist-tag holder', JSON.stringify({name: CLIENT, versions: {'0.1.0-pre21': {name: CLIENT,
      version: '0.1.0-pre21'}}, 'dist-tags': {latest: ['0.1.0-pre21']}})],
    ['no latest for a bootstrapped package', JSON.stringify({name: CLIENT, versions: {'0.1.0-pre21': {name: CLIENT,
      version: '0.1.0-pre21'}}, 'dist-tags': {bootstrap: '0.1.0-pre21'}})],
    ['HTTP 206', {status: 206, body: JSON.stringify({name: CLIENT, versions: {}, 'dist-tags': {}})}],
    // A foreign entry posing as a final would make d8Tag choose preview (R23-C23).
    ['a foreign final version entry', JSON.stringify({name: CLIENT, 'dist-tags': {latest: '0.1.0-pre21'}, versions: {
      '0.1.0-pre21': {name: CLIENT, version: '0.1.0-pre21'}, '0.1.0': {name: '@other/package', version: '0.1.0'}}})],
  ]) {
    const registry = await startRegistry({provenanceOnPublish: identity(VERSION)});
    try {
      const rel = release(VERSION);
      registry.overrides.set(CLIENT, body);
      const result = await publish(registry, rel);
      assert.equal(result.status, 1, label + '\n' + show(result));
      assert.match(result.text, /the registry's (document for @julclang\/wasm-client|answer)|HTTP 206/, label);
      assert.deepEqual(registry.publishes, [], label);
      // The same answer makes npm-verify fail, after a good publication.
      registry.overrides.clear();
      assert.equal((await publish(registry, rel)).status, 0, label);
      registry.overrides.set(VM, body);
      const verified = await verify(registry, rel, ['--work', scratch('verify-work')]);
      assert.equal(verified.status, 1, label + '\n' + show(verified));
    } finally { await registry.close(); }
  }
});

test('a 404 for one of the three packages is never "unpublished": asked again briefly, then the job fails (R23-C31)', async () => {
  // A final version exists, so 0.1.0-pre22 belongs on preview: a 404 must never make it "no final yet", on latest.
  for (const firstGood of [0, 1]) {
    const label = firstGood ? 'a 404 after a valid read' : 'a 404 at the first read';
    const registry = await startRegistry({provenanceOnPublish: identity(VERSION)});
    try {
      const rel = release(VERSION);
      registry.seed(CLIENT, '0.0.1', release('0.0.1').bytes(CLIENT), {provenance: identity('0.0.1')});
      let reads = 0;
      registry.overrides.set(CLIENT, () => ++reads > firstGood ? {status: 404, body: '{"error":"not found"}'} : null);
      const result = await publish(registry, rel);
      assert.equal(result.status, 1, label + '\n' + show(result));
      assert.match(result.text, /HTTP 404 for a bootstrapped package/, label);
      assert.ok(reads >= 3, `${label}: asked again (${reads} reads)`);
      assert.equal(result.outputs.published, 'false', label);
      if (firstGood) {
        // Published with the tag of the one valid read, then the 404 stops the job instead of passing as published.
        assert.deepEqual(registry.publishes, [`${CLIENT}@${VERSION}`], label);
        assert.deepEqual(registry.packages.get(CLIENT)['dist-tags'], {latest: '0.0.1', preview: VERSION}, label);
      } else {
        assert.deepEqual(registry.publishes, [], label);
      }
    } finally { await registry.close(); }
  }
});

test('an engine archive deleted from or replaced in the release stops publication and verification', async () => {
  for (const archive of [`julc-vm-wasm-${VERSION}.tar.gz`, `julc-wasm-${VERSION}.tar.gz`]) {
    for (const change of ['deleted', 'replaced']) {
      for (const run of ['push', 'dispatch']) {
        const registry = await startRegistry({provenanceOnPublish: identity(VERSION)});
        try {
          const rel = release(VERSION);
          const digests = run === 'push' ? rel.digests() : [];
          if (change === 'deleted') rmSync(path.join(rel.dir, archive));
          else writeFileSync(path.join(rel.dir, archive), 'another archive\n');
          const label = `${archive} ${change}, ${run}`;
          const result = await publish(registry, rel, digests);
          assert.equal(result.status, 1, label + '\n' + show(result));
          assert.match(result.text, change === 'deleted' ? new RegExp(`missing ${archive.replace(/\./g, '\\.')}`)
            : /SHA-256 [0-9a-f]{64}, but julc-npm-0\.1\.0-pre22-SHA256SUMS\.txt says/, label);
          assert.deepEqual(registry.publishes, [], label);
          const verified = await verify(registry, rel, ['--work', scratch('verify-work'), ...digests]);
          assert.equal(verified.status, 1, label);
        } finally { await registry.close(); }
      }
    }
  }
});
