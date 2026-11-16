// The Promise client of JuLC's engines and of images other languages build on julc-wasm (ADR-064), through the
// shipped worker and Node bridge: archive mode as the release archives use it, the release checks (ADR-077 D1a), the
// REST adapter's default (D4) and prompt start-up errors (M1).
import test from 'node:test';
import assert from 'node:assert/strict';
import {rmSync} from 'node:fs';
import path from 'node:path';
import {
  CLIENT_VERSION, LAUNCHER, archiveBundle, archiveDir, launcherText, runtimeStamped, workerFactory,
} from './support.mjs';

const {createJulc, isFullClient} = await import(new URL('julc-wasm.js', archiveDir));
const OTHER = '0.0.1-other';
const escape = text => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const start = (bundle, options = {}) => createJulc({baseUrl: bundle.baseUrl, manifest: bundle.manifest, workerFactory,
  ...options});
const lang = (schemas, groups, extra = {}) => archiveBundle({launcher: launcherText({schemas, groups, variant: 'lang',
  version: '1.0.0', ...extra}), variant: 'lang', groups});

test('an advertised uplc group gets defaultTransaction even when the engine registers no uplc operation', async () => {
  const client = await start(lang({'lang.check': {}}, ['lang', 'uplc']));
  try {
    assert.equal(typeof client.uplc?.defaultTransaction, 'function');
    const transaction = await client.uplc.defaultTransaction({purpose: 'mint'});
    assert.equal(transaction.mint.length, 1);
  } finally { client.dispose(); }
  const withoutUplc = await start(lang({'lang.check': {}}, ['lang']));
  try { assert.equal(withoutUplc.uplc, undefined); } finally { withoutUplc.dispose(); }
});

test('a custom operation\'s string costModel reaches the engine unchanged', async () => {
  const client = await start(lang({'lang.check': {costModel: 'string'}}, ['lang']));
  try {
    assert.deepEqual(await client.lang.check({costModel: 'custom-model'}),
      {method: 'lang.check', body: {costModel: 'custom-model'}});
  } finally { client.dispose(); }
});

test('an engine whose variant or API version differs from its manifest is refused', async () => {
  const wrongVariant = lang({'lang.check': {}}, ['lang']);
  wrongVariant.manifest.variant = 'vm';
  await assert.rejects(start(wrongVariant), /variant or API version does not match/);
  // The api field comes from the runtime, so the stand-in engine changes what its runtime reports.
  const runtimeApi99 = archiveBundle({launcher: launcherText({schemas: {'lang.check': {}}, groups: ['lang'],
    variant: 'lang', version: '1.0.0'}), variant: 'lang', groups: ['lang'],
  runtime: runtimeStamped(CLIENT_VERSION).replace('api: 0, variant, groups', 'api: 99, variant, groups')});
  await assert.rejects(start(runtimeApi99), /variant or API version does not match/);
});

test('only JuLC\'s marked cost model record takes a profile name', async () => {
  const foreign = {'lang.check': {costModel: {profile: 'string', target: 'boolean', parameters: 'int'}}};
  const client = await start(lang(foreign, ['lang']));
  try {
    await assert.rejects(client.lang.check({costModel: 'custom-model'}), /Expected request object/);
    assert.deepEqual(await client.lang.check({costModel: {profile: 'p', target: true, parameters: 1}}),
      {method: 'lang.check', body: {costModel: {profile: 'p', target: true, parameters: 1}}});
  } finally { client.dispose(); }
  const marked = {'vm.run': {costModel: {$costModel: true, profile: 'string'}}};
  const julc = await start(archiveBundle({launcher: launcherText({schemas: marked})}));
  try {
    assert.deepEqual(await julc.vm.run({costModel: 'some-profile'}), {method: 'vm.run', body: {costModel: {profile: 'some-profile'}}});
  } finally { julc.dispose(); }
});

