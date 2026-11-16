# ADR-077: npm packages for the WebAssembly engines, and `vm.applyParams`

**Status:** Proposed (2026-11-16). Design only; no implementation in this change.
**Date:** 2026-11-16
**Risk class:** **medium**.
- This is a public distribution with a lasting API. Package names and exported type names are hard to change once
  published.
- It adds a supply-chain surface: three packages published from CI.
- One new engine operation, `vm.applyParams`, computes the bytes and hash of a parameterised script that applications
  will deploy and pay to. A wrong result there costs users funds, so its invariants (I4, I5) and tests are strict.
- Nothing here changes the compiler, the VM, script bytes or budgets.

**Builds on:** [ADR-057](057-julc-wasm-distribution.md) (the `julc-wasm` distribution, JavaScript API version 0),
[ADR-064](064-embeddable-wasm-engine.md) (engines other languages build on `julc-wasm`).
**Changes:** ADR-057's non-goal "Publishing to npm. Deferred until the API has had at least one release of use", and
it answers ADR-057's open question 1 (npm package names and cadence).
**Relates to:** [#244](https://github.com/bloxbean/julc/issues/244) (requests from Yaci Viewer's Script Lab, which uses the
engine).

## Context

Since 0.1.0-pre18 every release attaches the two WebAssembly engines as GitHub release assets. These are the sizes in
the pre20 release:

| Asset | Engine (`.wasm`) | Archive | Groups (`engine.json`) |
|---|--:|--:|---|
| `julc-wasm-<version>.tar.gz` | 24,527,451 B | 8,465,421 B | `vm, debug, compiler, uplc, sourceDebug, sourceDebugLocals` |
| `julc-vm-wasm-<version>.tar.gz` | 14,282,613 B | 5,281,353 B | `vm, debug` |

Both archives hold the same JavaScript SDK, built by `wasmBundle{Full,Vm}` in `julc-wasm/build.gradle`:
- `julc-wasm.js`: `createJulc`, `isFullClient`;
- `julc-worker.js`, `julc-runtime.js` and `rest-adapter.js`;
- the declarations `julc-wasm.d.ts` and `julc-models.d.ts`.

Beside the SDK are the engine's content-addressed launcher and module, and `engine.json`.

To use an engine today, an application downloads an archive, checks it against `julc-wasm-<version>-SHA256SUMS.txt`,
and copies the files into its static assets. ADR-057's precondition for npm is now met: the API has been released three
times, and Yaci Viewer's Script Lab has built on it (#244). Two other facts make npm practical now:
- The `@julclang` scope exists: JOUST publishes `@julclang/joust-runtime` and `@julclang/state-client` from its release
  workflow (both at `0.1.0-pre1-rc9`).
- `@julclang/joust-runtime` ships a Web Image built on `julc-wasm` (ADR-064) and loads it in Node.js.

`npm view` on 2026-11-16 found `@julclang/wasm-client`, `@julclang/julc-vm-wasm` and `@julclang/julc-wasm` unused.

### Current behavior that blocks a package (main at `8724e2ce`)

1. **Node.js.**
   - `createJulc` starts a browser `Worker` by default, and `julc-worker.js` is a classic worker that loads its files
     with `importScripts`. Node's `worker_threads` has neither.
   - The only Node bridge is the test shim `julc-wasm/src/test/js/node-worker.mjs`, which is not shipped.
   - On Node 22 the image also needs `--experimental-wasm-exnref`. `wasmSmoke` passes that flag, and JOUST's `load.js`
     sets it with `v8.setFlagsFromString`.
2. **Bundlers.**
   - `createJulc` derives every URL at run time: `baseUrl` defaults to the client's own directory, it then fetches
     `engine.json`, and builds `julc-worker.js?engine=<hash>`. The launcher name is only known once `engine.json` has
     been read.
   - Vite and webpack copy an asset only when the code names it statically (`new URL('./file', import.meta.url)`). So
     a bundled application would ship none of the engine's files.
3. **The REST adapter.** `julc-worker.js` always loads `rest-adapter.js`. Only the playground needs it, for its old
   HTTP routes (`client.rest(...)`).
