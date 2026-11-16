# ADR-077: npm packages for the WebAssembly engines, and `vm.applyParams`

**Status:** Proposed (2026-11-16). Design only; no implementation in this change.
**Date:** 2026-11-16 (r1, r2)
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
- ADR-064's **I4** (the worker loads only a plain launcher file name next to the manifest). It is kept for archive
  loading, and a second, separately checked loading mode is added for packages (D2a, I10).
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
- `julc-runtime.js`, a copy of the runtime the tests use. The image installs its own copy from its resources
  (`JsApi.install`), so the runtime belongs to the engine, not to the client;
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
| `@julclang/wasm-client` | The client (`createJulc`, `isFullClient`, sessions), the worker, both halves of the Node bridge, and the declarations. No engine, no REST adapter; the runtime is inside each engine. | — |
| `@julclang/julc-vm-wasm` | The VM engine (launcher, module, `engine.json`) and `createJulcVm()` | `@julclang/wasm-client`, same version, exact |
| `@julclang/julc-wasm` | The full engine and `createJulc()` | `@julclang/wasm-client`, same version, exact |

Reasons for this split:
- An application that only evaluates scripts does not download the 24.5 MB engine.
- An engine an embedder builds (ADR-064), such as JOUST's, can depend on the client alone instead of copying it.

The exact dependency stops npm from pairing an engine package with another release's client. D1a checks the pairing
again at run time, for the cases dependency resolution cannot cover: an application that installs both, or `overrides`.

### D1a. The client checks the engine's release (r2)

- `createJulc` gains an `expectedVersion` option. When it is set, the client compares it with the engine's reported
  `version()` and disposes the worker on a mismatch, as it does today for `api`, `variant` and `groups`.
- `createJulcVm()` and `createJulc()` in the engine packages pass their own package version. JuLC's images report the
  JuLC version, which equals it (D8).
- In archive mode the client compares the manifest's `julcVersion` with `version()` for JuLC's own variants (`full`,
  `vm`).
- An embedder's engine (ADR-064) reports its own version, so for any other variant the check runs only when the caller
  passes `expectedVersion`.

### D2. ES-module entry points, CommonJS engine assets, static URLs

- **Entry points.** Each package's entry points are ES modules (`"type": "module"` at the package root). Each package
  declares `"engines": {"node": ">=22"}`.
- **Engine assets.**
  - An engine package keeps the launcher, the module and `engine.json` in an `engine/` directory whose own
    `package.json` is `{ "type": "commonjs" }`, as JOUST's `joust-runtime/engine/` does.
  - The launcher stays byte-for-byte the archive's (I1) and keeps its CommonJS `require`.
- **Generated `engine.js`.** It sits at the package root and names every engine file statically, so bundlers copy them
  and the browser and Node resolve them next to the module:

  ```js
  // generated at build time (illustrative)
  export const manifest = { /* contents of engine/engine.json */ };
  export const launcherUrl = new URL('./engine/julc-vm-b69c37c29c6b.js', import.meta.url);
  export const moduleUrl = new URL('./engine/julc-vm-b69c37c29c6b.js.wasm', import.meta.url);
  export const version = '0.1.0-pre22';
  ```

- **Entry functions.** `createJulcVm(options)` and `createJulc(options)` pass these values to `wasm-client`'s
  `createJulc` as `launcherUrl`, `moduleUrl`, `manifest` and `expectedVersion`, with `rest: false` (D4). The existing
  `baseUrl` mode stays for the archives.
- **The worker in browsers.** `wasm-client` constructs it in the one form both Vite and webpack recognise, as a single
  expression: `new Worker(new URL('./julc-worker.js', import.meta.url), …)`. Each client owns one worker, as today.
  - Whether it is a classic or a module worker is decided by M0's experiment (open question 2), before M1 starts.
    `importScripts` exists only in classic workers. A module worker would have to load the CommonJS launcher in another
    way, and M0 must prove that way before it is chosen.
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
  - each sets `sideEffects` to the files that have them.
- **Bundler configuration.** Vite pre-bundles dependencies, which moves modules and can break
  `new URL(…, import.meta.url)`, and webpack may parse a `.js` file named that way.
  - M0 finds what configuration each bundler needs, if any. For Vite, `optimizeDeps.exclude` is the likely one.
  - The READMEs state it.
  - The fixtures test both the development server and the production build.
