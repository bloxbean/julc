// Runs the parity request fixtures against the WebAssembly playground engine and compares every response with
// the JVM dispatcher results (ParityFixtures). Invoked by `./gradlew :julc-playground-wasm:wasmSmoke`.
//
//   node --experimental-wasm-exnref wasm-smoke.mjs <julc-playground.js> <parity-requests.json> <expected.json>
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { isDeepStrictEqual } from 'node:util';
import path from 'node:path';

const [launcher, requestsFile, expectedFile] = process.argv.slice(2);
const requests = JSON.parse(readFileSync(requestsFile, 'utf8'));
const expected = new Map(JSON.parse(readFileSync(expectedFile, 'utf8')).map((r) => [r.name, r]));

const started = performance.now();
const engine = await new Promise((resolve) => {
  globalThis.onJulcPlaygroundReady = resolve;
  createRequire(import.meta.url)(path.resolve(launcher));
});
console.log(`engine ready in ${Math.round(performance.now() - started)} ms (Java init ${engine.initMs} ms)`);

// Expectations that intentionally differ from the JVM.
const wasmOverrides = {
  'bls-unsupported': (actual) => actual.status === 200 && actual.body.success === false
    && /BLS12-381 builtins are not supported in the WebAssembly playground/.test(actual.body.error),
};

let failures = 0;
for (const request of requests) {
  const body = request.body === undefined ? null : JSON.stringify(request.body);
  const t = performance.now();
  const actual = JSON.parse(engine.dispatch(request.method, request.path, body));
  const ms = Math.round(performance.now() - t);
  const want = expected.get(request.name);

  let ok;
  if (request.wasm) {
    ok = wasmOverrides[request.wasm](actual);
  } else {
    ok = actual.status === want.status && isDeepStrictEqual(actual.body, want.body);
  }
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${request.name.padEnd(36)} ${String(actual.status).padEnd(4)} ${ms} ms`);
  if (!ok) {
    failures++;
    console.log(`  expected: ${JSON.stringify(request.wasm ? request.wasm : want).slice(0, 600)}`);
    console.log(`  actual:   ${JSON.stringify(actual).slice(0, 600)}`);
  }
}

console.log(failures === 0 ? `all ${requests.length} fixtures match` : `${failures} of ${requests.length} fixtures differ`);
process.exit(failures === 0 ? 0 : 1);
