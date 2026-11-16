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
  assert.equal(pkg.repository, 'github:bloxbean/julc', name + ' repository');
  assert.equal(pkg.license, 'MIT', name + ' license');
  assert.deepEqual(pkg.engines, {node: '>=22.3.0'}, name + ' engines');
  assert.equal(pkg.types, './index.d.ts', name + ' types');
  assert.equal(pkg.exports['./package.json'], './package.json', name + ' exports ./package.json');
  assert.deepEqual(pkg.exports['.'], {types: './index.d.ts', node: './node.js', default: './index.js'}, name + ' exports .');
}

const client = extract(tarballOf(CLIENT), 'client');
checkCommonFields(JSON.parse(readFileSync(path.join(client, 'package', 'package.json'), 'utf8')), CLIENT);
assert.ok(!walk(path.join(client, 'package')).includes('rest-adapter.js'), 'the client ships no REST adapter (D4)');
console.log(`ok ${CLIENT} ${version}: no scripts, ${path.basename(tarballOf(CLIENT))} sha256 ${sha256(readFileSync(tarballOf(CLIENT)))}`);

for (const engine of ENGINES) {
  const tarball = tarballOf(engine.name);
  const archive = path.join(archivesDir, `${engine.archive}-${version}.tar.gz`);
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
  const engineJs = readFileSync(path.join(packed, 'engine.js'), 'utf8');
  const inline = `export const manifest = ${engineJson.toString('utf8').trim()};\n`;
  assert.equal(engineJs.split(inline).length, 2, engine.name + ': engine.js does not inline engine/engine.json');
  assert.ok(engineJs.includes(`new URL('./engine/${manifest.launcher}', import.meta.url)`), 'launcher URL literal');
  assert.ok(engineJs.includes(`new URL('./engine/${manifest.launcher}.wasm', import.meta.url)`), 'module URL literal');
  assert.doesNotMatch(engineJs, /new URL\((?!'[^']+', import\.meta\.url\))/, 'every URL is a string literal');
  const engineUrl = pathToFileURL(path.join(packed, 'engine.js'));
  const loaded = await import(engineUrl.href);
  assert.deepEqual(loaded.manifest, manifest, engine.name + ': engine.js manifest');
  assert.equal(loaded.launcherUrl.href, new URL(`./engine/${manifest.launcher}`, engineUrl).href);
  assert.equal(loaded.moduleUrl.href, new URL(`./engine/${manifest.launcher}.wasm`, engineUrl).href);
  assert.equal(loaded.version, version);

  // package.json (D2, D8, I7).
  const pkg = JSON.parse(readFileSync(path.join(packed, 'package.json'), 'utf8'));
  checkCommonFields(pkg, engine.name);
  assert.deepEqual(pkg.dependencies, {[CLIENT]: version}, engine.name + ': the exact client dependency');
  assert.deepEqual(Object.keys(pkg.exports), ['.', './engine', './package.json']);
  assert.deepEqual(pkg.exports['./engine'], {types: './engine.d.ts', default: './engine.js'});
  assert.ok(!files.includes('rest-adapter.js'), 'no REST adapter (D4)');
  const entry = readFileSync(path.join(packed, 'index.js'), 'utf8');
  assert.match(entry, new RegExp(`export async function ${engine.entry}\\(`), engine.name + ' entry function');
  assert.match(entry, /expectedVersion: version, rest: false\}/, engine.name + ' passes its version and rest: false');

  console.log(`ok ${engine.name} ${version}: I1 against ${path.basename(archive)}; contents of ${path.basename(tarball)}`
    + ` (sha256 ${sha256(readFileSync(tarball))}, ${statSync(tarball).size} bytes):`);
  for (const file of files) {
    const bytes = readFileSync(path.join(packed, file));
    console.log(`  ${String(bytes.length).padStart(10)}  ${sha256(bytes).slice(0, 16)}  ${file}`);
  }
}
