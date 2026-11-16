import test from 'node:test';
import assert from 'node:assert/strict';
// The runtime as shipped: stamped with the JuLC version at build time.
import '../../../build/wasm-client/archive/julc-runtime.js';
import '../../main/js/rest-adapter.js';
const {encode, decode, dataText, legacy} = globalThis.JulcRuntime;

test('REST adapter reproduces JSON number rounding above 2^53 without changing typed results', () => {
  const exact = (1n << 53n) + 1n;
  const body = {success: false, budgetCpu: exact, budgetMem: (1n << 63n) - 1n, traces: []};
  const api = {__schemas: {'compiler.evaluate': {}}, compiler: {evaluate: () => body}};
  const adapter = new JulcRestAdapter(api);
  assert.deepEqual(adapter.request('POST', '/api/evaluate', {}), {
    status: 200,
    body: JSON.parse('{"success":false,"budgetCpu":9007199254740993,"budgetMem":9223372036854775807,"traces":[]}'),
  });
  assert.equal(body.budgetCpu, exact);
  assert.equal(typeof body.budgetMem, 'bigint');
});

test('long positions, collections and arrays stay exact', () => {
  for (const n of [(1n << 53n) - 1n, (1n << 53n) + 1n, -(1n << 63n), (1n << 63n) - 1n]) {
    assert.deepEqual(encode({scalar: n, list: [n]}, {scalar: 'long', list: ['long']}), {scalar: String(n), list: [String(n)]});
    assert.deepEqual(decode({status: 200, body: {list: [String(n)]}, integers: [['list', '0']]}), {list: [n]});
    assert.equal(legacy(n), JSON.parse(n.toString()));
  }
  assert.throws(() => encode(1n << 63n, 'long'), /range/);
  assert.throws(() => encode(42, 'long'), /BigInt/);
});
test('structured tags and nested Data have decimal text without rounding', () => {
  for (const tag of [(1n << 53n) + 1n, (1n << 64n) - 1n]) {
    const data = {constructor: tag, fields: [{list: [{map: [{k: {int: 1n}, v: {int: (1n << 80n) + 1n}}]}]}]};
    assert.ok(dataText(data).includes('"constructor":' + tag));
    assert.ok(dataText(data).includes('1208925819614629174706177'));
  }
  for (const tag of [-1n, 1n << 64n, 1]) assert.throws(() => dataText({constructor: tag, fields: []}));
});
test('failure results remain results; service errors preserve bodies', () => {
  assert.deepEqual(decode({status: 200, body: {success: false, cpu: '12'}, integers: [['cpu']]}), {success: false, cpu: 12n});
  assert.throws(() => decode({status: 422, body: {diagnostics: []}, integers: []}), e => e.code === 'blueprint-error' && e.status === 422);
  // vm.applyParams names its two 422 kinds; no other 422 body can choose its code.
  for (const code of ['invalid-schema', 'parameter-mismatch']) {
    assert.throws(() => decode({status: 422, body: {error: 'x', code}, integers: []}), e => e.code === code && e.status === 422);
  }
  assert.throws(() => decode({status: 422, body: {error: 'x', code: 'internal'}, integers: []}), e => e.code === 'blueprint-error');
  assert.throws(() => decode({status: 400, body: {error: 'x', code: 'invalid-schema'}, integers: []}), e => e.code === 'invalid-request');
});
test('explicit script hashes remain script credentials in transaction templates', () => {
  const scriptHash = 'ab'.repeat(28);
  const {defaultTransaction} = JulcRuntime;
  assert.equal(defaultTransaction({scriptHash}).inputs[0].address.payment, 'script:' + scriptHash);
  assert.equal(defaultTransaction({purpose: 'mint', scriptHash}).mint[0].policyId, scriptHash);
  assert.equal(defaultTransaction({purpose: 'reward', scriptHash}).purpose.target, 'script:' + scriptHash);
  assert.equal(defaultTransaction({purpose: 'certify', scriptHash}).certificates[0].credential, 'script:' + scriptHash);
  assert.equal(defaultTransaction({purpose: 'vote', scriptHash}).votes[0].voter, 'script:' + scriptHash);
  assert.equal(defaultTransaction({purpose: 'propose', scriptHash}).proposals[0].guardrail, scriptHash);
  assert.throws(() => defaultTransaction({scriptHash: 'abcd'}), /28-byte/);
});

