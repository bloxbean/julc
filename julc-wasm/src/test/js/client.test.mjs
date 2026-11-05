// The Promise client of an image another language builds on julc-wasm (ADR-064), through the shipped worker.
import test from 'node:test';
import assert from 'node:assert/strict';
import {copyFileSync, mkdtempSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {Worker} from 'node:worker_threads';
import {createJulc} from '../../main/js/julc-wasm.js';

const runtime = new URL('../../main/resources/julc-runtime.js', import.meta.url);
const workerFactory = url => {
  const worker = new Worker(new URL('./node-worker.mjs', import.meta.url), {workerData: url.href});
  const bridge = {postMessage: value => worker.postMessage(value), terminate: () => worker.terminate()};
  worker.on('message', data => bridge.onmessage?.({data}));
  worker.on('error', error => bridge.onerror?.(error));
  return bridge;
};

/** A bundle whose launcher installs the shipped runtime with an engine that echoes each request it receives. */
function bundle(schemas, groups, ready = 'api') {
  const dir = mkdtempSync(path.join(tmpdir(), 'julc-client-'));
  copyFileSync(fileURLToPath(new URL('../../main/js/julc-worker.js', import.meta.url)), path.join(dir, 'julc-worker.js'));
  writeFileSync(path.join(dir, 'rest-adapter.js'), 'globalThis.JulcRestAdapter = class {};');
  const launcher = 'lang-0123456789ab.js';
  writeFileSync(path.join(dir, launcher), `globalThis.importScripts(${JSON.stringify(runtime.href)});
    const invoke = (method, body) => JSON.stringify({status: 200, body: {method, body: JSON.parse(body)}, integers: []});
    const api = JulcRuntime.install(invoke, ${JSON.stringify(schemas)}, 'lang', '1.0.0', ${JSON.stringify(groups)});
    globalThis.onJulcReady(${ready});`);
  return {baseUrl: pathToFileURL(dir + '/'), manifest: {api: 0, version: '1.0.0', variant: 'lang', launcher, groups}};
}

test('an advertised uplc group gets defaultTransaction even when the engine registers no uplc operation', async () => {
  const client = await createJulc({...bundle({'lang.check': {}}, ['lang', 'uplc']), workerFactory});
  try {
    assert.equal(typeof client.uplc?.defaultTransaction, 'function');
    const transaction = await client.uplc.defaultTransaction({purpose: 'mint'});
    assert.equal(transaction.mint.length, 1);
  } finally { client.dispose(); }
  const withoutUplc = await createJulc({...bundle({'lang.check': {}}, ['lang']), workerFactory});
  try { assert.equal(withoutUplc.uplc, undefined); } finally { withoutUplc.dispose(); }
});

test('a custom operation\'s string costModel reaches the engine unchanged', async () => {
  const client = await createJulc({...bundle({'lang.check': {costModel: 'string'}}, ['lang']), workerFactory});
  try {
    assert.deepEqual(await client.lang.check({costModel: 'custom-model'}),
      {method: 'lang.check', body: {costModel: 'custom-model'}});
  } finally { client.dispose(); }
});

test('an engine whose variant or API version differs from its manifest is refused', async () => {
  const wrongVariant = bundle({'lang.check': {}}, ['lang']);
  wrongVariant.manifest.variant = 'vm';
  await assert.rejects(createJulc({...wrongVariant, workerFactory}), /variant or API version does not match/);
  const wrongApi = bundle({'lang.check': {}}, ['lang'], "{...api, features: () => ({...api.features(), api: 99})}");
  await assert.rejects(createJulc({...wrongApi, workerFactory}), /variant or API version does not match/);
  const noGroups = bundle({'lang.check': {}}, ['lang']);
  delete noGroups.manifest.groups;
  noGroups.manifest.variant = 'vm';
  await assert.rejects(createJulc({...noGroups, workerFactory}), /variant or API version does not match/);
});

test('only JuLC\'s marked cost model record takes a profile name', async () => {
  const foreign = {'lang.check': {costModel: {profile: 'string', target: 'boolean', parameters: 'int'}}};
  const client = await createJulc({...bundle(foreign, ['lang']), workerFactory});
  try {
    await assert.rejects(client.lang.check({costModel: 'custom-model'}), /Expected request object/);
    assert.deepEqual(await client.lang.check({costModel: {profile: 'p', target: true, parameters: 1}}),
      {method: 'lang.check', body: {costModel: {profile: 'p', target: true, parameters: 1}}});
  } finally { client.dispose(); }
  const marked = {'vm.run': {costModel: {$costModel: true, profile: 'string'}}};
  const julc = await createJulc({...bundle(marked, ['vm']), workerFactory});
  try {
    assert.deepEqual(await julc.vm.run({costModel: 'some-profile'}), {method: 'vm.run', body: {costModel: {profile: 'some-profile'}}});
  } finally { julc.dispose(); }
});