4. **Type names.**
   - `julc-models.d.ts` is generated from the Java records (`TypeScriptDeclarations`), so a nested record keeps its
     outer class in its name: `UplcModels_DecodeRequest`, `VmModels_Request`, `MockTransaction_TxOut`.
   - Once published, these names are API.
5. **Parameter application has no name.**
   - Every VM operation accepts `script.params`, and `ScriptDecoder.decode` (`julc-tools`, lines 171–186) applies them
     with `Program.applyParams`. It hashes the result, and returns it from `vm.decode` as `info.compiledCode`.
   - But nothing is called "apply parameters". Nor are the values checked against a blueprint's `parameters` schemas:
     a wrong value silently gives a different hash, and so a different address.
   - `BlueprintValidator` checks a blueprint's structure against the CIP-57 meta-schema, but not data against a schema.
     It also lives in `julc-blueprint`, with `json-schema-validator`. The VM image includes neither (the jar filter in
     `wasmImageVm`).

## Goals

- `npm install` gives a JavaScript or TypeScript application either engine, with types. It works in browsers, through
  Vite and webpack, and in Node.js 22 and later, without copying files by hand.
- A package version always contains one engine with the client built for it. They cannot be mixed.
- A clear operation for the most common dApp task: apply a validator's parameters, then get its bytes and hash.
  Values are checked against the blueprint's schemas when there is one.
- The packages are published from the same release workflow and the same build as the release archives, with npm
  provenance.

## Non-goals

- Any change to the release archives or to the static playground. They stay as they are.
- New evaluation features from #244, such as every redeemer in `MockTransaction`, evaluation from transaction CBOR, and
  profiling. They need their own designs. Open question 4 records where transaction-CBOR evaluation would go.
- BLS12-381 in WebAssembly. Both engines still lack it (ADR-057), and the packages say so.
- A stable API version 1. The API stays version 0, experimental (D7).
- Deriving bech32 addresses (open question 3).
- Publishing snapshot or development builds to npm.

## Decision

### D1. Three packages

| Package | Contents | Depends on |
|---|---|---|
| `@julclang/wasm-client` | The client (`createJulc`, `isFullClient`, sessions), the worker, the runtime, the browser and Node worker bridges, and the declarations. No engine. | — |
| `@julclang/julc-vm-wasm` | The VM engine (launcher, module, `engine.json`) and `createJulcVm()` | `@julclang/wasm-client`, same version, exact |
| `@julclang/julc-wasm` | The full engine and `createJulc()` | `@julclang/wasm-client`, same version, exact |

Reasons for this split:
- An application that only evaluates scripts does not download the 24.5 MB engine.
- An engine an embedder builds (ADR-064), such as JOUST's, can depend on the client alone instead of copying it.

The engine packages pin the client exactly, so npm cannot pair an engine with another release's client. The client
already refuses an engine whose `api`, `variant` or `groups` disagree with its manifest (`julc-wasm.js`).

### D2. ES modules with static asset URLs

- Each package is ESM only (`"type": "module"`) and declares `"engines": {"node": ">=22"}`.
- Each engine package has a generated `engine.js` that names every file statically. Bundlers then copy the files, and
  the browser and Node resolve them next to the module:

  ```js
  // generated at build time (illustrative)
  export const manifest = { /* contents of engine.json */ };
  export const launcherUrl = new URL('./julc-vm-b69c37c29c6b.js', import.meta.url);
  export const moduleUrl = new URL('./julc-vm-b69c37c29c6b.js.wasm', import.meta.url);
  ```

- `createJulcVm(options)` and `createJulc(options)` pass these URLs to `wasm-client`'s `createJulc`. For that,
  `createJulc` gains `launcherUrl` and `moduleUrl` options, and an inline `manifest` (it already accepts one). The
  existing `baseUrl` mode stays for the archives.
- The worker becomes a module that `wasm-client` names with `new URL('./julc-worker.js', import.meta.url)`. The browser
  keeps a classic worker where a module worker cannot load the launcher; M1 decides between them by test.
- The `exports` map selects the environment:

  ```json
  "exports": {
    ".": { "types": "./index.d.ts", "node": "./node.js", "default": "./index.js" },
    "./engine": "./engine.js"
  }
  ```

### D3. Node.js is supported, through a shipped bridge