test('source-debug REST routes retain the session and convert exact generation values', () => {
  let opened, acted, locals, children, closed;
  const api = {
    __schemas: {
      'sourceDebug.open': {source: 'string', maxCpu: 'long'},
      'sourceDebug.act': {sessionId: 'string', action: 'string', step: 'long'},
      'sourceDebug.locals': {sessionId: 'string', stopGeneration: 'long'},
      'sourceDebug.children': {sessionId: 'string', stopGeneration: 'long', handle: 'string'},
      'sourceDebug.close': {sessionId: 'string'},
    },
    sourceDebug: {
      open: request => (opened = request, {ok: true, sessionId: 'source-1', snapshot: {step: 0n}}),
      act: request => (acted = request, {ok: true, snapshot: {step: request.step}}),
      locals: request => (locals = request, {ok: true, stopGeneration: request.stopGeneration, scopes: []}),
      children: request => (children = request, {ok: true, stopGeneration: request.stopGeneration, children: []}),
      close: request => (closed = request, {closed: true}),
    },
  };
  const adapter = new JulcRestAdapter(api);
  assert.equal(adapter.request('POST', '/api/source-debug/open', {source: 'class V {}', maxCpu: '42'}).body.sessionId, 'source-1');
  assert.equal(opened.maxCpu, 42n);
  assert.equal(adapter.request('POST', '/api/source-debug/act', {sessionId: 'source-1', action: 'goto', step: '9007199254740993'}).body.snapshot.step,
    JSON.parse('9007199254740993'));
  assert.equal(acted.step, 9007199254740993n);
  assert.equal(adapter.request('POST', '/api/source-debug/locals', {
    sessionId: 'source-1', stopGeneration: '9007199254740995',
  }).body.stopGeneration, JSON.parse('9007199254740995'));
  assert.equal(locals.stopGeneration, 9007199254740995n);
  adapter.request('POST', '/api/source-debug/children', {
    sessionId: 'source-1', stopGeneration: '9007199254740995', handle: 'f-1', start: 0, count: 10,
  });
  assert.equal(children.stopGeneration, 9007199254740995n);
  assert.deepEqual(adapter.request('POST', '/api/source-debug/close', {sessionId: 'source-1'}).body, {closed: true});
  assert.equal(closed.sessionId, 'source-1');
});

test('capabilities are the groups the image passes', () => {
  const invoke = () => JSON.stringify({status: 200, body: {}, integers: []});
  const fullGroups = ['vm', 'debug', 'compiler', 'uplc', 'sourceDebug', 'sourceDebugLocals'];
  const full = JulcRuntime.install(invoke, {'uplc.decompile': {}}, 'full', 'test', fullGroups, 'j1');
  const vm = JulcRuntime.install(invoke, {'vm.evaluate': {}}, 'vm', 'test', ['vm', 'debug'], 'j1');
  assert.deepEqual(full.features().groups, fullGroups);
  assert.deepEqual(vm.features().groups, ['vm', 'debug']);
  assert.equal(typeof full.uplc.defaultTransaction, 'function');
  assert.equal(vm.uplc, undefined);
  // features() hands out copies: a caller cannot change what the next call reports.
  full.features().groups.push('extra');
  assert.deepEqual(full.features().groups, fullGroups);
});

test('an image built on julc-wasm for another language reports its own groups and variant', () => {
  const invoke = (method, body) => JSON.stringify({status: 200, body: {method, body: JSON.parse(body)}, integers: []});
  const api = JulcRuntime.install(invoke, {'vm.evaluate': {}, 'lang.compile': {sources: {$map: 'string'}}},
    'lang', '1.2.3', ['vm', 'debug', 'lang'], '0.1.0-test');
  assert.deepEqual(api.features(), {api: 0, variant: 'lang', groups: ['vm', 'debug', 'lang'], defaultProtocol: 11,
    bls: false, julcVersion: '0.1.0-test'});
  assert.equal(api.version(), '1.2.3');
  assert.deepEqual(api.lang.compile({sources: {'Main.elm': 'x'}}),
    {method: 'lang.compile', body: {sources: {'Main.elm': 'x'}}});
  assert.equal(api.uplc, undefined);
  assert.throws(() => JulcRuntime.install(invoke, {}, 'lang', '1', undefined), /groups are required/);
  assert.throws(() => JulcRuntime.install(invoke, {}, 'lang', '1', []), /groups are required/);
  // Every engine reports the JuLC build it is made with (ADR-077 D1a).
  assert.throws(() => JulcRuntime.install(invoke, {}, 'lang', '1', ['lang']), /Engine JuLC version is required/);
  assert.throws(() => JulcRuntime.install(invoke, {}, 'lang', '1', ['lang'], ''), /Engine JuLC version is required/);
});

test('operations stay in advertised groups and never extend a member every engine or object has', () => {
  const invoke = () => JSON.stringify({status: 200, body: {}, integers: []});
  assert.throws(() => JulcRuntime.install(invoke, {'lang.compile': {}}, 'lang', '1', ['vm'], 'j1'),
    /lang\.compile is outside the engine groups/);
  for (const group of ['version', 'features', '__schemas', 'constructor', 'toString', 'hasOwnProperty']) {
    assert.throws(() => JulcRuntime.install(invoke, {[group + '.run']: {}}, 'lang', '1', [group], 'j1'),
      /Reserved engine group/, group);
  }
  assert.equal(Object.prototype.run, undefined);
  assert.equal(Object.run, undefined);
});

