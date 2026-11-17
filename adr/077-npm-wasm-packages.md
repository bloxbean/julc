# ADR-077: npm packages for the WebAssembly engines, and `vm.applyParams`

**Status:** Accepted (2026-11-16) by the maintainer, after design approval from reviewer1 (r4) and the internal
Fable and Codex reviews (r9; r10 applies their last shared finding). Design only; the implementation follows in
milestones M0 to M5.
**Date:** 2026-11-16 to 2026-11-17 (r1 to r18)
**Risk class:** **medium**.
- This is a public distribution with a lasting API. Package names and exported type names are hard to change once
  published.
- It adds a supply-chain surface: three packages published from CI.
- One new engine operation, `vm.applyParams`, computes the bytes and hash of a parameterised script that applications
  will deploy and pay to. A wrong result there costs users funds, so its invariants (I4, I5) and tests are strict.
- Nothing here changes the compiler, the VM, script bytes or budgets.

**Builds on:** [ADR-057](057-julc-wasm-distribution.md) (the `julc-wasm` distribution, JavaScript API version 0),
[ADR-064](064-embeddable-wasm-engine.md) (engines other languages build on `julc-wasm`).
**Changes:**
- ADR-057's non-goal "Publishing to npm. Deferred until the API has had at least one release of use". This ADR also
  answers ADR-057's open question 1 (npm package names and cadence).
- ADR-064's **I4** (the worker loads only a plain launcher file name next to the manifest).
  - It is kept for archive loading, which also loads a second fixed file name, `julc-runtime.js`, from beside the
    manifest.
  - A second, separately checked loading mode is added for packages (D2a, I10).
- ADR-064's `EngineClient` type. It gains a package-mode variant without `rest` (D4).

