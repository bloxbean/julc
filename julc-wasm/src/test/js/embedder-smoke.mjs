// The image another language builds on julc-wasm (ADR-064), served with the client files from the julc-wasm jar:
// its own operations work, and JuLC's VM operations give exactly the JVM's results.
import {readFileSync} from 'node:fs';
import {pathToFileURL} from 'node:url';
import {Worker} from 'node:worker_threads';
import assert from 'node:assert/strict';
import path from 'node:path';
import '../../main/resources/julc-runtime.js';

const [bundle, vmFile] = process.argv.slice(2);
const baseUrl = pathToFileURL(path.resolve(bundle) + '/');
const {createJulc, isFullClient} = await import(new URL('julc-wasm.js', baseUrl));
const manifest = JSON.parse(readFileSync(new URL('engine.json', baseUrl)));
const workerFactory = url => {
  const worker = new Worker(new URL('./node-worker.mjs', import.meta.url), {workerData: url.href});
  const bridge = {postMessage: value => worker.postMessage(value), terminate: () => worker.terminate()};
  worker.on('message', data => bridge.onmessage?.({data}));
  worker.on('error', error => bridge.onerror?.(error));
  return bridge;
};
assert.match(manifest.launcher, /^demo-lang-[0-9a-f]{12}\.js$/);
const started = performance.now();
// Node's fetch cannot read file: URLs, so the manifest is passed in.
const api = await createJulc({baseUrl, manifest, workerFactory});
console.log(`embedder worker ready in ${Math.round(performance.now() - started)} ms`);
try {
  assert.equal(api.version(), '1.0.0-demo');
  assert.deepEqual(api.features(), {api: 0, variant: 'demo', groups: ['vm', 'debug', 'demo'], defaultProtocol: 11,
    bls: false});
  assert.equal(isFullClient(api), false);
  assert.equal(api.compiler, undefined);
  assert.equal(api.uplc, undefined);

  // The language's own records cross with exact integers (reflection registered by the image's metadata).
  const sum = await api.demo.sum({terms: [{name: 'a', value: (1n << 80n) + 1n}, {name: 'b', value: -1n}],
    scale: 1n << 40n});
  assert.deepEqual(sum, {total: (1n << 120n), count: 2n, names: ['a', 'b']});
  await assert.rejects(api.demo.sum({}), error => error.status === 400 && error.body.error === 'terms are required');
  await assert.rejects(api.demo.sum({terms: [], scale: 1n << 63n}), error => error.status === 400);
  console.log('ok demo.sum');

  for (const fixture of JSON.parse(readFileSync(vmFile))) {
    const request = JulcRuntime.decode(fixture.request);
    assert.deepEqual(await api.vm.evaluate(request), JulcRuntime.decode(fixture.evaluate), fixture.name);
    const session = await api.vm.debug(request);
    assert.deepEqual(await session.continue(), JulcRuntime.decode(fixture.debug), fixture.name + ' replay');
    await session.close();
    console.log('ok VM parity', fixture.name);
  }
} finally { api.dispose(); }
