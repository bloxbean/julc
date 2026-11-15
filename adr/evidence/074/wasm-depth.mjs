// Issue #327 (ADR-074): compile long, flat programs in the Wasm engine on a worker with a small stack.
// Usage, from the repository root after ./gradlew :julc-wasm:wasmBundle -PgraalvmHome=<GraalVM 25.3>:
//   node --experimental-wasm-exnref adr/evidence/074/wasm-depth.mjs julc-wasm/build/wasm-bundle-full <stack MB>
import {readFileSync} from 'node:fs';
import {pathToFileURL} from 'node:url';
import {Worker} from 'node:worker_threads';
import path from 'node:path';

const [bundle, stackMb] = process.argv.slice(2);
const baseUrl = pathToFileURL(path.resolve(bundle) + '/');
const {createJulc} = await import(new URL('julc-wasm.js', baseUrl));
await import(new URL('julc-runtime.js', baseUrl));
const manifest = JSON.parse(readFileSync(new URL('engine.json', baseUrl)));
const workerFactory = url => {
  const worker = new Worker(new URL('../../../julc-wasm/src/test/js/node-worker.mjs', import.meta.url),
      {workerData: url.href, resourceLimits: {stackSizeMb: Number(stackMb)}});
  const bridge = {postMessage: value => worker.postMessage(value), terminate: () => worker.terminate()};
  worker.on('message', data => bridge.onmessage?.({data}));
  worker.on('error', error => bridge.onerror?.(error));
  return bridge;
};

function helpers(n) {
  let java = `import java.math.BigInteger;
import org.julclang.core.PlutusData;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

@SpendingValidator
class Deep {
    @Entrypoint
    static boolean validate(BigInteger x, PlutusData ctx) { return b0(x); }
`;
  for (let i = 0; i < n; i++) {
    java += `static boolean b${i}(BigInteger x){if(x.equals(BigInteger.valueOf(${i})))return true;return b${i + 1}(x);}
`;
  }
  return java + `    static boolean b${n}(BigInteger x) { return x.signum() > 0; }\n}\n`;
}

function earlyReturns(n) {
  let java = `import java.math.BigInteger;
import org.julclang.core.PlutusData;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;

@SpendingValidator
class Deep {
    @Entrypoint
    static boolean validate(BigInteger x, PlutusData ctx) {
`;
  for (let i = 0; i < n; i++) java += `        if (x.equals(BigInteger.valueOf(${i}))) { return ${i % 2 === 0}; }\n`;
  return java + `        return false;\n    }\n}\n`;
}

for (const [name, make] of [['helpers', helpers], ['early returns', earlyReturns]]) {
  for (const n of (name === 'helpers' ? [300, 600, 1000] : [1000, 1500, 2000])) {
    const api = await createJulc({baseUrl, manifest, catalogue: {}, workerFactory});
    const started = performance.now();
    let outcome;
    try {
      const compiled = await api.compiler.compile({source: make(n), language: 'java'});
      outcome = compiled.compiledCode ? `ok ${compiled.scriptSizeBytes} B` : `diagnostics ${JSON.stringify(compiled.diagnostics).slice(0, 200)}`;
    } catch (error) {
      outcome = `error ${String(error?.message ?? error).slice(0, 200)}`;
    } finally {
      api.dispose?.();
    }
    console.log(`${name} x${n}: ${outcome} (${Math.round(performance.now() - started)} ms)`);
  }
}
process.exit(0);