**Relates to:** [#244](https://github.com/bloxbean/julc/issues/244). Yaci Viewer's Script Lab is the engine's first known
integrator. #244 asks for neither npm nor `applyParams`, but for later features, a stable API and a browser support
matrix. `vm.applyParams` comes from the maintainer's request: a dApp's most common need is to parameterise a validator
and get its hash.

## Context

Since 0.1.0-pre17 every release attaches the two WebAssembly engines as GitHub release assets. These are the sizes in
the pre20 release:

| Asset | Engine (`.wasm`) | Archive | Groups (`engine.json`) |
|---|--:|--:|---|
| `julc-wasm-<version>.tar.gz` | 24,527,451 B | 8,465,421 B | `vm, debug, compiler, uplc, sourceDebug, sourceDebugLocals` |
| `julc-vm-wasm-<version>.tar.gz` | 14,282,613 B | 5,281,353 B | `vm, debug` |

Both archives hold the same JavaScript SDK, built by `wasmBundle{Full,Vm}` in `julc-wasm/build.gradle`:
- `julc-wasm.js`: `createJulc`, `isFullClient`;
- `julc-worker.js` and `rest-adapter.js`;
- `julc-runtime.js`, a copy of the runtime the tests use. The image installs its own copy from its resources, by
  evaluating it as a string (`JsApi.install`). r11 and r12 change this (D2);
- the declarations `julc-wasm.d.ts` and `julc-models.d.ts`.

Beside the SDK are the engine's content-addressed launcher and module, and `engine.json`.

To use an engine today, an application downloads an archive, checks it against `julc-wasm-<version>-SHA256SUMS.txt`,
and copies the files into its static assets. ADR-057's precondition for npm is now met: the API has been released four
times (pre17 to pre20), and Yaci Viewer's Script Lab has built on it (#244). Two other facts make npm practical
now:
- The `@julclang` scope exists. JOUST publishes `@julclang/joust-runtime` and `@julclang/state-client` from its release
  workflow, both at `0.1.0-pre1-rc9`. It uses npm trusted publishing and no token (`joust/.github/workflows/release.yml`,
  the `npm` job).
- `@julclang/joust-runtime` ships a Web Image built on `julc-wasm` (ADR-064) and loads it in Node.js, from an
  `engine/` directory whose `package.json` is `{ "type": "commonjs" }`.

`npm view` on 2026-11-16 found `@julclang/wasm-client`, `@julclang/julc-vm-wasm` and `@julclang/julc-wasm` unused.

### Current behavior that blocks a package (main at `8724e2ce`)

1. **Node.js.**
   - `createJulc` starts a browser `Worker` by default, and `julc-worker.js` is a classic worker that loads its files
     with `importScripts`. Node's `worker_threads` has neither.
   - Node works only through two test-only pieces, neither shipped:
     - the parent-side adapter in `julc-wasm/src/test/js/wasm-smoke.mjs` (lines 13–18);
     - the worker-side shim `julc-wasm/src/test/js/node-worker.mjs`.
   - The launcher detects Node's file system through a lexical CommonJS `require`. The shim loads it with
     `createRequire`, which works only while the launcher's directory is CommonJS.
   - On Node 22 the image also needs `--experimental-wasm-exnref`. `wasmSmoke` passes that flag, and JOUST's `load.js`
     sets it with `v8.setFlagsFromString`.
2. **Bundlers.**
   - `createJulc` derives every URL at run time: `baseUrl` defaults to the client's own directory, it then fetches
     `engine.json`, and builds `julc-worker.js?engine=<hash>`. The launcher name is only known once `engine.json` has
     been read.
   - Vite and webpack copy an asset only when the code names it statically (`new URL('./file', import.meta.url)`). They
     recognise a worker entry only in the form `new Worker(new URL('./file', import.meta.url))`. Today's code has
     neither form, so a bundled application would ship none of the engine's files.
3. **The REST adapter.** `julc-worker.js` always loads `rest-adapter.js`, and every client exposes `rest`, which is
   part of ADR-064's `EngineClient`. Only the playground uses it, for its old HTTP routes.
4. **Type names.**
   - `julc-models.d.ts` is generated from the Java records (`TypeScriptDeclarations`), so a nested record keeps its
     outer class in its name: `UplcModels_DecodeRequest`, `VmModels_Request`, `MockTransaction_TxOut`.
   - `Options.workerFactory` is typed with the DOM's `Worker`, which a Node TypeScript project without the DOM library
     does not have.
   - Once published, these names are API.
5. **Release identity is not checked at run time.**
   - The client compares the engine's `api`, `variant` and `groups` with its manifest (`julc-wasm.js`, lines 43–57). It
     does not compare versions.
   - JuLC's images report the JuLC version as `version()` (`JsApi.julcVersion()`), but nothing checks it against the
     client or the manifest's `julcVersion`.
   - The client has no version of its own to compare with.
6. **Parameter application has no name, and accepts guesses.**
   - Every VM operation accepts `script.params`, and `ScriptDecoder.decode` (`julc-tools`, lines 171–193) applies them
     with `Program.applyParams`. It hashes the result, and returns it from `vm.decode` as `info.compiledCode`, wrapped
     twice in CBOR.
   - Several of its choices are guesses, reported as warnings at most:
     - A script whose language cannot be told is taken as V2 (line 163), with only a warning. The language tag is part
       of the hash.
     - A blueprint with several validators and no selection gives the first one (`selectValidator`, line 252).
     - The check of a blueprint's hash against the script is skipped once parameters are applied (line 187).
     - A value given without a format is guessed (`DataInputs`, lines 38–43): `313233` becomes the integer 313233, not
       the bytes `#313233`. A hex value is read as CBOR, so a 28-byte hash that starts `58 1a` becomes a 26-byte string.
       The Claude review reproduced both through `vm.decode` on the pre20 engine.
     - With parameters, the program is decoded and encoded again before hashing (line 183), so a non-canonical
       encoding, or bytes after the CBOR item, are silently normalised or dropped. With no parameters, the original
       bytes are hashed.
   - `vm.hash` already hashes with parameters applied, through the same path.
   - Values are not checked against a blueprint's `parameters` schemas, so a wrong value silently gives a different
     hash, and a different address.
   - `BlueprintValidator` checks a blueprint's structure against the CIP-57 meta-schema, but not data against a schema.
     It also lives in `julc-blueprint`, with `json-schema-validator`. The VM image includes neither (the jar filter in
     `wasmImageVm`).
7. **Wrapping differs between toolchains.** A JuLC blueprint and a cardano-cli text envelope hold the script CBOR-wrapped
   twice. An Aiken blueprint holds it wrapped once (`ScriptDecoder`'s class comment). For example, applying the integer
   42 to `(program 1.1.0 (lam p (con unit ())))` gives:
   - Aiken v1.1.23 `blueprint apply`: `4c0101003249930102182a0001`;
   - JuLC `vm.decode` `info.compiledCode`: `4d4c0101003249930102182a0001`;
   - the same hash, `36f2b62169a767e3bcb49a0ff8dd3f473db91e15434ac69ace324ac6`, which is blake2b-224 of `03` and the
     single-wrapped bytes (reviewer1, round 1; recomputed with Python).

### CIP-57's data schemas (pinned)

JuLC pins CIP-57 at revision `0ed8837a02ed78b64847e5646f9572ee1830c7ba`
(`julc-blueprint/src/main/resources/cip57/`).
- The meta-schema `plutus-data.json` defines the data types `integer`, `bytes`, `list`, `map` and `constructor`, and the
  applicators `allOf`, `anyOf`, `oneOf` and `not`.
- The CIP's text at that revision (`CIP-0057/README.md`, "Additional keywords") defines these validation keywords:

| Applies to | Keywords |
|---|---|
| any type | `dataType` (optional; when missing, the value is opaque Plutus data), `title`, `description`, `$comment`, `allOf`, `anyOf`, `oneOf`, `not` |
| `bytes` | `enum` (hex strings), `maxLength`, `minLength` |
| `integer` | `multipleOf`, `maximum`, `exclusiveMaximum`, `minimum`, `exclusiveMinimum` |
| `list` | `items` (one schema for every element, or an array of schemas for exactly that many elements, by position), `maxItems`, `minItems`, `uniqueItems` |
| `map` | `keys`, `values`, `maxItems`, `minItems` |
| `constructor` | `index` (mandatory), `fields` (mandatory, by position) |

- A keyword used with another data type "should result in an error". A keyword with a malformed value must give "an
  appropriate error".
- It also lists builtin types (`#unit`, `#boolean`, `#integer`, `#bytes`, `#string`, `#pair`, `#list`), which are not
  Plutus data, and discourages them at a validator's interface.

## Goals

- `npm install` gives a JavaScript or TypeScript application either engine, with types. It works in browsers, through
  Vite and webpack, and in Node.js 22 and later, without copying files by hand or passing flags.
- A package version always contains one engine with the client built for it, and the client refuses any other pairing
  at run time.
- A clear operation for the most common dApp task: apply a validator's parameters, then get its bytes and hash. Nothing
  is guessed, and values are checked against the blueprint's schemas when there is one.
- The packages are published from the same release workflow, and the same build, as the release archives. Publishing
  credentials are kept out of the build jobs, and npm provenance is attached.

## Non-goals

- A change in behaviour for today's archive users. The archives and the static playground keep working as they do (I9).
  The one change to the archives' code is a new loading mode, which they don't use.
- New evaluation features from #244, such as every redeemer in `MockTransaction`, evaluation from transaction CBOR, and
  profiling. They need their own designs. Open question 4 records where transaction-CBOR evaluation would go.
- BLS12-381 in WebAssembly. Both engines still lack it (ADR-057), and the packages say so.
- A stable API version 1. The API stays version 0, experimental (D7).
- Deriving bech32 addresses (open question 3).
- Publishing snapshot or development builds to npm.
- Hosting package assets on another origin than the page (open question 7).

## Decision

### D1. Three packages

| Package | Contents | Depends on |
|---|---|---|
| `@julclang/wasm-client` | The client (`createJulc`, `isFullClient`, sessions), the worker, the stamped runtime file (D2), both halves of the Node bridge, and the declarations. No engine and no REST adapter. | — |
| `@julclang/julc-vm-wasm` | The VM engine (launcher, module, `engine.json`) and `createJulcVm()` | `@julclang/wasm-client`, same version, exact |
| `@julclang/julc-wasm` | The full engine and `createJulc()` | `@julclang/wasm-client`, same version, exact |

Reasons for this split:
- An application that only evaluates scripts does not download the full engine: 24.5 MB in pre20, and 34.5 MB built
  from main at M3. The VM engine is about 14.5 MB.
- An engine an embedder builds (ADR-064) can depend on the client alone instead of copying it, when the engine is built
  on a published JuLC release (D1a). An embedder built on a JuLC release candidate or snapshot, which npm never gets
  (D8), keeps using the client files from the `julc-wasm` jar.
- JOUST today uses neither the client nor a manifest: `load.js` loads `engine/joust-runtime.js` on the main thread.
  Moving it onto `wasm-client` is a larger change, for JOUST to decide (M5).

The exact dependency stops npm from pairing an engine package with another release's client. D1a checks the pairing
again at run time, for the cases dependency resolution cannot cover: `overrides`, duplicated installs, an application
that installs `wasm-client` itself, or an embedder's copy.

### D1a. The client and the engine prove they are one release (r2, revised in r4)

Two identities are checked, at run time, before the client returns.

- **The JuLC build.**
  - The client carries the version it was built as: a constant, `CLIENT_VERSION`, generated at build time, for example
    `0.1.0-pre22`.
  - Every engine built on `julc-wasm` reports the JuLC version it was built with, as `features().julcVersion`. JuLC's
    images and embedders' images alike take it from `JsApi.julcVersion()`, which reads the version the `julc-wasm` jar
    was built as.
  - The client requires `features().julcVersion === CLIENT_VERSION`, in both modes and for every engine. On a mismatch
    it disposes the worker and fails.
  - This is what catches a different client, whether from `overrides`, a duplicated install or a stale copy. The
    engine's runtime and the client must come from the same `julc-wasm` build (ADR-064 I3, extended to the npm client
    in Compatibility).
  - A build always records its version, because `wasmVersion` writes `project.version`. `dev` is reported only when
    that resource is missing, and it matches only a client also built as `dev`.
- **The engine's own release.**
  - For JuLC's variants, meaning an engine whose `variant` is `full` or `vm`, `version()` is the JuLC version too, so
    the client also requires `version() === features().julcVersion`. An embedder that names its variant `full` or
    `vm` is held to this rule as well.
  - An embedder's engine (ADR-064) reports its own application version as `version()`, which may differ. For it, the
    client compares `version()` only with an `expectedVersion` the caller passes.
- **The wrappers.** `createJulcVm()` and `createJulc()` pass their package version as `expectedVersion`. With the two
  rules above, a JuLC engine package therefore runs only with an engine and a client of its own release.
- **Archive mode.** The manifest's `julcVersion` must also equal `features().julcVersion`.

### D2. ES-module entry points, CommonJS engine assets, static URLs

- **Entry points.** Each package's entry points are ES modules (`"type": "module"` at the package root). Each package
  declares `"engines": {"node": ">=22.3.0"}` (see "Other package fields").
- **Engine assets.**
  - An engine package keeps the launcher, the module and `engine.json` in an `engine/` directory whose own
    `package.json` is `{ "type": "commonjs" }`, as JOUST's `joust-runtime/engine/` does.
  - The launcher stays byte-for-byte the archive's (I1) and keeps its CommonJS `require`.
- **Generated `engine.js`.** It sits at the package root and names every engine file statically, so bundlers copy them
  and the browser and Node resolve them next to the module:

  ```js
  // generated at build time (illustrative)
  export const manifest = deepFreeze({ /* contents of engine/engine.json */ });
  export const launcherUrl = new URL('./engine/julc-vm-b69c37c29c6b.js', import.meta.url);
  export const moduleUrl = new URL('./engine/julc-vm-b69c37c29c6b.js.wasm', import.meta.url);
  export const version = '0.1.0-pre22';
  export function engineFiles() {
    return {
      manifest: { /* contents of engine/engine.json, a new object */ },
      launcherUrl: new URL('./engine/julc-vm-b69c37c29c6b.js', import.meta.url),
      moduleUrl: new URL('./engine/julc-vm-b69c37c29c6b.js.wasm', import.meta.url),
      version: '0.1.0-pre22',
    };
  }
  ```

- **The `./engine` subpath** is public API. It exports:
  - `manifest`, deep-frozen;
  - `launcherUrl`, `moduleUrl` and `version`;
  - `engineFiles()`, which returns new, unfrozen copies of all four on every call. It is for passing the engine to
    `wasm-client`'s `createJulc` directly (M3).

  `engine.js` holds two copies of the manifest and four `new URL` calls, every one a string literal.
- **Entry functions.** `createJulcVm(options)` and `createJulc(options)` take their files from `engineFiles()` and pass
  them to `wasm-client`'s `createJulc` as `launcherUrl`, `moduleUrl`, `manifest` and `expectedVersion`, with
  `rest: false` (D4). They never read the exported `URL` objects, which a caller can change through their setters, so
  nothing done to the `./engine` exports redirects a wrapper (M3-C1). The existing `baseUrl` mode stays for the
  archives.
- **The worker in browsers.** `wasm-client` constructs it in the one form both Vite and webpack recognise, as a single
  expression: `new Worker(new URL('./julc-worker.js', import.meta.url), …)`. Each client owns one worker, as today.
  - It is a **classic worker** (M0, open question 2). A module worker also worked in Chromium, by loading the unchanged
    launcher with `import()`, but that runs a script generated as a classic script as an ES module. It also needs
    ignore comments in each bundler. M0's evidence is in `adr/evidence/077-m0/`.
- **The engine's runtime is a file, not a string** (r11, from M0; revised in r12).
  - Today `JsApi.install` evaluates the runtime from a string (`(0, eval)(String(source))`). So a Content Security
    Policy must allow `'unsafe-eval'`, not just `'wasm-unsafe-eval'`.
  - **A stamped runtime.**
    - The build generates `julc-runtime.js` once, stamped with the JuLC version it was built as
      (`JulcRuntime.julcVersion`), in the same way as `julc-wasm-version.properties`.
    - That single stamped file is the image's embedded resource, and also the file shipped beside the worker in:
      - `@julclang/wasm-client`;
      - the archives and the playground;
      - the jar's extractable SDK directory `META-INF/julc-wasm/js/`. ADR-064's embedder steps copy only that
        directory, so the file must be there.
    - All these copies are byte-equal.
  - **Where the worker loads it from, first:**
    - in package mode, from `@julclang/wasm-client`, named by the string literal
      `new URL('./julc-runtime.js', import.meta.url)`, under D2a's checks for script URLs;
    - in archive mode, the fixed file name `julc-runtime.js` beside the manifest, under ADR-064 I4's confinement;
    - in Node, through the shim's load path (D3).
  - **`JsApi.install` checks the runtime's identity before using it,** and never calls a runtime it has not checked:
    - **When the worker or shim recorded a preload** (r13, the strict case):
      - a well-formed `JulcRuntime` stamped with the image's `JsApi.julcVersion()` is used;
      - a malformed, unstamped or differently stamped one is a terminal start-up error, naming both versions, with no
        recovery by evaluation;
      - a recorded preload that failed is terminal too.
    - **When no preload was recorded,** the `JulcRuntime` found may be a leftover from another engine's own
      evaluation. This happens on a shared main-thread global, as with ADR-064 embedders like JOUST, or with an engine
      from before r12.
      - So a missing, malformed, unstamped or differently stamped one is treated as absent: the image evaluates its
        own embedded copy. Engines loaded earlier keep working, because their API closes over their own runtime.
      - A matching one may be used.
      - If a CSP refuses the evaluation, that refusal is an explicit start-up error.
    - The global is not deleted after install, because `rest-adapter.js` reads `JulcRuntime.legacy`.
    - The worker and the shim record that they preloaded, and the engine reports which runtime it used. The client
      fails if a preload happened but the engine says it evaluated its own copy.
    - The report travels as a property keyed by `Symbol.for('julc.runtime')`, not by a string, so it can never
      collide with an engine's operation group (M1 review).
  - **Why the stamp is needed.**
    - D1a compares the image with the client. It never compares the runtime file with either.
    - Several engines can share one global: an embedder that loads engines on the main thread, as JOUST does. A stale
      file beside an archive's manifest is the other risk.
    - The runtime is the wire codec (`encode`, `decode`, and the constructor-index bound D6a relies on). A runtime of
      another build could mis-encode values silently.
    - The stamp detects accidental mixing. It does not defend against malicious code already running inside the
      worker, which is outside the threat model.
  - **CSP.**
    - With the stamped file loaded first, `script-src 'self' 'wasm-unsafe-eval'` is enough. M0 confirmed this with the
      `eval` patched out.
    - A worker served over HTTP(S) takes its policy from its own response, not the page's. So the requirement, and
      M1's test, put the policy on the worker script's response, and on the launcher's and runtime's.
    - A main-thread embedder that preloads nothing keeps the evaluation path, and so still needs `'unsafe-eval'`. The
      README says so.
    - **A Chrome extension** (Manifest V3) declares the same `'wasm-unsafe-eval'` in its manifest:
      `content_security_policy.extension_pages`, for example
      `"script-src 'self' 'wasm-unsafe-eval'; object-src 'self'"`. MV3's default policy refuses WebAssembly, and M3's
      negative control shows the client fails at once, naming the CSP cause.
- **The client never writes `new URL(<expression>, import.meta.url)`.** webpack builds a context module for it, and
  Turbopack fails the build (M0). Every asset URL it names is a string literal.
- **The `exports` maps:**

  ```json
  // @julclang/wasm-client
  "exports": {
    ".":        { "types": "./index.d.ts",  "node": "./node.js", "default": "./index.js" },
    "./models": { "types": "./models.d.ts" }
  }
  // @julclang/julc-vm-wasm and @julclang/julc-wasm
  "exports": {
    ".":       { "types": "./index.d.ts", "node": "./node.js", "default": "./index.js" },
    "./engine": { "types": "./engine.d.ts", "default": "./engine.js" }
  }
  ```

  `./models` exports types only (D5). The `engine/` files are reached only through `engine.js`'s URLs, not through
  `exports`.
- **Other package fields:**
  - each package also exports `./package.json`;
  - each has a top-level `types` field, for TypeScript's older `node10` resolution, which ignores `exports`;
  - each sets `sideEffects` to the files that have them;
  - `engines` is `{"node": ">=22.3.0"}`, because the shim takes Node's builtins from `process.getBuiltinModule`, which
    Node 22.3.0 added (M0). M1 tests an installed package at exactly 22.3.0.
- **Bundler configuration.** Vite pre-bundles dependencies, which moves modules and can break
  `new URL(…, import.meta.url)`, and webpack may parse a `.js` file named that way.
  - M0 found:
    - Vite 8 needs no configuration;
    - the dev servers of Vite 5 to 7 need `optimizeDeps.exclude` for both packages;
    - webpack 5 needs none, but its Terser minifies the launcher. An `asset/resource` rule plus a Terser `exclude`
      keeps the launcher byte-identical, and the README shows it.
  - The READMEs state it.
  - The fixtures test both the development server and the production build.
- **Types for both environments.**
  - `workerFactory` takes a small `WorkerLike` interface (`postMessage`, `terminate`, `onmessage`, `onerror`) instead of
    the DOM's `Worker`.
  - The declarations must type-check from an installed tarball:
    - with `moduleResolution: "NodeNext"` and no DOM library;
    - with `moduleResolution: "bundler"` and the DOM library;
    - in both cases with `skipLibCheck: false`.

### D2a. Two loading modes, each confined (amends ADR-064 I4; r2, revised in r5)

- **Archive mode** (`baseUrl`, the manifest's `launcher` field): unchanged. The worker loads only a plain file name next
  to the manifest: lowercase words joined by hyphens, a 12-hex-digit hash, then `.js`, with no path, query or scheme
  (ADR-064 I4).
- **Package mode** (`launcherUrl` and `moduleUrl` given):
  - The URLs come only from the caller, normally from an engine package's `engine.js`. In this mode the manifest's
    `launcher` field is ignored, never read as a path.
  - **In a browser,** the worker accepts a URL only when:
    - its origin equals the worker's own origin (`self.location.origin`), and that origin is a tuple origin, not
      opaque (`null`). This holds for `https:` and `http:`, and for any other scheme the browser gives a tuple origin.
      - Chrome does so for `chrome-extension:`.
      - Electron does so for a custom scheme only when it is registered as standard.
      - Other hosts depend on their engine.
      - Hosts whose scheme gets an opaque origin fail closed.
      - A Chrome extension fixture is tested (M3). Other hosts are listed in the README as untested.
      - An Electron page loaded from `file:` (`loadFile`) is refused; such an app serves the files through a
        registered standard scheme instead.
    - its scheme is not `data:`, `blob:`, `javascript:` or `file:`;
    - it has no user name or password;
    - the launcher's path ends in `.js` and the module's in `.wasm`.
  - **In Node,** the worker accepts only `file:` URLs, with the same extension rule.
    - The worker knows it runs in Node because the Node bridge's shim marks the worker's global scope before the worker
      script runs (D3).
    - A browser worker never has that mark, so `file:` is always refused there.
  - Bundlers may rename the files, so the file-name pattern is not checked in this mode. Its role passes to the
    identity checks the engine must pass after loading: `api`, `variant` and `groups` against the manifest, and the
    versions (D1a). A mismatch disposes the worker.
  - Other origins are refused (open question 7).
- **The client's own identity snapshot** (r18, M3-C4).
  - Before any worker or `workerFactory` adapter exists, the client takes a private deep copy of the manifest it
    expects, and records the runtime, launcher and module URLs as strings. In archive mode they are resolved from
    `baseUrl` exactly as the worker resolves them.
  - The worker's start message carries a separate copy. Every readiness check uses only the snapshot.
  - The worker's ready message reports the URLs it loaded (`loaded: {runtime, launcher, module}`), and the client
    refuses any that differs from the one it named.
  - So nothing an adapter or the caller does to the objects it is given changes what the client accepts.
  - An adapter that forges the worker's replies is caller code, which already controls the worker; the snapshot does
    not claim to stop it. The worker and client are always one release (package mode ships them together, archive
    mode versions them with `?engine=`, embedders take both from the jar), so every worker sends `loaded`.
- **No mixing.** A client is in exactly one mode. Giving `baseUrl` together with `launcherUrl` or `moduleUrl` is an error.
- **The runtime file** (r12) is a third script the worker loads, under the same rules: D2a's URL checks in package
  mode, and a fixed file name beside the manifest in archive mode.

### D3. Node.js is supported, through a shipped bridge

- `wasm-client`'s `node` entry ships both halves of the bridge the tests prove today, as production code:
  - the parent-side adapter, which turns a `worker_threads` `Worker` into the `WorkerLike` the client drives
    (`wasm-smoke.mjs`, lines 13–18);
  - the worker-side shim, which provides `self`, `postMessage`, `addEventListener` and `importScripts`, and loads the
    launcher with `createRequire` (`node-worker.mjs`).
- **The shim's single load path is `vm.compileFunction`** (M0).
  - The shim compiles the launcher's unchanged bytes as a function, and calls it with `createRequire(launcherPath)`
    injected as `require`, plus `module`, `exports`, `__filename` and `__dirname`.
  - This passed on Node 22 and 24, with the engine directory as CommonJS and as an ES module, on the main thread and
    in an application worker. Loading with `createRequire` alone failed under a `"type":"module"` scope.
  - The `engine/` directory stays CommonJS, but nothing relies on it.
  - In package mode the shim loads the launcher at the URL it is given, and the runtime file first (D2). It no longer
    recognises the launcher by its file name, which a bundler may change (`node-worker.mjs`, line 16).
- **The shim is a plain file.** The Node entry names it with its own `const shimUrl = new URL('./node-worker.js',
  import.meta.url)`, and the shim has no `import` statements.
  - webpack 5.111.1 and 5.112.2 miscompile `new Worker(new URL(…))` from `node:worker_threads` for `target: 'node'`.
  - A plain file, which bundlers copy as an asset, avoids that.
- **Server-side bundling.** A server bundle (webpack `target: node`, Next.js, esbuild) can copy the launcher out of
  `engine/`, into a directory whose nearest `package.json` says `"type": "module"`. `createRequire` would then fail, as
  r1's all-ESM design did.
  - M0 tests loading the launcher's unchanged bytes as a function with an injected `require`, `module`, `exports`,
    `__filename` and `__dirname` (`vm.compileFunction`). That way, the directory's module type no longer matters.
  - If that works, the shim uses it as its **only** load path, so unbundled Node and bundled Node take the same path.
  - Loading the launcher this way is necessary but not enough. A server bundle must also emit the launcher, the
    module, `julc-worker.js` and the shim as separate files, and keep their `new URL(…, import.meta.url)` references
    resolvable.
  - So support is claimed **per bundler**, each backed by a real server-bundle fixture: M0 first, then M3, I8 and
    Verification.
  - M0 passed **webpack 5 `target: 'node'`** and **Next.js 16 with Turbopack**. These are claimed.
  - esbuild does not copy files named by `new URL`, and Next `--webpack` rewrites asset URLs to site-relative paths.
    The README tells their users to keep the packages external (`--packages=external`, `serverExternalPackages`), and
    M0 confirmed that this works.
- **WebAssembly exception references.**
  - The first `createJulc` call checks whether the Node runtime supports them, by running `WebAssembly.validate` on a
    minimal module that uses them, rather than by reading the Node version.
  - If they are missing, it enables them with `v8.setFlagsFromString('--experimental-wasm-exnref')` before starting
    the worker, as JOUST's `load.js` does.
  - Setting the flag in the parent is enough: both the Codex probe and the Claude reviewer's, on Node 22.12, loaded the
    real VM engine in a `worker_threads` worker that way.
  - The flag is process-wide, and the README says so. It is set lazily, never at import time.
- **One worker per client.** Each worker has its own global scope, so engines that announce themselves through
  `onJulcReady` cannot collide, and no load queue is needed. (JOUST's `load.js` needs one only because it loads in the
  main thread.)
- The caller passes no flags and changes no files (I8).

### D4. The REST adapter: kept for archives, left out of packages (r2, revised in r5)

- **The default depends on the distribution** (revised in r14, from M1's review):
  - `@julclang/wasm-client` ships a generated `client-defaults.js` with `rest: false`, beside its split client files.
  - The archives and the jar ship a generated **single-file** `julc-wasm.js`, with the client core, its version and
    `rest: true` inlined, and no imports.
    - The playground versions `julc-wasm.js` with `?engine=<hash>`. Imports from it would not carry that query, so
      after a deployment a browser could pair a new engine with a cached old client.
    - Every other script an archive page loads also carries `?engine=`. The launcher is versioned by its
      content-addressed name.
  - Both distributions ship a `client-defaults.d.ts` with the default as a literal type.
  - `createJulc`'s `rest` option overrides the default.
- **Archive clients:** the archives ship their own copy of the client, with `rest: true`. The worker loads
  `rest-adapter.js` from beside the manifest, and the client exposes `rest`, exactly as today. An archive application
  that calls `createJulc({baseUrl}).rest(…)` keeps working unchanged (I9).
- **Where the adapter is loaded from:**
  - in archive mode, only from beside the manifest, as today;
  - in package mode, never. `rest: true` with package mode is an error.
- **`@julclang/wasm-client` used directly** with `baseUrl`, for example by an embedder: `rest` defaults to `false`. With
  `rest: true`, the adapter must sit beside the manifest. Otherwise the client fails with an error that names the
  missing file.
- **The engine packages** use package mode, so they never load the adapter. None of the three packages ships
  `rest-adapter.js`.
- **Types:** the client returns `EngineClient`, which keeps `rest`, as ADR-064 defines it, when `rest` is on. Otherwise
  it returns `PackageClient`, which is `EngineClient` without `rest`. `VmClient` and `FullClient` have counterparts in
  the same way.
  - Each distribution also generates `client-defaults.d.ts`, which exports its default as a literal type
    (`true` or `false`). `createJulc`'s overloads use it:
    - with `rest` omitted, the return type is right for that distribution;
    - a `boolean` `rest` gives the union;
    - `createJulc(options: Options)` type-checks.
  - The `julc-wasm` jar carries the archives' files (`rest: true`), as ADR-064's embedders expect.
  - M1's type checks cover `rest` omitted, `true` and `false`, in both distributions.

### D5. Public type names

- `wasm-client`'s root exports the client types that are already clean (`VmClient`, `FullClient`, `VmRequest`,
  `Evaluation`, `DebugSession`, `DebugReply`, `SourceDebugSession`, `TransactionRequest`, `Features`, `JulcError`), plus
  `WorkerLike` and `PackageClient`. It adds plain aliases for the generated records:
  - `ScriptInput`, `DecodeRequest`, `DecodeResponse`, `ScriptInfo`;
  - `MockTransaction` and its parts (`TxIn`, `TxOut`, `Value`, `Datum`, and so on);
  - `PlutusData`, `DataInput`, `CompileRequest`, `CompileResponse`, `Breakpoints`, `Snapshot`, `Timeline`;
  - `CostModel`, `Target`, and the `vm.applyParams` types (D6).
- `Features` gains `julcVersion` (D1a).
- Where two records share a simple name, the alias names the operation instead: the compiler's Run Test
  `EvaluateRequest` becomes `TestRunRequest`.
- The generated `<Outer>_<Inner>` names stay available from the types-only sub-path `@julclang/wasm-client/models`, for
  code that used the archives.
- M1 fixes the list, and a test checks that every type reachable from a public operation has an alias.

### D6. `vm.applyParams`, in both engines

A new operation in the `vm` group (illustrative TypeScript):

```ts
vm.applyParams(request: {
  language: 'V1' | 'V2' | 'V3';     // required, exactly one of these three strings; never inferred (D6a)
  script: ScriptInput;              // CBOR hex (single or double wrapped), flat, CIP-57 blueprint, text envelope;
                                    // not UPLC text; script.validator selects a blueprint's validator by its exact
                                    // title; script.params must be empty
  params: Array<PlutusData | DataInput>;
  partial?: boolean;                // default false
  checkSchema?: boolean;            // default true: check values against the declared schemas (D6b)
}): Promise<{
  compiledCode: string;             // the applied script wrapped twice in CBOR, as a JuLC blueprint or a cardano-cli
                                    // text envelope holds it
  cborHex: string;                  // the same script wrapped once, as an Aiken blueprint holds it
  scriptHash: string;               // blake2b-224 of the language tag and the once-wrapped script
  language: 'V1' | 'V2' | 'V3';
  paramsApplied: number;
  remainingParams: number | null;   // declared minus applied; null when the input declares no parameter list
  schemaChecked: boolean;           // true only when every value was checked against a declared schema
}>;
```

**Application.** It applies the parameters as `ScriptDecoder.decode` does: `Program.applyParams`, which wraps each
value as a Plutus `Data` constant, then the same hash.

**Stack safety along the whole path** (M2):
- julc-core's Plutus `Data` CBOR encoder is iterative. A differential test against the previous recursive encoder
  confirms its output is byte-identical.
- `vm.applyParams` reads `cbor` and `uplc` values, and the script's own data constants, with its own stack-safe
  canonical readers. It reaches the data constants through a reader hook on `UplcFlatDecoder`, and the JSON reader has
  no nesting cap.
- Depth 10,000 in every format passes on 256 KiB JVM stacks and in the Web Images.
- The VM's own decoder for data constants (`PlutusDataCborDecoder`) is unchanged and still recursive. Making it
  stack-safe is out of this ADR's scope.

**The applied constants' encoding is part of the contract.**
- Each applied value is encoded in the canonical form of Plutus `Data` that `PlutusDataCborEncoder` writes, matching
  Plutus's `encodeData`. The rules:
  - non-empty lists and constructor fields are indefinite arrays, and empty ones definite;
  - byte strings, and bignum payloads, over 64 bytes are indefinite strings of 64-byte chunks;
  - maps are definite.
- The bytes, and so the hash, depend on this form. Another tool that encodes the same value differently, for example
  with definite-length lists, produces a different script and hash, and the README says so.
- The independent oracles (I4) are given the values in this form.
- `vm.decode` and `vm.hash` with `script.params` stay as they are, guesses included.
- The new operation is the documented way, with the rules of D6a and D6b.
- Only `remainingParams: 0` means the script is complete and ready to deploy. A partial result (`remainingParams` > 0),
  or one whose declaration is unknown (`null`), is labelled as such in the README and the declarations.

**Where the checker lives.** In new classes in `julc-tools` that use only `julc-core` and Jackson, so the VM image can
include them. `wasmImageVm` keeps its `-H:AbortOnTypeReachable` guards for the compiler and cardano-client-lib, so the
image build fails if the checker pulls either in (I3). Being in `julc-tools`, JVM callers, such as the CLI later, can use
the same checker.

### D6a. What `vm.applyParams` refuses to guess, and what it always checks (r2, revised in r4 and r5)

Unlike `vm.decode`, `vm.applyParams` turns `ScriptDecoder`'s guesses into errors, because its output is what gets
deployed. Each refusal is an `invalid-request` error (status 400), and names its cause.

**Preparation, always checked:**
- **Language: required, never inferred** (r6, r7).
  - The request must state `language`, exactly `V1`, `V2` or `V3`. These map to `PLUTUS_V1`, `PLUTUS_V2` and
    `PLUTUS_V3`.
  - Any other value is refused, and so is a missing one. The legacy suffix parser (`ScriptDecoder.languageOf`, which
    accepts anything ending in V1, V2 or V3) and its `auto` are not used.
  - `script.language` must be empty, like `script.params`. A non-empty one is refused, so it cannot silently disagree
    with `language`.
  - A wrong tag gives a wrong hash, and the bytes cannot tell the language:
    - UPLC 1.1.0 is allowed under all three languages at protocol version 11 (`ProtocolFeatureRegistry`);
    - builtins become available in older languages over time;
    - so `ScriptDecoder`'s inferences (`version`, `builtins`, `default`) are never used here.
  - The caller always knows it: JuLC compiles validators as V3, and an Aiken blueprint states it in its preamble.
  - **Cross-check.** When the input also carries language information, the operation compares it with `language` and
    fails on a mismatch, naming both. This catches a wrong `language` before it can produce a wrong hash. The
    information it compares:
    - a text envelope's `type`, which must be exactly `PlutusScriptV1`, `PlutusScriptV2` or `PlutusScriptV3`;
    - a CIP-57 blueprint's `preamble.plutusVersion`, which must be exactly `v1`, `v2` or `v3`;
    - a blueprint hash for the selected validator. This is the original-hash rule below, checked under `language`.
  - **Information present but not recognised is an error,** whatever `language` says. Examples: an envelope `type` of
    `PlutusScriptV4` or `SimpleScript`, or a `plutusVersion` of `v4`. It is never treated as absent.
- **Validator.**
  - A blueprint with more than one validator entry needs `script.validator`, even when the entries share one
    `compiledCode`. Entries with the same code can still declare different parameter counts, schemas and hashes, and
    those decide whether the request is accepted, so implicit selection could depend on the entries' order.
  - The selection is an exact title. `ScriptDecoder`'s numeric-index form is not accepted here.
  - A blueprint in which two entries share a title is refused.
  - Only a blueprint with exactly one entry is used without a selection.
- **Original hash.** If a blueprint gives a hash for the selected validator, it must equal the hash of the original
  once-wrapped script under `language`, before any parameter is applied. A mismatch is an error. If the hash matches the
  script under another language, the error says so, for example "the blueprint hash matches this script as V2, not V3".
- **No double application.** `script.params` must be empty; values go in `params` only.
- **Canonical script bytes, at every layer** (r5):
  - Each CBOR byte-string wrapper must be definite, with a minimal length header, and must have no bytes after it.
  - Decoding the program and encoding it again must give the input's FLAT bytes exactly.
  - So the output is the re-encoding, and equals the input's bytes, whether or not parameters are applied: zero
    parameters return the input unchanged. A non-canonical input is refused rather than silently normalised.
  - The error names the cause, for example "constant at term 14 is not in the canonical Data encoding", or "the CBOR
    wrapper has a non-minimal length".
  - M2 records which toolchains' scripts pass (Verification).
- **No UPLC text.** A UPLC text input is refused, because it has no bytes to keep.
- **No warnings.** Any warning `ScriptDecoder` would give is an error in this operation. The rule is evaluated after
  the explicit `language` is applied, so the language warning never arises.
- **Strict JSON everywhere** (r7). Every JSON input of this operation is read strictly: an envelope or blueprint, its
  schemas, and the values.
  - Exactly one document, with nothing after it.
  - No object may repeat a member name. Duplicates are refused before any tree is built. For example, an envelope
    with two `type` members is refused, whatever their order, so a cross-check can never pass on the last of two
    contradictory values.
  - Repeated keys inside a Data map's entry array remain legal, because they are entries, not members.

**Values, always checked; strict and lossless** (r5):
- **The forms accepted:** a `PlutusData` JSON object, or a `DataInput` with an explicit `format` (`json`, `cbor` or
  `uplc`). A missing format, or `auto`, is refused, because auto-detection is ambiguous (Context item 6).
- **A strict reader, separate from `vm.decode`'s.** The legacy `PlutusDataJson` coerces: `{"int":1.5}` becomes 1, and
  `{"constructor":"bogus"}` becomes constructor 0. It also drops duplicate map keys and ignores trailing documents.
  `applyParams` uses a strict reader of the same JSON shape instead:
  - each object has exactly one shape: `{int}`, `{bytes}`, `{list}`, `{map}`, or `{constructor, fields}`;
  - an `int` and a `constructor` index are JSON integer tokens, with no fraction and no exponent;
  - a `constructor` index satisfies 0 ≤ index < 2⁶⁴, at every depth and whatever `checkSchema` says, and `fields` is
    required.
    - A larger index would encode and apply, but the result cannot be decoded: Plutus `Data` constructor tags are
      unsigned 64-bit words (`PlutusDataCborDecoder`).
    - The JavaScript client already enforces this range for structured values (`julc-runtime.js`). JSON text must not
      bypass it;
  - `bytes` is even-length hex;
  - map entries are kept in order, duplicates included, as Plutus `Data` maps allow;
  - unknown keys, and anything after the value, are refused.

  JavaScript `bigint` values reach the engine as exact integer tokens.
- **A `cbor` value** must be exactly one item, in the canonical encoding above. It must have no trailing bytes, and
  encoding the decoded value again must give it back unchanged. Otherwise it is refused, and the error points to the
  JSON form.
- **A `uplc` value** is read strictly too, with nothing after it.
- **Builtin parameters.** The check reads a declared parameter's schema after resolving its `$ref`; Aiken's parameter
  schemas are always `$ref`s.
  - If the resolved top-level schema is a builtin type (`#…`), the operation fails, whatever `checkSchema` says. It
    applies Plutus `Data` constants only, so it cannot apply a builtin integer or byte string.
  - A builtin type nested inside a Data schema is an invalid schema (D6b). It is found only when `checkSchema` is
    true.
- **Count.** For a blueprint input, the selected validator declares its parameters. A validator without a `parameters`
  field declares none (JuLC omits the field then, `BlueprintGenerator`, lines 77–81).
  - More values than declared is an error, so extra values can never shift into the datum, redeemer or context
    positions.
  - Fewer is an error unless `partial: true`.
  - Only a non-blueprint input (CBOR, flat, envelope) has no declaration.

**Schema, checked unless `checkSchema: false`:** each value against its declared schema (D6b). `schemaChecked` is true
when `checkSchema` is true and the input has a declaration, so every given value was checked. This includes the vacuous
case of no values against a declaration. It is false whenever `checkSchema` is false, or the input has no declaration.

The options combine like this:

| Input declares parameters? | Values vs declared | `partial` | `checkSchema` | Result | `remainingParams` | `schemaChecked` |
|---|---|---|---|---|---|---|
| yes | equal | any | true | applied if every value matches its schema | 0 | true |
| yes | equal | any | false | applied, shapes not checked | 0 | false |
| yes | fewer | false | any | error, naming the missing titles | — | — |
| yes | fewer | true | true / false | applied, with or without the shape check | declared − given | as `checkSchema` |
| yes | more | any | any | error, naming the declared titles | — | — |
| blueprint, no `parameters` field (declares 0) | any value given | any | any | error | — | — |
| blueprint, no `parameters` field (declares 0) | none given | any | any | the script as it is | 0 | as `checkSchema` |
| no declaration (CBOR, flat, envelope) | — | any | any | applied, nothing to check against | `null` | false |

### D6b. The schema check: its domain and rules (r2)

**Two kinds of failure, never mixed.**
- An **invalid schema** is a fault of the blueprint: an unknown keyword, a malformed keyword value, a keyword used with
  the wrong data type, a missing or external `$ref`, or a reference cycle that D6b forbids.
- A **mismatch** is a fault of a value.
- The checker finds every schema fault first, in a pass over the parameter schemas and the definitions they reach,
  before it looks at any value. So `not` cannot turn a schema fault into a success, and `anyOf` cannot hide one.
- The two kinds are reported with different codes. The full table, as implemented in M2:

  | Code | Status | When |
  |---|---|---|
  | `invalid-request` | 400 | Every D6a refusal, and a request body the strict reader refuses. |
  | `work-limit-exceeded` | 400 | The schema check exceeded its work bound (default 1,000,000 units). The message suggests `checkSchema: false`. |
  | `invalid-schema` | 422 | A D6b fault in any declared parameter schema; also an applied parameter's top-level `$ref` that cannot be followed, even with `checkSchema: false`, because the builtin rule needs it resolved. |
  | `parameter-mismatch` | 422 | A value does not match its declared schema. |
  | `internal` | 500 | An unexpected engine error. |

**Supported domain.** Exactly the keywords in the CIP-57 table in Context, with these meanings:
- **Annotations** (`title`, `description`, `$comment`) never affect the result.
- **Opaque data** is a schema with no `dataType`, no applicator and no `$ref`. It accepts any Plutus data, as CIP-57's
  `dataType` rule says. It is recognised by structure only: names and titles never matter.
  - A blueprint definition or record named `Data` is an ordinary schema. JuLC emits opaque data as an annotation-only
    schema (`SchemaGenerator.data()`), and supports a user record named `Data` beside it (`BlueprintTest`).
- **Applicators** have their general JSON Schema meaning:
  - `allOf`: every listed schema matches;
  - `anyOf`: at least one matches;
  - `oneOf`: exactly one matches;
  - `not`: the schema does not match.

  Choosing a constructor branch by its `index` is allowed only as a shortcut that gives the same answer. It is not
  the rule, because branches may share an index, or be primitives.
- **`bytes`:** the length is in bytes. `enum` compares the value's hex form, ignoring case.
- **`integer`:** bounds and `multipleOf` are evaluated at arbitrary precision.
- **`list`:**
  - a single `items` schema applies to every element;
  - an `items` array requires exactly that many elements, matched by position;
  - `uniqueItems` compares elements as Plutus data: by their canonical encoding, using a hash set.
- **`map`:** `keys` and `values` apply to every pair. `minItems` and `maxItems` count the pairs.
- **`constructor`:** the value's index equals `index`, and its fields match `fields` by position. The field count must
  be equal.
- **Anything else is an invalid schema,** including keywords outside the table.

**References.**
- `$ref` is local only: `#/definitions/<name>`, with JSON Pointer escaping (`~1` for `/`, `~0` for `~`). Aiken
  blueprints use `~1` in definition names.
- Any other form, and a reference to a missing definition, is an invalid schema.
- A schema with `$ref` may also carry annotations (`title`, `description`, `$comment`). Any other keyword beside `$ref`
  makes it an invalid schema.

**Cycles.** The supported domain is a deliberate subset of CIP-57's schemas, chosen so that every check ends.
- Before checking values, the checker builds the graph of references and applicators reachable from each parameter
  schema.
- An edge **consumes** when it moves into part of the value: a `list`'s `items`, a `map`'s `keys` or `values`, or a
  `constructor`'s `fields`. Other edges (`$ref`, `allOf`, `anyOf`, `oneOf`, `not`) stay at the same value.
- **Supported:** every cycle contains at least one consuming edge. Equivalently, the edges that don't consume form no
  cycle. Every recursive step then descends into a strictly smaller part of a finite value, so checking ends.
  - This covers the recursive types JuLC and Aiken emit, such as `Tree = anyOf[Leaf, Node(fields: [$ref Tree])]`, and
    recursion through an alias (`A = $ref B`, `B = anyOf[Nil, Cons(fields: [integer, $ref A])]`).
- **Not supported, and reported as an invalid schema that names the cycle:**
  - A cycle with no consuming edge (`A → A`, `A → anyOf[A, …]`). Checking it would not end.
  - Any cycle that passes through `not`. Some such schemas have no consistent meaning (`S = not S`). Some have more
    than one (`S = not(not S)`). Some would end on smaller values. They are excluded as a deliberate simplification,
    not because each is meaningless, and they can be added later with a precise rule.

**Mismatch paths under applicators.**
- For `allOf`, the error reports the first listed schema that fails, at its path.
- For `anyOf` and `oneOf`, the error is reported at the applicator's path. It lists each branch's first failure, or,
  for `oneOf`, the branches that matched.
- For `not`, it reports that the value matched the schema it must not.

**Cost.** The checker memoises the result for each pair of a schema node and a value node, so a check costs at most
O(|schema| × |value|) node visits.
- Without memoisation, branches that share a recursive schema would cost exponential time. An example:
  `T = anyOf[{list, items: $ref T}, {list, items: $ref T}]`, against a list nested n deep, costs 2ⁿ checks.
- A bound on the work is still enforced. Exceeding it is its own error, not a timeout of the client.

**Partial application** (M2): when `checkSchema` is true, every declared parameter schema is compiled before any value
is read, including those of parameters a partial application leaves unapplied. So a schema fault is reported first,
as `invalid-schema`. The builtin refusal applies only to parameters receiving values.

**Depth.** The checker walks schemas and values with an explicit stack, not Java recursion. Deep data or deeply nested
applicators cannot exhaust the small stack of a Web Image (as ADR-074 does for the compiler's walks).

**Errors** name the parameter by its 1-based index (as `ScriptDecoder` numbers them) and its title, and the path inside
the value or the schema. For example,
`Parameter 2 (owner): at fields[0]: expected bytes, found int`, or `Parameter 1 (cfg): invalid schema: definitions/A
refers to itself without consuming data`.

### D7. API version 0, experimental

- The npm version is the JuLC version (D8), and `engine.json` keeps `"api": 0`.
- Each package README states that the API may change in any release before 1.0. The release notes list every change to
  it.
- When the API reaches version 1 is open question 6.

### D8. Versioning and publishing (r2)

**Version.**
- The npm version is the JuLC release version, for example `0.1.0-pre22`.
- JuLC's images report that version as `version()` (D1a).
- Snapshot and development builds (`native-image-dev.yml`) are not published.

**Which versions go to npm, and with which tag:**
1. **A release candidate (`-rc`) is not published.** The release job runs only a dry run for it.
   - Semver orders `0.1.0-pre21-rc2` above `0.1.0-pre21`, checked with npm's own `semver`.
   - So once a candidate is on npm, a range such as `^0.1.0-pre20` resolves to the candidate instead of the final
     release, and Dependabot would propose it.
   - JOUST, which matches `-pre` first, publishes its candidates to `latest` (`0.1.0-pre1-rc9` is its `latest`). JuLC
     deliberately differs.
2. **A `-beta` version** goes to `beta`. JuLC uses no `-beta` versions before 1.0. Semver compares `beta1` as text, so
   it sorts below every `preN`.
3. **Before 1.0, any other prerelease (`-preN`)** goes to `latest`.
4. **A version with no prerelease part** (for example `0.1.0`, or `1.0.0`) goes to `latest`.

At 1.0 this table is revisited.

The reasons are npm's resolution rules:
- `npm install <name>` without a tag or range installs the `latest` tag.
- A caret range admits prereleases of the same `major.minor.patch` only: `^0.1.0-pre20` accepts `0.1.0-pre22`, but
  `^0.1.0` accepts no prerelease.
- JOUST's workflow records that a package's first published version became `latest`, whatever `--tag` said. M4 checks
  this on the registry for the first publication.
- A published version number can never be used again, even after unpublishing.

Semver compares `preN` as text, so `0.1.0-pre100` sorts below `0.1.0-pre20` (open question 8).

The publishing jobs are specified in D8a.

### D8a. Pack in the read-only build, publish the same files from a gated job (r2)

The release workflow keeps its current boundary: build jobs have a read-only token and no secrets, and only separate jobs
that do not run the Gradle build get write access or credentials (`native-image-release.yml`, lines 10–12).

**In the `wasm` build job** (unchanged permissions, `contents: read`):
- It assembles the three package directories from the same `wasm-bundle-{vm,full}` output that it archives.
- It runs `npm pack`, which gives three tarballs.
- It checks I1 on the packed result, never on the shared directory:
  - it extracts each engine tarball, and the matching archive, into separate directories;
  - it compares their engine files byte for byte;
  - it checks that both copies of the manifest in `engine.js` equal `engine/engine.json`.
- It writes `julc-npm-<version>-SHA256SUMS.txt`, covering the three tarballs and the two archives.
- It uploads all of them as the job's artifact.
- It also emits the five SHA-256 digests as job outputs, for the checks within the same run.

**A new `attest` job** (r8) makes the durable anchor.
- It has no build. It downloads the `wasm` artifact, and holds only `id-token: write` and `attestations: write`.
- It creates a GitHub artifact attestation, signed with Sigstore under this workflow's identity, for the three
  tarballs and the two archives.
- This attestation, not a checksum file from the release, is what every later check trusts. A checksum file in the
  release proves only that the download arrived intact: anyone able to edit release assets could replace both the
  file and the tarballs. Job outputs belong to a single run attempt.
- Every later check verifies a release asset with `gh attestation verify --repo bloxbean/julc --signer-workflow
  bloxbean/julc/.github/workflows/native-image-release.yml --source-ref refs/tags/v<version>`.
  - That checks the identity in the Sigstore certificate, not strings in the attestation's predicate. So an attestation
    from a modified workflow, another branch or another tag fails.
  - Every job that verifies, including the build-once check in `wasm`, also holds `attestations: read`.
- `github-release` runs only after `attest` succeeds, so a release never exists without its attestation. `attest` is
  added to its existing prerequisites, which stay as they are: `needs: [validate, native-image, wasm,
  native-image-playground, shadow-jars, attest]` (`native-image-release.yml`, line 382).

**The `github-release` job** attaches the three tarballs and their checksum file to the GitHub release, beside the
archives.
- **Create-once.**
  - Before uploading, it checks the release's existing assets, using GitHub's asset `state`:
    - a completed asset (`uploaded`) with the same name and different bytes stops the job, and nothing is replaced;
    - a failed upload's placeholder (`starter`, which GitHub documents an HTTP 502 can leave behind, and which is safe
      to delete) is deleted, and only then are the original bytes uploaded again.
  - The upload runs with `overwrite_files: false`. Today's action defaults to `true` (`native-image-release.yml`, lines
    401–413).
- **A release is built once.**
  - The `wasm` job starts by checking whether the tag's GitHub release already holds attested npm tarballs. If it does,
    the job stops before building, with a message pointing to recovery.
  - So a whole-workflow re-run, which would rebuild different bytes (ADR-057, open question 4), changes nothing.
- **Recovery never rebuilds.** It has two paths, chosen by the state:
  - **Within the original attempt,** before any full re-run, "re-run failed jobs" recovers. It keeps that attempt's
    assets, artifacts and job outputs.
  - **After a refused full re-run, or once the original attempt can no longer be re-run,** only the manual
    `workflow_dispatch` entry recovers. After the early stop in `wasm`, "re-run failed jobs" would hit the same stop,
    and the downstream jobs would lack their artifacts.
- **The `workflow_dispatch` entry's contract:**
  - It is dispatched on the tag itself: its execution ref must be exactly `refs/tags/v<version>`. A run on a branch,
    or whose ref does not match the version, is refused before anything runs.
  - It reads the version from the tag name, and the gates from `gradle.properties` at that tag, using `git show`,
    with no Gradle: `npm_publish`, `github_release`, and the release-candidate rule (D8).
    - With `npm_publish = false`, or on a release-candidate tag, it publishes nothing.
  - On dispatch, every build, `attest`, `github-release` and Homebrew job is skipped (`if: github.event_name ==
    'push'`).
  - `npm-publish` admits dispatch explicitly:
    `if: !cancelled() && (github.event_name == 'workflow_dispatch' || (needs.github-release.result == 'success' &&
    needs.attest.result == 'success'))`. The ref and property gates still apply on top of it.
    - A status function is needed. A job whose `needs` were skipped is otherwise skipped too, because GitHub applies an
      implicit `success()`. On dispatch, `attest` and `github-release` are skipped by design.
    - There is no bare `always()`, so a normal run whose `github-release` or `attest` failed never publishes.
  - `npm-verify` uses the same form:
    `if: !cancelled() && needs.npm-publish.result == 'success' && needs.npm-publish.outputs.published == 'true'`.
  - It runs only `npm-publish` and `npm-verify`, on the release's existing assets, after verifying them against their
    attestation.
  - The entry lives in the same `native-image-release.yml`, because npm's trusted publisher validates the workflow
    file.
- **Release assets are never deleted** to recover, and a release is never recreated. I11 relies on this, and M4 tests
  that a deleted asset is detected.
- Once a release exists, its exact npm files are kept there for good. Every publication and every retry takes them
  from there, never from a run artifact, which expires, and never from a rebuild.

**A new `npm-publish` job:**
- **Gate.**
  - It runs only when both `npm_publish` and `github_release` are `true` in `gradle.properties`; the default for
    `npm_publish` is `false`.
  - In a normal run, it runs only after `github-release` and `attest` have succeeded. Otherwise it runs only from the
    recovery entry, under its contract above.
  - It verifies each downloaded release asset against its attestation (`gh attestation verify`). In a normal run it
    also compares the asset with the `wasm` job's digest outputs. Any difference stops it.
  - So npm never gets a version whose archives are not public, and users can always check I1.
- **Credentials.**
  - It runs in the `release-staging` environment. That environment accepts `v*` tags, but also `main` and `release/**`
    (`native-image-release.yml`, lines 422–424), so it cannot by itself ensure a tag.
  - The job therefore requires its own ref to be exactly `refs/tags/v<version>`, and refuses any other.
  - It gets `id-token: write` for npm trusted publishing (OIDC), `contents: read` to download the release assets, and
    `attestations: read`, and nothing else. No npm token exists.
- **Concurrency.** It runs in a per-tag concurrency group, without cancelling a run in progress, so a recovery run never
  overlaps the original run's publication.
- **Tools.** Node ≥ 22.14, and npm installed at an exact pinned version ≥ 11.5.1. Trusted publishing needs these, and
  Node 22 bundles npm 10. JOUST installs `npm@latest` unpinned; JuLC does not.
- **What it doesn't do.** It runs no Gradle, no `npm install` and no package scripts, and it does not check out or build
  the code. It downloads the tarballs and the checksum file from the GitHub release's assets, and verifies them.
- **Publishing.** It publishes in dependency order: `@julclang/wasm-client`, then `@julclang/julc-vm-wasm`, then
  `@julclang/julc-wasm`. Each is published with `npm publish <tarball> --access public --tag <tag> --provenance
  --ignore-scripts`.
- **Each package's `package.json`** names the `repository` `github:bloxbean/julc`, which provenance requires.

**Partial publication and retries.**
- npm never lets a version be overwritten. Before publishing each package, the job asks the registry for that
  `name@version`:
  - if it is absent, the job publishes it;
  - if it is present with the same tarball integrity (`dist.integrity` equals the tarball's SHA-512) and with a
    provenance attestation from this workflow, the job skips it.
    - Here the attestation is read from the registry's metadata: its predicate's repository, workflow and ref. Its
      signature is verified later, by `npm-verify`.
  - if it is present with different contents, or without that provenance (for example, published by hand), the job
    fails and publishes nothing more.
- If a publish fails with "cannot publish over the previously published version", the registry was slow to show the
  version. The job then checks integrity and provenance as above, and counts a match as success.
- A retry is the same job, run again on the same files.
- The job outputs `published = true` when it published or skipped (as verified) every package. For a dry run, such as
  a release candidate (D8), it outputs `published = false`.

**A `wasm-package-checks` job** (M1) runs the browser CSP smoke, the TypeScript checks, and the package smoke on Node
22.3.0 and 24 against the `wasm` job's packed output. It has a read-only token, no secrets and no Gradle, and
`github-release` waits for it.

**A separate `npm-verify` job** checks what the registry serves, after `npm-publish`.
- It runs only when `npm-publish` reports `published = true`. A dry run installs nothing and looks up no provenance.
- It has only `contents: read`: no `id-token`, no environment and no secrets.
- It installs the three exact versions into a fixture with `--ignore-scripts`, and runs `npm audit signatures`.
- It downloads each published tarball (`npm pack name@version`) and compares it with the release asset. That asset is
  itself verified against its attestation, and, in a normal run, against the `wasm` job's digest outputs, so the final
  check does not rest on the release alone.
- It requires each version to carry a provenance attestation whose identity is this repository
  (`bloxbean/julc`), this workflow file (`.github/workflows/native-image-release.yml`) and this tag
  (`refs/tags/v<version>`). It verifies the attestation's signature (`npm audit signatures` checks the attestations
  that are present) and reads its identity from the verified bundle. `npm audit signatures` alone would accept a
  version that has no attestation.
- Any difference fails the job and the release record.

**Bootstrap, once per package name, done by the maintainer:**
- npm configures a trusted publisher per package, and a package must exist before one can be configured. So for each new
  name the maintainer publishes a placeholder version by hand, with 2FA, as JOUST did.
  - JOUST used `0.0.0-oidc-bootstrap.0` on a `bootstrap` dist-tag. It briefly became `latest`, until the first real
    version replaced it.
- The maintainer then configures the trusted publisher:
  - repository `bloxbean/julc`;
  - workflow file `native-image-release.yml` (npm validates that the publish command is in that file);
  - environment `release-staging`;
  - **with direct `npm publish` allowed.** npm always allows staged publication, but direct publishing must be chosen.
- **Timing.** A newly configured trusted publisher expires unless its first publication succeeds within 2 days (npm's
  trusted-publishers documentation). So the configuration is done immediately before the first real release, after
  M4's dry runs. If it expires, it is created again.
- **Tokens are refused from the start.** The maintainer sets each package to require 2FA and to refuse tokens right
  after publishing its placeholder, if npm allows this before a trusted publisher exists. Otherwise it is set as soon
  as the trusted publisher is configured. Either way, no token-publishing window stays open until the first release.
- **Once the first real version is published,** the maintainer deprecates the placeholder version, so `latest` can
  never fall back to it.

**A bad release:**
- It is deprecated (`npm deprecate`), and `latest` is moved back to the previous good version (`npm dist-tag add`)
  until a fixed version is published.
- The maintainer does both by hand, with 2FA. Managing dist-tags through trusted publishing is a separate permission,
  off by default, and it stays off.
- It is not unpublished, because that breaks every project that depends on it, and npm allows it only within 72 hours
  and without dependents.
- A wrong result from `vm.applyParams` is a security issue, because funds can be locked or misdirected. It also gets a
  GitHub security advisory.

**The build's own inputs.** The `wasm` job downloads Binaryen, which optimises the engines npm ships. The download is
pinned by SHA-256, as JOUST pins it.

**A dry run** (`npm publish --dry-run`) checks only the package contents and tags. It proves neither authentication nor
provenance; only the first real publication does (M4).

## Invariants

- **I1 (identity).**
  - For a version, the `engine/` launcher, module and `engine.json` extracted from each engine package's tarball equal
    the files extracted from that version's `julc-{vm-,}wasm-<version>.tar.gz`, byte for byte.
  - both copies of the manifest in `engine.js` equal `engine/engine.json`.
  - The three npm tarballs are packed once, in the build job. What npm serves for that version is exactly those
    tarballs, checked before publishing (SHA-256 and npm's integrity) and again from the registry after publishing.

  Constrains D2, D8a.
- **I2 (one build).** An engine package depends on exactly its own version of `wasm-client`. At run time the client
  refuses to run unless all of these agree:
  - the engine's `api`, `variant` and `groups` with its manifest;
  - the engine's `features().julcVersion` with the client's own `CLIENT_VERSION`;
  - for an engine whose `variant` is `full` or `vm`, the engine's `version()` with both;
  - a preloaded runtime's stamp with the image's version, checked by the image before the runtime is used (D2);
  - the files the worker reports it loaded with the ones the client named (D2a, r18).

  The client compares against its own snapshot, taken before any worker or adapter sees the request (D2a). Constrains
  D1, D1a, D2, D2a.
- **I3 (VM image contents).** The VM engine contains no compiler, decompiler, JavaParser or cardano-client-lib code,
  including after `vm.applyParams` (the existing `-H:AbortOnTypeReachable` guards). Constrains D6.
- **I4 (application is exact).** For any request `vm.applyParams` accepts (D6a):
  - Its `compiledCode` and `scriptHash` equal those of the reference request, a `vm.decode` call built by this
    adapter: `script.language` set to the request's `language`, then `script.params` set to the values. They also
    equal the JVM's `ScriptDecoder.decode` with the same input. This holds whenever the legacy reader reads the values
    the same way. It does not when a map has a duplicate key, which the legacy reader drops. This is the same code
    path, so it guards against regressions only. The independent checks are the ones below.
  - The values are compared in the canonical `Data` encoding (D6), and zero parameters return the input's bytes
    (D6a).
  - For any accepted script within the pinned Scalus oracle's domain, Scalus applying the same values gives the same
    `cborHex` and hash. Scalus is an independent implementation, already a test dependency of
    `julc-cardano-client-lib`.
    - Scalus 1.1.0's domain excludes constructor indices of 2⁶³ or more. For such a canonical script it fails ("Expected
      Long but got OverLong").
    - Valid JuLC inputs are not narrowed to suit it. That range is covered by Aiken's UPLC encoder and decoder, if M2
      confirms that they handle the full unsigned 64-bit range, and otherwise by the Python hash with a JuLC decode
      round trip.
  - For an Aiken blueprint:
    - `cborHex` equals the `compiledCode` that `aiken blueprint apply`, run independently, writes;
    - `compiledCode` equals that value wrapped once more as a CBOR byte string;
    - the two hashes are equal.
  - `scriptHash` equals blake2b-224 of the language tag and `cborHex`, computed independently.

  Constrains D6, D6a.
- **I5 (the schema check is sound and complete on its domain).** For a well-formed schema in D6b's domain, the check
  accepts exactly the values that conform, and reports a mismatch at the first wrong path. A schema outside the domain,
  or ill-formed, is reported as an invalid schema, before and regardless of any value. It never passes. Constrains D6b.
- **I6 (same answers everywhere).** The packages give the same results in a browser, in Node 22 and in Node 24, and the
  same as the release archives, when installed from their tarballs. Constrains D2, D3.
- **I7 (no install-time code).** No package has `preinstall`, `install` or `postinstall` scripts. The publish job runs
  no build, no install and no package scripts. The verify job installs with `--ignore-scripts` and holds no
  credentials. Constrains D8a.
- **I8 (loads as installed).** An engine package installed from its tarball loads without the caller passing flags or
  changing files:
  - in Node 22 and Node 24, both on the main thread and inside an application's own `worker_threads` worker;
  - from Vite and webpack production builds;
  - from each server bundler that M0 recorded as passing;
  - in a Chrome extension.

  Constrains D2, D2a, D3.
- **I9 (archive compatibility).** An unchanged archive application, calling `createJulc({baseUrl})` and then any
  operation or `rest(…)`, behaves as it does on the previous release. Constrains D2a, D4.
- **I10 (loading confinement).** In archive mode the worker loads only the plain launcher file name and
  `julc-runtime.js` next to the manifest (ADR-064 I4). In package mode it loads only launcher, module and runtime URLs
  that pass D2a's checks. No mode loads a path taken from the
  manifest's `launcher` field. Constrains D2a.
- **I11 (no rebuild, no overwrite).**
  - A publication or a retry uses only the tarballs packed by the build job. They are taken from the GitHub release
    and verified against their attestation (and, in the same run, the digest outputs).
  - A tag's release is built once, as long as its release and assets are not deleted. Deleting and recreating a
    release, or its assets, is forbidden by the release procedure (D8a).
  - Once npm holds a version, npm is the durable record. `npm-verify` compares the registry's tarballs with the
    release assets, so a recreated release that differs is detected.
  - A completed release asset is never replaced. Only an incomplete upload's `starter` placeholder is ever removed.
  - A version already on npm with different contents, or without this workflow's provenance, stops the job.

  Constrains D8a.
- **I12 (provenance).** Every real release version of the three packages carries a verified provenance attestation
  naming `bloxbean/julc`, `.github/workflows/native-image-release.yml` and the release tag. The sole exception is each
  package's hand-published bootstrap placeholder (D8a), which is deprecated. The `npm-verify` job checks it for
  every version, including those a retry skipped. Constrains D8a.

## Alternatives rejected

- **One package with both engines.** Every application would download both engines, about 49 MB as built at M3, to use one.
- **Two self-contained packages, each with its own copy of the client.** Embedders such as JOUST would still need a
  third copy. An application that installs both engines would carry two clients that could drift apart.
- **Publishing the archives' contents as they are.** That would publish Node support that doesn't work, assets
  bundlers cannot find, and the generated type names (Context, items 1–5).
- **ES modules all the way, launcher included** (r1's D2). The launcher's lexical `require` is gone in an ES module, and
  loading fails ("Failed to parse URL"). reviewer1 reproduced this on Node 22.12.0 with the pre20 VM engine, while the
  same engine under CommonJS loaded. Rewriting the launcher instead would break I1.
- **Publishing from the build job** (r1's D8). It would give a job that runs the whole Gradle and npm build either an
  OIDC token or a publish token, against the workflow's own boundary. `--ignore-scripts` limits what `npm publish` runs,
  not what the build already ran.
- **Rebuilding on retry.** Web Image output is not guaranteed byte-identical across builds (ADR-057, open question 4).
  So a rebuild could publish packages that differ from those already on npm, or from the archives.
- **A token fallback for publishing.** JOUST runs without one. A token is a long-lived secret that trusted publishing
  makes unnecessary.
- **A client-side `applyParams` in JavaScript.** It would need a second implementation of FLAT encoding, parameter
  application and the CIP-57 checker, for the JVM to differ from. One Java implementation in the engine keeps I4
  checkable against `ScriptDecoder` and Aiken.
- **Checking parameters with `BlueprintValidator`'s `json-schema-validator`.** It checks JSON documents against JSON
  Schema, not Plutus data against CIP-57's data schemas. It is also not in the VM image (I3).
- **Constructor-index dispatch as the meaning of `anyOf`.** It is wrong for primitive branches and for branches that
  share an index (D6b).
- **Making `rest` opt-in everywhere** (r1's D4). That would break archive applications that call `rest` (I9).
- **Every prerelease on `latest`, as JOUST does,** or release candidates on `next`. Semver orders a candidate above its
  final release, so either way ranges would keep choosing the candidate (D8).
- **`@julclang/wasm-client` as a peer dependency.** npm installs peers itself, but it only warns on a version mismatch.
  An exact dependency, plus the run-time check (D1a), is stricter.
- **One package with sub-path exports for both engines.** Every install would carry both engines, about 49 MB, as with one
  package.
- **Keeping a non-canonical input's original bytes, or splicing the application onto them.**
  - FLAT encodes an application before its function, so applying a value cannot simply append bits.
  - Splicing would mean re-encoding the prefix and shifting the original term by a bit offset: a second, bit-level
    encoder, for the JVM and Aiken to differ from.
  - Refusing non-canonical input (D6a) gives no wrong hash, and M2 records which toolchains it affects.
- **Fetching the engine at run time from a CDN, with subresource integrity.** It would mean no offline install, a CSP
  allowance for another origin, and two sources of truth for one version. It could be added later, beside the packages.

## Affected stages and modules

- `julc-wasm`:
  - `julc-wasm.js`:
    - the package-mode options `launcherUrl`, `moduleUrl` and `expectedVersion`, and the `rest` option (D1a, D2, D4);
    - the version check (D1a);
    - the worker's construction in the form bundlers recognise (D2);
  - `julc-worker.js`: the two loading modes and their checks (D2a), and the adapter only when `rest` is set (D4);
  - `JsApi` and `julc-runtime.js`: `features().julcVersion` (D1a);
  - the generated `CLIENT_VERSION` constant (D1a);
  - the shipped Node bridge, both halves (D3);
  - `VmApi`: `vm.applyParams` (D6);
  - `TypeScriptDeclarations` and the declaration files: the aliases, `WorkerLike`, `PackageClient` (D2, D4, D5);
  - `build.gradle`: the package directories, the CommonJS `engine/` directory, the generated `engine.js`, and `npm
    pack` (D2, D8a).
- `julc-tools`: the CIP-57 data-schema checker and the `applyParams` service method (D6, D6a, D6b).
- `.github/workflows/native-image-release.yml` (D8a):
  - `wasm`: packing, the identity check, the digest outputs, the build-once check, and the Binaryen pin;
  - `github-release`: the create-once attachments;
  - the new `attest`, `npm-publish` and `npm-verify` jobs;
  - a `workflow_dispatch` recovery entry.
- `gradle.properties`: `npm_publish = false`.
- Docs: `docs/src/content/docs/guides/javascript-wasm.md` gains an npm section; the release notes.
- Not affected: the compiler, VM, stdlib, ledger API, script bytes and budgets.

## Compatibility

- **Release archives:** unchanged in behaviour (I9).
  - The client and worker gain package mode, which archives don't use.
  - The `rest` option defaults to the current behaviour.
  - The version check uses the archive's own manifest.
- **ADR-064 I4:** kept for archive mode. Package mode is new and separately confined (D2a, I10).
- **ADR-064 `EngineClient`:** unchanged in archive mode. Package mode returns `PackageClient`, without `rest` (D4).
- **`vm.decode` with `params`:** unchanged, guesses and warnings included. `vm.applyParams` is a new operation, so
  engines and clients from earlier versions don't have it. `features()` does not advertise it separately, because I2
  pins the versions.
- **Type names:** additive. Code written against the archives' generated names keeps them through
  `@julclang/wasm-client/models`.
- **Embedders (ADR-064):**
  - An embedder image that calls `VmApi.register`, as the demo fixture does (`DemoLangWasm`, line 31), gains
    `vm.applyParams` and the checker's code with the new `julc-wasm` version.
  - **The npm client works only with engines built on the identical published JuLC version** (D1a).
    - An engine built on another JuLC release, or before `features().julcVersion` existed, is refused before the
      client returns.
    - An embedder therefore keeps the client that matches its engine: the files from the same `julc-wasm` jar. Or it
      rebuilds its engine on the new JuLC release and moves to that release's client.
    - An embedder built on a JuLC release candidate or snapshot uses the jar's client files, because npm never gets
      those versions (D8).
  - An embedder's TypeScript types should describe the operations its engine has (`Operations<T>`), not `VmClient`'s.
  - Its archive-style loading is unchanged, and it may use package mode and pass `expectedVersion`.
  - ADR-064's I3 (the jar's client files equal the bundles') extends to the npm package: for a version, every file that
    `@julclang/wasm-client` and the `julc-wasm` jar both contain is byte-equal, including the stamped
    `julc-runtime.js` (now also in the jar's `META-INF/julc-wasm/js/`), `julc-worker.js`, `julc-wasm.d.ts` and
    `julc-models.d.ts`. There are two exceptions (D4):
    - `julc-wasm.js`: the archives' generated single file, against the package's split entry;
    - `client-defaults.d.ts`: `rest: true` against `rest: false`.
  - **An embedder that uses the worker must ship the jar's `julc-runtime.js` beside its manifest.** The worker preloads
    it, and a missing file is a start-up error that names it. It is not optional.
  - A main-thread embedder that preloads nothing keeps the evaluation path. It needs `'unsafe-eval'`, and keeps working
    beside other engines on the same global (D2).
    - The package adds the Node bridge.
    - The jar also has `rest-adapter.js`.

## Risks

- **Supply chain.** A compromised package runs in every application that installs it.
  - Mitigations:
    - publishing only from a gated job without a build or a token (D8a);
    - provenance;
    - no install scripts (I7);
    - the identity checks (I1, I11);
    - the client's run-time checks (D1a, D2a).
  - The scope's maintainers and 2FA are the developer's to keep, as for JOUST.
- **Size.** Installs carry about 14.5 MB (VM) or 34.5 MB (full) of engine, as built at M3; pre20's were 14.3 MB and
  24.5 MB. Each README states the size, generated at build time, and the first-load time:
  - M3's fixtures measured about 0.2 s (VM) and 0.35 to 0.45 s (full) to ready, with the module cached;
  - Yaci Viewer measured about 0.7 s for the full pre20 engine (#244).

  The full engine grew by about 10 MB after pre20, while the VM engine grew by about 0.2 MB. The probable cause is
  ADR-068's move from JavaParser to javac, which is not in pre20: the full module now contains `com.sun.tools.javac`
  classes. The growth was not measured per component. The release notes for the first published version state it.
- **Browser support.** The engines need WebAssembly GC and exception handling (`exnref`). They are tested in Chrome
  (`julc-playground/BUILD_FROM_SOURCE.md`). The READMEs state the requirement, and M3 records which browser versions
  were tested.
- **A Content Security Policy** that blocks WebAssembly compilation stops the engine loading. A Chrome extension sets
  the policy in its manifest (D2). Tested at M3 in Chromium 156.0.8078.4.
  - From M1 the engines need `script-src 'self' 'wasm-unsafe-eval'`. M0 found that pre20 needs `'unsafe-eval'`,
    because of the runtime's `eval`, which D2 removes.
  - The README documents the expression.
- **I1 covers the package as installed, not an application's bundle.** webpack's default Terser minifies the
  launcher. The engine still works, but the bytes an application serves are no longer the attested ones. The README
  shows the configuration that keeps them identical (D2). Firefox and Safari are not yet tested (M0).
- **Checks that need a browser or extra Node versions** (the CSP browser smoke, the TypeScript checks, and the Node
  22.3.0 and 24 package runs) run in CI's `wasm` jobs (M1). The Node binaries are checksum-verified, and Playwright is
  installed with `--ignore-scripts`.
- **Fixture tooling drift.** The fixtures' bundler toolchain is installed from a committed lockfile with
  `npm ci --ignore-scripts`, so a check runs the same compiler and minifier code on a retry. A broken upstream
  release is fixed by updating the lockfile, not by re-resolving dependencies in the release job (M3).
- **TypeScript floor.** The declarations use `export type *`, so they need TypeScript 5.0 or later. The READMEs state
  this, and a type-check project runs on TypeScript 5.0.x (M3).
- **Bundler behaviour changes.** The worker and asset patterns depend on how Vite and webpack recognise
  `new URL(…, import.meta.url)`. Mitigated by building fixture applications in CI, in development and production modes,
  from the packed tarballs (M1, M3).
- **API churn under version 0** (D7). Mitigated by listing every change in the release notes.
- **Version ordering.** Semver sorts `pre100` below `pre20` (D8). Releases up to `pre99` sort correctly. Open question
  8 records the choice to make before then.
- **`vm.applyParams` mistakes are expensive.** A wrong hash means funds sent to the wrong address. Mitigated by:
  - refusing guesses (D6a);
  - the always-on checks;
  - I4 and I5, with the Aiken oracle and the fixed vector;
  - checking schemas by default.

## Implementation milestones

**M0: worker and bundler experiment** (D2, open question 2).
- Entry gate: this ADR accepted.
- Work: a throw-away prototype of `wasm-client` and the VM engine package, packed with `npm pack`. It is tried as a
  classic and as a module worker, in:
  - Vite in development and production modes;
  - a webpack production build;
  - plain `<script type="module">`;
  - Node 22 and Node 24, including the launcher loaded through `vm.compileFunction` with an injected `require` (D3);
  - one real server bundle for each server bundler to be claimed: webpack `target: node`, Next.js and esbuild.
- Exit:
  - Open question 2 decided, with the evidence recorded in this ADR's revision history.
  - The CSP source expression the engines need, recorded.
  - D3's load path chosen (`vm.compileFunction` or the CommonJS directory), and the list of server bundlers with a
    passing fixture recorded.

**M1: `@julclang/wasm-client`** (D1a, D2, D2a, D3, D4, D5).
- Entry gate: M0 done (open question 1 is decided).
- Work:
  - package mode and its checks;
  - the version check;
  - the `rest` option;
  - the shipped Node bridge with the `exnref` handling;
  - the worker in M0's form (classic);
  - the runtime loaded as a file before the launcher, and `JsApi.install` using it (D2);
  - the shim as a plain file, on the `vm.compileFunction` path (D3);
  - prompt start-up errors. A Java `main` that throws, or a worker script that fails to load, rejects at once with
    the cause. M0 saw both wait out the 120 s load timeout instead;
  - `WorkerLike` and `PackageClient`, and the type aliases.
- Exit:
  - The existing client and worker tests pass, including ADR-064 I4's.
  - New negative loading tests pass (I10): another origin, `data:`, `blob:`, `javascript:`, credentials in the URL, a
    wrong extension, `baseUrl` with `launcherUrl`, a manifest `launcher` with a path in package mode.
  - The version-mismatch tests pass (I2):
    - a wrong engine;
    - a wrong manifest;
    - an engine and wrapper of one release run with an installed client of another, through `overrides`;
    - an embedder engine whose `julc-wasm` build differs from the client's.
  - The unchanged-archive-consumer fixture passes, including `rest` (I9).
  - The type checks under NodeNext without DOM, and under bundler with DOM, pass with `skipLibCheck: false`.
  - The alias coverage test passes.
  - Both engines, with the real preload, load when `Content-Security-Policy: script-src 'self' 'wasm-unsafe-eval'` is
    on the page and on the worker's, launcher's and runtime's responses. A probe confirms that string evaluation is
    blocked in the worker. A negative control without `'wasm-unsafe-eval'` fails.
  - The runtime identity cases:
    - a matching preload is used;
    - a mismatched or unstamped preload is refused, with no evaluation;
    - no preload falls back to the embedded copy;
    - with no preload recorded:
      - two engines of different builds on one global both work, the second evaluating its own copy;
      - an engine loaded after a pre-r12 engine, which left an unstamped runtime, works;
    - with a preload recorded:
      - a malformed preload (the wrong shape, no `install`) is refused;
      - a preload that failed to load is refused;
      - a stale runtime file beside an archive's manifest is refused;
      - a preload reported by the engine as "evaluated its own" makes the client fail.
  - A build-failing check that the stamped runtime is byte-equal across:
    - the image's embedded `/julc-runtime.js`;
    - the jar's `META-INF/julc-wasm/js/julc-runtime.js`;
    - `@julclang/wasm-client`;
    - the archive bundles;
    - the playground.

    It also checks that the stamp equals `julc-wasm-version.properties`. Equal stamps alone cannot detect different
    bytes.
  - The embedder smoke preloads the runtime from the jar's SDK directory.
  - An installed package loads at exactly Node 22.3.0, and on 22.12 and 24, on the main thread and in an application
    worker, including the exception-references path.
  - A start-up failure rejects within seconds, naming its cause.

**M2: `vm.applyParams`** (D6, D6a, D6b).
- Entry gate: this ADR accepted (open question 5 is decided).
  - M2 does not depend on M1: the checker and `VmApi` are Java, independent of the JavaScript client. Only its
    TypeScript declarations wait for M1's aliases.
- Work: the checker, the strict value reader and the preparation rules in `julc-tools`, the `VmApi` operation, and the
  declarations.
- Exit, all tests listed under Verification:
  - I4's differential tests, including the fixed vector;
  - the Aiken oracle;
  - the option matrix of D6a, each row, including `schemaChecked`;
  - a corpus check of the canonical-bytes rule. It records which of these pass and which are refused:
    - JuLC's output;
    - Aiken v1.1.23, and at least one older Aiken release;
    - a PlutusTx script;
    - scripts already partially applied by Aiken and by Scalus;
  - I5's tests for each keyword, applicator, reference form and cycle kind;
  - the VM image still builds under its guards (I3);
  - a mutation check on each schema rule and each D6a refusal.

**M3: the engine packages** (D1, D2, D8a).
- Entry gate: M1 and M2 merged, M0's server-side bundling result applied (D3).
- Work: Gradle tasks that assemble `@julclang/julc-vm-wasm` and `@julclang/julc-wasm` from `wasm-bundle-{vm,full}`,
  with the CommonJS `engine/` directory and the generated `engine.js`, plus `npm pack`.
- Exit:
  - `npm pack` contents are listed and checked.
  - I1 is checked against locally built archives.
  - I6 and I8 hold for both engine packages, installed from their tarballs, including the per-bundler server-bundle
    fixtures M0 recorded and the Chrome extension fixture (D2a).
  - READMEs with size, first-load time, tested browser, TypeScript floor and CSP notes.
  - The launcher's byte identity is asserted per fixture, with a negative control, and the redirect test passes
    (Verification "Packages").
  - The fixture tooling installs from committed lockfiles.

**M4: publishing** (D8, D8a).
- Entry gate:
  - M3 merged;
  - the maintainer has published a placeholder for each of the three names;
  - `npm_publish` stays `false` until the maintainer decides.
- Work:
  - packing and checksums in `wasm`;
  - the attachments in `github-release`;
  - the `npm-publish` job, with its gate, `needs`, OIDC permissions, ordering, retry rules and dist-tags;
  - the `npm-verify` and `attest` jobs, the build-once check in `wasm`, and the `workflow_dispatch` recovery entry;
  - pinning the `wasm` job's Binaryen download by SHA-256 (today it pipes `curl` into `tar`, lines 200–204).
- Exit:
  - A release candidate's run produces the tarballs and checksums.
  - Its publish job, in dry-run mode, lists the expected files and tags.
  - The retry rules pass against a local registry, for these cases:
    - already published and identical;
    - already published and different;
    - a failure after the first package.
  - The recovery sequences, against a test repository:
    1. Attempt 1 releases and attests bytes A, then fails in `npm-publish`. "Re-run failed jobs" in attempt 1
       publishes A.
    2. Attempt 1 fails the same way. A whole-workflow re-run stops in `wasm` before building, so no bytes B ever
       exist. The `workflow_dispatch` entry on the tag then publishes A.
  - Dispatch negatives:
    - a dispatch from a branch is refused;
    - a tag that does not match the version is refused;
    - an attestation from a modified workflow fails verification;
    - a dispatch on a release-candidate tag, or with `npm_publish = false`, publishes nothing.
  - A normal run whose `attest` or `github-release` fails does not publish. An `attest` failure creates no release.
  - The job conditions are tested against the real dependency graph: on dispatch, `npm-publish` and `npm-verify` run
    although their `needs` were skipped. In a normal run, they don't run after a failed prerequisite.
  - Deleting a release asset after publication is detected by `npm-verify`.
  - Release assets edited between jobs fail attestation verification.
  - An interrupted upload that leaves a `starter` placeholder (a simulated HTTP 502) is cleaned up and retried with the
    original bytes. A differing completed asset still stops the job.
  - A release candidate's dry run runs no `npm-verify`: it installs nothing and looks up no provenance.
  - Then, immediately before the first real release, the maintainer configures each trusted publisher, with direct
    publishing allowed (D8a, Timing). The first real publication follows within 2 days, by the maintainer's decision.
  - That publication is the only proof of authentication and provenance. Its tags, provenance (I12) and registry
    contents are checked by `npm-verify`.

**M5: documentation and adopters.**
- Entry gate: M4's first real publication.
- Work: the npm section of `javascript-wasm.md`, release notes, and an `applyParams` example.
- Exit: the guide's examples run against the published packages, in a browser fixture and in Node.
- Optional, in JOUST's own repository and by its decision: `@julclang/joust-runtime` moves its loader onto
  `@julclang/wasm-client`.

## Verification

Independent evidence, not values the code under test produced:
- **Application (I4).**
  - **The fixed vector.** Apply the integer 42 (`182a`) to `(program 1.1.0 (lam p (con unit ())))`, with
    `language: 'V3'` (D6a). Expect:
    - `cborHex` `4c0101003249930102182a0001`;
    - `compiledCode` `4d4c0101003249930102182a0001`;
    - `scriptHash` `36f2b62169a767e3bcb49a0ff8dd3f473db91e15434ac69ace324ac6`.
  - **Generated cases.**
    - Compile a parameterised validator from `julc-examples` with JuLC, and a parameterised Aiken validator with Aiken
      v1.1.23.
    - For random conforming parameter lists, including zero parameters and partial lists, `vm.applyParams` must match
      `vm.decode` with `params` and the JVM's `ScriptDecoder.decode`.
    - For the Aiken blueprint, it must match `aiken blueprint apply` run as a separate process, compared at the layers
      I4 names.
  - **Independent hash.** Hashes are checked with Python's `hashlib.blake2b(digest_size=28)` over the language tag and
    `cborHex`.
  - **Scalus.** For JuLC-made scripts, Scalus applies the same values, and its bytes and hash are compared at the same
    layers.
  - **Aiken's own schema check.** `aiken blueprint apply` checks the value against the blueprint's schema. The values
    it refuses for an Aiken blueprint must also be refused by `vm.applyParams`, and the ones it accepts must also be
    accepted.
  - **Cross-language vectors.** The same raw script with `language` V1, V2 and V3 gives identical bytes and three
    different hashes. A request without `language` is refused.
  - **Cross-checks.** A stated `language` that disagrees with an envelope's `type`, a blueprint's
    `preamble.plutusVersion`, or the blueprint hash is refused, naming both. When the hash matches another language,
    the error names that language.
  - **Language literals.** These are refused:
    - `language` given as `v3`, `notV3`, `auto`, null or absent;
    - a non-empty `script.language`;
    - an envelope `type` of `PlutusScriptV4` or `SimpleScript`, and a `plutusVersion` of `v4`, whatever `language`
      says.
  - **The reference adapter.** Raw FLAT `0101002499` with the integer 42:
    - `language: 'V2'` gives `c32369659a87707014447455f64c1dcf338d7a44748fb6b45a220439`, the same as the adapted
      `vm.decode` call;
    - `language: 'V3'` gives `36f2b62169a767e3bcb49a0ff8dd3f473db91e15434ac69ace324ac6`.
  - **Strict JSON.** These are refused, in both member orders:
    - an envelope with two `type` members;
    - a blueprint with a duplicated member, at the top and nested;
    - a value `{"int":1,"int":2}`;
    - trailing JSON documents.
  - **Constructor bounds.** Index 2⁶⁴ − 1 is accepted, and 2⁶⁴ refused, at the top and nested, given as structured
    values and as JSON text. The applied FLAT of the accepted case decodes. The canonical script with index 2⁶⁴ − 1,
    `010100324993010dd866821bffffffffffffffff800001`, is checked against the oracle that covers that range (I4).
  - **Encodings.**
    - `58050101002499`, which wraps canonical FLAT in a non-minimal CBOR header, and an indefinite-length wrapper are
      refused.
    - A `cbor` value in a non-canonical encoding (`d8799f01ff` is canonical for a one-field constructor, because
      non-empty fields are indefinite arrays; `d8798101` is not) is refused; the canonical one is accepted.
    - Strict values: `{"int":1.5}`, `{"constructor":"bogus","fields":[]}`, two JSON documents, an object with two
      shapes, and odd-length hex are refused. A map with a duplicate key keeps both entries, in order.
  - **Preparation (D6a).** Negative tests for each refusal:
    - a request without `language`, including for a program at UPLC 1.1.0, and for a blueprint whose preamble
      states the language;
    - a `language` that disagrees with the envelope, the preamble or the hash;
    - UPLC text input;
    - a blueprint with a duplicated title, and a numeric selection;
    - several validators without a selection;
    - a blueprint hash that does not match;
    - values in `script.params`;
    - a `ScriptDecoder` warning;
    - a builtin parameter schema with `checkSchema: false`;
    - a `DataInput` without a format, or with `auto`;
    - trailing bytes after the script, and a script that does not round-trip through decoding and encoding;
    - a value given to a blueprint validator without `parameters`;
    - a blueprint with several entries and no selection, including entries with identical code but different parameter
      types or counts. Reordering the entries must never change which contract is chosen.
  - A positive case: a single-entry blueprint is used without a selection, and a selected entry of a multi-purpose
    blueprint gives the same script as the other entries with the same code.
- **Schema check (I5).**
  - Values against schemas.
    - For each keyword in D6b's table and each applicator, the tests generate values that conform and values that
      differ in exactly one place. Among them: wrong constructor indices, wrong field counts, positional `items` of
      the wrong arity, bounds at and either side of the limit, duplicates under `uniqueItems`, and `oneOf` with two
      matching branches.
    - Each must be accepted or rejected as expected, with the path at the difference.
  - **Invalid schemas.** Each must be reported as an invalid schema, before any value is checked:
    - an unknown keyword;
    - a malformed keyword value;
    - a keyword with the wrong data type;
    - a missing definition, or an external `$ref`;
    - a self cycle (`A → A`), a mutual cycle (`A → B → A`), and cycles through `not` (`S = not S`, `S = not(not S)`).

    The same faults placed under `not` and inside an `anyOf` branch must still be reported as invalid schemas.
  - **Accepted, as they should be:**
    - productive recursive types, such as a list type and a tree type defined through constructor fields, a recursive
      sum type whose branch edges don't consume (`Tree = anyOf[Leaf, Node(fields: [$ref Tree])]`), and recursion through
      an alias (`A = $ref B`, `B = anyOf[Nil, Cons(fields: [integer, $ref A])]`);
    - a definition named `Data` that is a constructor, which must be checked as a constructor;
    - opaque, annotation-only data;
    - names with `~1` and `~0` escapes.
  - **Cost.** The exponential case of D6b (`anyOf` of two branches that recurse, against a list nested 30 deep) ends
    within the memoised bound. The work bound's error is tested separately.
  - **Real blueprints.** The blueprints JuLC generates for every `@Param` type in `julc-examples` must accept the values
    their own tests apply. A sample of Aiken blueprints must accept the values of their own tests.
- **Packages (I1, I2, I6–I11).**
  - SHA-256 equality with the archives, checked in CI.
  - The VM parity fixtures run through each package entry, installed from its tarball, in Node 22, Node 24 and Chrome.
  - Node loading from the main thread and from an application worker, with no flags (I8).
  - Vite (development and production) and webpack (production) fixture builds.
  - The launcher's expected byte identity, asserted for each fixture on disk and as served:
    - identical for Vite's production build, webpack with the README configuration, the Chrome extension, the plain
      page and Next.js with Turbopack;
    - identical or with only a source-map comment added, for Vite's development server;
    - recorded only, for webpack without that configuration.

    A negative control changes the launcher and must fail the assertion (M3).
  - A test that changing the `./engine` exports, the URLs or the manifest, does not redirect a wrapper (M3).
  - The server-bundle fixture for each bundler M0 recorded as passing, and a Chrome extension fixture.
  - The negative loading tests (I10), and the unchanged-archive-consumer fixture (I9).
  - A check that no `package.json` has install scripts.
  - Tests that a client refuses an engine of another release, and that an engine package's matching engine and wrapper
    are refused with an overridden client of another release (I2).
  - The stamped runtime's byte equality across every copy, and its stamp against the build version (D2, M1).
  - The publish job's retry cases against a local registry (I11): identical with provenance (skipped), identical
    without provenance (fails), different contents (fails), "already exists" with a match (success), and a failure
    after the first package.
  - `npm-verify` after the first real publication: the registry comparison, `npm audit signatures`, and the provenance
    identity of each version (I12).
- **No regression.** The existing `julc-wasm` tests, `wasmSmoke{Full,Vm}`, the embedder smoke test and the
  playground's static build keep passing.

## Open questions

All questions are decided. M0's experiment decided question 2. The maintainer decided the others on 2026-11-16,
choosing the recommendation recorded with each.

1. **Package names.** `@julclang/wasm-client`, `@julclang/julc-vm-wasm` and `@julclang/julc-wasm`, or shorter ones
   (`@julclang/vm`, `@julclang/julc`)?
   - **Decided** (maintainer, 2026-11-16, r3): the proposed names, which match the archive names.
2. **The worker form.** A classic worker, which keeps `importScripts`, or a module worker with another way to load the
   CommonJS launcher?
   - **Decided** by M0's experiment (r11): a classic worker. The evidence is in `adr/evidence/077-m0/REPORT.md`.
3. **Addresses.** Should `applyParams` also return a bech32 address for a network ID?
   - The VM image has no cardano-client-lib, so this would need a small bech32 encoder.
   - **Decided** (maintainer, 2026-11-16, r3): no; return the hash, and leave addresses to the application's Cardano library.
4. **Evaluation from transaction CBOR (#244, request 2).** Should it ship in the VM engine?
   - That needs `JulcTransactionEvaluator` and so cardano-client-lib, which I3 keeps out of the VM image.
   - **Decided** (maintainer, 2026-11-16, r3): the full engine only, in its own ADR.
5. **The default for partial application.** Is refusing fewer values than declared, unless `partial: true` is given,
   the right default?
   - **Decided** (maintainer, 2026-11-16, r3): yes, because a missing parameter is more often a mistake than intended.
6. **API version 1.** What makes the API stable?
   - **Decided** (maintainer, 2026-11-16, r3): after M0 to M5 and one release of npm use, with the type aliases unchanged across that release.
7. **Assets on another origin.** Should package mode accept launcher and module URLs on a CDN origin other than the
   page's?
   - Workers must be same-origin anyway.
   - **Decided** (maintainer, 2026-11-16, r3): no until someone needs it. Then add an explicit allow-list option, never a default.
8. **Version naming.** Semver compares `preN` as text, so `pre100` sorts below `pre20`. Should JuLC move to
   `0.1.0-pre.N`, which semver compares as a number, before `pre99`?
   - That changes the Maven versions too, so it is a release-process decision.
   - **Decided** (maintainer, 2026-11-16, r3): decide before `pre90`.
9. **Schema checks for inputs without a blueprint.** Should `vm.applyParams` accept the parameter schemas in the
   request, so that a staged application (a partial result, then the rest) keeps being checked?
   - **Decided** (maintainer, 2026-11-16, r3): later, if asked for.

## Revision history

- r1 (2026-11-16): first version.
- r2 (2026-11-16), responding to reviewer1's round-1 review of `6addf2d7` and an internal Codex review of the same
  commit (C1 to C13). Every finding was accepted after checking it against the code.
  - **reviewer1 F1, Codex C6.** The CommonJS engine directory (D2), and Context item 1. I1 is unchanged. I8 requires
    loading from an installed tarball.
  - **reviewer1 F2.** D2a amends ADR-064 I4 for package mode, with its own checks and negative tests (I10, M1). The
    "Changes" header and Compatibility name it.
  - **reviewer1 F3, Codex C8, C13.**
    - D8a packs in the read-only build job and publishes the same tarballs from a gated `npm-publish` job: OIDC, no
      token, no build.
    - It adds ordering, the retry rules, the maintainer's bootstrap, and the point that a dry run is no proof of
      authentication.
    - New invariant I11.
  - **reviewer1 F4, Codex C2, C3.** D6b gains:
    - a normative domain, matching the CIP-57 keyword table now in Context;
    - general applicator semantics;
    - opaque data recognised by structure;
    - local `$ref` rules;
    - a cycle policy;
    - invalid schemas kept apart from mismatches.

    I5 is qualified to that domain.
  - **reviewer1 F5, Codex C5.** Context item 7, D6's result comments, and I4 now compare Aiken at the single-wrapped
    layer, with reviewer1's fixed vector.
  - **reviewer1 F6, Codex C12.** D4 keeps `rest` by default for archive clients and leaves it out of packages
    (`PackageClient`). New invariant I9.
  - **Codex C1.** D6a: `vm.applyParams` refuses an ambiguous language, an unselected validator, a mismatched blueprint
    hash, values in `script.params`, and warnings. I4 is qualified to accepted requests.
  - **Codex C4.** D6a separates always-on checks from the optional schema check, makes builtin parameters always an
    error, and adds the option matrix.
  - **Codex C7.** D2 fixes the worker's construction form and ships both halves of the Node bridge. New milestone M0
    decides open question 2 before M1, which removes the circular gate.
  - **Codex C9.** New decision D1a checks the engine's version at run time. I2 is updated.
  - **Codex C10.** D8's dist-tags are now a stated policy: `-rc` is matched first, deliberately unlike JOUST. The npm
    resolution rules are corrected (caret ranges and prereleases), and the first-publication behaviour is something M4
    checks on the registry.
  - **Codex C11.** `WorkerLike` replaces the DOM `Worker` in the types. The `exports` maps include `./models` and
    `./engine`. The type checks run with and without the DOM library.
  - **An internal Claude review of `6addf2d7` (K1 to K20).** K1, K2, K5, K6 and K11 match the findings above. New
    changes:
    - **K3:** D6a refuses values without an explicit format, and scripts with trailing bytes or that don't round-trip.
    - **K4:** a blueprint validator without `parameters` declares zero; the count and builtin checks are always on.
      (Its "selection only when codes differ" was superseded in r4, by F8.)
    - **K7:** release candidates are not published, because semver orders them above their final release (checked
      with npm's `semver`); open question 8.
    - **K8:** I1 is checked on the packed tarball against the extracted archive, and again from the registry.
    - **K9:** a pinned npm, the bootstrap details, and 2FA with tokens refused.
    - **K10:** publishing only with a GitHub release; deprecation, rolling `latest` back, and an advisory.
    - **K12:** the runtime belongs to the engine. (Superseded in r11 and r12: the runtime is a stamped file that
      ships with the client too.)
    - **K13:** Scalus as a second oracle; same-path checks labelled as regression guards; Aiken's schema check as an
      acceptance oracle.
    - **K14:** the embedder effects, and ADR-064 I3 extended.
    - **K15:** the bundler configuration, found by M0.
    - **K16:** `./package.json`, top-level `types`, `sideEffects`.
    - **K17:** no load queue, since each client has its own worker; `WebAssembly.validate` detection; the flag set
      lazily.
    - **K18:** pre17 had the archives; how #244 relates; `vm.hash`; 1-based parameter numbers.
    - **K19:** the Binaryen download is pinned.
    - **K20:** new alternatives (a peer dependency, one package, a CDN with SRI) and open question 9.
- r3 (2026-11-16): records the maintainer's decisions on open questions 1 and 3 to 9, each as the recommendation it
  already carried. Open question 2 stays with M0's experiment. No design change.
- r4 (2026-11-16), responding to reviewer1's round-2 review of `232df104`:
  - **F7:** D1a gives the client its own `CLIENT_VERSION` and has every engine report `features().julcVersion`. The
    client requires them to be equal, which catches an overridden client. The embedder rule is kept separate
    (`expectedVersion`). I2 and M1's tests are updated.
  - **F8:** D6a requires `script.validator` for any blueprint with more than one entry, whatever their code. The
    verification covers identical-code entries with different parameters, and reordering.
  - **F9:** D6b's cycle rule is now the per-cycle condition: every cycle contains a consuming edge. That supports
    recursive sum types and alias recursion, which r2's rule would have rejected. Cycles through `not` are described as
    a deliberately unsupported subset. Verification adds both recursion cases.
- r5 (2026-11-16), responding to reviewer1's F10 (round 3, approval of `e05d2012`), a Fable review of `e05d2012` (R1 to
  R11) and Codex's second review of `e05d2012` (C14 to C19). Every finding was checked against the code, and against
  npm's trusted-publishers documentation for C18, and accepted.
  - **R1 = C14 (P1):** D6a takes the language only from evidence (request, envelope `type`, blueprint preamble, a hash
    under exactly one language). `version` and `builtins` inferences are refused, because UPLC 1.1.0 is allowed under
    all languages at protocol 11. Adds cross-language vectors; the fixed vector states V3.
  - **C15 (P1):** a strict, lossless value reader for `applyParams`. No coercions, exactly one shape per object,
    integer tokens only, nothing trailing, and map entries kept with duplicates and order. I4 is qualified where the
    legacy reader differs.
  - **R2:** the applied constants' canonical `Data` encoding is part of D6's contract. Non-canonical `cbor` values are
    refused, and the oracles get canonical values.
  - **R3, C16:** canonical bytes at the CBOR-wrapper layer too, so zero parameters return the input unchanged and I4
    holds. Errors name the cause. M2 runs a toolchain corpus check. A new rejected alternative covers keeping or
    splicing original bytes.
  - **R4, F10:** the embedder compatibility consequence is stated: the npm client works only with engines built on the
    identical published JuLC version; release-candidate and snapshot embedders use the jar's client. JOUST's
    description is corrected.
  - **R5:** D2a's browser rule is same-origin with the worker, whatever the scheme (extensions and app schemes work).
    `file:` is accepted only in Node. M0 tests an injected-`require` launcher load for server-side bundling, or the
    README declares it unsupported.
  - **R6:** `rest`'s default is set per distribution (`client-defaults.js`), with where the adapter is loaded from in
    each mode. The I3 extension is reworded.
  - **R7, C19:** `npm-publish` needs `github-release`, and takes its tarballs from the release. A separate `npm-verify`
    job without credentials installs, audits and compares.
  - **C17:** provenance must be present and match this workflow, including for skipped versions. New invariant I12.
  - **C18:** direct publishing must be allowed in the trusted publisher, and the configuration is made immediately
    before the first release, because it expires after 2 days unused.
  - **R8:** memoised checking, O(|schema| × |value|), with a work bound; `allOf` mismatch reporting.
  - **R9:**
    - UPLC text refused;
    - exact unique titles;
    - the builtin check after `$ref`;
    - a `schemaChecked` field;
    - `enum` compared ignoring case;
    - `$ref` siblings limited to annotations;
    - the error codes.
  - **R10:**
    - the tag table covers non-prerelease versions and `beta`;
    - the placeholder is deprecated;
    - dist-tag changes and deprecation are done by hand with 2FA;
    - registry lag;
    - the Binaryen pin is listed in M4.
  - **R11:**
    - the header date;
    - K4 annotated as superseded;
    - M2 independent of M1;
    - M5 gets entry and exit criteria;
    - I2 names the variants;
    - the `dev` case.
- r6 (2026-11-16), at the maintainer's suggestion: `vm.applyParams` takes `language` as a required request field and
  never infers it. Language information in the input (an envelope's `type`, a blueprint's `preamble.plutusVersion`, the
  blueprint hash) is cross-checked against it, and a mismatch is an error. This replaces r5's evidence ranking with a
  simpler, stricter rule. The verification vectors are updated.
- r7 (2026-11-16), responding to Fable's re-review of r6 (approved; R12 to R18) and Codex's round 3 on r6 (C20 to C26).
  Every finding was checked against the code and accepted.
  - **C21 (P1):** the strict reader bounds constructor indices to 0 ≤ index < 2⁶⁴, at every depth and whatever
    `checkSchema` says.
  - **R12:** M0 now contains the `vm.compileFunction` load and real server-bundle fixtures. Server bundlers are
    supported per bundler, by fixture, with a single load path.
  - **R13, C23:**
    - the `wasm` job's digest outputs anchor the release assets;
    - `github-release` is create-once (`overwrite_files: false`, and differing assets refused);
    - a whole-workflow re-run is refused, and recovery is by "re-run failed jobs".
  - **C24:** `npm-verify` runs only when `npm-publish` reports a real or verified publication.
  - **R14, C26, R18:**
    - `language` must be exactly V1, V2 or V3;
    - unrecognised envelope or preamble language information is an error;
    - `script.language` must be empty;
    - the hash-mismatch error names the language that matches;
    - I4's reference adapter carries `language`.
  - **C20:** strict JSON for every input: one document, and no duplicate object members.
  - **C22:** I4 states the pinned Scalus oracle's domain, and covers constructor indices of 2⁶³ or more otherwise.
  - **C25:** I12 is scoped to real releases, and the bootstrap placeholder is the sole exception.
  - **R15:** D2a's browser rule is worded as "a tuple origin". The tested hosts are named, and Electron `file:` pages
    are refused.
  - **R16:** a generated `client-defaults.d.ts`, the jar's default, and the type checks.
  - **R17:**
    - `schemaChecked` in the vacuous cases;
    - the credentials wording;
    - how the attestation identity is read and verified;
    - tokens refused from the start.
- r8 (2026-11-16), responding to Codex's round 4 on r7 (approved; C27, C28) and Fable's re-review of r7 (approved; R19,
  R20):
  - **C27:** the durable anchor is a GitHub artifact attestation, made by a new build-free `attest` job, instead of
    one run attempt's job outputs.
    - A tag's release is built once: `wasm` stops before building when the release already holds attested tarballs.
    - Recovery never rebuilds. It is "re-run failed jobs", or a `workflow_dispatch` entry in the same workflow file,
      which publishes the existing assets after verifying their attestation.
    - M4 tests the full three-attempt sequence.
  - **C28:** create-once distinguishes completed assets from a failed upload's `starter` placeholder, which is deleted
    before the original bytes are retried. I11 and M4 are updated.
  - **R19:** the per-bundler server fixtures and the Chrome extension fixture are in I8, M3's exit and Verification.
  - **R20:** `npm-verify` also checks the release asset against its attestation and, in a normal run, the digest
    outputs.
- r9 (2026-11-16), responding to Codex's round 5 on r8 (approved; C29 to C31) and Fable's re-review of r8 (approved;
  R21, R22):
  - **C29:** recovery is chosen by state. Within the original attempt, "re-run failed jobs" recovers. After a refused
    full re-run, only the `workflow_dispatch` entry does. M4 tests both sequences.
  - **C30, R21:** the dispatch entry's contract:
    - its execution ref must be exactly the tag;
    - the version and gates are read from the tag without Gradle;
    - the build, attest, release and Homebrew jobs are skipped;
    - `npm-publish` has an explicit `if`, with no bare `always()`;
    - verification is certificate-backed (`--signer-workflow`, `--source-ref`), and `attestations: read` is granted;
    - negative tests.

    It also corrects "`release-staging` accepts only `v*` tags": the environment also accepts `main` and
    `release/**`, so the job checks the ref itself.
  - **C31, R22:** `github-release` needs `attest`. I11 is narrowed: built once as long as nothing is deleted, deleting
    and recreating is forbidden, and npm is the durable record once a version is published. M4 covers an attestation
    failure, partial uploads and deleted assets.
  - **R22:** a per-tag concurrency group on `npm-publish`.
  - **Editorial:** I11's run-together bullet is split.
- r10 (2026-11-16), responding to the final confirmations of r9. Both approved, with the same finding: Fable's R23 and
  Codex's C32.
  - `npm-publish`'s condition is now `!cancelled() && (dispatch || normal success)`, because without a status function
    GitHub's implicit `success()` skips a job whose `needs` were skipped. That would have blocked the dispatch recovery.
  - `npm-verify` uses the same form.
  - `attest` is added to `github-release`'s existing prerequisites, which are kept.
  - M4 tests the conditions against the real dependency graph.
- r11 (2026-11-16), M0's results (`adr/evidence/077-m0/`):
  - open question 2 decided: a classic worker;
  - D3's single load path is `vm.compileFunction`;
  - the server bundlers claimed are webpack `target: 'node'` and Next.js with Turbopack; esbuild and Next
    `--webpack` need externals;
  - the bundler configuration needed is documented;
  - `engines` is `node >=22.3`, and the shim is a plain file;
  - the client never builds a URL from an expression.

  **One amendment the evidence forced:** the engine evaluates its runtime from a string, so pre20 needs CSP
  `'unsafe-eval'`. From M1, the worker loads `julc-runtime.js` as a file first, and `JsApi.install` uses it, so
  `'wasm-unsafe-eval'` is enough. The runtime file now ships with the client, and D1a's version check keeps it in
  step with the image. Also: start-up failures reject promptly (M1). The CSP risk is corrected.
- r12 (2026-11-16), responding to the reviews of r11 by Fable (R24 to R27) and Codex (C33 to C36). Both found the same
  P1 (R24 = C33), and every finding was accepted.
  - **R24, C33 (P1):** the runtime is stamped with its JuLC version at build time. `JsApi.install` checks a preloaded
    runtime's stamp against the image before calling it:
    - absent: evaluate the embedded copy;
    - matching: use it;
    - malformed, unstamped or mismatched: a terminal error, with no evaluation;
    - a failed preload: terminal too.

    The engine reports which runtime it used. I2 is updated.
  - **C34, R25:** the stamped runtime is also in the jar's `META-INF/julc-wasm/js/`, and all copies are byte-equal. The
    load locations per mode are named. The runtime is under D2a and I10. Stale text is corrected (Context, the D1
    table, K12). The embedder guidance and the I3 list are updated.
  - **C35:** the CSP must be on the worker's own response, and M1 checks that evaluation is blocked inside the worker,
    with a negative control.
  - **C36, R25:** `engines` is `>=22.3.0` throughout, tested at exactly 22.3.0.
  - **R26:** a preload recorded but not used makes the client fail.
  - **R27:** a Risks note that I1 covers the package as installed, not an application's minified bundle.
- r13 (2026-11-16), from the confirmations of r12. Fable approved, with R28 and R29; Codex approved, with C33's tests
  and C37.
  - **R28:** the strict identity rule applies only when the worker or shim recorded a preload. Without one, a
    non-matching leftover, from another engine's own evaluation or a pre-r12 engine, is treated as absent, and the
    image evaluates its own copy. So main-thread multi-engine embedders keep working. M1's cases are updated.
  - **R29:** the runtime file is mandatory, beside the manifest, for embedders that use the worker, and the
    missing-file error names it. The Changes header notes archive mode's second fixed file name.
  - **C33:** negative tests for a malformed preload, a failed preload, and a preload reported as evaluated.
  - **C37:** a build-failing byte comparison of the stamped runtime across every copy, and its stamp against
    `julc-wasm-version.properties`.
- r14 (2026-11-16), from M1's implementation review (Codex M1-C1 to C3, Fable M1-F1 to F5):
  - **The archives' client is one generated file** (`julc-wasm.js`), with its version and `rest: true` inlined.
    - Unversioned module imports could pair a new engine with a cached old client after a deployment.
    - Every archive script is versioned by `?engine=`.
    - The npm package keeps split files, and ships `client-defaults.js` with `rest: false`.
  - **D4 and the I3 exception list** become `julc-wasm.js` and `client-defaults.d.ts`.
  - **The runtime-provenance report** is keyed by `Symbol.for('julc.runtime')`.
  - **The browser, TypeScript and Node-matrix checks run in CI's `wasm` jobs.**
- r15 (2026-11-16), recording M1 and M2 as built and reviewed:
  - the error-code table, with `work-limit-exceeded` as its own code;
  - the partial-application schema rule;
  - stack safety along the `applyParams` path (an iterative julc-core encoder; its own canonical readers; the VM's
    decoder unchanged);
  - the `wasm-package-checks` release job that `github-release` waits for.

  Evidence: `adr/evidence/077-m2-apply-params-evidence.md` (on the M2 branch).
- r16 (2026-11-17), recording M3 as built and reviewed (Fable M3-F1 to F7, Codex M3-C1 to C3):
  - the Chrome extension's manifest CSP requirement (D2, Risks);
  - the engine sizes as built (full 34.5 MB, VM about 14.5 MB), with the READMEs generating them;
  - the tested Chromium version;
  - locked fixture tooling;
  - the TypeScript 5.0 floor.

  The wrappers take their engine files from `engineFiles()`, which the `./engine` exports cannot change (M3-C1).
  Launcher byte-identity is asserted per fixture, with a negative control (M3-C2).
- r17 (2026-11-17, responds to Fable's review of M3 at 2c790e0c):
  - M3-F8: D2 names the `./engine` subpath as public API, including `engineFiles()` and the frozen `manifest`, and says
    the wrappers use `engineFiles()`. I1 and D8a check both copies of the manifest. r16's "private descriptors"
    was wrong: `engineFiles()` is public.
  - M3-F9: both first-load figures with their conditions; the both-engines total corrected to about 49 MB; the full
    engine's growth since pre20 and its probable cause; the asserted launcher identity and the redirect test added to
    Verification and M3's exit criteria.
- r18 (2026-11-17, responds to Codex's review of M3 at 2c790e0c):
  - M3-C4: D2a adds the client's private identity snapshot and the worker's `loaded` report, and I2 checks the loaded
    files. A forwarding `workerFactory` that rewrote the start message could make `createJulcVm()` accept the full
    engine; the client now refuses it.
  - The threat-model limit is explicit: an adapter that forges the worker's replies is not stopped.
