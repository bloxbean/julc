# @julclang/wasm-client

The Promise client, worker and Node.js bridge for JuLC's WebAssembly engines: `@julclang/julc-vm-wasm` (the VM: decode,
hash, evaluate and debug Plutus V1, V2 and V3 scripts) and `@julclang/julc-wasm` (the full toolchain: also the Java
compiler, decompiler, mock transactions and source debugging). Install an engine package; it depends on this client at
its own exact version.

**Experimental.** The JavaScript API is version 0 and may change in any release before 1.0; the release notes list
every change. JuLC is a research project: do not treat a result as proof that a contract is safe to deploy.

## Requirements

- Node.js 22.3.0 or later, or a browser with WebAssembly GC and exception references (tested in Chromium
  156.0.8078.4).
- **Node.js security releases:** `engines` (`>=22.3.0`) is the lowest version that works, not a recommendation. Run a
  supported Node.js release line, updated to its latest security release.
- TypeScript 5.0 or later for the declarations.
- **Node.js 22:** the engines use WebAssembly exception references, which Node 22 enables only with
  `--experimental-wasm-exnref`. The client checks for them with `WebAssembly.validate` before it starts its first
  worker and, when they are missing, sets the flag with `v8.setFlagsFromString`. The flag is process-wide. Node 24
  needs nothing.
- **Content Security Policy:** the page and the worker scripts need `script-src 'self' 'wasm-unsafe-eval'`. Send it on
  the worker's response too: a worker script has its own policy. No `'unsafe-eval'` is needed. A Chrome extension
  (Manifest V3) must add `'wasm-unsafe-eval'` to its `content_security_policy.extension_pages`.
- BLS12-381 builtins are not available in WebAssembly.

## Use

```js
import {createJulcVm} from '@julclang/julc-vm-wasm';

const julc = await createJulcVm();
try {
  const {info} = await julc.vm.decode({script: {script: '46450101002499'}});
  console.log(info.scriptHash);
} finally {
  julc.dispose();
}
```

Each client runs its engine in its own worker (`worker_threads` in Node.js). The client refuses an engine, a manifest
or a runtime file that is not of its own JuLC release (`features().julcVersion`).

Types: the root exports the client types and plain names for the records (`ScriptInput`, `TxOut`, `PlutusData`, …).
The generated `<Outer>_<Inner>` names of the release archives are under the types-only `@julclang/wasm-client/models`.

## Bundlers

The client names the worker as `new Worker(new URL('./julc-worker.js', import.meta.url))` and every engine file as
`new URL('<file>', import.meta.url)`, so bundlers copy them.

- **Vite 8** needs no configuration. The **development servers of Vite 5 to 7** need both packages excluded from
  dependency pre-bundling: `optimizeDeps: {exclude: ['@julclang/wasm-client', '@julclang/julc-vm-wasm']}` (or
  `@julclang/julc-wasm`). Their production builds need nothing.
- **webpack 5** needs no configuration, but its Terser minifies the engine's launcher. To keep it unchanged, emit the
  engine files as assets and exclude them from minification:

  ```js
  // webpack.config.cjs (as tested in ADR-077 M0)
  const TerserPlugin = require('terser-webpack-plugin');
  module.exports = {
    module: {rules: [{test: /[\\/]@julclang[\\/][^\\/]+[\\/]engine[\\/]/, type: 'asset/resource',
      generator: {filename: 'engine/[name]-[contenthash:8][ext]'}}]},
    optimization: {minimizer: [new TerserPlugin({exclude: /^engine\//})]},
  };
  ```
- **Server bundles:** webpack `target: 'node'` and Next.js with Turbopack work as they are. esbuild does not copy files
  named by `new URL`: keep the packages external (`--packages=external`). Next.js with `--webpack` rewrites the URLs:
  list both packages in `serverExternalPackages`.

## Without an engine package

`createJulc({launcherUrl, moduleUrl, manifest, expectedVersion})` loads an engine whose files the caller names (package
mode). The worker loads them only from its own origin (in Node.js, only `file:` URLs). `createJulc({baseUrl})` loads a
release archive's directory, or an engine another language built on `julc-wasm` (archive mode); `rest` is off by
default here.

Source, issues and the design (ADR-077): https://github.com/bloxbean/julc
