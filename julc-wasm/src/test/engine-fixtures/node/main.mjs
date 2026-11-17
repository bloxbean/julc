// Node.js: both engine packages, installed from their tarballs, on this thread (or in the application worker
// in-worker.mjs starts), with no flags. Prints one JSON line. The parity fixtures' path is the first argument.
import {readFileSync} from 'node:fs';
import {Worker, isMainThread, parentPort, workerData} from 'node:worker_threads';
import {createJulcVm} from '@julclang/julc-vm-wasm';
import * as vm from '@julclang/julc-vm-wasm/engine';
import {createJulc} from '@julclang/julc-wasm';
import * as full from '@julclang/julc-wasm/engine';
import {checkBoth} from '../shared/check.js';

/**
 * A workerFactory that forwards to the client's own worker through its shim, but rewrites the init message to load
 * the other engine: its manifest, launcher and module (M3 review). The wrapper must still refuse it.
 */
const client = import.meta.resolve('@julclang/wasm-client');
const forwardingTo = other => () => {
  const worker = new Worker(new URL('./node-worker.js', client),
    {workerData: {workerUrl: new URL('./julc-worker.js', client).href}});
  const adapter = {
    postMessage: message => {
      if (message.type === 'init') {
        Object.assign(message.manifest, other.engineFiles().manifest);
        message.launcherUrl = other.launcherUrl.href;
        message.moduleUrl = other.moduleUrl.href;
      }
      worker.postMessage(message);
    },
    terminate: () => worker.terminate(),
  };
  worker.on('message', data => adapter.onmessage?.({data}));
  worker.on('error', error => adapter.onerror?.(error));
  return adapter;
};

const parity = isMainThread ? process.argv[2] : workerData?.parity;
const fixtures = parity ? JSON.parse(readFileSync(parity, 'utf8')) : [];
let line;
try {
  const report = await checkBoth({createJulcVm, createJulc}, {fixtures, engines: {vm, full}, forwardingTo});
  line = JSON.stringify({node: process.version, thread: isMainThread ? 'main' : 'worker', ok: true, report});
} catch (error) {
  line = JSON.stringify({node: process.version, ok: false, error: String(error?.stack ?? error)});
}
if (isMainThread) console.log(line); else parentPort.postMessage(line);
