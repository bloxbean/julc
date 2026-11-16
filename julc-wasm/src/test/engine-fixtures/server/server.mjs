// A server bundle's entry (webpack target: 'node', esbuild): both engine packages, checked once. Prints one JSON line.
import {createJulcVm} from '@julclang/julc-vm-wasm';
import {createJulc} from '@julclang/julc-wasm';
import {checkBoth} from '../shared/check.js';

try {
  console.log(JSON.stringify({node: process.version, ok: true, report: await checkBoth({createJulcVm, createJulc})}));
} catch (error) {
  console.log(JSON.stringify({node: process.version, ok: false, error: String(error?.stack ?? error)}));
}