test('the costModel profile shorthand applies only where costModel is JuLC\'s cost model record', () => {
  const invoke = (method, body) => JSON.stringify({status: 200, body: JSON.parse(body), integers: []});
  const costModel = {$costModel: true, profile: 'string', target: {language: 'string', protocol: 'int'}, parameters: ['long']};
  const api = JulcRuntime.install(invoke, {'vm.evaluate': {costModel}, 'lang.check': {costModel: 'string'},
    'lang.tune': {costModel: {name: 'string'}}}, 'lang', '1', ['vm', 'lang'], 'j1');
  assert.deepEqual(api.vm.evaluate({costModel: 'plutus-v3'}), {costModel: {profile: 'plutus-v3'}});
  assert.deepEqual(api.vm.evaluate({costModel: {profile: 'plutus-v3'}}), {costModel: {profile: 'plutus-v3'}});
  assert.deepEqual(api.lang.check({costModel: 'custom-model'}), {costModel: 'custom-model'});
  // Another record named costModel is not JuLC's: a string is not turned into it.
  assert.throws(() => api.lang.tune({costModel: 'custom-model'}), /Expected request object/);
});

test('dataText is iterative and gives the recursive version\'s text and errors, byte for byte', async () => {
  const {dataText: recursive} = await import('./data-text-recursive.mjs');
  // A small deterministic generator (mulberry32), so a failure is reproducible from its seed.
  let seed = 0x5eed1234;
  const random = () => {
    seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  const pick = items => items[Math.floor(random() * items.length)];
  const invalidNode = () => pick([null, 1, 'x', [], {}, {int: 1}, {int: 1n, bytes: ''}, {bytes: 'abc'}, {bytes: 7},
    {constructor: -1n, fields: []}, {constructor: 1n << 64n, fields: []}, {constructor: 0n}, {constructor: 0n, fields: {}},
    {list: 'x'}, {map: {}}, {map: [{k: {int: 1n}}]}, {map: [null]}, {int: 1n, extra: true}]);
  const sparse = items => { const copy = [...items]; if (copy.length > 1 && random() < 0.3) delete copy[1]; return copy; };
  const generate = (depth, bad) => {
    if (bad && random() < 0.05) return invalidNode();
    const kind = depth <= 0 ? pick(['int', 'bytes']) : pick(['int', 'bytes', 'list', 'map', 'constructor']);
    const many = () => Array.from({length: Math.floor(random() * 4)}, () => generate(depth - 1, bad));
    switch (kind) {
      case 'int': return {int: BigInt(Math.floor((random() - 0.5) * 2 ** 40)) * (random() < 0.2 ? 1n << 70n : 1n)};
      case 'bytes': return {bytes: Array.from({length: Math.floor(random() * 6)}, () => pick(['00', 'ab', 'FF', '7e'])).join('')};
      case 'list': return {list: sparse(many())};
      case 'constructor': return {constructor: pick([0n, 1n, 121n, (1n << 64n) - 1n]), fields: sparse(many())};
      case 'map': return {map: sparse(many().map(k => ({k, v: generate(depth - 1, bad)})))};
    }
  };
  const outcome = (f, value) => { try { return {text: f(value)}; } catch (error) { return {error: error.message}; } };
  let valid = 0, invalid = 0;
  for (let i = 0; i < 3000; i++) {
    const value = generate(1 + Math.floor(random() * 5), i % 2 === 1);
    const expected = outcome(recursive, value);
    assert.deepEqual(outcome(dataText, value), expected, 'seed case ' + i);
    if (expected.error) invalid++; else valid++;
  }
  assert.ok(valid > 1000 && invalid > 150, `both kinds compared: ${valid} valid, ${invalid} invalid`);
});

test('dataText encodes Data nested 100,000 deep, which the recursive version cannot', async () => {
  const {dataText: recursive} = await import('./data-text-recursive.mjs');
  const depth = 100000;
  let value = {int: 42n};
  for (let i = 0; i < depth; i++) {
    value = i % 3 === 0 ? {list: [value]} : i % 3 === 1 ? {constructor: 0n, fields: [{bytes: ''}, value]}
      : {map: [{k: {int: BigInt(i)}, v: value}]};
  }
  assert.throws(() => recursive(value), RangeError);
  const text = dataText(value);
  // The engine's JSON reader is not this one, so check the shape here: every level is present once, in order.
  assert.equal(text.split('{"list":[').length - 1, Math.ceil(depth / 3));
  assert.match(text, /\{"int":42\}/);
  assert.equal(encode(value, 'data').value, text);
});
