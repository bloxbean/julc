// A stand-in for a browser's classic worker scope, for the worker's package-mode checks (ADR-077 D2a): self.location
// is the given URL, there is no Node mark, and importScripts reports each URL instead of loading it.
import {parentPort, workerData} from 'node:worker_threads';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

globalThis.self = globalThis;
globalThis.location = new URL(workerData.location);
globalThis.postMessage = value => parentPort.postMessage(value);
globalThis.addEventListener = () => {};
globalThis.importScripts = (...urls) => { for (const url of urls) parentPort.postMessage({type: 'loaded', url}); };
const file = new URL(workerData.workerUrl);
vm.runInThisContext(readFileSync(file, 'utf8'), {filename: file.pathname});
parentPort.on('message', data => globalThis.onmessage({data}));
