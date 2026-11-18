# @julclang/julc-vm-wasm

JuLC's Plutus virtual machine, compiled to WebAssembly, for browsers and Node.js. It decodes, hashes, evaluates and
debugs Plutus V1, V2 and V3 scripts (UPLC 1.0.0 and 1.1.0), and applies parameters to them: `vm.applyParams` returns the
applied script and its hash under the language the request states (V1, V2 or V3). It runs in a worker, through
[`@julclang/wasm-client`](https://www.npmjs.com/package/@julclang/wasm-client), which this package depends on at its own
exact version. For the Java compiler, the decompiler and source debugging as well, install
[`@julclang/julc-wasm`](https://www.npmjs.com/package/@julclang/julc-wasm) instead.

**Experimental.** The JavaScript API is version 0 and may change in any release before 1.0; the release notes list
every change. JuLC is a research project: do not treat a result as proof that a contract is safe to deploy.

## Versions

The npm version is the JuLC release version. Dist-tags:

| Install | Gets |
|---|---|
| `npm install @julclang/julc-vm-wasm` | `latest`: the newest final release (until JuLC's first final release, the newest `-preN`) |
| `npm install @julclang/julc-vm-wasm@preview` | the newest `-preN` prerelease after a final release |
| `npm install @julclang/julc-vm-wasm@beta` | the newest `-betaN` |
| `npm install @julclang/julc-vm-wasm@next` | the newest release candidate of a final release (`X.Y.Z-rcN`) |

## Use

```js
import {createJulcVm} from '@julclang/julc-vm-wasm';

const julc = await createJulcVm();
try {
  const {info} = await julc.vm.decode({script: {script: '46450101002499'}});
  console.log(info.scriptHash);
  const evaluation = await julc.vm.evaluate({script: {script: '46450101002499'}});
  console.log(evaluation.status, evaluation.cpu, evaluation.mem);
  // Apply the integer 42 to a parameterised script (the language is always stated).
  const applied = await julc.vm.applyParams({language: 'V3', script: {script: '46450101002499'}, params: [{int: 42n}]});
  console.log(applied.cborHex, applied.scriptHash);
} finally {
  julc.dispose();
}
```

Each client runs the engine in its own worker (`worker_threads` in Node.js); `dispose()` ends it. The package's version
is the JuLC release, and the client refuses to run an engine, a manifest or a runtime file of another release.
`createJulcVm(options)` takes `timeoutMs`, `loadTimeoutMs` and `workerFactory`; the engine's files are always this
package's. The types of every request and response are exported from the package root. The subpath `./engine` describes
the engine: its frozen `manifest`, `launcherUrl`, `moduleUrl` and `version`, and `engineFiles()`, which returns new
copies of them, for example for `@julclang/wasm-client`'s own `createJulc`.

## Size and requirements

- The WebAssembly module is @MODULE_SIZE@ (uncompressed). Serve it compressed: it compresses well.
- **First load:** a client was ready in about 0.2 s in Node.js 22 and 24 and in Chromium on an Apple-silicon
  laptop, with the module already on disk or in the HTTP cache. Network transfer comes on top.
- **Browsers:** WebAssembly GC and exception references (`exnref`). Tested in Chromium 156.0.8078.4; Firefox and
  Safari are not tested yet. BLS12-381 builtins are not available in WebAssembly.
- **TypeScript 5.0 or later** for the declarations, which re-export `@julclang/wasm-client`'s types with
  `export type *`.
- **Node.js 22.3.0 or later.** Node 22 enables WebAssembly exception references only with
  `--experimental-wasm-exnref`: the client checks for them before it starts its first worker and, when they are
  missing, sets that flag with `v8.setFlagsFromString`. The flag is process-wide. Node 24 needs nothing. The engine
  also loads inside your own `worker_threads` worker.
- **Content Security Policy:** `script-src 'self' 'wasm-unsafe-eval'`, on the page and on the responses of the worker
  scripts, the runtime and the launcher (a worker script has its own policy). No `'unsafe-eval'` is needed.
- **Origins:** in a browser the worker loads the engine's files only from its own origin, which must not be opaque.
  Other hosts, such as Electron with a custom scheme registered as standard, are untested; an Electron page loaded
  from `file:` is refused.
- **Chrome extensions** (tested with an unpacked Manifest V3 extension page): Manifest V3's default policy does not
  allow WebAssembly, so the manifest must:

  ```json
  "content_security_policy": {"extension_pages": "script-src 'self' 'wasm-unsafe-eval'; object-src 'self'"}
  ```

## Bundlers

The engine's files are named by `new URL('./engine/…', import.meta.url)` in `engine.js`, and the worker by
`new Worker(new URL('./julc-worker.js', import.meta.url))` in `@julclang/wasm-client`, so bundlers copy them.

- **Vite 8** needs no configuration. The **development servers of Vite 5 to 7** need both packages excluded from
  dependency pre-bundling; their production builds need nothing:

  ```js
  // vite.config.js
  export default {optimizeDeps: {exclude: ['@julclang/wasm-client', '@julclang/julc-vm-wasm']}};
  ```
- **webpack 5** works with no configuration, but its Terser minifies the engine's launcher. To ship the launcher
  unchanged, emit the engine files as assets and keep them out of minification:

  ```js
  // webpack.config.cjs
  const TerserPlugin = require('terser-webpack-plugin');
  module.exports = {
    module: {rules: [{test: /[\\/]@julclang[\\/][^\\/]+[\\/]engine[\\/]/, type: 'asset/resource',
      generator: {filename: 'engine/[name]-[contenthash:8][ext]'}}]},
    optimization: {minimizer: [new TerserPlugin({exclude: /^engine\//})]},
  };
  ```
- **Plain `<script type="module">`** with an import map naming both packages' `index.js` works without a bundler.
- **Server bundles:** webpack with `target: 'node'` (its Terser minifies the launcher here too, which still works), and
  Next.js with Turbopack (`next build`, `next dev` and `output: 'standalone'`), work as they are. **esbuild** does not
  copy files named by `new URL`: keep the packages external (`--packages=external`, or `external: ['@julclang/*']`).
  **Next.js with `--webpack`** rewrites the URLs: list both packages in `serverExternalPackages`.

Version @VERSION@. Source, issues and the design (ADR-077): https://github.com/bloxbean/julc
