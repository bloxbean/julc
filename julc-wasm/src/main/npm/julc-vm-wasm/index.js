// @julclang/julc-vm-wasm (ADR-077 D2): JuLC's VM engine, loaded by @julclang/wasm-client in package mode from the
// files engine.js names. In Node.js, @julclang/wasm-client resolves to its Node.js bridge.
import {createJulc} from '@julclang/wasm-client';
import {engineFiles} from './engine.js';

// This package names its engine: the caller never chooses the files, the manifest or the version they must match.
const OWN = ['baseUrl', 'launcherUrl', 'moduleUrl', 'manifest', 'expectedVersion'];

/** Create a client of JuLC's VM engine, running in its own worker; dispose() ends it. */
export async function createJulcVm(options) {
  options ??= {};
  for (const name of OWN) {
    if (options[name] !== undefined) throw new Error(`createJulcVm() loads this package's engine: remove ${name}`);
  }
  if (options.rest !== undefined && options.rest !== false) {
    throw new Error('The REST adapter is not available in package mode: remove rest');
  }
  // Fresh objects for each client: nothing a caller does to engine.js's exports reaches them.
  const {manifest, launcherUrl, moduleUrl, version} = engineFiles();
  return createJulc({...options, launcherUrl, moduleUrl, manifest, expectedVersion: version, rest: false});
}
