// What the worker loads (ADR-077 D2a, I10). Archive mode: only a launcher named in engine.json next to it, whatever
// language built the image (ADR-064 I4). Package mode: only the URLs the caller gives, on the worker's own origin in a
// browser, file: URLs under the Node bridge, never a path from the manifest.
import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync, readFileSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {Worker} from 'node:worker_threads';
import {archiveDir, runtimeText, shimUrl} from './support.mjs';

const workerScript = new URL('julc-worker.js', archiveDir);

/** The first reply of a worker under the shipped Node shim. */
function init(message) {
  const worker = new Worker(shimUrl, {workerData: {workerUrl: workerScript.href}});
  return new Promise((resolve, reject) => {
    worker.once('message', data => { worker.terminate(); resolve(data); });
    worker.once('error', error => { worker.terminate(); reject(error); });
    worker.postMessage({type: 'init', ...message});
  });
}
/** The replies of a worker in a stand-in browser scope at location, until an error or the launcher's load. */
function browserInit(location, message) {
  const worker = new Worker(new URL('./browser-worker.mjs', import.meta.url),
    {workerData: {workerUrl: workerScript.href, location}});
  const replies = [];
  return new Promise((resolve, reject) => {
    const done = () => { worker.terminate(); resolve(replies); };
    worker.on('message', data => {
      replies.push(data);
      if (data.type === 'error' || (data.type === 'loaded' && data.url === message.launcherUrl)) done();
    });
    worker.once('error', error => { worker.terminate(); reject(error); });
    worker.postMessage({type: 'init', mode: 'package', ...message});
  });
}
function bundleDir() {
  const dir = mkdtempSync(path.join(tmpdir(), 'julc-worker-'));
  writeFileSync(path.join(dir, 'rest-adapter.js'), 'globalThis.JulcRestAdapter = class {};');
  writeFileSync(path.join(dir, 'julc-runtime.js'), runtimeText);
  return dir;
}
const manifest = {api: 0, version: 'x', variant: 'demo'};

test('the worker rejects a launcher that is not a plain file name next to the manifest', async () => {
  const base = pathToFileURL(mkdtempSync(path.join(tmpdir(), 'julc-worker-')) + '/');
  const hash = '0123456789ab';
  for (const launcher of [`../demo-${hash}.js`, `sub/demo-${hash}.js`, `https://example.com/demo-${hash}.js`,
    `//example.com/demo-${hash}.js`, `demo-${hash}.js?x=1`, `demo-${hash}.js#x`, `demo-${hash}.js\n`,
    `Demo-${hash}.js`, `ElciWasm-${hash}.js`, `elci_wasm-${hash}.js`, `demo-${hash}.mjs`, `demo-${hash}.js.wasm`,
    `demo-0123456789a.js`, `demo--${hash}.js`, `-demo-${hash}.js`, `${hash}.js`, 42, undefined]) {
    const reply = await init({base: base.href, manifest: {...manifest, version: hash, launcher}});
    assert.equal(reply.type, 'error', String(launcher));
    assert.match(reply.message, /^Invalid engine manifest: launcher .* is not a file name of lowercase words joined by /s,
      String(launcher));
  }
});

test('the worker rejects unknown API versions and invalid variants', async () => {
  const base = pathToFileURL(mkdtempSync(path.join(tmpdir(), 'julc-worker-')) + '/').href;
  const launcher = 'demo-0123456789ab.js';
  assert.match((await init({base, manifest: {api: 1, version: 'x', launcher, variant: 'demo'}})).message,
    /^Invalid engine manifest: unsupported API version 1$/);
  for (const variant of ['Demo', '1demo', 'de mo', '', 7]) {
    assert.match((await init({base, manifest: {api: 0, version: 'x', launcher, variant}})).message,
      /^Invalid engine manifest: variant .* does not match/, String(variant));
  }
});

test('the worker reports main failing before the engine is ready instead of waiting for the load timeout', async () => {
  const dir = bundleDir();
  // What the launcher hook does when main ends with an exception (exit code 1) before JsApi.install.
  writeFileSync(path.join(dir, 'demo-0123456789ab.js'), 'globalThis.onJulcExit(1);');
  const reply = await init({base: pathToFileURL(dir + '/').href, manifest: {...manifest, launcher: 'demo-0123456789ab.js'}});
  assert.deepEqual(reply, {type: 'error',
    message: 'The engine stopped before it was ready (exit code 1); its error is in the worker console'});
});