- `wasm-client` ships the bridge `julc-wasm/src/test/js/node-worker.mjs` proves today, made production code: a
  `worker_threads` worker that provides `self`, `postMessage` and `importScripts`.
- Its `node` entry uses that bridge as the default `workerFactory`.
- On Node 22, the entry enables WebAssembly exception references before the first engine loads, as JOUST's `load.js`
  does. Node 24 has them on by default.
- Loads are serialised, because every engine announces itself through the one global `onJulcReady` (JOUST's `load.js`
  has the same queue).
- M1 proves this in Node 22 and 24, and in a worker thread started by an application.

### D4. The REST adapter is not in the packages

- `julc-worker.js` loads `rest-adapter.js` only when the client asks for it (an `options.rest` flag the playground
  sets). The packages ship neither the adapter nor `client.rest`.
- The archives and the playground keep both, unchanged in behaviour.

### D5. Public type names

- `wasm-client`'s root exports the client types that are already clean (`VmClient`, `FullClient`, `VmRequest`,
  `Evaluation`, `DebugSession`, `DebugReply`, `SourceDebugSession`, `TransactionRequest`, `Features`, `JulcError`).
  It adds plain aliases for the generated records:
  - `ScriptInput`, `DecodeRequest`, `DecodeResponse`, `ScriptInfo`;
  - `MockTransaction` and its parts (`TxIn`, `TxOut`, `Value`, `Datum`, and so on);
  - `PlutusData`, `CompileRequest`, `CompileResponse`, `Breakpoints`, `Snapshot`, `Timeline`;
  - `CostModel`, `Target`, and the `vm.applyParams` types (D6).
- Where two records share a simple name, the alias names the operation instead: the compiler's Run Test
  `EvaluateRequest` becomes `TestRunRequest`.
- The generated `<Outer>_<Inner>` names stay available from the sub-path `@julclang/wasm-client/models`, for code that
  used the archives.
- M1 fixes the list, and a test checks that every type reachable from a public operation has an alias.

### D6. `vm.applyParams`, in both engines

A new operation in the `vm` group:

```ts
vm.applyParams(request: {
  script: ScriptInput;              // CBOR hex (single or double wrapped), flat, CIP-57 blueprint, text envelope
                                    // (script.validator selects a blueprint's validator; script.params must be empty)
  params: Array<PlutusData | DataInput>;
  partial?: boolean;                // default false
  checkSchema?: boolean;            // default true
}): Promise<{
  compiledCode: string;             // the applied script, double-wrapped CBOR (as a text envelope or blueprint holds it)
  cborHex: string;                  // the same script, single-wrapped CBOR
  scriptHash: string;               // blake2b-224 of the language tag and the single-wrapped script
  language: 'V1' | 'V2' | 'V3';
  paramsApplied: number;
  remainingParams: number | null;   // null when the input declares no parameter list
}>;
```

**Application.** It applies the parameters exactly as `ScriptDecoder.decode` does today: `Program.applyParams`, then
the same hash. `vm.decode` with `script.params` stays as it is. The new operation is the documented way, and it adds the
checks below.

**Schema check.** Applies when the input is a blueprint whose selected validator declares `parameters`, and
`checkSchema` is not false:
- **Count.** The number of values must equal the number of declared parameters. Fewer is allowed only with
  `partial: true`. More is always an error.
