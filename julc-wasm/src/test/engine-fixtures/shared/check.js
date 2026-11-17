// The functional check every engine-package fixture runs (ADR-077 M3), in Node.js, in browsers and in server bundles.
// It uses the packages only through their entry points, as an application does:
// - vm.decode and vm.evaluate on CBOR 46450101002499, with the answers M0 recorded;
// - vm.applyParams on ADR-077's fixed vector (Aiken v1.1.23 and Python's blake2b-224 agree on it);
// - for the full engine, a compile, whose hash vm.decode must reproduce;
// - the entry function refuses options that would name other engine files, another version or the REST adapter;
// - given the packages' ./engine modules, the client is created while the public exports point at the other
//   engine's files: the entry function must still load its own engine (M3 review), and the manifest is frozen;
// - given forwardingTo (Node.js), a workerFactory that rewrites the init message to the other engine is refused;
// - when given, the VM parity fixtures (build/wasm-parity/vm.json, the JVM's answers): evaluate and a debug replay.
// No Node.js or DOM API is used, so the same file runs everywhere.

const SCRIPT = '46450101002499';
const APPLIED = {compiledCode: '4d4c0101003249930102182a0001', cborHex: '4c0101003249930102182a0001',
  scriptHash: '36f2b62169a767e3bcb49a0ff8dd3f473db91e15434ac69ace324ac6', language: 'V3', paramsApplied: 1,
  remainingParams: null, schemaChecked: false};
const VALIDATOR = `import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

@SpendingValidator
class AlwaysTrue {
    @Entrypoint
    static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
`;

const show = value => JSON.stringify(value, (_key, v) => typeof v === 'bigint' ? v + 'n' : v);
function same(actual, expected, label) {
  if (Object.is(actual, expected)) return;
  if (typeof actual === 'object' && typeof expected === 'object' && actual !== null && expected !== null
      && Array.isArray(actual) === Array.isArray(expected)) {
    const keys = Object.keys(expected);
    if (Object.keys(actual).length === keys.length) {
      for (const key of keys) {
        if (!Object.hasOwn(actual, key)) throw new Error(`${label}: missing ${key}`);
        same(actual[key], expected[key], label + '.' + key);
      }
      return;
    }
  }
  throw new Error(`${label}: got ${show(actual)}, expected ${show(expected)}`);
}

/** A parity fixture's envelope ({status, body, integers}) with its integers as BigInt. */
function decodeEnvelope(envelope) {
  if (envelope.status !== 200) throw new Error('A parity fixture expects status ' + envelope.status);
  let body = structuredClone(envelope.body);
  for (const p of envelope.integers) {
    if (p.length === 0) { body = BigInt(body); continue; }
    let parent = body;
    for (let i = 0; i < p.length - 1; i++) parent = parent[p[i]];
    parent[p[p.length - 1]] = BigInt(parent[p[p.length - 1]]);
  }
  return body;
}

/**
 * Points an engine package's exported URLs at another engine's files and tries to change its manifest, as a caller
 * could; returns the function that undoes it.
 */
function redirect(own, other) {
  const saved = [own.launcherUrl.href, own.moduleUrl.href];
  own.launcherUrl.href = other.launcherUrl.href;
  own.moduleUrl.href = other.moduleUrl.href;
  for (const change of [() => { own.manifest.variant = other.manifest.variant; },
    () => { own.manifest.launcher = other.manifest.launcher; }, () => { own.manifest.groups.push('compiler'); }]) {
    let refused = false;
    try { change(); } catch { refused = true; }
    if (!refused) throw new Error('the exported manifest can be changed');
  }
  return () => { [own.launcherUrl.href, own.moduleUrl.href] = saved; };
}

/**
 * Creates a client with create(), checks it, disposes it and returns a short report. Throws on any difference.
 * @param create (options?) => Promise<client>, the package's createJulcVm or createJulc
 * @param options {variant: 'vm' | 'full', version?: string, fixtures?: Array, engines?: {own, other}}
 */
