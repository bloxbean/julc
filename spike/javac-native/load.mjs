// Measures WebAssembly.compile time (ms) of each .wasm argument, 5 runs each, in one Node process.
import {readFileSync} from 'node:fs';
for (const f of process.argv.slice(2)) {
  const bytes = readFileSync(f);
  const times = [];
  for (let i = 0; i < 5; i++) {
    const t0 = performance.now();
    await WebAssembly.compile(bytes);
    times.push((performance.now() - t0).toFixed(1));
  }
  console.log(`${f}\tcompile ms: ${times.join(' ')}`);
}
