# ADR-077 M0: worker and bundler experiment

Run on 2026-11-16 with throw-away prototype packages built from `julc-wasm/src/main/js` at `d17d69e1` and the released
pre20 VM engine (`julc-vm-b69c37c29c6b`). Both packages were packed with `npm pack` and installed from their tarballs
into each fixture. The prototype sources are not committed. The raw results are in this directory.

**The functional check.** Every PASS means the same result:
- `vm.decode` and `vm.evaluate` were run on CBOR `46450101002499`;
- they returned `(program 1.1.0 (lam i_1 (con unit ())))` and script hash
  `186e32faa80a26810392fda6d559c7ed4721a65ce1c9d4ef3e1c87b4`;
- the evaluation gave `ok: true`, result `(lam i_1 (con unit ()))`, CPU 16100 and memory 200.

**Versions:**
- Node 22.12.0, and Node 24.22.3 (the official darwin-arm64 build, its SHA-256 checked);
- npm 10.9.0;
- Playwright 1.64.1, driving Chromium 156.0.8078.4;
- Vite 8.3.8, 7.3.8, 6.4.6 and 5.4.21;
- webpack 5.112.2, 5.111.1 and 5.105.4;
- esbuild 0.28.2;
- Next.js 16.4.6.

## Decisions

| Question | Result |
|---|---|
| Open question 2: worker form | **Classic worker.** A module worker also passed in Chromium, by loading the unchanged launcher with `import()`. But it runs a launcher generated as a script as an ES module, and it needs ignore comments in each bundler. `importScripts` inside a module worker fails (`Module scripts don't support importScripts()`). |
| D3: Node load path | **`vm.compileFunction` only,** with `createRequire(launcherPath)` injected as `require`. It passed with the engine directory as CommonJS and as an ES module, on Node 22 and 24, on the main thread and in an application worker. Loading with `createRequire` alone failed under a `"type":"module"` scope (`Failed to parse URL from …wasm`). |
| WebAssembly exception references | Node 22.12 needs the flag. The client detects this with a 27-byte `WebAssembly.validate` probe, and sets the flag lazily, also from within an application worker. Without it, loading fails with `Invalid opcode 0x1f`. Node 24.22.3 needs no flag. |
| Server bundlers supported | webpack 5 with `target: 'node'` (CJS and ESM output), and Next.js 16 with Turbopack (build, dev, and standalone output). |
| Server bundlers needing externals | esbuild: it does not copy files named by `new URL`, but passes with `--packages=external`. Next `--webpack`: it rewrites asset URLs to site-relative paths, but passes with `serverExternalPackages`. |
| Browser bundlers | Vite 8 needs no configuration. The dev servers of Vite 5 to 7 need `optimizeDeps.exclude` for both packages; their builds need nothing. webpack 5 passes with no configuration, but Terser minifies the launcher. An `asset/resource` rule and a Terser `exclude` keep it byte-identical. |
| CSP | **pre20 needs `script-src 'self' 'unsafe-eval'`,** because the engine evaluates its runtime from a string (`JsApi.install`). With that removed (a patched control), `script-src 'self' 'wasm-unsafe-eval'` was enough, in both worker forms. |

## Findings that change the ADR or M1

1. **The engine evaluates its runtime with `eval`.** `JsApi.install` runs `(0, eval)(String(source))`. ADR-077 r11 moves the runtime to a file the worker loads first.
2. **Start-up failures surfaced only as the 120 s load timeout.** This happened when main threw, as in the CSP case, and when the worker 404'd ("JuLC worker failed", with no detail). M1 reports them promptly.
3. **webpack 5.111.1 and 5.112.2 miscompile `new Worker(new URL(…))` from `node:worker_threads` in `target: 'node'`** (`Cannot read properties of undefined (reading 'wc')`). 5.105.4 compiles it correctly.
   - The working design names the shim with its own `const shimUrl = new URL('./node-worker.js', import.meta.url)`, and has no `import` statements.
   - The shim takes Node's builtins from `process.getBuiltinModule`, which needs Node 22.3 or later.
4. **The client must never write `new URL(<expression>, import.meta.url)`.** webpack builds a context module for it, and Turbopack fails the build.
5. **Next `--webpack` makes server asset URLs site-relative,** which Node's `Worker` rejects. The README tells users to mark the packages as `serverExternalPackages`.

## Not covered

- Firefox and Safari.
- The full engine, whose launcher has the same structure and the same `eval`.
- The `julcVersion` check (D1a), which pre20 does not report.
- The TypeScript declaration checks.
