// Runs JavacCheck on the JVM for every example (one JVM per example) and prints a compact summary.
import {readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import vm from 'node:vm';
const [java, cp] = process.argv.slice(2);
vm.runInThisContext(readFileSync(new URL('./examples.js', import.meta.url), 'utf8'));
let ok = true;
for (const ex of globalThis.JAVAC_EXAMPLES) {
  const out = JSON.parse(execFileSync(java, ['-cp', cp, 'spike.JavacCheck', ex.source], {encoding: 'utf8'}).trim());
  const codes = (out.diagnostics || []).filter(d => d.kind === 'ERROR').map(d => d.code);
  const pass = JSON.stringify(codes) === JSON.stringify(ex.expect) && !out.internalError;
  ok &&= pass;
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${ex.name}: errors=${JSON.stringify(codes)} analyzeMs=${out.analyzeMs}${out.internalError ? ' internalError=' + out.internalError : ''}`);
  for (const d of out.diagnostics || []) console.log(`      ${d.line}:${d.column} ${d.kind} ${d.code} ${d.message.split('\n')[0]}`);
}
process.exit(ok ? 0 : 1);
