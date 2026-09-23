/** Create an isolated Promise-based JuLC client. Each client owns one worker. */
export function isFullClient(client) {
  const features = client.features();
  return features.variant === 'full' && features.groups.includes('compiler')
    && features.groups.includes('sourceDebug');
}
export async function createJulc({baseUrl = new URL('.', import.meta.url), manifest, catalogue,
  workerFactory = url => new Worker(url), timeoutMs = 30000, loadTimeoutMs = 120000} = {}) {
  const base = new URL(baseUrl, import.meta.url);
  manifest ??= await (await fetch(new URL('engine.json', base))).json();
  if (manifest.api !== 0) throw new Error('Unsupported JuLC API version: ' + manifest.api);
  const worker = workerFactory(new URL('julc-worker.js?engine=' + encodeURIComponent(manifest.version), base));
  let nextId = 0, disposed = false;
  const pending = new Map(), sessions = new Set();
  let resolveReady, rejectReady;
  const ready = new Promise((resolve, reject) => { resolveReady = resolve; rejectReady = reject; });
  const closedError = () => Object.assign(new Error('Worker or session is closed'), {code: 'not-found', status: 404});
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
  worker.onerror = event => dispose(new Error(event.message || 'JuLC worker failed'));
  worker.onmessage = ({data}) => {
    if (data.type === 'ready') { clearTimeout(loadTimer); resolveReady(data); return; }
    if (data.type === 'error') { dispose(new Error(data.message)); return; }
    const item = pending.get(data.id);
    if (!item) return;
    clearTimeout(item.timer); pending.delete(data.id);
    if (data.error) item.reject(Object.assign(new Error(data.error.message), data.error));
    else item.resolve(data.result);
  };
  try { worker.postMessage({type: 'init', base: base.href, manifest, catalogue}); }
  catch (error) { dispose(error); }
  const info = await ready;
  if (Array.isArray(manifest.groups)) {
    const declared = [...manifest.groups].sort();
    const actual = [...info.features.groups].sort();
    if (JSON.stringify(declared) !== JSON.stringify(actual)) {
      const error = new Error('JuLC engine capabilities do not match its manifest');
      dispose(error);
      throw error;
    }
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
    const state = {open: true, step: descriptor.snapshot.step}; sessions.add(state);
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
      return value;
    };
    result.step = () => act('goto', state.step + 1n);
    result.goto = step => act('goto', step);
    result.continue = breakpoints => act('continue', state.step, breakpoints);
    result.over = breakpoints => act('over', state.step, breakpoints);
    result.out = breakpoints => act('out', state.step, breakpoints);
    result.snapshot = () => act('goto');
    result.close = async () => {
      if (!result.isOpen()) throw closedError();
      try { return await send({method: group + '.close', body: {sessionId: descriptor.sessionId}}); }
      finally { state.open = false; sessions.delete(state); }
    };
    return result;
  }
  const api = {version: () => info.version, features: () => info.features, dispose,
    rest: (method, path, body) => send({op: 'rest', method, path, body})};
  for (const method of Object.keys(info.schemas)) {
    const [group, name] = method.split('.'); api[group] ??= {};
    api[group][name] = async (body = {}) => {
      const result = await send({method, body});
      if (method === 'vm.debug' || method === 'uplc.debugTransaction') return proxy(result);
      return method === 'sourceDebug.open' ? proxy(result, 'sourceDebug') : result;
    };
  }
  if (api.uplc) api.uplc.defaultTransaction = body => send({method: 'uplc.defaultTransaction', body});
  return api;
}
