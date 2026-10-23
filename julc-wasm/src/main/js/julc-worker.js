/* Classic worker: every engine call is synchronous, messages are processed in order. It runs JuLC's images and any
 * image another language builds on julc-wasm (ADR-064): the launcher is a plain file name next to the manifest. */
let engine, julcAdapter;
self.onmessage = event => {
  const request = event.data;
  if (request.type === 'init') {
    const {manifest, base, catalogue} = request;
    const launcherName = /^[a-z][a-z0-9]*(?:-[a-z0-9]+)*-[0-9a-f]{12}\.js$/;
    const invalid = manifest.api !== 0 ? 'unsupported API version ' + JSON.stringify(manifest.api)
      : typeof manifest.variant !== 'string' || !/^[a-z][a-z0-9-]*$/.test(manifest.variant)
        ? 'variant ' + JSON.stringify(manifest.variant) + ' does not match [a-z][a-z0-9-]*'
      : typeof manifest.launcher !== 'string' || !launcherName.test(manifest.launcher)
        ? 'launcher ' + JSON.stringify(manifest.launcher) + ' is not a file name of lowercase words joined by '
          + 'hyphens, a 12-hex-digit hash and .js'
      : null;
    if (invalid) {
      self.postMessage({type: 'error', message: 'Invalid engine manifest: ' + invalid});
      return;
    }
    self.JULC_WASM_PATH = new URL(manifest.launcher + '.wasm', base).href;
    // The launcher hook reports main ending with an error before onJulcReady, for example an operation whose
    // records are not registered for reflection; without it the client would wait for its load timeout.
    self.onJulcExit = code => {
      if (!engine) self.postMessage({type: 'error', message: 'The engine stopped before it was ready (exit code '
        + code + '); its error is in the worker console'});
    };
    self.onJulcReady = api => {
      engine = api;
      julcAdapter = new JulcRestAdapter(api, catalogue);
      self.postMessage({type: 'ready', version: api.version(), features: api.features(), schemas: api.__schemas});
    };
    try {
      importScripts(new URL('rest-adapter.js?engine=' + encodeURIComponent(manifest.version), base).href);
      importScripts(new URL(manifest.launcher, base).href);
    } catch (error) {
      self.postMessage({type: 'error', message: error.message});
    }
    return;
  }
  try {
    let result;
    if (request.op === 'rest') result = julcAdapter.request(request.method, request.path, request.body);
    else {
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
  self.postMessage({type: 'error', message: String(event.reason?.message || event.reason)});
});