export async function checkEngine(create, {variant, version, fixtures = [], engines, forwardingTo}) {
  if (forwardingTo) {
    const error = await create({workerFactory: forwardingTo(engines.other)}).then(client => {
      client.dispose();
      return null;
    }, caught => caught);
    if (!/^JuLC engine variant or API version does not match its manifest$/.test(error?.message ?? '')) {
      throw new Error('a workerFactory that loads the other engine: ' + (error?.message ?? 'accepted'));
    }
  }
  // The package names its own engine: options that would choose other files, or the REST adapter, are refused.
  for (const option of [{launcherUrl: 'x.js'}, {moduleUrl: 'x.wasm'}, {manifest: {}}, {expectedVersion: '0'},
    {baseUrl: '/'}, {rest: true}]) {
    const refused = await create(option).then(client => { client.dispose(); return false; }, () => true);
    if (!refused) throw new Error('accepted ' + show(option));
  }
  if (await create(null).then(client => { client.dispose(); return false; }, () => true)) {
    throw new Error('create(null) is refused');
  }
  const restore = engines ? redirect(engines.own, engines.other) : () => {};
  const started = performance.now();
  let client;
  try {
    client = await create();
  } finally {
    restore();
  }
  const ready = Math.round(performance.now() - started);
  try {
    same(client.features().variant, variant, 'variant');
    if (version !== undefined) {
      same(client.version(), version, 'version()');
      same(client.features().julcVersion, version, 'julcVersion');
    }
    same(typeof client.rest, 'undefined', 'rest');
    same(client.compiler === undefined, variant === 'vm', 'compiler group');
    const decoded = await client.vm.decode({script: {script: SCRIPT}});
    same(decoded.uplcText, '(program 1.1.0 (lam i_1 (con unit ())))', 'decode uplcText');
    same(decoded.info.scriptHash, '186e32faa80a26810392fda6d559c7ed4721a65ce1c9d4ef3e1c87b4', 'decode scriptHash');
    const evaluated = await client.vm.evaluate({script: {script: SCRIPT}});
    same([evaluated.ok, evaluated.status, evaluated.result, evaluated.cpu, evaluated.mem],
      [true, 'success', '(lam i_1 (con unit ()))', 16100n, 200n], 'evaluate');
    same(await client.vm.applyParams({language: 'V3', script: {script: SCRIPT}, params: [{int: 42n}]}), APPLIED,
      'applyParams');
    let compiled = null;
    if (variant === 'full') {
      const result = await client.compiler.compile({source: VALIDATOR});
      if (!/^[0-9a-f]+$/.test(result.compiledCode ?? '')) throw new Error('compile: ' + show(result.diagnostics));
      const again = await client.vm.decode({script: {script: result.compiledCode}});
      same(again.info.scriptHash, result.scriptHash, 'compile scriptHash');
      compiled = result.scriptHash;
    }
    for (const fixture of fixtures) {
      const request = decodeEnvelope(fixture.request);
      same(await client.vm.evaluate(request), decodeEnvelope(fixture.evaluate), fixture.name + ' evaluate');
      const session = await client.vm.debug(request);
      same(await session.continue(), decodeEnvelope(fixture.debug), fixture.name + ' debug replay');
      await session.close();
    }
    return {variant, version: client.version(), ready, total: Math.round(performance.now() - started),
      parity: fixtures.length, compiled, redirected: Boolean(engines), forwardingRefused: Boolean(forwardingTo)};
  } finally {
    client.dispose();
  }
}

/**
 * Both packages, one after the other, as an application imports them. With engines ({vm, full}: the packages'
 * ./engine modules), each is created while its exports point at the other's files.
 */
export async function checkBoth({createJulcVm, createJulc}, {engines, ...options} = {}) {
  return [
    await checkEngine(createJulcVm, {...options, variant: 'vm', engines: engines && {own: engines.vm, other: engines.full}}),
    await checkEngine(createJulc, {...options, variant: 'full', engines: engines && {own: engines.full, other: engines.vm}}),
  ];
}
