// Exercise the shipped classic-worker protocol in a real isolated Node worker.
import {parentPort, workerData} from 'node:worker_threads';
import {createRequire} from 'node:module';
import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import vm from 'node:vm';
globalThis.self = globalThis;
globalThis.postMessage = value => parentPort.postMessage(value);
globalThis.addEventListener = (name, callback) => {
  if (name === 'unhandledrejection') process.on('unhandledRejection', reason => callback({reason}));
};
globalThis.importScripts = (...urls) => {
  for (const url of urls) {
    const filename = fileURLToPath(url);
    if (/julc(?:-vm)?-[0-9a-f]{12}\.js$/.test(filename)) {
      globalThis.JULC_WASM_PATH = fileURLToPath(globalThis.JULC_WASM_PATH);
      createRequire(import.meta.url)(filename);
    } else vm.runInThisContext(readFileSync(filename, 'utf8'), {filename});
  }
};
vm.runInThisContext(readFileSync(fileURLToPath(workerData), 'utf8'), {filename: workerData});
parentPort.on('message', data => globalThis.onmessage({data}));