test('the client reports the engine\'s julcVersion and refuses an engine of another JuLC build (I2)', async () => {
  const client = await start(archiveBundle());
  try {
    assert.equal(client.version(), CLIENT_VERSION);
    assert.equal(client.features().julcVersion, CLIENT_VERSION);
    assert.equal(isFullClient(client), false);
  } finally { client.dispose(); }
  // A wrong engine: built on another julc-wasm, served with that build's runtime file.
  const wrongEngine = archiveBundle({launcher: launcherText({version: OTHER, julcVersion: OTHER,
    source: runtimeStamped(OTHER)}), runtime: runtimeStamped(OTHER), manifest: {julcVersion: OTHER}});
  await assert.rejects(start(wrongEngine),
    new RegExp(`built with JuLC ${escape(OTHER)}, but this client is JuLC ${escape(CLIENT_VERSION)}`));
  // A wrong manifest.
  await assert.rejects(start(archiveBundle({manifest: {julcVersion: OTHER}})),
    new RegExp(`but its manifest says JuLC ${escape(OTHER)}`));
  await assert.rejects(start(archiveBundle({manifest: {julcVersion: undefined}})), /but its manifest says JuLC undefined/);
  // JuLC's own variants report the JuLC version as version().
  await assert.rejects(start(archiveBundle({launcher: launcherText({version: '9.9.9'})})),
    /The JuLC vm engine reports version 9\.9\.9/);
  // An embedder's engine reports its own version, held only to expectedVersion.
  const embedder = lang({'lang.check': {}}, ['lang']);
  const demo = await start(embedder, {expectedVersion: '1.0.0'});
  try { assert.equal(demo.version(), '1.0.0'); } finally { demo.dispose(); }
  await assert.rejects(start(embedder, {expectedVersion: '2.0.0'}), /is version 1\.0\.0, not the expected 2\.0\.0/);
  // An embedder engine whose julc-wasm build differs from the client's.
  const otherBuild = archiveBundle({launcher: launcherText({schemas: {'lang.check': {}}, groups: ['lang'],
    variant: 'lang', version: '1.0.0', julcVersion: OTHER, source: runtimeStamped(OTHER)}),
  runtime: runtimeStamped(OTHER), variant: 'lang', groups: ['lang'], manifest: {julcVersion: OTHER}});
  await assert.rejects(start(otherBuild), new RegExp(`built with JuLC ${escape(OTHER)}`));
});

test('the runtime file beside the manifest must be the engine\'s own build (archive mode)', async () => {
  // A stale runtime file: the worker recorded its preload, so the engine refuses it, naming both versions.
  await assert.rejects(start(archiveBundle({runtime: runtimeStamped(OTHER)})),
    new RegExp(`JuLC engine ${escape(CLIENT_VERSION)} cannot start: the runtime file julc-runtime.js loaded before it `
      + `is JuLC ${escape(OTHER)}; serve the julc-runtime.js of JuLC ${escape(CLIENT_VERSION)}`));
  await assert.rejects(start(archiveBundle({runtime: runtimeStamped(null)})), /is not a stamped JuLC runtime/);
  // A malformed runtime: a JulcRuntime without install.
  await assert.rejects(start(archiveBundle({runtime: `globalThis.JulcRuntime = {julcVersion: ${JSON.stringify(CLIENT_VERSION)}};`})),
    /is not a stamped JuLC runtime/);
  // A missing runtime file is named.
  await assert.rejects(start(archiveBundle({runtime: null})), /Cannot load the runtime .*julc-runtime\.js\?engine=/);
  // An engine that reports it evaluated its own runtime although one was loaded: the client fails.
  const evaluated = archiveBundle({launcher: launcherText().replace("Object.defineProperty(api, '__runtime', {value: origin});",
    "Object.defineProperty(api, '__runtime', {value: 'evaluated'});")});
  await assert.rejects(start(evaluated), /did not use the runtime file julc-runtime\.js loaded before it \(it used evaluated\)/);
});