test('the worker accepts any language\'s launcher name and loads it from the manifest\'s directory', async () => {
  const dir = bundleDir();
  for (const launcher of ['julc-0123456789ab.js', 'julc-vm-0123456789ab.js', 'elci-0123456789ab.js',
    'demo-lang2-0123456789ab.js']) {
    // A launcher that installs a stub engine, as JsApi does.
    writeFileSync(path.join(dir, launcher), `globalThis.onJulcReady({version: () => '${launcher}',
      features: () => ({groups: ['vm']}), __schemas: {}, __runtime: 'preloaded'});`);
    const reply = await init({base: pathToFileURL(dir + '/').href, manifest: {...manifest, launcher}});
    assert.equal(reply.type, 'ready', reply.message);
    assert.equal(reply.version, launcher);
    assert.deepEqual(reply.runtime, {preloaded: JSON.parse(/julcVersion = ("[^"]*")/.exec(runtimeText)[1]),
      used: 'preloaded'});
  }
});

test('the launcher hook sets the module path and reports a failed main', async () => {
  const hook = readFileSync(fileURLToPath(new URL('../../main/resources/META-INF/julc-wasm/launcher-hook.js', import.meta.url)), 'utf8');
  const run = async (exitCode) => {
    const exits = [];
    const config = {};
    const scope = {GraalVM: {run: async () => ({exitCode})}, config, globalThis: {JULC_WASM_PATH: '/x/engine.wasm', onJulcExit: code => exits.push(code)}};
    new Function('GraalVM', 'config', 'globalThis', hook)(scope.GraalVM, config, scope.globalThis);
    await scope.GraalVM.run();
    return {exits, path: config.wasm_path};
  };
  assert.deepEqual(await run(1), {exits: [1], path: '/x/engine.wasm'});
  assert.deepEqual(await run(0), {exits: [], path: '/x/engine.wasm'});
});

const ORIGIN = 'https://app.example';
const ok = {runtimeUrl: ORIGIN + '/assets/julc-runtime-1a2b.js', launcherUrl: ORIGIN + '/assets/julc-vm-x.js',
  moduleUrl: ORIGIN + '/assets/julc-vm-x.js.wasm', manifest: {...manifest, variant: 'vm'}};

test('in a browser, package mode loads the runtime and then the launcher on the worker\'s own origin', async () => {
  const replies = await browserInit(ORIGIN + '/assets/julc-worker-9f.js', ok);
  assert.deepEqual(replies, [{type: 'loaded', url: ok.runtimeUrl}, {type: 'loaded', url: ok.launcherUrl}]);
  // The manifest's launcher field is never read as a path in package mode.
  const withPath = await browserInit(ORIGIN + '/w.js', {...ok, manifest: {...ok.manifest,
    launcher: '../../evil/julc-vm-0123456789ab.js'}});
  assert.deepEqual(withPath.map(reply => reply.url), [ok.runtimeUrl, ok.launcherUrl]);
  const noLauncher = await browserInit(ORIGIN + '/w.js', {...ok, manifest: {...ok.manifest, launcher: undefined}});
  assert.deepEqual(noLauncher.map(reply => reply.url), [ok.runtimeUrl, ok.launcherUrl]);
});

test('in a browser, package mode refuses other origins, refused schemes, credentials and wrong extensions (I10)', async () => {
  const refused = async (change, pattern, location = ORIGIN + '/w.js') => {
    const replies = await browserInit(location, {...ok, ...change});
    assert.equal(replies.length, 1, JSON.stringify(replies));
    assert.equal(replies[0].type, 'error');
    assert.match(replies[0].message, pattern);
  };
  for (const [name, extension] of [['runtime', '.js'], ['launcher', '.js'], ['module', '.wasm']]) {
    const key = name + 'Url';
    await refused({[key]: 'https://cdn.example/x' + extension}, new RegExp(`^Refused ${name} URL .*is not on the worker's origin https://app\\.example$`));
    await refused({[key]: 'http://app.example/x' + extension}, /is not on the worker's origin/);
    await refused({[key]: 'https://app.example:8443/x' + extension}, /is not on the worker's origin/);
    await refused({[key]: 'data:text/javascript,1//x' + extension}, /has the refused scheme data:/);
    await refused({[key]: 'blob:https://app.example/0b1c' + extension}, /has the refused scheme blob:/);
    await refused({[key]: 'javascript:1//' + extension}, /has the refused scheme javascript:/);
    await refused({[key]: 'file:///tmp/x' + extension}, /has the refused scheme file:/);
    await refused({[key]: 'https://user:secret@app.example/x' + extension}, /has a user name or password/);
    await refused({[key]: 'https://user@app.example/x' + extension}, /has a user name or password/);
    await refused({[key]: '/assets/x' + extension}, /is not an absolute URL/);
    await refused({[key]: ORIGIN + '/x' + (extension === '.js' ? '.mjs' : '.js')}, new RegExp(`does not end in \\${extension}`));
    await refused({[key]: ORIGIN + '/x' + extension + '.txt'}, /does not end in/);
  }
  // A worker with an opaque origin cannot check where a URL points, so it loads nothing.
  await refused({}, /this worker has an opaque origin/, 'data:text/javascript,1');
  // No REST adapter in package mode.
  await refused({rest: true}, /^The REST adapter is not available in package mode$/);
});

test('under the Node bridge, package mode loads only file: URLs', async () => {
  const dir = bundleDir();
  const file = name => pathToFileURL(path.join(dir, name)).href;
  for (const [key, url, pattern] of [
    ['launcherUrl', 'https://app.example/x.js', /^Refused launcher URL .*is not a file: URL$/],
    ['moduleUrl', 'http://localhost/x.wasm', /^Refused module URL .*is not a file: URL$/],
    ['runtimeUrl', 'data:text/javascript,1//.js', /^Refused runtime URL .*is not a file: URL$/],
    ['launcherUrl', file('x.mjs'), /^Refused launcher URL .*does not end in \.js$/],
    ['moduleUrl', file('x.wasm.js'), /^Refused module URL .*does not end in \.wasm$/],
  ]) {
    const reply = await init({mode: 'package', manifest, runtimeUrl: file('julc-runtime.js'),
      launcherUrl: file('demo-0123456789ab.js'), moduleUrl: file('demo-0123456789ab.js.wasm'), [key]: url});
    assert.equal(reply.type, 'error');
    assert.match(reply.message, pattern);
  }
});
