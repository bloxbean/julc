// The worker loads only a launcher named in engine.json next to it (ADR-064 I4), whatever language built the image.
import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import {Worker} from 'node:worker_threads';

const workerScript = new URL('../../main/js/julc-worker.js', import.meta.url);

function init(manifest, base) {
  const worker = new Worker(new URL('./node-worker.mjs', import.meta.url), {workerData: workerScript.href});
  return new Promise((resolve, reject) => {
    worker.once('message', data => { worker.terminate(); resolve(data); });
    worker.once('error', error => { worker.terminate(); reject(error); });
    worker.postMessage({type: 'init', base: base.href, manifest});
  });
}

test('the worker rejects a launcher that is not a plain file name next to the manifest', async () => {
  const base = pathToFileURL(mkdtempSync(path.join(tmpdir(), 'julc-worker-')) + '/');
  const hash = '0123456789ab';
  for (const launcher of [`../demo-${hash}.js`, `sub/demo-${hash}.js`, `https://example.com/demo-${hash}.js`,
    `//example.com/demo-${hash}.js`, `demo-${hash}.js?x=1`, `demo-${hash}.js#x`, `demo-${hash}.js\n`,
    `Demo-${hash}.js`, `ElciWasm-${hash}.js`, `elci_wasm-${hash}.js`, `demo-${hash}.mjs`, `demo-${hash}.js.wasm`,
    `demo-0123456789a.js`, `demo--${hash}.js`, `-demo-${hash}.js`, `${hash}.js`, 42, undefined]) {
    const reply = await init({api: 0, version: hash, launcher, variant: 'demo'}, base);
    assert.equal(reply.type, 'error', String(launcher));
    assert.match(reply.message, /^Invalid engine manifest: launcher .* is not a file name of lowercase words joined by /s,
      String(launcher));
  }
});

test('the worker rejects unknown API versions and invalid variants', async () => {
  const base = pathToFileURL(mkdtempSync(path.join(tmpdir(), 'julc-worker-')) + '/');
  const launcher = 'demo-0123456789ab.js';
  assert.match((await init({api: 1, version: 'x', launcher, variant: 'demo'}, base)).message,
    /^Invalid engine manifest: unsupported API version 1$/);
  for (const variant of ['Demo', '1demo', 'de mo', '', 7]) {
    assert.match((await init({api: 0, version: 'x', launcher, variant}, base)).message,
      /^Invalid engine manifest: variant .* does not match/, String(variant));
  }
});

test('the worker reports main failing before the engine is ready instead of waiting for the load timeout', async () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'julc-worker-'));
  writeFileSync(path.join(dir, 'rest-adapter.js'), 'globalThis.JulcRestAdapter = class {};');
  // What the launcher hook does when main ends with an exception (exit code 1) before JsApi.install.
  writeFileSync(path.join(dir, 'demo-0123456789ab.js'), 'globalThis.onJulcExit(1);');
  const reply = await init({api: 0, version: 'x', launcher: 'demo-0123456789ab.js', variant: 'demo'},
    pathToFileURL(dir + '/'));
  assert.deepEqual(reply, {type: 'error',
    message: 'The engine stopped before it was ready (exit code 1); its error is in the worker console'});
});

test('the worker accepts any language\'s launcher name and loads it from the manifest\'s directory', async () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'julc-worker-'));
  writeFileSync(path.join(dir, 'rest-adapter.js'), 'globalThis.JulcRestAdapter = class {};');
  for (const launcher of ['julc-0123456789ab.js', 'julc-vm-0123456789ab.js', 'elci-0123456789ab.js',
    'demo-lang2-0123456789ab.js']) {
    // A launcher that installs a stub engine, as JsApi does.
    writeFileSync(path.join(dir, launcher), `globalThis.onJulcReady({version: () => '${launcher}',
      features: () => ({groups: ['vm']}), __schemas: {}});`);
    const reply = await init({api: 0, version: 'x', launcher, variant: 'demo'}, pathToFileURL(dir + '/'));
    assert.equal(reply.type, 'ready', reply.message);
    assert.equal(reply.version, launcher);
  }
});
