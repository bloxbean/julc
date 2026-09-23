/* Private wire codec and synchronous API. Also loaded by SDK tests without a VM. */
(() => {
  'use strict';
  const LONG_MIN = -(1n << 63n), LONG_MAX = (1n << 63n) - 1n;
  const own = (o, k) => Object.prototype.hasOwnProperty.call(o, k);
  function invalid(message) {
    return Object.assign(new Error(message), { code: 'invalid-request', status: 400, body: { error: message } });
  }
  function dataText(value) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw invalid('Expected structured Plutus Data');
    const kinds = ['int', 'bytes', 'list', 'map', 'constructor'].filter(k => own(value, k));
    if (kinds.length !== 1) throw invalid('Data must have exactly one constructor');
    const kind = kinds[0];
    if (Object.keys(value).some(k => k !== kind && !(kind === 'constructor' && k === 'fields'))) {
      throw invalid('Unexpected Data field');
    }
    const array = (items) => {
      if (!Array.isArray(items)) throw invalid('Expected Data array');
      return '[' + items.map(dataText).join(',') + ']';
    };
    switch (kind) {
      case 'int':
        if (typeof value.int !== 'bigint') throw invalid('Data int must be BigInt');
        return '{"int":' + value.int.toString() + '}';
      case 'constructor':
        if (typeof value.constructor !== 'bigint' || value.constructor < 0n || value.constructor >= 1n << 64n) {
          throw invalid('Data constructor must be a BigInt in unsigned Word64 range');
        }
        return '{"constructor":' + value.constructor.toString() + ',"fields":' + array(value.fields) + '}';
      case 'bytes':
        if (typeof value.bytes !== 'string' || !/^(?:[0-9a-fA-F]{2})*$/.test(value.bytes)) throw invalid('Data bytes must be hex');
        return JSON.stringify({ bytes: value.bytes });
      case 'list': return '{"list":' + array(value.list) + '}';
      case 'map':
        if (!Array.isArray(value.map)) throw invalid('Data map must be an entry array');
        return '{"map":[' + value.map.map(e => {
          if (!e || !own(e, 'k') || !own(e, 'v') || Object.keys(e).length !== 2) throw invalid('Expected map entry {k, v}');
          return '{"k":' + dataText(e.k) + ',"v":' + dataText(e.v) + '}';
        }).join(',') + ']}';
    }
  }
  function encode(value, schema, legacy = false) {
    if (value == null) return value;
    if (schema === 'long' || schema === 'bigint') {
      if (legacy && typeof value === 'number' && Number.isInteger(value)) value = BigInt(value);
      if (typeof value !== 'bigint') throw invalid('Expected BigInt');
      if (schema === 'long' && (value < LONG_MIN || value > LONG_MAX)) throw invalid('Integer exceeds signed long range');
      return value.toString();
    }
    if (schema === 'data') {
      if (own(value, 'value') || own(value, 'format')) return { ...value };
      return { format: 'json', value: dataText(value) };
    }
    if (schema === 'int') {
      if (!Number.isInteger(value) || value < -2147483648 || value > 2147483647) throw invalid('Expected 32-bit integer Number');
      return value;
    }
    if (Array.isArray(schema)) {
      if (!Array.isArray(value)) throw invalid('Expected array');
      return value.map(v => encode(v, schema[0], legacy));
    }
    if (schema && typeof schema === 'object') {
      if (typeof value !== 'object' || Array.isArray(value)) throw invalid('Expected request object');
      return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, encode(v, schema.$map ?? schema[k], legacy)]));
    }
    return value;
  }
  function decode(envelope) {
    let body = envelope.body;
    for (const path of envelope.integers) {
      if (path.length === 0) { body = BigInt(body); continue; }
      let parent = body;
      for (let i = 0; i < path.length - 1; i++) parent = parent[path[i]];
      const key = path[path.length - 1];
      parent[key] = BigInt(parent[key]);
    }
    if (envelope.status !== 200) {
      const code = ({400: 'invalid-request', 404: 'not-found', 422: 'blueprint-error'})[envelope.status] || 'internal';
      throw Object.assign(new Error(body?.error || body?.diagnostics?.[0]?.message || code),
        { code, status: envelope.status, body });
    }
    return body;
  }
  function legacy(value) {
    if (typeof value === 'bigint') return Number(value);
    if (Array.isArray(value)) return value.map(legacy);
    if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, legacy(v)]));
    return value;
  }
  function install(invoke, schemas, variant, version) {
    const api = { version: () => version, features: () => ({
      api: 0, variant,
      groups: variant === 'full' ? ['vm', 'debug', 'compiler', 'uplc', 'sourceDebug'] : ['vm', 'debug'],
      defaultProtocol: 11, bls: false,
    }) };
    for (const [method, schema] of Object.entries(schemas)) {
      const [group, name] = method.split('.');
      api[group] ??= {};
      api[group][name] = (request = {}) => {
        if (typeof request.costModel === 'string') request = { ...request, costModel: { profile: request.costModel } };
        return decode(JSON.parse(invoke(method, JSON.stringify(encode(request, schema)))));
      };
    }
    if (variant === 'full') api.uplc.defaultTransaction = defaultTransaction;
    Object.defineProperty(api, '__schemas', { value: schemas });
    return api;
  }
  function defaultTransaction({ purpose = 'spend', scriptHash = '$self' } = {}) {
    if (scriptHash !== '$self' && !/^[0-9a-fA-F]{56}$/.test(scriptHash)) throw invalid('Expected a 28-byte script hash');
    const credential = scriptHash === '$self' ? scriptHash : 'script:' + scriptHash;
    const alice = 'a11ce'.padEnd(56, '0'), bob = 'b0b'.padEnd(56, '0');
    const unit = {format: 'uplc', value: 'Constr 0 []'};
    const change = (amount, assets = []) => ({address: {payment: 'key:' + alice}, value: {lovelace: amount, assets}, datum: {kind: 'none'}});
    const wallet = {txId: 'feed'.padEnd(64, '0'), index: 1n, address: {payment: 'key:' + alice}, value: {lovelace: '100000000'}, datum: {kind: 'none'}};
    const tx = {purpose: {type: purpose, index: 0, target: ['reward', 'vote'].includes(purpose) ? credential : scriptHash, voterType: 'drep'}, redeemer: unit,
      inputs: [wallet], referenceInputs: [], outputs: [change('99800000')], fee: '200000', mint: [],
      certificates: [], withdrawals: [], validRange: {from: null, to: null, fromInclusive: true, toInclusive: true},
      signatories: [], datums: [], txId: '7ac0'.padEnd(64, '0'), votes: [], proposals: []};
    switch (purpose) {
      case 'spend': tx.inputs.unshift({txId: 'c0ffee'.padEnd(64, '0'), index: 0n, address: {payment: credential}, value: {lovelace: '10000000'}, datum: {kind: 'inline', data: unit}}); tx.outputs = [change('109800000')]; break;
      case 'mint': tx.mint = [{policyId: scriptHash, tokenName: '746f6b656e', quantity: '1'}]; tx.outputs = [change('99800000', tx.mint)]; break;
      case 'reward': tx.withdrawals = [{credential, amount: '0'}]; break;
      case 'certify': tx.certificates = [{type: 'regStaking', credential, deposit: '2000000'}]; tx.outputs = [change('97800000')]; break;
      case 'vote': tx.votes = [{voterType: 'drep', voter: credential, actionTxId: '90e1'.padEnd(64, '0'), actionIndex: 0n, vote: 'yes'}]; break;
      case 'propose': tx.proposals = [{deposit: '100000000000', returnCredential: 'key:' + alice, actionType: 'treasuryWithdrawals', guardrail: scriptHash, withdrawals: [{credential: 'key:' + bob, amount: '1000000'}]}]; break;
      default: throw invalid('Unknown script purpose: ' + purpose);
    }
    return tx;
  }
  globalThis.JulcRuntime = { install, encode, decode, legacy, dataText, invalid, defaultTransaction };
})();
