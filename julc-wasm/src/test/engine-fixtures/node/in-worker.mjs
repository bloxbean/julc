// Node.js: the clients run inside the application's own worker_threads worker (ADR-077 I8).
import {Worker} from 'node:worker_threads';

const worker = new Worker(new URL('./main.mjs', import.meta.url), {workerData: {parity: process.argv[2]}});
worker.on('message', line => { console.log(line); worker.terminate(); });
worker.on('error', error => { console.log(JSON.stringify({node: process.version, ok: false, error: String(error?.stack ?? error)})); });