- **Types for both environments.**
  - `workerFactory` takes a small `WorkerLike` interface (`postMessage`, `terminate`, `onmessage`, `onerror`) instead of
    the DOM's `Worker`.
  - The declarations must type-check from an installed tarball:
    - with `moduleResolution: "NodeNext"` and no DOM library;
    - with `moduleResolution: "bundler"` and the DOM library;
    - in both cases with `skipLibCheck: false`.

### D2a. Two loading modes, each confined (amends ADR-064 I4; r2)

- **Archive mode** (`baseUrl`, the manifest's `launcher` field): unchanged. The worker loads only a plain file name next
  to the manifest: lowercase words joined by hyphens, a 12-hex-digit hash, then `.js`, with no path, query or scheme
  (ADR-064 I4).
- **Package mode** (`launcherUrl` and `moduleUrl` given):
  - The URLs come only from the caller, normally from an engine package's `engine.js`. In this mode the manifest's
    `launcher` field is ignored, never read as a path.
  - The worker accepts a URL only when all of these hold:
    - its scheme is `https:` or `http:` and its origin equals the worker script's origin (browsers), or its scheme is
      `file:` (Node);
    - it has no user name or password;
    - the launcher's path ends in `.js` and the module's in `.wasm`.
  - Bundlers may rename the files, so the file-name pattern is not checked in this mode. Its role passes to the
    identity checks the engine must pass after loading: `api`, `variant` and `groups` against the manifest, and the
    version (D1a). A mismatch disposes the worker.
  - `data:`, `blob:`, `javascript:` and other schemes are refused, as are other origins (open question 7).
- **No mixing.** A client is in exactly one mode. Giving `baseUrl` together with `launcherUrl` or `moduleUrl` is an error.

### D3. Node.js is supported, through a shipped bridge

- `wasm-client`'s `node` entry ships both halves of the bridge the tests prove today, as production code:
  - the parent-side adapter, which turns a `worker_threads` `Worker` into the `WorkerLike` the client drives
    (`wasm-smoke.mjs`, lines 13–18);
  - the worker-side shim, which provides `self`, `postMessage`, `addEventListener` and `importScripts`, and loads the
    launcher with `createRequire` (`node-worker.mjs`).
- The shim works because the launcher's directory is CommonJS (D2).
  - In package mode the shim loads the launcher at the URL it is given.
  - It no longer recognises the launcher by its file name, which a bundler may change (`node-worker.mjs`, line 16).
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

### D4. The REST adapter: kept for archives, left out of packages (r2)

- **Archive clients and direct `wasm-client` users:** `createJulc` gains a `rest` option that defaults to `true`. With
  it, the worker loads `rest-adapter.js` and the client exposes `rest`, exactly as today. An archive application that
  calls `createJulc({baseUrl}).rest(…)` keeps working unchanged (I9).
- **The engine packages:** `createJulcVm()` and `createJulc()` pass `rest: false`. None of the three packages ships
  `rest-adapter.js`, so `rest: true` against a package-mode engine fails with a clear error.
- **Types:** the client returns `EngineClient`, which keeps `rest`, as ADR-064 defines it, in archive mode. In package
  mode it returns `PackageClient`, which is `EngineClient` without `rest`. `VmClient` and `FullClient` have
  package-mode counterparts in the same way.

### D5. Public type names

- `wasm-client`'s root exports the client types that are already clean (`VmClient`, `FullClient`, `VmRequest`,
  `Evaluation`, `DebugSession`, `DebugReply`, `SourceDebugSession`, `TransactionRequest`, `Features`, `JulcError`), plus
  `WorkerLike` and `PackageClient`. It adds plain aliases for the generated records:
  - `ScriptInput`, `DecodeRequest`, `DecodeResponse`, `ScriptInfo`;
  - `MockTransaction` and its parts (`TxIn`, `TxOut`, `Value`, `Datum`, and so on);
  - `PlutusData`, `DataInput`, `CompileRequest`, `CompileResponse`, `Breakpoints`, `Snapshot`, `Timeline`;
  - `CostModel`, `Target`, and the `vm.applyParams` types (D6).
- Where two records share a simple name, the alias names the operation instead: the compiler's Run Test
  `EvaluateRequest` becomes `TestRunRequest`.
- The generated `<Outer>_<Inner>` names stay available from the types-only sub-path `@julclang/wasm-client/models`, for
  code that used the archives.
