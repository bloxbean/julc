/* The Promise client shared by every entry: julc-wasm.js (browsers, and the archives' entry) and the npm package's
 * node.js. It starts one worker per client, in archive mode (baseUrl) or package mode (launcherUrl and moduleUrl), and
 * refuses any engine that is not of its own JuLC release (ADR-077 D1a, D2a). */
import {CLIENT_VERSION} from './client-version.js';
import {rest as REST_DEFAULT} from './client-defaults.js';

export {CLIENT_VERSION};
// Resolves a relative baseUrl as before. Never new URL(<expression>, import.meta.url): bundlers treat that form as an
// asset reference (ADR-077 D2).
const clientLocation = import.meta.url;
const closedError = () => Object.assign(new Error('Worker or session is closed'), {code: 'not-found', status: 404});

/** True for JuLC's full image, which adds the compiler, transaction and source-debug helpers. */
export function isFullClient(client) {
  const features = client.features();
  return features.variant === 'full' && features.groups.includes('compiler')
    && features.groups.includes('sourceDebug');
}
/**
 * Starts a client. The entry supplies its environment: packageWorker() and archiveWorker(url) make the default worker,
 * readJson(url) reads engine.json in archive mode, and prepare() runs once before the worker starts.
 */
export async function startClient(options = {}, environment) {
  const {baseUrl, launcherUrl, moduleUrl, expectedVersion, catalogue, workerFactory, timeoutMs = 30000,
    loadTimeoutMs = 120000} = options;
  let {manifest} = options;
  const packageMode = launcherUrl !== undefined || moduleUrl !== undefined;
  if (packageMode && baseUrl !== undefined) {
    throw new Error('Give either baseUrl (archive mode) or launcherUrl and moduleUrl (package mode), not both');
  }
  if (packageMode && (launcherUrl === undefined || moduleUrl === undefined || manifest === undefined)) {
    throw new Error('Package mode needs launcherUrl, moduleUrl and the engine\'s manifest');
  }
  if (packageMode && options.rest === true) throw new Error('The REST adapter is not available in package mode');
  const rest = packageMode ? false : options.rest ?? REST_DEFAULT;
  if (typeof rest !== 'boolean') throw new Error('rest must be true or false');
  let base, init;
  if (packageMode) {
    // The worker checks where these point (ADR-077 D2a); the runtime file comes with this client.
    init = {type: 'init', mode: 'package', manifest, catalogue, rest,
      runtimeUrl: new URL('./julc-runtime.js', import.meta.url).href,
      launcherUrl: String(launcherUrl instanceof URL ? launcherUrl.href : launcherUrl),
      moduleUrl: String(moduleUrl instanceof URL ? moduleUrl.href : moduleUrl)};
  } else {
    base = new URL(baseUrl ?? '.', clientLocation);
    manifest ??= await environment.readJson(new URL('engine.json', base));
    init = {type: 'init', base: base.href, manifest, catalogue, rest};
  }
  if (!manifest || manifest.api !== 0) throw new Error('Unsupported JuLC API version: ' + manifest?.api);
  environment.prepare?.();
  let worker, workerName;
  if (packageMode) {
    workerName = 'julc-worker.js';
    worker = workerFactory ? workerFactory() : environment.packageWorker();
  } else {
    const url = new URL('julc-worker.js?engine=' + encodeURIComponent(manifest.version), base);
    workerName = url.href;
    worker = (workerFactory ?? environment.archiveWorker)(url);
  }
  let nextId = 0, disposed = false;
  const pending = new Map(), sessions = new Set();
  let resolveReady, rejectReady;
  const ready = new Promise((resolve, reject) => { resolveReady = resolve; rejectReady = reject; });
  function dispose(reason = closedError()) {
    if (disposed) return;
    disposed = true;
    worker.terminate();
    clearTimeout(loadTimer);
    rejectReady(reason);
    for (const item of pending.values()) { clearTimeout(item.timer); item.reject(reason); }
    pending.clear();
    for (const session of sessions) session.open = false;
  }
  const loadTimer = setTimeout(() => dispose(new Error('JuLC engine load timed out')), loadTimeoutMs);
  // A worker script that fails to load gives an event without a message in browsers.
  worker.onerror = event => dispose(new Error('The JuLC worker ' + workerName + ' failed: ' + (event?.message
    || 'the script did not load, or it stopped before the engine started')));
  worker.onmessage = ({data}) => {
    if (data.type === 'ready') { clearTimeout(loadTimer); resolveReady(data); return; }
    if (data.type === 'error') { dispose(new Error(data.message)); return; }
    const item = pending.get(data.id);
    if (!item) return;
    clearTimeout(item.timer); pending.delete(data.id);
    if (data.error) item.reject(Object.assign(new Error(data.error.message), data.error));
    else item.resolve(data.result);
  };
  try { worker.postMessage(init); }
  catch (error) { dispose(error); }
  const info = await ready;
  const refuse = message => {
    const error = new Error(message);
    dispose(error);
    throw error;
  };
  if ((manifest.variant !== undefined && manifest.variant !== info.features.variant)
      || (manifest.api !== undefined && manifest.api !== info.features.api)) {
    refuse('JuLC engine variant or API version does not match its manifest');
  }
  if (Array.isArray(manifest.groups)) {
    const declared = [...manifest.groups].sort();
    const actual = [...info.features.groups].sort();
    if (JSON.stringify(declared) !== JSON.stringify(actual)) refuse('JuLC engine capabilities do not match its manifest');
  }
  // One release (ADR-077 D1a): the engine was built with this client's julc-wasm, ...
  const julcVersion = info.features.julcVersion;
  if (julcVersion !== CLIENT_VERSION) {
    refuse('The JuLC engine was built with '
      + (julcVersion === undefined ? 'a JuLC release without features().julcVersion' : 'JuLC ' + julcVersion)
      + ', but this client is JuLC ' + CLIENT_VERSION + '; use the client of the engine\'s own release'
      + (packageMode && julcVersion !== undefined ? ', or install the @julclang/wasm-client of JuLC ' + julcVersion : ''));
  }
  if (manifest.julcVersion !== julcVersion) {
    refuse('The JuLC engine is JuLC ' + julcVersion + ', but its manifest says JuLC ' + manifest.julcVersion);
  }
  // ... JuLC's own images are that release, ...
  if ((info.features.variant === 'full' || info.features.variant === 'vm') && info.version !== julcVersion) {
    refuse('The JuLC ' + info.features.variant + ' engine reports version ' + info.version + ', not JuLC '
      + julcVersion);
  }
  // ... an engine package's is the package's, ...
  if (expectedVersion !== undefined && info.version !== expectedVersion) {
    refuse('The JuLC engine is version ' + info.version + ', not the expected ' + expectedVersion);
  }
  // ... and the engine runs on the runtime file the worker loaded, the one of this client's release.
  if (info.runtime?.used !== 'preloaded') {
    refuse('The JuLC engine did not use the runtime file julc-runtime.js loaded before it (it used '
      + (info.runtime?.used ?? 'its own copy') + ')');
  }
  function send(payload) {
    if (disposed) return Promise.reject(closedError());
    return new Promise((resolve, reject) => {
      const id = ++nextId;
      const timer = setTimeout(() => dispose(Object.assign(new Error('Request timed out'),
        {code: 'timeout', status: 408, body: {error: 'Request timed out'}})), timeoutMs);
      pending.set(id, {resolve, reject, timer});
      try { worker.postMessage({id, ...payload}); }
      catch (error) { clearTimeout(timer); pending.delete(id); reject(error); }
    });
  }
  function proxy(descriptor, group = 'debug') {
    if (!descriptor.sessionId) return descriptor;
    const state = {open: true, step: descriptor.snapshot.step,
      generation: descriptor.snapshot.stopGeneration}; sessions.add(state);
    const result = {...descriptor, isOpen: () => state.open && !disposed};
    const act = async (action, step = state.step, breakpoints) => {
      if (!result.isOpen()) throw closedError();
      let value;
      try { value = await send({method: group + '.act', body: {sessionId: descriptor.sessionId, action, step, breakpoints}}); }
      catch (error) {
        if (error.status === 404) { state.open = false; sessions.delete(state); }
        throw error;
      }
      if (value.snapshot) state.step = value.snapshot.step;
      if (value.snapshot?.stopGeneration != null) state.generation = value.snapshot.stopGeneration;
      return value;
    };
    result.step = () => act('goto', state.step + 1n);
    result.goto = step => act('goto', step);
    result.continue = breakpoints => act('continue', state.step, breakpoints);
    result.over = breakpoints => act('over', state.step, breakpoints);
    result.out = breakpoints => act('out', state.step, breakpoints);
    result.snapshot = () => act('goto');
    if (group === 'sourceDebug') {
      result.locals = () => send({method: 'sourceDebug.locals', body: {
        sessionId: descriptor.sessionId, stopGeneration: state.generation,
      }});
      result.children = (handle, start = 0, count = 50) => send({method: 'sourceDebug.children', body: {
        sessionId: descriptor.sessionId, stopGeneration: state.generation, handle, start, count,
      }});
    }
    result.close = async () => {
      if (!result.isOpen()) throw closedError();
      try { return await send({method: group + '.close', body: {sessionId: descriptor.sessionId}}); }
      finally { state.open = false; sessions.delete(state); }
    };
    return result;
  }
  const api = {version: () => info.version, features: () => info.features, dispose};
  if (rest) api.rest = (method, path, body) => send({op: 'rest', method, path, body});
  const created = new Set();
  for (const method of Object.keys(info.schemas)) {
    const [group, name] = method.split('.');
    if (!created.has(group)) {
      if (group in api) { dispose(); throw new Error('Reserved engine group: ' + group); }
      api[group] = {};
      created.add(group);
    }
    api[group][name] = async (body = {}) => {
      const result = await send({method, body});
      if (method === 'vm.debug' || method === 'uplc.debugTransaction') return proxy(result);
      return method === 'sourceDebug.open' ? proxy(result, 'sourceDebug') : result;
    };
  }
  // The runtime adds this helper to every engine that advertises uplc, with or without uplc operations.
  if (info.features.groups.includes('uplc')) {
    (api.uplc ??= {}).defaultTransaction = body => send({method: 'uplc.defaultTransaction', body});
  }
  return api;
}
