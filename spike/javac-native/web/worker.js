/* Classic worker that hosts the javac Web Image, mirroring julc-wasm's julc-worker.js:
   set the .wasm URL for the (patched) launcher, importScripts it, wait for the engine's ready callback. */
const started = performance.now();
self.JAVAC_WASM_PATH = new URL('javac-check.js.wasm', self.location.href).href;
self.onJavacReady = () => self.postMessage({type: 'ready', loadMs: performance.now() - started});
self.onmessage = event => {
  const {id, source} = event.data;
  const t0 = performance.now();
  try {
    const result = JSON.parse(self.javacCheck(source));
    self.postMessage({type: 'result', id, result, callMs: performance.now() - t0});
  } catch (error) {
    self.postMessage({type: 'result', id, result: {internalError: String(error && error.message || error)},
      callMs: performance.now() - t0});
  }
};
self.addEventListener('unhandledrejection', event => {
  self.postMessage({type: 'error', message: String(event.reason && event.reason.message || event.reason)});
});
try {
  importScripts('javac-check.js');
} catch (error) {
  self.postMessage({type: 'error', message: String(error.message || error)});
}
