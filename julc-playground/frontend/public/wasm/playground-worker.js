// Web Worker hosting the JuLC playground engine compiled to WebAssembly (GraalVM Web Image).
//
// `./gradlew :julc-playground-wasm:wasmBundle` places the engine next to this script under content-addressed names:
//   julc-playground-<version>.js       Web Image launcher (classic script)
//   julc-playground-<version>.js.wasm  the engine
// The page starts the worker as `playground-worker.js?engine=<version>`.
//
// Protocol: the page posts {id, method, path, body}; the worker replies {id, response} with the JSON envelope
// {"status": <http status>, "body": <json>} or {id, error} when the engine throws.
/* global importScripts */

const version = new URL(self.location.href).searchParams.get('engine') || '';
const launcher = `julc-playground-${version}.js`;

self.JULC_PLAYGROUND_WASM_PATH = new URL(`${launcher}.wasm`, self.location.href).href;

const started = performance.now();
let engine = null;

self.onJulcPlaygroundReady = (api) => {
  engine = api;
  self.postMessage({ type: 'ready', ms: Math.round(performance.now() - started), initMs: api.initMs });
};

self.addEventListener('unhandledrejection', (event) => {
  if (!engine) {
    self.postMessage({ type: 'error', message: String(event.reason && event.reason.message || event.reason) });
  }
});

self.onmessage = (event) => {
  const { id, method, path, body } = event.data;
  try {
    self.postMessage({ id, response: engine.dispatch(method, path, body) });
  } catch (e) {
    self.postMessage({ id, error: String(e && e.message || e) });
  }
};

if (!/^[0-9a-f]{12}$/.test(version)) {
  self.postMessage({ type: 'error', message: `invalid engine version '${version}'` });
} else {
  try {
    importScripts(launcher);
  } catch (e) {
    self.postMessage({ type: 'error', message: `cannot load ${launcher} (${e && e.message || e})` });
  }
}
