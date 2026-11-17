// ADR-077 I1 and I7 on the packed engine packages. Each engine tarball and the release archive of the same variant are
// extracted into separate directories: the launcher, the module and engine.json must be equal byte for byte, and
// engine.js's inline manifest must be engine/engine.json. Each tarball must hold exactly the expected files, and the
// three packages' package.json files must have no scripts and the fields D2 and D8 require. Prints each tarball's
// contents with sizes and SHA-256 digests.
//   node engine-identity.mjs <directory of the three tarballs> <directory of the two archives> <version>
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {mkdtempSync, readFileSync, readdirSync, realpathSync, statSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {pathToFileURL} from 'node:url';

const [npmDir, archivesDir, version] = process.argv.slice(2);
const CLIENT = '@julclang/wasm-client';
// The object form npm publishes, which provenance compares with the publishing repository (ADR-077 D8a).
const REPOSITORY = {type: 'git', url: 'git+https://github.com/bloxbean/julc.git', directory: 'julc-wasm'};
const ENGINES = [
  {name: '@julclang/julc-vm-wasm', archive: 'julc-vm-wasm', variant: 'vm', entry: 'createJulcVm'},
  {name: '@julclang/julc-wasm', archive: 'julc-wasm', variant: 'full', entry: 'createJulc'},
];
const tarballOf = name => path.join(npmDir, `${name.slice(1).replace('/', '-')}-${version}.tgz`);
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const extract = (file, label) => {
  const dir = realpathSync(mkdtempSync(path.join(tmpdir(), `julc-identity-${label}-`)));
  execFileSync('tar', ['-xzf', file, '-C', dir]);
  return dir;
};
/** An npm tarball holds nothing outside package/. */
const checkEntries = (file, name) => {
  const entries = execFileSync('tar', ['-tzf', file], {encoding: 'utf8'}).split('\n').filter(Boolean);
  const outside = entries.filter(entry => !entry.startsWith('package/') || entry.split('/').includes('..'));
  assert.deepEqual(outside, [], name + ': tarball entries outside package/');
};
const walk = (dir, prefix = '') => readdirSync(dir, {withFileTypes: true}).flatMap(entry => entry.isDirectory()
  ? walk(path.join(dir, entry.name), prefix + entry.name + '/') : [prefix + entry.name]).sort();
const INSTALL_SCRIPTS = ['preinstall', 'install', 'postinstall', 'prepare', 'preprepare', 'postprepare'];

/** I7, and the fields every package has (D2, D8). */
function checkCommonFields(pkg, name) {
  assert.equal(pkg.name, name);
  assert.equal(pkg.version, version, name + ' version');
  assert.equal(pkg.type, 'module', name + ' type');
  assert.equal(pkg.scripts, undefined, name + ' has scripts');
  for (const script of INSTALL_SCRIPTS) assert.equal(pkg.scripts?.[script], undefined, name + ' ' + script);
  assert.deepEqual(pkg.repository, REPOSITORY, name + ' repository');
  assert.equal(pkg.license, 'MIT', name + ' license');
  assert.deepEqual(pkg.engines, {node: '>=22.3.0'}, name + ' engines');
  assert.equal(pkg.types, './index.d.ts', name + ' types');
  assert.equal(pkg.exports['./package.json'], './package.json', name + ' exports ./package.json');
  assert.deepEqual(pkg.exports['.'], {types: './index.d.ts', node: './node.js', default: './index.js'}, name + ' exports .');
  for (const field of ['peerDependencies', 'optionalDependencies', 'bundledDependencies', 'bundleDependencies',
    'peerDependenciesMeta']) {
    assert.equal(pkg[field], undefined, name + ' has ' + field);
  }
}
/** package.json's files lists exactly the packed top-level entries (npm adds package.json itself). */
function checkFilesField(pkg, packed, name) {
  const entries = readdirSync(packed, {withFileTypes: true}).filter(entry => entry.name !== 'package.json')
    .map(entry => entry.isDirectory() ? entry.name + '/' : entry.name).sort();
  assert.deepEqual([...pkg.files].sort(), entries, name + ' files');
}

checkEntries(tarballOf(CLIENT), CLIENT);
const client = extract(tarballOf(CLIENT), 'client');
const clientPkg = JSON.parse(readFileSync(path.join(client, 'package', 'package.json'), 'utf8'));
checkCommonFields(clientPkg, CLIENT);
assert.equal(clientPkg.dependencies, undefined, CLIENT + ' has no dependencies');
assert.deepEqual(clientPkg.sideEffects, ['./julc-worker.js', './node-worker.js', './julc-runtime.js'], CLIENT + ' sideEffects');
checkFilesField(clientPkg, path.join(client, 'package'), CLIENT);
assert.ok(!walk(path.join(client, 'package')).includes('rest-adapter.js'), 'the client ships no REST adapter (D4)');
console.log(`ok ${CLIENT} ${version}: no scripts, ${path.basename(tarballOf(CLIENT))} sha256 ${sha256(readFileSync(tarballOf(CLIENT)))}`);

for (const engine of ENGINES) {
  const tarball = tarballOf(engine.name);
  const archive = path.join(archivesDir, `${engine.archive}-${version}.tar.gz`);
  checkEntries(tarball, engine.name);
  const packed = path.join(extract(tarball, engine.variant), 'package');
  const released = extract(archive, engine.variant + '-archive');
  const engineJson = readFileSync(path.join(released, 'engine.json'));
  const manifest = JSON.parse(engineJson.toString('utf8'));
  assert.equal(manifest.variant, engine.variant);
  assert.equal(manifest.julcVersion, version);

  // The tarball's contents, exactly.
  const files = walk(packed);
  assert.deepEqual(files, ['LICENSE', 'README.md', 'engine.d.ts', 'engine.js', 'engine/engine.json',
    `engine/${manifest.launcher}`, `engine/${manifest.launcher}.wasm`, 'engine/package.json', 'index.d.ts', 'index.js',
    'node.js', 'package.json'].sort(), engine.name + ' contents');

  // I1: the engine files are the archive's, byte for byte.
  for (const file of [manifest.launcher, manifest.launcher + '.wasm', 'engine.json']) {
    const ours = readFileSync(path.join(packed, 'engine', file));
    const theirs = readFileSync(path.join(released, file));
    assert.ok(ours.equals(theirs), `${engine.name}: engine/${file} differs from ${path.basename(archive)}'s ${file}`);
  }
  assert.equal(readFileSync(path.join(packed, 'engine', 'package.json'), 'utf8'), '{"type": "commonjs"}\n');

  // I1: engine.js inlines engine/engine.json, and names the files beside it with string literals (D2).
  // The public exports and engineFiles(), the wrapper's own copy, both carry engine.json's text exactly.
  const engineJs = readFileSync(path.join(packed, 'engine.js'), 'utf8');
  const json = engineJson.toString('utf8').trim();
  assert.equal(engineJs.split(`export const manifest = freeze(${json});\n`).length, 2,
    engine.name + ': engine.js does not export engine/engine.json');
  assert.equal(engineJs.split(`    manifest: ${json},\n`).length, 2,
    engine.name + ': engineFiles() does not return engine/engine.json');
  for (const file of [manifest.launcher, manifest.launcher + '.wasm']) {
    assert.equal(engineJs.split(`new URL('./engine/${file}', import.meta.url)`).length, 3, file + ' URL literals');
  }
  assert.equal(engineJs.split('new URL(').length, 5, 'four URLs, each named above');
  assert.doesNotMatch(engineJs, /new URL\((?!'[^']+', import\.meta\.url\))/, 'every URL is a string literal');
  const engineUrl = pathToFileURL(path.join(packed, 'engine.js'));
  const loaded = await import(engineUrl.href);
  const expectedUrls = [`./engine/${manifest.launcher}`, `./engine/${manifest.launcher}.wasm`]
    .map(file => new URL(file, engineUrl).href);
  for (const described of [loaded, loaded.engineFiles()]) {
    assert.deepEqual(described.manifest, manifest, engine.name + ': engine.js manifest');
    assert.deepEqual([described.launcherUrl.href, described.moduleUrl.href], expectedUrls);
    assert.equal(described.version, version);
  }
  assert.ok(Object.isFrozen(loaded.manifest) && Object.isFrozen(loaded.manifest.groups), 'the exported manifest is frozen');
  assert.notEqual(loaded.engineFiles().launcherUrl, loaded.engineFiles().launcherUrl, 'engineFiles() returns new objects');

  // package.json (D2, D8, I7).
  const pkg = JSON.parse(readFileSync(path.join(packed, 'package.json'), 'utf8'));
  checkCommonFields(pkg, engine.name);
  assert.deepEqual(pkg.dependencies, {[CLIENT]: version}, engine.name + ': the exact client dependency');
  assert.deepEqual(Object.keys(pkg.exports), ['.', './engine', './package.json']);
  assert.deepEqual(pkg.exports['./engine'], {types: './engine.d.ts', default: './engine.js'});
  assert.deepEqual(pkg.sideEffects, ['./engine/*.js'], engine.name + ' sideEffects');
  assert.deepEqual(pkg.files, ['LICENSE', 'README.md', 'engine/', 'engine.d.ts', 'engine.js', 'index.d.ts', 'index.js',
    'node.js'], engine.name + ' files');
  checkFilesField(pkg, packed, engine.name);
  assert.ok(!files.includes('rest-adapter.js'), 'no REST adapter (D4)');
  const entry = readFileSync(path.join(packed, 'index.js'), 'utf8');
  assert.match(entry, new RegExp(`export async function ${engine.entry}\\(`), engine.name + ' entry function');
  assert.match(entry, /const \{manifest, launcherUrl, moduleUrl, version\} = engineFiles\(\);/, engine.name + ' copies');
  assert.match(entry, /expectedVersion: version, rest: false\}/, engine.name + ' passes its version and rest: false');
  assert.doesNotMatch(entry, /import \{[^}]*\b(manifest|launcherUrl|moduleUrl)\b[^}]*\} from '\.\/engine\.js'/,
    engine.name + ' must not use the public exports');

  console.log(`ok ${engine.name} ${version}: I1 against ${path.basename(archive)}; contents of ${path.basename(tarball)}`
    + ` (sha256 ${sha256(readFileSync(tarball))}, ${statSync(tarball).size} bytes):`);
  for (const file of files) {
    const bytes = readFileSync(path.join(packed, file));
    console.log(`  ${String(bytes.length).padStart(10)}  ${sha256(bytes).slice(0, 16)}  ${file}`);
  }
}
