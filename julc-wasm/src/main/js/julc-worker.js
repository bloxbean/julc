/* Classic worker: every engine call is synchronous, messages are processed in order. It runs JuLC's images and any
 * image another language builds on julc-wasm (ADR-064), in one of two loading modes (ADR-077 D2a):
 * - archive mode: the runtime, the REST adapter and the launcher are files next to the manifest, and the launcher is a
 *   plain file name (ADR-064 I4);
 * - package mode: the caller gives the runtime, launcher and module URLs, which must be on the worker's own origin, or
 *   file: URLs under the Node bridge. The manifest's launcher field is never read in this mode.
 * The runtime file is loaded before the launcher, and the engine uses it (ADR-077 D2). Under Node.js the bridge's shim
 * (node-worker.js) defines self.JULC_NODE before this script runs; a browser worker never has it. */
let engine, julcAdapter, starting = true, startupOutput = [];
const launcherName = /^[a-z][a-z0-9]*(?:-[a-z0-9]+)*-[0-9a-f]{12}\.js$/;
// What the engine prints while it starts: when main fails, its first lines name the cause. The Web Image launcher
// keeps the console functions it finds when it loads, so they are wrapped before it loads.
for (const level of ['error', 'warn', 'log']) {
  const original = console[level];
  console[level] = (...args) => {
    if (starting && startupOutput.length < 20) startupOutput.push(args.map(String).join(' '));
    return original.apply(console, args);
  };
}
/** Reports a start-up failure once; the client disposes the worker. */
function fail(message) {
  if (!starting) return;
  starting = false;
  self.postMessage({type: 'error', message});
}
function manifestProblem(manifest, packageMode) {
  if (!manifest || typeof manifest !== 'object') return 'missing';
  if (manifest.api !== 0) return 'unsupported API version ' + JSON.stringify(manifest.api);
  if (typeof manifest.variant !== 'string' || !/^[a-z][a-z0-9-]*$/.test(manifest.variant)) {
    return 'variant ' + JSON.stringify(manifest.variant) + ' does not match [a-z][a-z0-9-]*';
  }
  if (!packageMode && (typeof manifest.launcher !== 'string' || !launcherName.test(manifest.launcher))) {
    return 'launcher ' + JSON.stringify(manifest.launcher) + ' is not a file name of lowercase words joined by '
      + 'hyphens, a 12-hex-digit hash and .js';
  }
  return null;
}
/** Why a package-mode URL is refused, or null (ADR-077 D2a). */
function packageUrlProblem(href, extension) {
  let url;
  try { url = new URL(href); } catch { return 'is not an absolute URL'; }
  if (url.username || url.password) return 'has a user name or password';
  if (self.JULC_NODE) {
    if (url.protocol !== 'file:') return 'is not a file: URL';
  } else {
    if (['data:', 'blob:', 'javascript:', 'file:'].includes(url.protocol)) return 'has the refused scheme ' + url.protocol;
    const origin = self.location.origin;
    if (!origin || origin === 'null') return 'cannot be checked: this worker has an opaque origin';
    if (url.origin !== origin) return 'is not on the worker\'s origin ' + origin;
  }
  if (!url.pathname.endsWith(extension)) return 'does not end in ' + extension;
  return null;
}
function load(url, kind) {
  try {
    if (kind === 'launcher' && self.JULC_NODE) self.JULC_NODE.loadLauncher(url);
    else importScripts(url);
    return true;
  } catch (error) {
    fail('Cannot load the ' + kind + ' ' + url + ': ' + (error?.message || error));
    return false;
  }
}
function init(request) {
  const packageMode = request.mode === 'package';
  const {manifest, catalogue} = request;
  // Archive clients from before ADR-077 send no rest flag and expect the adapter.
  const rest = packageMode ? request.rest === true : request.rest !== false;
  const invalid = manifestProblem(manifest, packageMode);
  if (invalid) return fail('Invalid engine manifest: ' + invalid);
  let runtimeUrl, launcherUrl, moduleUrl, adapterUrl;
  if (packageMode) {
    if (rest) return fail('The REST adapter is not available in package mode');
    for (const [name, href, extension] of [['runtime', request.runtimeUrl, '.js'],
      ['launcher', request.launcherUrl, '.js'], ['module', request.moduleUrl, '.wasm']]) {
      const problem = packageUrlProblem(String(href), extension);
      if (problem) return fail('Refused ' + name + ' URL ' + JSON.stringify(String(href)) + ': it ' + problem);
    }
    ({runtimeUrl, launcherUrl, moduleUrl} = request);
  } else {
    const query = '?engine=' + encodeURIComponent(manifest.version);
    runtimeUrl = new URL('julc-runtime.js' + query, request.base).href;
    if (rest) adapterUrl = new URL('rest-adapter.js' + query, request.base).href;
    launcherUrl = new URL(manifest.launcher, request.base).href;
    moduleUrl = new URL(manifest.launcher + '.wasm', request.base).href;
  }
  self.JULC_WASM_PATH = moduleUrl;
  // The launcher hook reports main ending with an error before onJulcReady, for example an operation whose records
  // are not registered for reflection; without it the client would wait for its load timeout.
  self.onJulcExit = code => {
    if (engine) return;
    const output = startupOutput.join('\n').slice(0, 2000);
    fail('The engine stopped before it was ready (exit code ' + code + ')'
      + (output ? ': ' + output : '; its error is in the worker console'));
  };
  // JsApi reports why it cannot install the engine, for example a runtime file of another build.
  self.onJulcStartupError = message => fail(message);
  self.onJulcReady = api => {
    if (!starting) return;
    engine = api;
    if (rest) julcAdapter = new JulcRestAdapter(api, catalogue);
    starting = false;
    startupOutput = [];
    self.postMessage({type: 'ready', version: api.version(), features: api.features(), schemas: api.__schemas,
      runtime: {preloaded: self.JulcRuntime?.julcVersion ?? null, used: api.__runtime ?? null}});
  };
  // Recorded for JsApi: the engine must then use this file, which must be stamped with its own JuLC version.
  self.JULC_RUNTIME_PRELOAD = 'pending';
  if (!load(runtimeUrl, 'runtime')) return;
  self.JULC_RUNTIME_PRELOAD = 'loaded';
  if (adapterUrl && !load(adapterUrl, 'REST adapter')) return;
  load(launcherUrl, 'launcher');
}
self.onmessage = event => {
  const request = event.data;
  if (request.type === 'init') {
    if (engine || !starting) return;
    init(request);
    return;
  }
  try {
    let result;
    if (request.op === 'rest') {
      if (!julcAdapter) throw Object.assign(new Error('The REST adapter is not loaded'), {code: 'not-found', status: 404});
      result = julcAdapter.request(request.method, request.path, request.body);
    } else {
      const [group, name] = request.method.split('.');
      result = engine[group][name](request.body);
    }
    self.postMessage({id: request.id, result});
  } catch (error) {
    self.postMessage({id: request.id, error: {code: error.code || 'internal', status: error.status || 500,
      body: error.body || {error: error.message}, message: error.message}});
  }
};
self.addEventListener('unhandledrejection', event => {
  const message = String(event.reason?.message || event.reason);
  if (starting) fail(message);
  else self.postMessage({type: 'error', message});
});
