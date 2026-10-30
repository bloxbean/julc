// Loads the javac Web Image the way the browser worker does (patched launcher + JAVAC_WASM_PATH) and runs
// every example through globalThis.javacCheck. Optional: EXTRA_SOURCE=<file.java> adds one more source.
// Run: node --experimental-wasm-exnref check-node.mjs
import {readFileSync} from 'node:fs';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
import vm from 'node:vm';
vm.runInThisContext(readFileSync(new URL('./examples.js', import.meta.url), 'utf8'));
const examples = [...globalThis.JAVAC_EXAMPLES];
if (process.env.EXTRA_SOURCE) {
  examples.push({name: process.env.EXTRA_SOURCE, expect: [], source: readFileSync(process.env.EXTRA_SOURCE, 'utf8')});
}
const started = performance.now();
globalThis.JAVAC_WASM_PATH = fileURLToPath(new URL('./javac-check.js.wasm', import.meta.url));
globalThis.onJavacReady = () => {
  console.log(`engine ready after ${(performance.now() - started).toFixed(1)} ms`);
  let ok = true;
  for (const ex of examples) {
    const t0 = performance.now();
    const out = JSON.parse(globalThis.javacCheck(ex.source));
    const ms = (performance.now() - t0).toFixed(1);
    const codes = (out.diagnostics || []).filter(d => d.kind === 'ERROR').map(d => d.code);
    const pass = JSON.stringify(codes) === JSON.stringify(ex.expect) && !out.internalError;
    ok &&= pass;
    console.log(`${pass ? 'PASS' : 'FAIL'}  ${ex.name}: errors=${JSON.stringify(codes)} analyzeMs=${out.analyzeMs} callMs=${ms}`
      + (out.internalError ? ` internalError=${out.internalError}` : '')
      + (out.summary ? ` methods=${out.summary.methods.length} calls=${out.summary.calls.length} switches=${out.summary.switchTypes.length}` : ''));
    for (const d of out.diagnostics || []) {
      console.log(`      ${d.line}:${d.column}  ${d.kind}  ${d.code}  [${d.start},${d.end}]  ${d.message.split('\n')[0]}`);
    }
  }
  process.exitCode = ok ? 0 : 1;
};
createRequire(import.meta.url)(fileURLToPath(new URL('./javac-check.js', import.meta.url)));