- **Shape.** Each value must match its parameter's schema, resolved through the blueprint's `definitions` (`$ref`).
  The rules are those of the CIP-57 data schema JuLC pins (`julc-blueprint/src/main/resources/cip57/plutus-data.json`,
  CIP revision `0ed8837a`):
  - `integer` and `bytes`;
  - `list` (with `items`) and `map` (with `keys` and `values`);
  - `constructor` (`index` and `fields`, in order);
  - the applicators `anyOf` (for a sum type, a value matches the branch with its constructor's `index`), `oneOf`,
    `allOf` and `not`;
  - an empty schema or `Data` (any value).
- **Errors** name the parameter by index and title, and the path inside the value. For example: `Parameter 2 (owner):
  at fields[0]: expected bytes, found int`. The count error names the declared titles.
- **Other keywords.**
  - The pinned meta-schema defines no constraint keywords, but it doesn't forbid them either. The CIP's text may
    describe some, such as lengths, bounds and item counts.
  - M2 lists those the CIP text at that revision defines, and checks them.
  - Any other keyword in a parameter's schema fails the check with a message that names it, rather than being ignored.
    `checkSchema: false` skips the check.
- **A builtin (`#…`) schema** describes a non-Data parameter, which `applyParams` cannot apply (it applies Plutus data
  only). It fails with a message saying so.

**Inputs without a schema.** A CBOR, flat or envelope input has no parameter list. The operation applies what it is
given and reports `remainingParams: null`.

**Where the checker lives.** In a new class in `julc-tools` that uses only `julc-core` and Jackson, so the VM image can
include it. `wasmImageVm` keeps its `-H:AbortOnTypeReachable` guards for the compiler and cardano-client-lib, so the
image build fails if the checker pulls either in. Being in `julc-tools`, JVM callers, such as the CLI later, can use the
same checker.

### D7. API version 0, experimental

- The npm version is the JuLC version (D8), and `engine.json` keeps `"api": 0`.
- Each package README states that the API may change in any release before 1.0. The release notes list every change to
  it.
- When the API reaches version 1 is open question 6.

### D8. Versioning and publishing

- **Same build as the archives.** All three packages are published by `native-image-release.yml`, in the job that
  builds the archives, from the same `wasm-bundle-{vm,full}` directories. The `.wasm`, launcher and `engine.json` in a
  package are byte-for-byte the archive's (I1).
- **Version.** The npm version is the JuLC release version, for example `0.1.0-pre21`.
- **dist-tags, as JOUST's release workflow does:**
  - before 1.0, a `-preN` version goes to `latest`, because npm makes a package's first version `latest` whatever
    `--tag` says, and both `npm install` and semver ranges resolve only `latest`;
  - `-rc` goes to `next`, and `-beta` to `beta`.
- **Flags:** `--access public --ignore-scripts --provenance`. The packages have no install scripts.
- **Credentials:** npm trusted publishing (OIDC) when it is configured for the scope, otherwise the token JOUST uses. A
  workflow input or property can turn publishing into `npm publish --dry-run`, as JOUST's `npm_publish` does.
- **No snapshots.** Snapshot and development builds (`native-image-dev.yml`) are not published.

## Invariants

- **I1 (identity).** For a version, each engine package's `.wasm`, launcher and `engine.json` equal the files in that
  version's `julc-{vm-,}wasm-<version>.tar.gz`, byte for byte. Checked in the release job by SHA-256 before `npm
  publish`. Constrains D8.
- **I2 (one build).** An engine package depends on exactly its own version of `wasm-client`. The client refuses an
  engine whose `api`, `variant` or `groups` disagree with its manifest. Constrains D1 and D2.
- **I3 (VM image contents).** The VM engine contains no compiler, decompiler, JavaParser or cardano-client-lib code,
  including after `vm.applyParams` (the existing `-H:AbortOnTypeReachable` guards). Constrains D6.
- **I4 (application is exact).** For any script and parameter list, `vm.applyParams` returns the same `compiledCode`
  and `scriptHash` as `vm.decode` with the same `script.params`, and as the JVM's `ScriptDecoder.decode`. For an Aiken
  blueprint, it returns the same compiled code as `aiken blueprint apply`, run independently. Constrains D6.
- **I5 (the schema check is sound and complete for the pinned CIP-57 data schema).** It accepts every value that
  conforms to the declared schema and rejects every value that does not, at the first wrong path. It fails on a schema
  keyword it does not check, never passes it. With `checkSchema: false` or no
  declared parameters, it applies without checking. Constrains D6.
- **I6 (same answers everywhere).** The packages give the same results in a browser, in Node 22 and in Node 24, and the
  same as the release archives. The existing VM parity fixtures run through each package entry point. Constrains D2
  and D3.
- **I7 (no install-time code).** No package has `preinstall`, `install` or `postinstall` scripts, and the release
  publishes with `--ignore-scripts`. Constrains D8.

## Alternatives rejected

- **One package with both engines.** Every application would download 38.8 MB of engines to use one.
- **Two self-contained packages, each with its own copy of the client.** Embedders such as JOUST would still need a
  third copy. An application that installs both engines would carry two clients that could drift apart.
- **Publishing the archives' contents as they are.** That would publish Node support that doesn't work, assets
  bundlers cannot find, the REST adapter, and the generated type names (Context, items 1 to 4).
- **A client-side `applyParams` in JavaScript.** It would need a second implementation of FLAT encoding and parameter
  application, and of the CIP-57 checker, for the JVM to differ from. One Java implementation in the engine keeps I4
  checkable against `ScriptDecoder` and Aiken.
- **Checking parameters with `BlueprintValidator`'s `json-schema-validator`.** It checks JSON documents against JSON
  Schema, not Plutus data against CIP-57's data schemas. It is also not in the VM image (I3).
- **Pre-releases on the `next` tag.** Rejected by JOUST's experience (D8): a package's first version becomes `latest`
  anyway, and `npm install` without a tag would never see the pre-releases.

## Affected stages and modules

- `julc-wasm`:
  - `julc-wasm.js`: the asset URL options (D2), and `rest` only when asked (D4);
  - `julc-worker.js`: the adapter only when asked (D4);
  - a shipped Node bridge (D3);
  - `VmApi`: `vm.applyParams` (D6);
  - `TypeScriptDeclarations` and the declaration files: the aliases (D5);
  - `build.gradle`: package directories, the generated `engine.js`, and `npm pack` (D2, D8).
- `julc-tools`: the CIP-57 data-schema checker and the `applyParams` service method (D6).
- `.github/workflows/native-image-release.yml`: the identity check and `npm publish` (D8).
- Docs: `docs/src/content/docs/guides/javascript-wasm.md` gains an npm section; the release notes.
- Not affected: the compiler, VM, stdlib, ledger API, script bytes and budgets.

## Compatibility

- **Release archives:** unchanged, except that their worker loads the REST adapter only when asked. The playground asks
  for it, so it behaves as before.
- **`vm.decode` with `params`:** unchanged. `vm.applyParams` is a new operation, so engines and clients from earlier
  versions don't have it, and `features()` does not advertise it separately (I2 pins the versions).
- **Type names:** additive. Code written against the archives' generated names keeps them through
  `@julclang/wasm-client/models`.
- **Embedders (ADR-064):** unaffected. An embedder's engine can depend on `@julclang/wasm-client` instead of a copied
  client.

## Risks

- **Supply chain.** A compromised package runs in every application that installs it.
  - Mitigations: publishing only from CI (D8), provenance, no install scripts (I7), the identity check (I1), and the
    existing manifest checks in the client.
  - The scope's maintainers and 2FA are the developer's to keep, as for JOUST.
- **Size.** Installs carry 14.3 MB or 24.5 MB of engine. The READMEs state it, and the first-load time (about 0.7 s
  for the full engine, measured by Yaci Viewer in #244).
- **Browser support.** The engines need WebAssembly GC and exception handling (`exnref`). They are tested in Chrome
  (`julc-playground/BUILD_FROM_SOURCE.md`). The READMEs state the requirement, and M3 records which browser versions
  were tested.
- **A Content Security Policy** that blocks WebAssembly compilation stops the engine loading. M1 checks which source
  expression the engines need, and the README documents it.
- **API churn under version 0** (D7). Mitigated by listing every change in the release notes.
- **`vm.applyParams` mistakes are expensive.** A wrong hash means funds sent to the wrong address. Mitigated by I4 and
  I5, the Aiken oracle, and checking by default.

## Implementation milestones

**M1: `@julclang/wasm-client`** (D2, D3, D4, D5).
- Entry gate: this ADR accepted; open questions 1 and 2 decided.
- Work: static asset options, the module or classic worker, the shipped Node bridge with the `exnref` handling, the
  REST adapter only when asked, and the type aliases.
- Exit: the existing client and worker tests pass. The VM parity fixtures pass through the package in Node 22, Node 24
  and Chrome (I6). Small fixture applications build with Vite and with webpack and evaluate a script. The alias
  coverage test passes.

**M2: `vm.applyParams`** (D6).
- Entry gate: M1 merged; open question 5 decided.
- Work: the checker in `julc-tools`, the `VmApi` operation, the declarations.
- Exit, all tests listed under Verification:
  - I4 differential tests against `vm.decode` with `params` and the JVM's `ScriptDecoder.decode`;
  - the Aiken oracle;
  - I5 tests for each schema kind, with generated conforming and non-conforming values and negative cases;
  - the VM image still builds under its guards (I3);
  - a mutation check on each schema rule.

**M3: the engine packages** (D1, D2, D8).
- Entry gate: M1 and M2 merged.
- Work: Gradle tasks that assemble `@julclang/julc-vm-wasm` and `@julclang/julc-wasm` from `wasm-bundle-{vm,full}`
  with the generated `engine.js`, plus `npm pack`.
- Exit: `npm pack` contents listed and checked; I1 checked against locally built archives; the M1 fixtures pass with
  both engine packages; READMEs with size, browser and CSP notes.

**M4: publishing** (D8).
- Entry gate: M3 merged; npm credentials for the scope configured by the developer.
- Work: the release job's identity check and `npm publish`, with the dist-tag policy, provenance and the dry-run
  switch.
- Exit: a dry run on a release candidate lists the expected files and tags. Then the first real publication, by the
  developer's decision.

**M5: documentation and adopters.**
- Work: the npm section of `javascript-wasm.md`, release notes, and an `applyParams` example.
- Optional, in JOUST's own repository and by its decision: `@julclang/joust-runtime` moves its loader onto
  `@julclang/wasm-client`.

## Verification

Independent evidence, not values the code under test produced:
- **Application (I4).**
  - Compile a parameterised validator from `julc-examples` with JuLC, and a parameterised Aiken validator with Aiken
    v1.1.23.
  - For random conforming parameter lists, `vm.applyParams` must match `vm.decode` with `params`, the JVM's
    `ScriptDecoder.decode`, and, for the Aiken blueprint, `aiken blueprint apply` run as a separate process.
  - Hashes are also checked with Python's `hashlib.blake2b(digest_size=28)` over the language tag and the
    single-wrapped script.
- **Schema check (I5).**
  - For each CIP-57 data-schema kind, each applicator and each constraint M2 adopts, the tests generate values that
    conform and values that differ in exactly one place, including wrong constructor indices, wrong field counts, and
    nested `anyOf` and `$ref` chains. A schema with an unknown keyword must fail.
  - Each must be accepted or rejected as expected, with the reported path at the difference.
  - The blueprints JuLC generates for every `@Param` type in `julc-examples` must accept the values their own tests
    apply.
- **Packages (I1, I2, I6, I7).**
  - SHA-256 equality with the archives, checked in CI.
  - The VM parity fixtures run through each package entry in Node 22, Node 24 and Chrome.
  - Vite and webpack fixture builds.
  - A check that no `package.json` has install scripts.
  - A test that an engine package refuses another version's client.
- **No regression.** The existing `julc-wasm` tests, `wasmSmoke{Full,Vm}`, the embedder smoke test and the
  playground's static build keep passing.

## Open questions

1. **Package names.** `@julclang/wasm-client`, `@julclang/julc-vm-wasm` and `@julclang/julc-wasm`, or shorter ones
   (`@julclang/vm`, `@julclang/julc`)?
   - Lean: the proposed names, which match the archive names.
2. **The worker form.** A module worker everywhere, or a classic worker in browsers where a module worker cannot load
   the launcher?
   - Lean: decide in M1, by test.
3. **Addresses.** Should `applyParams` also return a bech32 address for a network ID?
   - The VM image has no cardano-client-lib, so this would need a small bech32 encoder.
   - Lean: no; return the hash, and leave addresses to the application's Cardano library.
4. **Evaluation from transaction CBOR (#244, request 2).** Should it ship in the VM engine?
   - That needs `JulcTransactionEvaluator` and so cardano-client-lib, which I3 keeps out of the VM image.
   - Lean: the full engine only, in its own ADR.
5. **The default for partial application.** Is refusing fewer values than declared, unless `partial: true` is given,
   the right default?
   - Lean: yes, because a missing parameter is more often a mistake than intended.
6. **API version 1.** What makes the API stable?
   - Lean: after M1 to M5 and one release of npm use, with the type aliases unchanged across that release.

## Revision history

- r1 (2026-11-16): first version.
