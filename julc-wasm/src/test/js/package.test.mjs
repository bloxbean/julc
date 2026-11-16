// @julclang/wasm-client as npm packs it (ADR-077 D1a, D2, D3, D4, D5, I2, I7, I8): its contents and package.json,
// loading from the installed tarball in Node.js with no flags, on the main thread and in an application worker, the
// release check through npm overrides, and the plain type names.
import test from 'node:test';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {cpSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {CLIENT_VERSION, launcherText, npmDir, packageDir, runtimeStamped} from './support.mjs';

const OTHER = '0.0.1-other';
const tarball = fileURLToPath(new URL(readdirSync(npmDir).find(name => name.startsWith('julclang-wasm-client-')), npmDir));
const scratch = () => mkdtempSync(path.join(tmpdir(), 'julc-package-'));
const npm = (cwd, ...args) => execFileSync('npm', [...args, '--offline', '--ignore-scripts', '--no-audit', '--no-fund'],
  {cwd, stdio: 'pipe', encoding: 'utf8'});
const node = (cwd, ...args) => execFileSync(process.execPath, args, {cwd, stdio: 'pipe', encoding: 'utf8'});
const json = (file, value) => writeFileSync(file, JSON.stringify(value, null, 2));

/** A stand-in engine package's files: a launcher installed through JsApi's JavaScript, which also uses the require,
 * __dirname and module bindings the Node bridge injects, in a "type": "module" directory. */
function engineFiles(dir, {variant = 'vm', version = CLIENT_VERSION, julcVersion = CLIENT_VERSION} = {}) {
  mkdirSync(path.join(dir, 'engine'), {recursive: true});
  json(path.join(dir, 'engine', 'package.json'), {type: 'module'});
  const launcher = 'julc-vm-0123456789ab.js';
  writeFileSync(path.join(dir, 'engine', launcher), launcherText({variant, version, julcVersion,
    source: runtimeStamped(julcVersion), groups: ['vm'],
    before: "if (require('node:path').basename(__dirname) !== 'engine' || typeof module.exports !== 'object') throw new Error('bindings');"}));
  writeFileSync(path.join(dir, 'engine', launcher + '.wasm'), '');
  const manifest = {version: '0123456789ab', launcher, variant, api: 0, groups: ['vm'], julcVersion};
  writeFileSync(path.join(dir, 'engine.js'), `export const manifest = ${JSON.stringify(manifest)};
export const launcherUrl = new URL('./engine/${launcher}', import.meta.url);
export const moduleUrl = new URL('./engine/${launcher}.wasm', import.meta.url);
export const version = ${JSON.stringify(version)};
`);
}
const MAIN = `import {createJulc} from '@julclang/wasm-client';
import {manifest, launcherUrl, moduleUrl, version} from './engine.js';
const client = await createJulc({manifest, launcherUrl, moduleUrl, expectedVersion: version});
try {
  console.log(JSON.stringify({version: client.version(), features: client.features(), rest: typeof client.rest,
    echo: await client.vm.echo({x: 1})}));
} finally { client.dispose(); }
`;

test('the tarball holds exactly the package directory, and package.json follows ADR-077 D2', () => {
  const dir = scratch();
  execFileSync('tar', ['-xzf', tarball, '-C', dir]);
  const packed = path.join(dir, 'package');
  const files = readdirSync(packed).sort();
  assert.deepEqual(files, readdirSync(packageDir).sort());
  for (const file of files) {
    assert.deepEqual(readFileSync(path.join(packed, file)), readFileSync(new URL(file, packageDir)), file);
  }
  for (const file of ['julc-wasm.js', 'julc-client.js', 'julc-worker.js', 'julc-runtime.js', 'node.js', 'node-worker.js',
    'index.js', 'index.d.ts', 'models.d.ts', 'client-defaults.js', 'client-defaults.d.ts', 'client-version.js']) {
    assert.ok(files.includes(file), file);
  }
  assert.ok(!files.includes('rest-adapter.js'), 'the package has no REST adapter (D4)');
  const manifest = JSON.parse(readFileSync(path.join(packed, 'package.json'), 'utf8'));
  assert.equal(manifest.name, '@julclang/wasm-client');
  assert.equal(manifest.version, CLIENT_VERSION);
  assert.equal(manifest.type, 'module');
  assert.equal(manifest.types, './index.d.ts');
  assert.equal(manifest.repository, 'github:bloxbean/julc');
  assert.deepEqual(manifest.engines, {node: '>=22.3.0'});
  assert.deepEqual(manifest.exports, {
    '.': {types: './index.d.ts', node: './node.js', default: './index.js'},
    './models': {types: './models.d.ts'},
    './package.json': './package.json',
  });
  assert.deepEqual(manifest.sideEffects, ['./julc-worker.js', './node-worker.js', './julc-runtime.js']);
  // No install-time code (I7), and no dependencies.
  assert.equal(manifest.scripts, undefined);
  assert.equal(manifest.dependencies, undefined);
  assert.equal(readFileSync(path.join(packed, 'client-defaults.js'), 'utf8').includes('export const rest = false;'), true);
  assert.match(readFileSync(path.join(packed, 'client-version.js'), 'utf8'),
    new RegExp(`CLIENT_VERSION = ${JSON.stringify(CLIENT_VERSION).replace(/[.]/g, '\\.')};`));
});

test('the client names every asset with a string literal, and builds the worker in the form bundlers recognise (D2, D3)', () => {
  const sources = ['julc-wasm.js', 'julc-client.js', 'node.js', 'index.js']
    .map(file => readFileSync(new URL(file, packageDir), 'utf8')).join('\n').replace(/^\s*\/\/.*$/gm, '');
  const urls = [...sources.matchAll(/new URL\(([^,()]+),\s*import\.meta\.url\)/g)].map(match => match[1]);
  assert.deepEqual(urls.sort(), ["'./julc-runtime.js'", "'./julc-worker.js'", "'./julc-worker.js'", "'./node-worker.js'"]);
  assert.match(sources, /new Worker\(new URL\('\.\/julc-worker\.js', import\.meta\.url\)\)/);
  assert.match(sources, /const shimUrl = new URL\('\.\/node-worker\.js', import\.meta\.url\);/);
  const shim = readFileSync(new URL('node-worker.js', packageDir), 'utf8');
  assert.doesNotMatch(shim, /^\s*import\b/m);
  assert.doesNotMatch(shim, /\brequire\(/);
  assert.match(shim, /vm\.compileFunction\(/);
});

test('installed from its tarball, the client loads an engine in Node.js with no flags, on the main thread and in a worker (I8)', () => {
  const app = scratch();
  json(path.join(app, 'package.json'), {name: 'app', private: true, type: 'module'});
  npm(app, 'install', tarball);
  engineFiles(app);
  writeFileSync(path.join(app, 'main.mjs'), MAIN);
  writeFileSync(path.join(app, 'in-worker.mjs'), `import {Worker} from 'node:worker_threads';
const worker = new Worker(new URL('./main.mjs', import.meta.url));
worker.on('error', error => { console.error(error); process.exitCode = 1; });
`);
  for (const script of ['main.mjs', 'in-worker.mjs']) {
    const result = JSON.parse(node(app, script).trim());
    assert.equal(result.version, CLIENT_VERSION, script);
    assert.equal(result.features.julcVersion, CLIENT_VERSION, script);
    assert.equal(result.rest, 'undefined', 'no REST adapter in package mode: ' + script);
    assert.deepEqual(result.echo, {method: 'vm.echo', body: {x: 1}}, script);
  }
  // The engine's directory may be ESM or CommonJS: the bridge loads the launcher as a function either way.
  json(path.join(app, 'engine', 'package.json'), {type: 'commonjs'});
  assert.equal(JSON.parse(node(app, 'main.mjs').trim()).version, CLIENT_VERSION);
  // A CommonJS application imports the ES module entry.
  writeFileSync(path.join(app, 'main.cjs'), `import('@julclang/wasm-client').then(m => console.log(typeof m.createJulc, typeof m.isFullClient));`);
  assert.equal(node(app, 'main.cjs').trim(), 'function function');
});

test('an engine package and wrapper of one release are refused with an overridden client of another (I2)', () => {
  const root = scratch();
  // The client of another release, as npm overrides would install it.
  const other = path.join(root, 'other-client');
  cpSync(fileURLToPath(packageDir), other, {recursive: true});
  const manifest = JSON.parse(readFileSync(path.join(other, 'package.json'), 'utf8'));
  json(path.join(other, 'package.json'), {...manifest, version: OTHER});
  writeFileSync(path.join(other, 'client-version.js'), `export const CLIENT_VERSION = ${JSON.stringify(OTHER)};\n`);
  writeFileSync(path.join(other, 'julc-runtime.js'), runtimeStamped(OTHER));
  const otherTarball = path.join(root, npm(other, 'pack', '--pack-destination', root).trim().split('\n').pop());
  // An engine package of this release, depending on its own client exactly (D1).
  const engine = path.join(root, 'engine-package');
  engineFiles(engine);
  json(path.join(engine, 'package.json'), {name: '@julclang/julc-vm-wasm', version: CLIENT_VERSION, type: 'module',
    exports: {'.': './index.js', './engine': './engine.js'}, dependencies: {'@julclang/wasm-client': CLIENT_VERSION}});
  writeFileSync(path.join(engine, 'index.js'), `import {createJulc} from '@julclang/wasm-client';
import {manifest, launcherUrl, moduleUrl, version} from './engine.js';
export function createJulcVm(options = {}) {
  return createJulc({...options, manifest, launcherUrl, moduleUrl, expectedVersion: version, rest: false});
}
`);
  const engineTarball = path.join(root, npm(engine, 'pack', '--pack-destination', root).trim().split('\n').pop());
  const run = `import {createJulcVm} from '@julclang/julc-vm-wasm';
try { const client = await createJulcVm(); console.log('ok', client.version()); client.dispose(); }
catch (error) { console.log('refused', error.message); }
`;
  const install = overrides => {
    const app = path.join(root, overrides ? 'app-overridden' : 'app');
    mkdirSync(app);
    json(path.join(app, 'package.json'), {name: 'app', private: true, type: 'module',
      dependencies: {'@julclang/julc-vm-wasm': 'file:' + engineTarball, '@julclang/wasm-client': 'file:' + (overrides ? otherTarball : tarball)},
      ...(overrides ? {overrides: {'@julclang/wasm-client': 'file:' + otherTarball}} : {})});
    npm(app, 'install');
    writeFileSync(path.join(app, 'main.mjs'), run);
    return node(app, 'main.mjs').trim();
  };
  assert.equal(install(false), 'ok ' + CLIENT_VERSION);
  // The overridden client's runtime file is the first thing of another release the engine meets.
  assert.equal(install(true), `refused JuLC engine ${CLIENT_VERSION} cannot start: the runtime file julc-runtime.js `
    + `loaded before it is JuLC ${OTHER}; serve the julc-runtime.js of JuLC ${CLIENT_VERSION} with this engine`);
});

test('every generated record an operation reaches has a plain name at the package root (D5)', () => {
  const read = file => readFileSync(new URL(file, packageDir), 'utf8');
  const models = read('julc-models.d.ts'), client = read('julc-wasm.d.ts'), root = read('index.d.ts');
  const bodies = new Map([...models.matchAll(/export (?:interface|type) (\w+)([^]*?)(?=\nexport |$)/g)]
    .map(match => [match[1], match[2]]));
  // Start from the records the client's operations name, then follow their fields.
  const operations = client.slice(client.indexOf('export interface VmGroups'), client.indexOf('export interface Manifest'));
  const reached = new Set(), queue = [...operations.matchAll(/\bM\.(\w+)/g)].map(match => match[1]);
  queue.push('PlutusData');
  while (queue.length) {
    const name = queue.pop();
    if (reached.has(name)) continue;
    reached.add(name);
    for (const [, used] of bodies.get(name).matchAll(/\b([A-Z]\w*)\b/g)) if (bodies.has(used)) queue.push(used);
  }
  const exported = new Set([...root.matchAll(/type (\w+)/g)].map(match => match[1]));
  const aliases = new Map([...client.matchAll(/export type (\w+) = M\.(\w+);/g)].map(match => [match[2], match[1]]));
  const missing = [...reached].filter(name => !(name.includes('_') ? exported.has(aliases.get(name))
    : exported.has(name) || exported.has(aliases.get(name))));
  assert.deepEqual(missing, []);
  assert.ok(reached.size > 40, 'the walk reached the records: ' + reached.size);
  // The plain names are the root's; the generated names stay under ./models.
  assert.doesNotMatch(root, /\b\w+_\w+\b/);
  assert.match(read('models.d.ts'), /export \* from '\.\/julc-models\.js';/);
});
