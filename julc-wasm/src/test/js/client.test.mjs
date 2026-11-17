// The Promise client of JuLC's engines and of images other languages build on julc-wasm (ADR-064), through the
// shipped worker and Node bridge: archive mode as the release archives use it, the release checks (ADR-077 D1a), the
// REST adapter's default (D4) and prompt start-up errors (M1).
import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {pathToFileURL} from 'node:url';
import path from 'node:path';
import {
  CLIENT_VERSION, LAUNCHER, archiveBundle, archiveDir, launcherText, packageDir, runtimeStamped, workerFactory,
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
  const evaluated = archiveBundle({launcher: launcherText().replace(
    "Object.defineProperty(api, Symbol.for('julc.runtime'), {value: origin});",
    "Object.defineProperty(api, Symbol.for('julc.runtime'), {value: 'evaluated'});")});
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

test('an embedder may name an operation group __runtime: the runtime\'s provenance is not a group (M1-C2)', async () => {
  const client = await start(lang({'__runtime.check': {}, 'lang.run': {}}, ['__runtime', 'lang']));
  try {
    assert.deepEqual(await client.__runtime.check({x: 1}), {method: '__runtime.check', body: {x: 1}});
    assert.deepEqual(await client.lang.run({}), {method: 'lang.run', body: {}});
  } finally { client.dispose(); }
});

test('a second init during start-up is ignored (M1-F5)', async () => {
  const bundle = archiveBundle({launcher: `setTimeout(() => {\n${launcherText()}\n}, 100);`});
  let sent = 0;
  const twice = url => {
    const worker = workerFactory(url);
    const postMessage = worker.postMessage;
    worker.postMessage = message => {
      postMessage(message);
      // The same init again, with another manifest, while the engine is starting.
      if (message.type === 'init' && sent++ === 0) postMessage({...message, manifest: {...message.manifest, variant: 'Bad'}});
    };
    return worker;
  };
  const client = await createJulc({baseUrl: bundle.baseUrl, manifest: bundle.manifest, workerFactory: twice});
  try {
    assert.equal(client.features().variant, 'vm');
    assert.deepEqual(await client.vm.echo({}), {method: 'vm.echo', body: {}});
  } finally { client.dispose(); }
});

// Package mode in Node.js through the npm package's own entry and bridge.
const node = await import(new URL('node.js', packageDir));
function packageEngine(launcher) {
  const dir = mkdtempSync(path.join(tmpdir(), 'julc-package-mode-'));
  writeFileSync(path.join(dir, LAUNCHER), launcher);
  writeFileSync(path.join(dir, LAUNCHER + '.wasm'), '');
  return {launcherUrl: pathToFileURL(path.join(dir, LAUNCHER)), moduleUrl: pathToFileURL(path.join(dir, LAUNCHER + '.wasm')),
    manifest: {api: 0, version: '0123456789ab', variant: 'vm', launcher: LAUNCHER, groups: ['vm'], julcVersion: CLIENT_VERSION}};
}

test('the Node bridge fails the client when its worker exits by itself (M1-F2)', async () => {
  const started = performance.now();
  await assert.rejects(node.createJulc(packageEngine('process.exit(3);')),
    /^Error: The JuLC worker julc-worker\.js failed: the worker exited with code 3$/);
  assert.ok(performance.now() - started < 5000);
  // After the engine is ready, too: pending requests fail instead of waiting for their timeout.
  const client = await node.createJulc(packageEngine(launcherText({schemas: {'vm.echo': {}, 'vm.exit': {}}})
    .replace('argv => JSON.stringify(', 'argv => argv[0] === "vm.exit" ? process.exit(4) : JSON.stringify(')));
  await assert.rejects(client.vm.exit({}), /the worker exited with code 4/);
});

test('in package mode, an engine of another release points to the client of its own release (M1-F3)', async () => {
  // The engine checks the runtime file, which comes with this client, first.
  const engine = packageEngine(launcherText({version: OTHER, julcVersion: OTHER, source: runtimeStamped(OTHER)}));
  await assert.rejects(node.createJulc({...engine, manifest: {...engine.manifest, julcVersion: OTHER}}),
    new RegExp(`loaded before it is JuLC ${escape(CLIENT_VERSION)}; serve the julc-runtime.js of JuLC ${escape(OTHER)} `
      + `with this engine, or install the @julclang/wasm-client of JuLC ${escape(OTHER)}$`));
  // An engine whose runtime check passes but which reports another build is refused by the client.
  const reports = packageEngine(launcherText({version: OTHER}).replace(
    'String(version), JSON.parse(String(groups)), own);', `String(version), JSON.parse(String(groups)), ${JSON.stringify(OTHER)});`));
  await assert.rejects(node.createJulc({...reports, manifest: {...reports.manifest, julcVersion: OTHER}}),
    new RegExp(`built with JuLC ${escape(OTHER)}, but this client is JuLC ${escape(CLIENT_VERSION)}; use the client of `
      + `the engine's own release, or install the @julclang/wasm-client of JuLC ${escape(OTHER)}$`));
  // In archive mode the hint is not given: the archive's files belong together.
  await assert.rejects(start(archiveBundle({launcher: launcherText({version: OTHER, julcVersion: OTHER,
    source: runtimeStamped(OTHER)})})), error => !error.message.includes('@julclang/wasm-client'));
});

test('readiness is checked against the client\'s own copy of the identity, not the init a workerFactory sees (M3-C4)', async () => {
  const vm = packageEngine(launcherText());
  const other = packageEngine(launcherText({variant: 'full'}));
  other.manifest.variant = 'full';
  const twin = packageEngine(launcherText());
  // Must be refused; a client that starts anyway is disposed, so a failure cannot leave its worker running.
  const refused = (started, pattern) => assert.rejects(started.then(client => {
    client.dispose();
    throw new Error('accepted');
  }), pattern);
  // A forwarding adapter that rewrites the init message it is given before passing it on.
  const forwarding = rewrite => () => {
    const worker = workerFactory(new URL('julc-worker.js', packageDir));
    const postMessage = worker.postMessage;
    worker.postMessage = message => postMessage(message.type === 'init' ? rewrite(message) : message);
    return worker;
  };
  // The manifest in the init and both URLs moved to another engine: the engine is compared with the client's copy.
  await refused(node.createJulc({...vm, workerFactory: forwarding(init => {
    Object.assign(init.manifest, other.manifest);
    init.launcherUrl = other.launcherUrl.href;
    init.moduleUrl = other.moduleUrl.href;
    return init;
  })}), /^Error: JuLC engine variant or API version does not match its manifest$/);
  // Only the URLs moved, to an engine with the same identity elsewhere: the worker reports what it loaded.
  await refused(node.createJulc({...vm, workerFactory: forwarding(init => {
    init.launcherUrl = twin.launcherUrl.href;
    init.moduleUrl = twin.moduleUrl.href;
    return init;
  })}), new RegExp(`^Error: The JuLC worker loaded the launcher ${escape(JSON.stringify(twin.launcherUrl.href))}, `
    + `not ${escape(JSON.stringify(vm.launcherUrl.href))}$`));
  // Archive mode: another directory.
  const bundle = archiveBundle(), elsewhere = archiveBundle();
  await refused(createJulc({baseUrl: bundle.baseUrl, manifest: bundle.manifest,
    workerFactory: url => {
      const worker = workerFactory(url);
      const postMessage = worker.postMessage;
      worker.postMessage = message => postMessage(message.type === 'init' ? {...message, base: elsewhere.baseUrl.href} : message);
      return worker;
    }}), /^Error: The JuLC worker loaded the runtime .*, not /);
  // The caller's own objects, changed after the call, do not matter either.
  const manifest = {...vm.manifest}, launcherUrl = new URL(vm.launcherUrl);
  const started = node.createJulc({...vm, manifest, launcherUrl});
  Object.assign(manifest, other.manifest);
  launcherUrl.href = other.launcherUrl.href;
  const client = await started;
  try {
    assert.equal(client.features().variant, 'vm');
  } finally { client.dispose(); }
});
