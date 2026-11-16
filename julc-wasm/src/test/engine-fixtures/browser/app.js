// The browser application of every browser fixture (Vite, webpack, plain <script type="module">, the Chrome
// extension): both engine packages, then window.__result. With ?parity=<url> it also runs the VM parity fixtures.
import {createJulcVm} from '@julclang/julc-vm-wasm';
import {version as vmVersion} from '@julclang/julc-vm-wasm/engine';
import {createJulc} from '@julclang/julc-wasm';
import {checkBoth} from '../shared/check.js';

(async () => {
  try {
    const parity = new URLSearchParams(location.search).get('parity');
    const fixtures = parity ? await (await fetch(parity)).json() : [];
    const report = await checkBoth({createJulcVm, createJulc}, {fixtures, version: vmVersion});
    window.__result = {ok: true, origin: location.origin, userAgent: navigator.userAgent, report};
  } catch (error) {
    window.__result = {ok: false, error: String(error?.stack ?? error)};
  }
})();
