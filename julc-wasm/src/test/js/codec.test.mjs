import test from 'node:test';
import assert from 'node:assert/strict';
import '../../main/resources/julc-runtime.js';
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

test('capabilities advertise source debugging only in the full image', () => {
  const invoke = () => JSON.stringify({status: 200, body: {}, integers: []});
  const full = JulcRuntime.install(invoke, {'uplc.defaultTransaction': {}}, 'full', 'test');
  const vm = JulcRuntime.install(invoke, {}, 'vm', 'test');
  assert.deepEqual(full.features().groups, ['vm', 'debug', 'compiler', 'uplc', 'sourceDebug', 'sourceDebugLocals']);
  assert.deepEqual(vm.features().groups, ['vm', 'debug']);
});
