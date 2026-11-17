/* @julclang/wasm-client in Node.js (ADR-077 D3): the parent half of the Node bridge. Each client runs its engine in
 * its own worker_threads worker, through the shim node-worker.js, which gives julc-worker.js a classic worker's
 * globals. Callers pass no flags. */
import {Worker} from 'node:worker_threads';
import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import v8 from 'node:v8';
import {startClient, isFullClient} from './julc-client.js';

export {isFullClient};
// (module (func (result exnref) ref.null exn)): valid only where WebAssembly exception references are enabled.
const EXNREF_PROBE = new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 96, 0, 1, 105, 3, 2, 1, 0, 10, 6, 1, 4, 0,
  208, 105, 11]);
let exnrefChecked = false;
/** Node 22 needs a flag for the engines' exception references. It is process-wide, so it is set only when needed. */
function enableExnref() {
  if (exnrefChecked) return;
  exnrefChecked = true;
  if (!WebAssembly.validate(EXNREF_PROBE)) v8.setFlagsFromString('--experimental-wasm-exnref');
}
/** Turns a worker_threads Worker running the shim into the worker the client drives. */
function bridge(workerUrl) {
  // Its own constant, not new Worker(new URL(…)): bundlers copy the shim as a file (ADR-077 D3).
  const shimUrl = new URL('./node-worker.js', import.meta.url);
  const worker = new Worker(shimUrl, {workerData: {workerUrl: workerUrl.href}});
  let terminated = false, reported = false;
  const client = {postMessage: value => worker.postMessage(value),
    terminate: () => { terminated = true; return worker.terminate(); }};
  worker.on('message', data => client.onmessage?.({data}));
  // An error, or a worker that ends by itself (for example through process.exit in the engine), fails the client at
  // once; an error is followed by an exit, which is not reported again.
  const report = event => {
    if (terminated || reported) return;
    reported = true;
    client.onerror?.(event);
  };
  worker.on('error', report);
  worker.on('exit', code => report({message: 'the worker exited with code ' + code}));
  return client;
}
const node = {
  prepare: enableExnref,
  packageWorker: () => bridge(new URL('./julc-worker.js', import.meta.url)),
  archiveWorker: bridge,
  readJson: async url => {
    if (url.protocol === 'file:') return JSON.parse(readFileSync(fileURLToPath(url), 'utf8'));
    const response = await fetch(url);
    if (!response.ok) throw new Error('Cannot read ' + url.href + ': HTTP ' + response.status);
    return response.json();
  },
};
/** Create an isolated Promise-based client of a JuLC engine, or of one built on julc-wasm. Each owns one worker. */
export function createJulc(options = {}) {
  return startClient(options, node);
}
