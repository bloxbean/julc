// Runs the page's worker.js unchanged inside a Node worker thread with browser-like shims (self, location,
// importScripts, postMessage), in the style of julc-wasm's node-worker.mjs, and sends it two examples.
// Run: node --experimental-wasm-exnref check-worker-node.mjs
import {Worker, isMainThread, parentPort} from 'node:worker_threads';
import {readFileSync} from 'node:fs';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
import vm from 'node:vm';
if (isMainThread) {
  vm.runInThisContext(readFileSync(new URL('./examples.js', import.meta.url), 'utf8'));
  const w = new Worker(fileURLToPath(import.meta.url));
  const picks = [0, 3, 5];
  w.on('message', m => {
    if (m.type === 'ready') {
      console.log(`worker ready, loadMs=${m.loadMs.toFixed(1)}`);
      picks.forEach(i => w.postMessage({id: i, source: globalThis.JAVAC_EXAMPLES[i].source}));
    } else if (m.type === 'result') {
      const codes = (m.result.diagnostics || []).map(d => `${d.line}:${d.column} ${d.code}`);
      console.log(`result ${globalThis.JAVAC_EXAMPLES[m.id].name}: ${JSON.stringify(codes)} callMs=${m.callMs.toFixed(1)}`);
      if (m.id === picks[picks.length - 1]) w.terminate();
    } else console.log('message', m);
  });
} else {
  const here = new URL('./worker.js', import.meta.url);
  globalThis.self = globalThis;
  globalThis.location = {href: here.href};
  globalThis.postMessage = v => parentPort.postMessage(v);
  globalThis.addEventListener = () => {};
  globalThis.importScripts = (...urls) => {
    for (const u of urls) {
      globalThis.JAVAC_WASM_PATH = fileURLToPath(globalThis.JAVAC_WASM_PATH);
      createRequire(import.meta.url)(fileURLToPath(new URL(u, here)));
    }
  };
  vm.runInThisContext(readFileSync(here, 'utf8'), {filename: fileURLToPath(here)});
  parentPort.on('message', data => globalThis.onmessage({data}));
}