test('the REST adapter: on by default in the archives, off on request, named when missing (D4, I9)', async () => {
  const client = await start(archiveBundle({launcher: launcherText({schemas: {'vm.decode': {}}})}), {catalogue: {examples: [{name: 'x', language: 'java'}]}});
  try {
    assert.deepEqual(await client.rest('GET', '/api/health'), {status: 200, body: {status: 'ok', engine: 'wasm'}});
    assert.deepEqual((await client.rest('GET', '/api/examples')).body, [{name: 'x', language: 'java'}]);
    assert.deepEqual(await client.rest('POST', '/api/uplc/decode', {script: {script: 'x'}}),
      {status: 200, body: {method: 'vm.decode', body: {script: {script: 'x'}}}});
  } finally { client.dispose(); }
  const noAdapter = archiveBundle({rest: false});
  const plain = await start(noAdapter, {rest: false});
  try {
    assert.equal(plain.rest, undefined);
    assert.equal(plain.features().julcVersion, CLIENT_VERSION);
  } finally { plain.dispose(); }
  await assert.rejects(start(noAdapter), /Cannot load the REST adapter .*rest-adapter\.js\?engine=/);
});

test('the modes do not mix, and package mode has no REST adapter (D2a, D4)', async () => {
  const bundle = archiveBundle();
  const launcherUrl = new URL(LAUNCHER, bundle.baseUrl), moduleUrl = new URL(LAUNCHER + '.wasm', bundle.baseUrl);
  await assert.rejects(createJulc({baseUrl: bundle.baseUrl, launcherUrl, moduleUrl, manifest: bundle.manifest}),
    /Give either baseUrl \(archive mode\) or launcherUrl and moduleUrl \(package mode\), not both/);
  await assert.rejects(createJulc({baseUrl: bundle.baseUrl, moduleUrl, manifest: bundle.manifest}), /not both/);
  await assert.rejects(createJulc({launcherUrl, moduleUrl}), /Package mode needs launcherUrl, moduleUrl and the engine's manifest/);
  await assert.rejects(createJulc({launcherUrl, manifest: bundle.manifest}), /Package mode needs/);
  await assert.rejects(createJulc({launcherUrl, moduleUrl, manifest: bundle.manifest, rest: true}),
    /The REST adapter is not available in package mode/);
});

test('a start-up failure rejects within seconds with its cause', async () => {
  // Java main throwing: the Web Image prints the exception, and the launcher hook reports the exit code.
  const thrown = archiveBundle({launcher: `console.error('Exception in thread "main" java.lang.IllegalStateException: '
    + 'Record components not available for record class Demo');
    globalThis.onJulcExit(1);`});
  let started = performance.now();
  await assert.rejects(start(thrown), /^Error: The engine stopped before it was ready \(exit code 1\): Exception in thread "main" java\.lang\.IllegalStateException: Record components not available/);
  assert.ok(performance.now() - started < 5000);
  await assert.rejects(start(archiveBundle({launcher: 'throw new Error("launcher broke");'})),
    /Cannot load the launcher .*lang-0123456789ab\.js: launcher broke/);
  // A worker script that does not exist.
  const missing = archiveBundle();
  rmSync(path.join(missing.dir, 'julc-worker.js'));
  started = performance.now();
  await assert.rejects(start(missing), /The JuLC worker .*julc-worker\.js\?engine=0123456789ab failed: .*julc-worker\.js/);
  assert.ok(performance.now() - started < 5000);
  // An unhandled rejection while the engine starts.
  await assert.rejects(start(archiveBundle({launcher: 'Promise.reject(new Error("main rejected"));'})), /main rejected/);
});

test('an exit reported after the engine is ready does not break the client', async () => {
  const late = archiveBundle({launcher: `${launcherText()}\nsetTimeout(() => globalThis.onJulcExit(1), 50);`});
  const client = await start(late);
  try {
    await new Promise(resolve => setTimeout(resolve, 100));
    assert.deepEqual(await client.vm.echo({x: 1}), {method: 'vm.echo', body: {x: 1}});
  } finally { client.dispose(); }
});