- M1 fixes the list, and a test checks that every type reachable from a public operation has an alias.

### D6. `vm.applyParams`, in both engines

A new operation in the `vm` group (illustrative TypeScript):

```ts
vm.applyParams(request: {
  script: ScriptInput;              // CBOR hex (single or double wrapped), flat, CIP-57 blueprint, text envelope;
                                    // script.validator selects a blueprint's validator; script.params must be empty
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
}>;
```

**Application.** It applies the parameters as `ScriptDecoder.decode` does: `Program.applyParams`, which wraps each
value as a Plutus `Data` constant, then the same hash.
- `vm.decode` and `vm.hash` with `script.params` stay as they are, guesses included.
- The new operation is the documented way, with the rules of D6a and D6b.
- Only `remainingParams: 0` means the script is complete and ready to deploy. A partial result (`remainingParams` > 0),
  or one whose declaration is unknown (`null`), is labelled as such in the README and the declarations.

**Where the checker lives.** In new classes in `julc-tools` that use only `julc-core` and Jackson, so the VM image can
include them. `wasmImageVm` keeps its `-H:AbortOnTypeReachable` guards for the compiler and cardano-client-lib, so the
image build fails if the checker pulls either in (I3). Being in `julc-tools`, JVM callers, such as the CLI later, can use
the same checker.

### D6a. What `vm.applyParams` refuses to guess, and what it always checks (r2)

Unlike `vm.decode`, `vm.applyParams` turns `ScriptDecoder`'s guesses into errors, because its output is what gets
deployed.

**Preparation, always checked:**
- **Language.** If the language cannot be told from the input, the request must state it (`script.language`). In
  `ScriptDecoder`'s terms, that is when `languageSource` would be `default`. Otherwise the operation fails with "state
  the language".
- **Validator.** A blueprint whose validators hold more than one distinct `compiledCode` needs `script.validator`. A
  blueprint whose validators all share one `compiledCode`, such as a multi-purpose validator's entries, gives the same
  script whichever is chosen.
- **Original hash.** If a blueprint gives a hash for the selected validator, it must equal the hash of the script
  before any parameter is applied. A mismatch is an error.
- **No double application.** `script.params` must be empty; values go in `params` only.
- **Exact script bytes.**
  - There must be no bytes after the script's CBOR item.
  - Decoding the program and encoding it again must give the input's FLAT bytes exactly. So the hash after application
    covers the same code the input holds, and a non-canonical encoding is refused rather than silently normalised.
- **No warnings.** Any warning `ScriptDecoder` would give is an error in this operation.

**Application, always checked:**
- **Data only, never guessed.** Every value is either a `PlutusData` JSON object, or a `DataInput` with an explicit
  `format`: `json`, `cbor` or `uplc`. A missing format, or `auto`, is refused, because auto-detection is ambiguous
  (Context item 6).
- **Builtin parameters.** If a declared parameter's schema is a builtin type (`#…`), the operation fails, whatever
  `checkSchema` says. It applies Plutus `Data` constants only, so it cannot apply a builtin integer or byte string.
- **Count.** For a blueprint input, the selected validator declares its parameters. A validator without a `parameters`
  field declares none (JuLC omits the field then, `BlueprintGenerator`, lines 77–81).
  - More values than declared is an error, so extra values can never shift into the datum, redeemer or context
    positions.
  - Fewer is an error unless `partial: true`.
  - Only a non-blueprint input (CBOR, flat, envelope) has no declaration.

**Schema, checked unless `checkSchema: false`:** each value against its declared schema (D6b).

The options combine like this:

| Input declares parameters? | Values vs declared | `partial` | `checkSchema` | Result | `remainingParams` |
|---|---|---|---|---|---|
| yes | equal | any | true | applied if every value matches its schema | 0 |
| yes | equal | any | false | applied, shapes not checked | 0 |
| yes | fewer | false | any | error, naming the missing titles | — |
| yes | fewer | true | true / false | applied, with or without the shape check | declared − given |
| yes | more | any | any | error, naming the declared titles | — |
| blueprint, no `parameters` field (declares 0) | any value given | any | any | error | — |
| blueprint, no `parameters` field (declares 0) | none given | any | any | the script as it is | 0 |
| no declaration (CBOR, flat, envelope) | — | any | any | applied, nothing to check against | `null` |

### D6b. The schema check: its domain and rules (r2)

