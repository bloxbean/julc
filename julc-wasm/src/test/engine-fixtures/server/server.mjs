// A server bundle's entry (webpack target: 'node', esbuild): both engine packages, checked once. Prints one JSON line.
import {createJulcVm} from '@julclang/julc-vm-wasm';
import * as vm from '@julclang/julc-vm-wasm/engine';
import {createJulc} from '@julclang/julc-wasm';
import * as full from '@julclang/julc-wasm/engine';
import {checkBoth} from '../shared/check.js';

try {
  console.log(JSON.stringify({node: process.version, ok: true, report: await checkBoth({createJulcVm, createJulc}, {engines: {vm, full}})}));
} catch (error) {
  console.log(JSON.stringify({node: process.version, ok: false, error: String(error?.stack ?? error)}));
}
