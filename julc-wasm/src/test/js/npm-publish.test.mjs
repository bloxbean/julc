// ADR-077 M4 and Verification "Packages": the release workflow's npm-publish and npm-verify scripts
// (julc-wasm/src/ci) run with the npm CLI against a local registry (npm-registry-stub.mjs), for each retry case of
// D8a and I11. Provenance cannot be produced outside GitHub Actions: the scripts run with --no-provenance (accepted only
// for a loopback registry), and the registry attaches an unsigned provenance statement to what it publishes, as npm
// does under trusted publishing, or is seeded with versions with or without one. The publish job reads only that
// statement; signatures are npm-verify's, tested on a real attestation in npm-release.test.mjs.
//   JULC_TEST_NPM=<npm command> selects the npm CLI (default: npm on PATH).
import assert from 'node:assert/strict';
import {spawn, spawnSync} from 'node:child_process';
import {mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync} from 'node:fs';
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
function release(version, {salt = {}} = {}) {
  const dir = scratch(`release-${version}`);
  const env = npmEnv('http://127.0.0.1:1/', dir);
  for (const name of [CLIENT, VM, FULL]) {
    const source = path.join(dir, 'src', name.split('/')[1]);
    mkdirSync(source, {recursive: true});
    const pkg = {name, version, type: 'module', license: 'MIT', repository: 'github:bloxbean/julc', files: ['index.js'],
      ...(name === CLIENT ? {} : {dependencies: {[CLIENT]: version}})};
    writeFileSync(path.join(source, 'package.json'), JSON.stringify(pkg, null, 2) + '\n');
    writeFileSync(path.join(source, 'index.js'), `export const name = ${JSON.stringify(name)};\n// ${salt[name] ?? ''}\n`);
    const pack = spawnSync(NPM, ['pack', '--ignore-scripts', '--pack-destination', dir], {cwd: source, env, encoding: 'utf8'});
    assert.equal(pack.status, 0, pack.stderr);
  }
  const archives = [`julc-vm-wasm-${version}.tar.gz`, `julc-wasm-${version}.tar.gz`];
  for (const archive of archives) writeFileSync(path.join(dir, archive), `archive ${archive}\n`);
  const files = [...[CLIENT, VM, FULL].map(name => tarballName(name, version)), ...archives];
  writeFileSync(path.join(dir, `julc-npm-${version}-SHA256SUMS.txt`),
    files.map(file => `${sha256(readFileSync(path.join(dir, file)))}  ${file}\n`).join(''));
  return {dir, version, bytes: name => readFileSync(path.join(dir, tarballName(name, version))),
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
const attempts = (registry, name) => registry.publishes.filter(key => key === `${name}@${VERSION}`).length;

test('a new version: the three packages are published in dependency order, with provenance attached', async () => {
  const registry = await startRegistry({provenanceOnPublish: identity(VERSION)});
  try {
    const rel = release(VERSION);
    const result = await publish(registry, rel, rel.digests());
    assert.equal(result.status, 0, show(result));
    assert.deepEqual(registry.publishes, [CLIENT, VM, FULL].map(name => `${name}@${VERSION}`));
    assert.deepEqual(result.outputs, {dist_tag: 'latest', published: 'true'});
    for (const name of [CLIENT, VM, FULL]) assert.equal(registry.packages.get(name)['dist-tags'].latest, VERSION);
    const verified = await run('npm-verify.mjs', registry, ['--dir', rel.dir, '--version', VERSION, '--no-provenance',
      '--work', scratch('verify-work'), ...rel.digests()]);
    assert.equal(verified.status, 0, show(verified));
    assert.match(verified.text, /ok: 3 packages at 0\.1\.0-pre22/);
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
    assert.equal(registry.packages.get(FULL), undefined, 'the full engine was never attempted');

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

test('dry runs publish nothing: a release candidate uses its final release\'s tag, and is refused for real', async () => {
  const registry = await startRegistry();
  try {
    const rc = release('0.1.0-pre22-rc1');
    const dry = await run('npm-publish.mjs', registry, ['--dir', rc.dir, '--version', rc.version, '--mode', 'dry-run',
      '--no-provenance', ...rc.digests()]);
    assert.equal(dry.status, 0, show(dry));
    assert.match(dry.text, /0\.1\.0-pre22-rc1 is a release candidate: never published \(D8\)\. Dry run with its final release's tag, latest/);
    assert.equal(dry.text.match(/--tag latest --ignore-scripts --dry-run/g)?.length, 3, show(dry));
    assert.match(dry.text, /julclang-julc-wasm-0\.1\.0-pre22-rc1\.tgz|julc-wasm@0\.1\.0-pre22-rc1/);
    assert.deepEqual(dry.outputs, {dist_tag: 'latest', published: 'false'});
    const real = await publish(registry, rc);
    assert.equal(real.status, 1, show(real));
    assert.match(real.text, /release candidate, which is never published/);
    assert.deepEqual(registry.publishes, []);
    assert.equal(registry.packages.size, 0);
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
    registry.packages.get(VM)['dist-tags'].latest = '0.1.0-pre21';
    result = await verify(registry, rel, verifyWork());
    assert.equal(result.status, 1, show(result));
    assert.match(result.text, /julc-vm-wasm@0\.1\.0-pre22: dist-tag latest names 0\.1\.0-pre21, expected 0\.1\.0-pre22/);
    // A later version on the tag is a note, not a failure (a recovery run for an older release).
    registry.packages.get(VM)['dist-tags'].latest = '0.1.0-pre23';
    result = await verify(registry, rel, verifyWork());
    assert.equal(result.status, 0, show(result));
    assert.match(result.text, /note @julclang\/julc-vm-wasm@0\.1\.0-pre22: dist-tag latest names the later 0\.1\.0-pre23/);

    // A release asset deleted after publication.
    rmSync(path.join(rel.dir, tarballName(CLIENT, VERSION)));
    result = await verify(registry, rel, verifyWork());
    assert.equal(result.status, 1, show(result));
    assert.match(result.text, /missing julclang-wasm-client-0\.1\.0-pre22\.tgz/);
  } finally { await registry.close(); }
});