**Two kinds of failure, never mixed.**
- An **invalid schema** is a fault of the blueprint: an unknown keyword, a malformed keyword value, a keyword used with
  the wrong data type, a missing or external `$ref`, or a reference cycle that D6b forbids.
- A **mismatch** is a fault of a value.
- The checker finds every schema fault first, in a pass over the parameter schemas and the definitions they reach,
  before it looks at any value. So `not` cannot turn a schema fault into a success, and `anyOf` cannot hide one.
- The two kinds are reported with different error codes.

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
- **`bytes`:** the length is in bytes. `enum` compares the value's hex form.
- **`integer`:** bounds and `multipleOf` are evaluated at arbitrary precision.
- **`list`:**
  - a single `items` schema applies to every element;
  - an `items` array requires exactly that many elements, matched by position;
  - `uniqueItems` compares elements as Plutus data.
- **`map`:** `keys` and `values` apply to every pair. `minItems` and `maxItems` count the pairs.
- **`constructor`:** the value's index equals `index`, and its fields match `fields` by position. The field count must
  be equal.
- **Anything else is an invalid schema,** including keywords outside the table.

**References.**
- `$ref` is local only: `#/definitions/<name>`, with JSON Pointer escaping (`~1` for `/`, `~0` for `~`). Aiken
  blueprints use `~1` in definition names.
- Any other form, and a reference to a missing definition, is an invalid schema.

**Cycles.**
- Before checking values, the checker builds the graph of references reachable from each parameter schema.
- A cycle is allowed only if every edge of it passes through a position that consumes part of the value: a `list`'s
  `items`, a `map`'s `keys` or `values`, or a `constructor`'s `fields`. That is how a recursive type such as a list or
  tree is described, and the check of a value then always descends into a strictly smaller part of it, so it ends.
- A cycle through `$ref`s and applicators alone (`A → A`, `A → anyOf[A, …]`) is non-productive. A cycle that passes
  through `not` at any point (`S = not S`) has no consistent answer. Both are invalid schemas, and the error names the
  cycle's definitions.

**Mismatch paths under applicators.**
- For `anyOf` and `oneOf`, the error is reported at the applicator's path. It lists each branch's first failure, or,
  for `oneOf`, the branches that matched.
- For `not`, it reports that the value matched the schema it must not.

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
2. **A `-beta` version** goes to `beta`.
3. **Before 1.0, any other prerelease (`-preN`)** goes to `latest`.

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
  - it checks that `engine.js`'s inline manifest equals `engine/engine.json`.
- It writes `julc-npm-<version>-SHA256SUMS.txt`, covering the three tarballs and the two archives.
- It uploads all of them as the job's artifact.

**The `github-release` job** attaches the three tarballs and their checksum file to the GitHub release, beside the
archives. Once a release exists, its exact npm files are kept there for good, and a later retry uses them, never a
rebuild.

**A new `npm-publish` job:**
- **Gate.** It runs only when both `npm_publish` and `github_release` are `true` in `gradle.properties`; the default for
  `npm_publish` is `false`. npm never gets a version whose archives are not public, so users can always check I1.
- **Credentials.** It runs in the `release-staging` environment, which accepts only `v*` tags. It gets `id-token: write`
  for npm trusted publishing (OIDC) and the read access needed to download artifacts, and nothing else. No npm token
  exists.
- **Tools.** Node ≥ 22.14, and npm installed at an exact pinned version ≥ 11.5.1. Trusted publishing needs these, and
  Node 22 bundles npm 10. JOUST installs `npm@latest` unpinned; JuLC does not.
- **What it doesn't do.** It runs no Gradle, no `npm install` and no package scripts, and it does not check out or build
  the code. It downloads the tarballs and verifies them against the checksum file.
- **Publishing.** It publishes in dependency order: `@julclang/wasm-client`, then `@julclang/julc-vm-wasm`, then
  `@julclang/julc-wasm`. Each is published with `npm publish <tarball> --access public --tag <tag> --provenance
  --ignore-scripts`.
- **Each package's `package.json`** names the `repository` `github:bloxbean/julc`, which provenance requires.

