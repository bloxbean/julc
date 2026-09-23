import {readFileSync} from 'node:fs';
import {pathToFileURL} from 'node:url';
import {Worker} from 'node:worker_threads';
import assert from 'node:assert/strict';
import path from 'node:path';

const [bundle, variant, requestsFile, expectedFile, catalogueFile, vmFile] = process.argv.slice(2);
const baseUrl = pathToFileURL(path.resolve(bundle) + '/');
const {createJulc, isFullClient} = await import(new URL('julc-wasm.js', baseUrl));
await import(new URL('julc-runtime.js', baseUrl));
const manifest = JSON.parse(readFileSync(new URL('engine.json', baseUrl)));
const catalogue = JSON.parse(readFileSync(catalogueFile));
const workerFactory = url => {
  const worker = new Worker(new URL('./node-worker.mjs', import.meta.url), {workerData: url.href});
  const bridge = {postMessage: value => worker.postMessage(value), terminate: () => worker.terminate()};
  worker.on('message', data => bridge.onmessage?.({data}));
  worker.on('error', error => bridge.onerror?.(error));
  return bridge;
};
const started = performance.now();
const api = await createJulc({baseUrl, manifest, catalogue, workerFactory});
console.log(`${variant} worker ready in ${Math.round(performance.now() - started)} ms`);
try {
  const expectedGroups = variant === 'full'
    ? ['vm', 'debug', 'compiler', 'uplc', 'sourceDebug']
    : ['vm', 'debug'];
  assert.deepEqual(manifest.groups, expectedGroups);
  assert.deepEqual(api.features().groups, expectedGroups);
  assert.equal(isFullClient(api), variant === 'full');
  if (variant === 'full') {
    const requests = JSON.parse(readFileSync(requestsFile));
    const expected = new Map(JSON.parse(readFileSync(expectedFile)).map(r => [r.name, r]));
    for (const request of requests) {
      const actual = await api.rest(request.method, request.path, request.body);
      if (request.wasm === 'bls-unsupported') {
        assert.equal(actual.status, 200);
        assert.equal(actual.body.success, false);
        assert.match(actual.body.error, /BLS12-381 builtins are not supported/);
      } else {
        assert.equal(actual.status, expected.get(request.name).status, request.name);
        assert.deepEqual(actual.body, expected.get(request.name).body, request.name);
      }
      console.log('ok', request.name);
    }
  } else assert.equal(api.compiler, undefined);
  for (const fixture of JSON.parse(readFileSync(vmFile))) {
    const request = JulcRuntime.decode(fixture.request);
    const result = await api.vm.evaluate(request);
    assert.deepEqual(result, JulcRuntime.decode(fixture.evaluate), fixture.name + ' evaluation');
    const session = await api.vm.debug(request);
    assert.deepEqual(await session.continue(), JulcRuntime.decode(fixture.debug), fixture.name + ' replay');
    await session.close();
    console.log('ok VM parity', fixture.name);
  }
  const request = {script: {script: '(program 1.1.0 (lam x x))'},
    args: [{int: (1n << 80n) + 1n}]};
  const result = await api.vm.evaluate(request);
  assert.equal(result.status, 'success');
  assert.match(result.result, /1208925819614629174706177/);
  assert.equal(typeof result.cpu, 'bigint');
  for (const parameter of [{format: 'json', value: '{"int":42}'},
    {format: 'uplc', value: 'I 42'}, {format: 'cbor', value: '182a'}, {int: 42n}]) {
    const decoded = await api.vm.decode({script: {...request.script, params: [parameter]}});
    assert.equal(decoded.ok, true, decoded.error);
    assert.equal(decoded.info.paramsApplied, 1);
    const evaluated = await api.vm.evaluate({script: {script: decoded.info.compiledCode}});
    assert.equal(evaluated.status, 'success');
    assert.match(evaluated.result, /42/);
  }
  const decoded = await api.vm.decode({script: request.script});
  assert.equal((await api.vm.hash({script: request.script})).scriptHash, decoded.info.scriptHash);
  assert.equal((await api.vm.prettyPrint({script: request.script})).uplcText, decoded.uplcText);
  await assert.rejects(api.vm.evaluate({...request, target: {protocol: 9}}), error =>
    error.code === 'invalid-request' && error.status === 400 && typeof error.body.error === 'string');
  await assert.rejects(api.vm.evaluate({...request, maxCpu: 1n << 63n}), error => error.status === 400);
  const v2 = await api.vm.decode({script: {script: '(program 1.0.0 (lam x x))', language: 'V2'}});
  const envelope = {script: JSON.stringify({type: 'PlutusScriptV2', cborHex: v2.info.compiledCode})};
  for (const protocol of [10, 11]) {
    const evaluated = await api.vm.evaluate({script: envelope, args: [{int: 42n}], target: {protocol}});
    assert.deepEqual(evaluated.target, {language: 'PlutusV2', protocol});
    assert.equal(evaluated.costModelId, 'default:PLUTUS_V2:' + protocol);
  }
  await assert.rejects(api.vm.evaluate({...request, extra: () => 1}));
  assert.deepEqual(await api.vm.evaluate(request), result); // clone failures must not poison the worker
  if (variant === 'full') {
    for (const purpose of ['spend', 'mint', 'reward', 'certify', 'vote', 'propose']) {
      const script = {script: '(program 1.1.0 (lam x (con unit ())))'};
      const hash = (await api.vm.hash({script})).scriptHash;
      const implicit = await api.uplc.evaluateTransaction({script, transaction: await api.uplc.defaultTransaction({purpose})});
      const explicit = await api.uplc.evaluateTransaction({script, transaction: await api.uplc.defaultTransaction({purpose, scriptHash: hash})});
      assert.equal(implicit.ok, true, purpose);
      assert.deepEqual(explicit, implicit, purpose + ' explicit script credential');
    }
    const sourceSession = await api.sourceDebug.open({
      source: `@SpendingValidator
class WasmSourceDebugSmoke {
  @Entrypoint
  static boolean validate(PlutusData redeemer, ScriptContext ctx) {
    return true;
  }
}`,
      transaction: await api.uplc.defaultTransaction({purpose: 'spend'}),
    });
    assert.equal(sourceSession.ok, true, sourceSession.error);
    assert.ok(sourceSession.sessionId);
    assert.ok(sourceSession.executableJavaLines.length > 0);
    const sourceStep = await sourceSession.step();
    assert.equal(sourceStep.ok, true, sourceStep.error);
    assert.equal(typeof sourceStep.snapshot.step, 'bigint');
    await sourceSession.close();
    assert.equal(sourceSession.isOpen(), false);
    console.log('ok Java source-debug session');
  }
  const session = await api.vm.debug(request);
  assert.equal(session.isOpen(), true);
  const completed = await session.continue();
  assert.equal(completed.snapshot.status, 'success');
  await session.close();
  assert.equal(session.isOpen(), false);
  await assert.rejects(session.step(), error => error.status === 404);
  const lost = await api.vm.debug(request);
  api.dispose();
  assert.equal(lost.isOpen(), false);
  await assert.rejects(lost.snapshot(), error => error.status === 404);
  const timed = await createJulc({baseUrl, manifest, catalogue, workerFactory, timeoutMs: 200});
  try {
    const session = await timed.vm.debug(request);
    await assert.rejects(timed.vm.evaluate({
      script: {script: '(program 1.1.0 [(lam x [x x]) (lam x [x x])])'},
      maxCpu: (1n << 63n) - 1n, maxMem: (1n << 63n) - 1n,
    }), error => error.status === 408);
    assert.equal(session.isOpen(), false);
    await assert.rejects(session.step(), error => error.status === 404);
  } finally { timed.dispose(); }
  console.log('ok exact integers, debug replay, close and worker disposal');
} finally { api.dispose(); }
