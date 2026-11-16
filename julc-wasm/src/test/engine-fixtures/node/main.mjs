// Node.js: both engine packages, installed from their tarballs, on this thread (or in the application worker
// in-worker.mjs starts), with no flags. Prints one JSON line. The parity fixtures' path is the first argument.
import {readFileSync} from 'node:fs';
import {isMainThread, parentPort, workerData} from 'node:worker_threads';
import {createJulcVm} from '@julclang/julc-vm-wasm';
import {createJulc} from '@julclang/julc-wasm';
import {checkBoth} from '../shared/check.js';

const parity = isMainThread ? process.argv[2] : workerData?.parity;
const fixtures = parity ? JSON.parse(readFileSync(parity, 'utf8')) : [];
let line;
try {
  const report = await checkBoth({createJulcVm, createJulc}, {fixtures});
  line = JSON.stringify({node: process.version, thread: isMainThread ? 'main' : 'worker', ok: true, report});
} catch (error) {
  line = JSON.stringify({node: process.version, ok: false, error: String(error?.stack ?? error)});
}
if (isMainThread) console.log(line); else parentPort.postMessage(line);