**Partial publication and retries.**
- npm never lets a version be overwritten. Before publishing each package, the job asks the registry for that
  `name@version`:
  - if it is absent, the job publishes it;
  - if it is present with the same tarball integrity (`dist.integrity` equals the tarball's SHA-512), the job skips it;
  - if it is present with different contents, the job fails and publishes nothing more.
- A retry is the same job, run again on the same files.

**After publishing**, the job checks what the registry now serves:
- it downloads each published `name@version` (`npm pack name@version`) and compares it with the build's tarball;
- it runs `npm audit signatures` on a fixture that installs the three packages.

**Bootstrap, once per package name, done by the maintainer:**
- npm configures a trusted publisher per package, and a package must exist before one can be configured. So for each new
  name the maintainer publishes a placeholder version by hand, with 2FA, as JOUST did.
  - JOUST used `0.0.0-oidc-bootstrap.0` on a `bootstrap` dist-tag. It briefly became `latest`, until the first real
    version replaced it.
- The maintainer then configures the trusted publisher: repository `bloxbean/julc`, workflow file
  `native-image-release.yml` (npm validates that the publish command is in that file), environment `release-staging`.
- Once publishing works, the maintainer sets each package to require 2FA and to refuse tokens.

**A bad release:**
- It is deprecated (`npm deprecate`), and `latest` is moved back to the previous good version (`npm dist-tag add`)
  until a fixed version is published.
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
  - `engine.js`'s inline manifest equals `engine/engine.json`.
  - The three npm tarballs are packed once, in the build job. What npm serves for that version is exactly those
    tarballs, checked before publishing (SHA-256 and npm's integrity) and again from the registry after publishing.

  Constrains D2, D8a.
- **I2 (one build).** An engine package depends on exactly its own version of `wasm-client`. At run time the client
  refuses an engine whose `api`, `variant`, `groups` or version disagree with what it expects (D1a). Constrains D1, D1a,
  D2.
- **I3 (VM image contents).** The VM engine contains no compiler, decompiler, JavaParser or cardano-client-lib code,
  including after `vm.applyParams` (the existing `-H:AbortOnTypeReachable` guards). Constrains D6.
- **I4 (application is exact).** For any request `vm.applyParams` accepts (D6a):
  - Its `compiledCode` and `scriptHash` equal `vm.decode`'s `info.compiledCode` and `info.scriptHash` with the same
    values in `script.params`. They also equal the JVM's `ScriptDecoder.decode`. This is the same code path, so it
    guards against regressions only. The independent checks are the ones below.
  - For any accepted script, Scalus applying the same values gives the same `cborHex` and hash. Scalus is an
    independent implementation, already a test dependency of `julc-cardano-client-lib`.
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
  no build and no package scripts. Constrains D8a.
- **I8 (loads as installed).** An engine package installed from its tarball loads in Node 22 and Node 24, both on the
  main thread and inside an application's own `worker_threads` worker. It also loads from Vite and webpack production
  builds. In none of these cases does the caller pass flags or change files. Constrains D2, D3.
- **I9 (archive compatibility).** An unchanged archive application, calling `createJulc({baseUrl})` and then any
  operation or `rest(…)`, behaves as it does on the previous release. Constrains D2a, D4.
- **I10 (loading confinement).** In archive mode the worker loads only the plain launcher file name next to the
  manifest (ADR-064 I4). In package mode it loads only URLs that pass D2a's checks. No mode loads a path taken from the
  manifest's `launcher` field. Constrains D2a.
- **I11 (no rebuild, no overwrite).** A publication or a retry uses only the tarballs packed by the build job. A version
  already on npm with different contents stops the job. Constrains D8a.

## Alternatives rejected

- **One package with both engines.** Every application would download 38.8 MB of engines to use one.
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
- **One package with sub-path exports for both engines.** Every install would carry both engines, 38.8 MB, as with one
  package.
- **Fetching the engine at run time from a CDN, with subresource integrity.** It would mean no offline install, a CSP
  allowance for another origin, and two sources of truth for one version. It could be added later, beside the packages.

## Affected stages and modules

- `julc-wasm`:
  - `julc-wasm.js`:
    - the package-mode options `launcherUrl`, `moduleUrl` and `expectedVersion`, and the `rest` option (D1a, D2, D4);
    - the version check (D1a);
    - the worker's construction in the form bundlers recognise (D2);
  - `julc-worker.js`: the two loading modes and their checks (D2a), and the adapter only when `rest` is set (D4);
  - the shipped Node bridge, both halves (D3);
  - `VmApi`: `vm.applyParams` (D6);
  - `TypeScriptDeclarations` and the declaration files: the aliases, `WorkerLike`, `PackageClient` (D2, D4, D5);
  - `build.gradle`: the package directories, the CommonJS `engine/` directory, the generated `engine.js`, and `npm
    pack` (D2, D8a).
- `julc-tools`: the CIP-57 data-schema checker and the `applyParams` service method (D6, D6a, D6b).
- `.github/workflows/native-image-release.yml`: packing and the identity check in `wasm`, the attachments in
  `github-release`, and the new `npm-publish` job (D8a).
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
  - An older embedder engine used with a newer `wasm-client` lacks the operation at run time. The client builds its
    methods from the engine's schemas, so the method is simply absent. An embedder's TypeScript types should describe
    the operations its engine has (`Operations<T>`), not `VmClient`'s.
  - Its archive-style loading is unchanged, and it may use package mode and pass `expectedVersion`.
  - ADR-064's I3 (the jar's client files equal the bundles') extends to the npm package: for a version,
    `@julclang/wasm-client`'s files equal the `julc-wasm` jar's.

## Risks

- **Supply chain.** A compromised package runs in every application that installs it.
  - Mitigations:
    - publishing only from a gated job without a build or a token (D8a);
    - provenance;
    - no install scripts (I7);
    - the identity checks (I1, I11);
    - the client's run-time checks (D1a, D2a).
  - The scope's maintainers and 2FA are the developer's to keep, as for JOUST.
- **Size.** Installs carry 14.3 MB or 24.5 MB of engine. The READMEs state it, and the first-load time (about 0.7 s
  for the full engine, measured by Yaci Viewer in #244).
- **Browser support.** The engines need WebAssembly GC and exception handling (`exnref`). They are tested in Chrome
  (`julc-playground/BUILD_FROM_SOURCE.md`). The READMEs state the requirement, and M3 records which browser versions
  were tested.
- **A Content Security Policy** that blocks WebAssembly compilation stops the engine loading. M0 finds which source
  expression the engines need, and the README documents it.
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
  - Node 22 and Node 24.
- Exit:
  - Open question 2 decided, with the evidence recorded in this ADR's revision history.
  - The CSP source expression the engines need, recorded.

**M1: `@julclang/wasm-client`** (D1a, D2, D2a, D3, D4, D5).
- Entry gate: M0 done (open question 1 is decided).
- Work:
  - package mode and its checks;
  - the version check;
  - the `rest` option;
  - the shipped Node bridge with the `exnref` handling;
  - the worker in M0's form;
  - `WorkerLike` and `PackageClient`, and the type aliases.
- Exit:
  - The existing client and worker tests pass, including ADR-064 I4's.
  - New negative loading tests pass (I10): another origin, `data:`, `blob:`, `javascript:`, credentials in the URL, a
    wrong extension, `baseUrl` with `launcherUrl`, a manifest `launcher` with a path in package mode.
  - The version-mismatch test passes (I2).
  - The unchanged-archive-consumer fixture passes, including `rest` (I9).
  - The type checks under NodeNext without DOM, and under bundler with DOM, pass with `skipLibCheck: false`.
  - The alias coverage test passes.

**M2: `vm.applyParams`** (D6, D6a, D6b).
- Entry gate: M1 merged (open question 5 is decided).
- Work: the checker and the preparation rules in `julc-tools`, the `VmApi` operation, the declarations.
- Exit, all tests listed under Verification:
  - I4's differential tests, including the fixed vector;
  - the Aiken oracle;
  - the option matrix of D6a, each row;
  - I5's tests for each keyword, applicator, reference form and cycle kind;
  - the VM image still builds under its guards (I3);
  - a mutation check on each schema rule and each D6a refusal.

**M3: the engine packages** (D1, D2, D8a).
- Entry gate: M1 and M2 merged.
- Work: Gradle tasks that assemble `@julclang/julc-vm-wasm` and `@julclang/julc-wasm` from `wasm-bundle-{vm,full}`,
  with the CommonJS `engine/` directory and the generated `engine.js`, plus `npm pack`.
- Exit:
  - `npm pack` contents are listed and checked.
  - I1 is checked against locally built archives.
  - I6 and I8 hold for both engine packages, installed from their tarballs.
  - READMEs with size, browser and CSP notes.

**M4: publishing** (D8, D8a).
- Entry gate:
  - M3 merged;
  - the maintainer has published a placeholder for each of the three names and configured each trusted publisher;
  - `npm_publish` stays `false` until the maintainer decides.
- Work: packing and checksums in `wasm`, the attachments in `github-release`, and the `npm-publish` job with its gate,
  OIDC permissions, ordering, retry rules and dist-tags.
- Exit:
  - A release candidate's run produces the tarballs and checksums.
  - Its publish job, in dry-run mode, lists the expected files and tags.
  - The retry rules pass against a local registry, for these cases: already published and identical; already published
    and different; and a failure after the first package.
  - Then the first real publication, by the maintainer's decision. It is the only proof of authentication and
    provenance, and its tags are checked on the registry.

**M5: documentation and adopters.**
- Work: the npm section of `javascript-wasm.md`, release notes, and an `applyParams` example.
- Optional, in JOUST's own repository and by its decision: `@julclang/joust-runtime` moves its loader onto
  `@julclang/wasm-client`.

## Verification

Independent evidence, not values the code under test produced:
- **Application (I4).**
  - **The fixed vector.** Apply the integer 42 (`182a`) to `(program 1.1.0 (lam p (con unit ())))`. Expect:
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
  - **Preparation (D6a).** Negative tests for each refusal:
    - an ambiguous language without `script.language`;
    - several validators without a selection;
    - a blueprint hash that does not match;
    - values in `script.params`;
    - a `ScriptDecoder` warning;
    - a builtin parameter schema with `checkSchema: false`;
    - a `DataInput` without a format, or with `auto`;
    - trailing bytes after the script, and a script that does not round-trip through decoding and encoding;
    - a value given to a blueprint validator without `parameters`;
    - a multi-validator blueprint with distinct codes and no selection.
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
    - a self cycle (`A → A`), a mutual cycle (`A → B → A`), and a negative cycle (`S = not S`).

    The same faults placed under `not` and inside an `anyOf` branch must still be reported as invalid schemas.
  - **Accepted, as they should be:**
    - productive recursive types, such as a list type and a tree type defined through constructor fields;
    - a definition named `Data` that is a constructor, which must be checked as a constructor;
    - opaque, annotation-only data;
    - names with `~1` and `~0` escapes.
  - **Real blueprints.** The blueprints JuLC generates for every `@Param` type in `julc-examples` must accept the values
    their own tests apply. A sample of Aiken blueprints must accept the values of their own tests.
- **Packages (I1, I2, I6–I11).**
  - SHA-256 equality with the archives, checked in CI.
  - The VM parity fixtures run through each package entry, installed from its tarball, in Node 22, Node 24 and Chrome.
  - Node loading from the main thread and from an application worker, with no flags (I8).
  - Vite (development and production) and webpack (production) fixture builds.
  - The negative loading tests (I10), and the unchanged-archive-consumer fixture (I9).
  - A check that no `package.json` has install scripts.
  - A test that a client refuses an engine of another version (I2).
  - The publish job's retry cases against a local registry (I11).
  - The comparison of the published files from the registry, and `npm audit signatures`, after the first real
    publication (M4).
- **No regression.** The existing `julc-wasm` tests, `wasmSmoke{Full,Vm}`, the embedder smoke test and the
  playground's static build keep passing.

## Open questions

Only question 2 is still open, and M0's experiment decides it. The maintainer decided the others on 2026-11-16, choosing
the recommendation recorded with each.

1. **Package names.** `@julclang/wasm-client`, `@julclang/julc-vm-wasm` and `@julclang/julc-wasm`, or shorter ones
   (`@julclang/vm`, `@julclang/julc`)?
   - **Decided** (maintainer, 2026-11-16, r3): the proposed names, which match the archive names.
2. **The worker form.** A classic worker, which keeps `importScripts`, or a module worker with another way to load the
   CommonJS launcher?
   - Decided by M0's experiment before M1 starts.
   - Lean: classic, the form proven today, if both bundlers handle it.
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
    - **K4:** a blueprint validator without `parameters` declares zero; selection is needed only when codes differ;
      the count and builtin checks are always on.
    - **K7:** release candidates are not published, because semver orders them above their final release (checked
      with npm's `semver`); open question 8.
    - **K8:** I1 is checked on the packed tarball against the extracted archive, and again from the registry.
    - **K9:** a pinned npm, the bootstrap details, and 2FA with tokens refused.
    - **K10:** publishing only with a GitHub release; deprecation, rolling `latest` back, and an advisory.
    - **K12:** the runtime belongs to the engine.
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
