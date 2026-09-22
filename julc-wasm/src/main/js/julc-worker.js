/* Classic worker: every engine call is synchronous, messages are processed in order. */
let julcAdapter;
self.onmessage = event => {
  const request = event.data;
  if (request.type === 'init') {
    const {manifest, base, catalogue} = request;
    if (manifest.api !== 0 || !['vm', 'full'].includes(manifest.variant)
        || !/^julc(?:-vm)?-[0-9a-f]{12}\.js$/.test(manifest.launcher)) {
      self.postMessage({type: 'error', message: 'Unsupported or invalid JuLC engine manifest'});
      return;
    }
    self.JULC_WASM_PATH = new URL(manifest.launcher + '.wasm', base).href;
    self.onJulcReady = api => {
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
      result = self.julc[group][name](request.body);
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
