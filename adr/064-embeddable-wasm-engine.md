# ADR-064: Publish `julc-wasm` so other languages can build their own WebAssembly engine

**Status:** Proposed. Implemented in the same PR; independent review pending.
**Date:** 2026-10-23
**Builds on:** ADR-054 (browser engine), ADR-057 (`julc-wasm` distribution), ADR-056 and ADR-059 (language-neutral
backend). **Changes:** ADR-057's "the engine is JuLC's" assumption in the JavaScript runtime and worker.

## Context and problem

ADR-056 and ADR-059 made JuLC's backend usable from other frontends. Elci (`bloxbean/elci-lang`, an Elm-syntax
language) is the first: it compiles through `julc-compiler`'s backend and evaluates with `julc-vm-java`. Elci now
wants what JuLC's playground has, compilation and evaluation in the browser, shipped as an `elci-wasm-<version>`
distribution beside its native CLI.

A Web Image is closed-world: everything it can run is fixed when it is built, and it cannot load classes later.
So JuLC's released bundles (`julc-wasm-<version>.tar.gz`, `julc-vm-wasm-<version>.tar.gz`) cannot be extended with
Elci's compiler. Elci has to build its own image, from its own class path, with the same JuLC version it compiles
with. Using JuLC's released VM bundle beside it would add a second JuLC version to the page, which the developer
rejected as a source of version mismatch.

Elci's image needs exactly the language-independent parts of `julc-wasm`:

- the operation registry and its JSON wire format (`ApiRegistry`, `JsMarshalling`);
- the Web Image bridge (`JsApi`) and the synchronous JavaScript runtime (`julc-runtime.js`);
- the VM and debugger operations (`VmApi`: `vm.*`, `debug.*`);
- the BLS12-381 substitutions that make the image buildable at all;
- the Promise client and worker (`julc-wasm.js`, `julc-worker.js`).

None of this is published. `julc-wasm` and `julc-tools`, which it builds on, are in the root `nonPublishable`
list, so Elci could only copy them, and copies drift.

## Goals and non-goals

Goals:

- An image for another language is built from the published `org.julclang:julc-wasm` of the JuLC version it
  already uses, adding only its own operations.
- JuLC's own images, bundles and API keep their behaviour.
- The JavaScript client and worker ship in the same artifact as the Java side, so they cannot come from a
  different version than the image.

Non-goals:

- Building Elci's image. That is Elci's PR, which this ADR unblocks.
- A Gradle plugin or reusable task for the Web Image build. The build steps are documented; a second consumer
  would justify packaging them.
- Making `julc-tools` a stable API. It stays experimental, like the rest of JuLC before 1.0.
- BLS12-381 in WebAssembly.

## Current behavior (main at ebaf028c)

- `build.gradle`: `nonPublishable` contains `julc-tools` and `julc-wasm`. The Central scope is 20 projects.
- `JsApi.install(ApiRegistry, String variant)` always installs `globalThis.julc` and reads the version from
  `julc-wasm-version.properties`, so another image would report JuLC's version.
- `julc-runtime.js` maps the variant to groups itself: `full` gets
  `vm, debug, compiler, uplc, sourceDebug, sourceDebugLocals`, anything else `vm, debug`. It adds
  `uplc.defaultTransaction` only for `full`.
- `julc-worker.js` accepts a manifest only if `variant` is `vm` or `full` and the launcher matches
  `^julc(?:-vm)?-[0-9a-f]{12}\.js$`, and calls `self.julc[group][name]`.
- The JavaScript client files live in `src/main/js` and reach a bundle only through `wasmBundle*`.
- `ApiRegistry.add` takes operations that return `org.julclang.tools.service.ServiceResult`. It accepts any name,
  and a second registration of a name replaces the first.
- Jars on `julc-wasm`'s runtime class path that carry native-image metadata: `julc-tools` (119 JavaParser and 45
  `julc-tools` types for reflection), `julc-blueprint`, nine cardano-client-lib modules, the JSON schema validator,
  `julc-bls` (run-time initialisation of its native code) and `julc-wasm` itself (the 49 `tools.model` records and
  5 of its own that the VM operations need). native-image applies all of it, reachable or not. JuLC's VM image keeps
  it out by filtering its class path, stripping `julc-tools`' metadata (`vmToolsJar`) and aborting if compiler,
  decompiler, JavaParser or cardano-client-lib types become reachable (`-H:AbortOnTypeReachable`).
- `julc-tools` pins cardano-client-lib with `strictly '0.8.0-pre6'` as a dependency constraint, which Gradle
  publishes in the module metadata.

## Invariants

- **I1.** JuLC's full and VM images expose the same operations, groups, global (`julc`), variant and version as
  before. The bundles' `engine.json` keeps its variant, `api` and groups; only its content hash changes.
- **I2.** `features().groups` equals the groups the image was built with, and the client keeps refusing an engine
  whose features differ from its `engine.json` (D2).
- **I3.** The JavaScript client files in the `julc-wasm` jar are the files JuLC's bundles ship from the same build
  (D3).
- **I4.** The worker still loads only a plain file name next to the manifest: lowercase words joined by hyphens,
  a 12-hex-digit hash, `.js`. No path, query or scheme (D2).
- **I5.** `julc-wasm` is on Web Image class paths only. Its substitutions apply to every native image it is part
  of, and they disable BLS12-381 (D4).
- **I6.** The Central deployment scope equals the set of projects with a Maven publication
  (`verifyMavenReleasePublicationScope`), now 22.
- **I7.** An image for another language built from the published jars by the README's steps runs its own
  operations, gives the JVM's results for the VM operations, and contains no JavaParser, JuLC compiler, decompiler or
  cardano-client-lib code when the language does not use them (D5).
- **I8.** Every registered operation is in an advertised group, and no group or global replaces or extends a member
  the client, the runtime or every JavaScript object already has (D2a).
- **I9.** The published metadata of `julc-tools` and `julc-wasm` has no strict version constraint (D1a).
- **I10.** When `main` ends with an error before the engine is ready, the client fails with an error instead of
  waiting for its load timeout (D3b).

## Decision

### D1. Publish `julc-tools` and `julc-wasm`

Remove both from `nonPublishable`. They then reach Central, the BloxBean snapshot repository and the staging
repository with sources and javadoc, like every other library. `julc-wasm` depends on `julc-tools` as `api`,
because embedders compile against `ServiceResult` and `VmApi`'s models. The Web Image API and SVM stay
`compileOnly`, so they are absent from the POM; a consumer compiles and runs `ApiRegistry`, `VmApi` and
`JsApi.julcVersion()` on a plain JVM (verified, below).

### D1a. Pin cardano-client-lib only where JuLC builds images (r2)

The strict constraint moves out of `julc-tools`' dependencies into an unpublished resolution rule
(`pinWebImageCardanoClientLib` in the root build), applied by `julc-tools`, `julc-playground` and `julc-wasm`. JuLC's
own builds still resolve 0.8.0-pre6; a consumer that resolves another cardano-client-lib no longer fails (I9).
`julc-bom` now aligns `julc-tools` and `julc-wasm` too.

### D2. The image describes itself with `EngineInfo`

`JsApi.install(ApiRegistry, EngineInfo)` replaces `install(ApiRegistry, String)`. `EngineInfo(global, variant,
version, groups)` validates that `global` and each group are JavaScript identifiers, `variant` is
`[a-z][a-z0-9-]*`, the version is not blank and there is at least one group. `JsApi` installs the API as
`globalThis[global]` and passes it to `onJulcReady`.

`julc-runtime.js` takes the groups as an argument and reports them as given; it adds `uplc.defaultTransaction`
when the groups include `uplc`. JuLC's lists move to `JulcWasm.GROUPS` and `JulcVmWasm.GROUPS`, with the
versions from `JsApi.julcVersion()`, so JuLC's images report what they reported before (I1).

`julc-worker.js` accepts any variant matching `^[a-z][a-z0-9-]*$` and any launcher matching
`^[a-z][a-z0-9]*(?:-[a-z0-9]+)*-[0-9a-f]{12}\.js$` (I4). It calls the API it received in `onJulcReady`, not
`self.julc`, so it works whatever the global is called.

### D2a. Names cannot collide (r2)

`ApiRegistry.add` accepts only `group.operation` with two JavaScript identifiers, and rejects a name registered
twice. `EngineInfo` rejects groups that are members of the client or runtime API (`version`, `features`, `dispose`,
`rest`, `__schemas`) or of every JavaScript object (`constructor`, `toString`, `__proto__`, …), and globals the
runtime, worker or launcher define (`JulcRuntime`, `JulcRestAdapter`, `onJulcReady`, `onJulcExit`,
`JULC_WASM_PATH`, `GraalVM`). `JsApi.install` requires every operation's group to be advertised and the global not
to be defined yet. The runtime and the client check again with `group in api`, which covers every inherited member
without a list (I8).

### D3. The JavaScript client ships in the jar

The jar gets `META-INF/julc-wasm/js/` with `julc-wasm.js`, `julc-worker.js`, `rest-adapter.js`, `julc-wasm.d.ts`
and the generated `julc-models.d.ts`, the files `wasmBundle*` copies (I3). `julc-runtime.js` was already a
resource.

### D3a. The declarations describe any engine (r2)

`julc-wasm.d.ts` describes JuLC's images and others: `Variant` and `FeatureGroup` keep JuLC's names but accept
others, `EngineClient` is what every engine has (`version`, `features`, `dispose`, `rest`), `Operations<T>` turns a
language's synchronous signatures into the client's Promise-returning ones, and `createJulc<C extends EngineClient>`
returns the client type the caller names (default `VmClient | FullClient`, as before). `Manifest.julcVersion` stays
required, and the README's `engine.json` includes it.

### D3b. The launcher hook ships in the jar (r2)

The text inserted into the Web Image launcher moves into `META-INF/julc-wasm/launcher-hook.js`. JuLC's build and an
embedder insert the same file. Besides setting the module path, it wraps `GraalVM.run` and calls `onJulcExit` when
`main` returns with a non-zero exit code. The worker turns that into an error if the engine is not ready yet, so a
`main` that fails (for example on an unregistered record) fails the client at once instead of after 120 s (I10).
The worker's manifest errors now name the field and the rule it breaks.

### D4. Keep the BLS substitutions in `julc-wasm`

They stay in the published jar, and the README states I5. Splitting them out would need a third artifact that
every embedder must add anyway, since no Web Image of the VM builds without them.

### D5. A demo language is built and tested like an embedder's image (r2)

The `embedderFixture` source set holds a language with one operation (`demo.sum`) beside `VmApi`, its own
reflection metadata, global, variant and version. It is not published. `wasmSmokeEmbedder`, part of `wasmSmoke`
and so of CI's native-image builds, builds it as the README tells an embedder to: from the `julc-wasm` jar and its
runtime class path, with the metadata of `julc-tools`, `julc-blueprint`, cardano-client-lib and the JSON schema
validator excluded (`--exclude-config`), `-H:AbortOnTypeReachable` for JavaParser, the compiler, the decompiler and
cardano-client-lib, the launcher hook and client files taken from the jar, and the launcher named `demo-lang-<hash>.js`.
It then runs the image in Node.js: its own operation with exact integers, its 400 paths, and the VM parity fixtures
against the JVM (I7). Measured locally with Oracle GraalVM 25.3.4.1 on macOS: 15,741,926 bytes and 6,160 reachable
types with the exclusion, 19,123,155 bytes and 7,182 types without it (with JavaParser and cardano-client-lib);
JuLC's VM image is 14,272,315 bytes and the full image 24,500,429. The review's estimate of "close to full-image
size" overstated the effect, but the excluded code is dead weight either way. `EmbeddingTest` runs the same class on the JVM. The README documents the reflection metadata,
the exclusion and its size effect, the launcher naming rule and the TypeScript usage.

## Alternatives

- **Copy the parts into Elci.** Rejected: the codec, the substitutions and the worker protocol would drift from
  JuLC's, and nothing would flag it. The developer asked not to repeat them.
- **Use JuLC's released bundles in Elci's pages.** Rejected: closed-world images cannot gain Elci's compiler, and a
  second JuLC version beside Elci's own risks mismatch (developer's decision).
- **Extract a new, `julc-tools`-free `julc-wasm-runtime` module.** Rejected for now: `ApiRegistry` returns
  `ServiceResult`, `JsMarshalling` encodes `MockTransaction.DataInput`, and `VmApi` is built on `julc-tools`'
  UPLC services. A tools-free module would need a second result type and would leave out `VmApi`, which is the
  part Elci most wants unchanged. Revisit if `julc-tools`' surface becomes a burden.
- **A Gradle plugin for the Web Image build.** Deferred: one consumer, and the steps are short (README).
- **Embedders filter their class path, as JuLC's VM image does (r2).** Not the documented default: a language's
  compiler needs some of the same jars (`julc-compiler`, `julc-core`), and a filtered class path must be kept in
  step with the transitive dependencies. Excluding the metadata keeps every jar and drops only what roots unused
  code. Either works; the README's pattern is the one CI checks.
- **Strip the full-image metadata from the published `julc-tools` jar (r2).** Not now: JuLC's playground native
  image relies on it, and cardano-client-lib's and the schema validator's metadata are upstream's, so embedders
  need the exclusion anyway. Open question 3.
- **Register an embedder's records for reflection automatically (r2).** Rejected: `ApiRegistry.add` runs in `main`,
  after the image is analysed, so it cannot register anything; a build-time Feature would have to guess the
  operations. The README step and the fail-fast error (D3b) cover it.

## Affected stages and modules

- `build.gradle` (root): the `nonPublishable` list.
- `julc-wasm`: `EngineInfo` (new), `JsApi`, `JulcWasm`, `JulcVmWasm`, `julc-runtime.js`, `julc-worker.js`, the jar
  contents, the `api` dependency on `julc-tools`, `sourcesJar` depending on `wasmVersion`, the README.
- `julc-tools`: published; no source change.
- No compiler stage, lowering, UPLC, ledger encoding or VM behaviour changes.

## Compatibility

- **Generated UPLC and script hashes:** unchanged; no compiler code changes.
- **JuLC's bundles:** the same operations, groups, global and version (I1). The worker and runtime files change, so
  the bundles' content hash, and therefore the launcher file names, change. Nothing depends on a particular hash.
- **Java API:** `JsApi.install(ApiRegistry, String)` is replaced. It had no callers outside `julc-wasm` and was
  never published.
- **JavaScript:** `JulcRuntime.install` now requires its fifth argument. It is called only by `JsApi` and by the
  codec tests. The worker accepts a superset of the manifests it accepted. Its manifest error message changed from
  "Unsupported or invalid JuLC engine manifest" to "Invalid engine manifest: <reason>"; nothing in the repository
  matches on it. The launcher hook (D3b) changes the launcher text, so the bundles' hash changes again.
- **TypeScript:** the declarations widen (D3a); code that switched exhaustively on `variant` sees a wider type.
- **`ApiRegistry.add`** now rejects malformed and duplicate names; JuLC's images register neither.
- **Gradle:** consumers of `julc-tools` no longer inherit a strict cardano-client-lib constraint (D1a).
- **Maven:** two new artifacts, `org.julclang:julc-tools` and `org.julclang:julc-wasm`. Once a release reaches
  Central they cannot be withdrawn.

## Risks

- **Published surface.** `julc-tools` models become something others compile against. Mitigation: JuLC is
  pre-1.0 and experimental; the ADR marks the surface experimental.
- **Substitutions on the wrong class path (I5).** Putting `julc-wasm` on a native CLI's class path would silently
  disable BLS there. Mitigation: README; open question 1 asks for a build-time guard.
- **Web Image is preview technology.** Elci must pin the same GraalVM 25.3 line JuLC releases with.
- **The worker pattern is wider (I4).** It still admits only a file name in the manifest's directory, and the
  manifest comes from the same origin as the worker. `worker.test.mjs` pins the rejected forms.
- **The exclusion pattern drifts (I7).** A new dependency with native-image metadata would grow embedders' images.
  `wasmSmokeEmbedder` fails if JavaParser, the compiler, the decompiler or cardano-client-lib become reachable; a
  dependency outside those packages would show only as a larger image (logged by the task).

## Implementation milestones

- **M1 (this PR):** D1 to D4, tests, README. Exit: `:julc-wasm:test`, `:julc-tools:test`, both javadocs and
  `verifyMavenReleasePublicationScope` pass; staged artifacts have the expected POM scopes and jar contents; an
  external Gradle project compiles and runs against the staged `julc-wasm`; `native-image-dev.yml` on the branch
  builds both images and passes `wasmSmoke` (the Node parity check against the JVM). r2 adds D1a to D3b and D5 to
  M1: `wasmSmoke` now includes `wasmSmokeEmbedder` (I7), and the published module metadata has no strict
  constraint (I9).
- **M2 (Elci, separate PR):** `elci-wasm` from the published snapshot, with `elci.*` operations, a parity test and
  `elci-wasm-<version>.tar.gz` in Elci's snapshot distribution. Entry gate: M1 merged and a JuLC snapshot
  published.

## Verification strategy

- `EmbeddingTest`: a registry that combines `VmApi` with another language's operation; `vm.evaluate` is
  byte-identical to `JulcVmWasm`'s; malformed bodies and unknown operations are 400 and 404 envelopes;
  `EngineInfo` rejects names JavaScript cannot use; JuLC's groups match the operations each image registers.
- `codec.test.mjs`: the runtime reports the groups it is given, hands out copies, adds `defaultTransaction` only
  with `uplc`, rejects missing groups, serves another language's operations, and rejects operations outside the
  groups and groups that would extend `version`, `features`, `__schemas` or `Object.prototype` members (I8).
- `worker.test.mjs` (r2): the worker rejects launchers with a directory, scheme, query, fragment, trailing newline,
  uppercase, underscore, wrong hash length or extension, and invalid API versions and variants; it loads any
  language's correctly named launcher; it reports `onJulcExit` before ready as an error (I4, I10). Weakening the
  launcher check or the reserved-group check fails these tests (checked by mutation).
- `wasmSmokeEmbedder` (r2, CI): the demo language's image, built from the jars (D5, I7).
- `EmbeddingTest` (r2): the demo language on the JVM, operation names and duplicates, reserved groups and globals,
  `requireAdvertises` (I8).
- TypeScript (r2, local): an embedder usage file type-checks against the new declarations with `tsc --strict` and
  fails against the old ones. Not in CI, which has no TypeScript toolchain.
- `wasmSmoke` (CI, GraalVM 25.3): both images in real Node workers, with `engine.json` and `features()` compared,
  parity fixtures and session lifecycle (I1, I2, I4).
- Staging: POM scopes and jar contents inspected; an external consumer compiled and run (D1, D3).

## Open questions

1. **A build-time guard for I5.** Can the substitutions be limited to Web Image builds, for example with a
   `@TargetClass(onlyWith = ...)` predicate that detects the Web Image platform? This needs a GraalVM 25.3
   build to confirm the predicate. Lean: add it once confirmed, keep the README rule meanwhile.
2. **Should JuLC's release attach the jar's JavaScript files separately?** Not needed while the jar carries them.
   Lean: no.
3. **Should the published `julc-tools` jar stop carrying the full image's metadata (r2)?** Moving it to the builds
   that need it (the full Web Image and the playground's native image) would shorten the embedders' exclusion
   pattern but not remove it. Lean: only if a second embedder finds the pattern a burden.
4. **One source for JuLC's group lists (r2).** `JulcWasm.GROUPS`, `JulcVmWasm.GROUPS` and `build.gradle`'s
   `engine.json` lists must agree; `wasmSmoke` checks `engine.json` against `features()` and `EmbeddingTest` checks
   the Java lists against the registered operations, but only the GraalVM job checks `build.gradle`. Lean: leave it
   until the lists change again.

## Revision history

- r1 (2026-10-23): initial version, with M1.
- r2 (2026-10-23, responds to the review of ddd1eaba): P2-1 → D5, README reflection section, D3b fail-fast;
  P2-2 → D5 and the README exclusion (the review named `julc-tools`; `julc-blueprint`, cardano-client-lib and the
  schema validator turned out to need excluding too); P2-3 → D3a; P3-1 → D2a; P3-2 → `worker.test.mjs`, specific
  worker errors, README naming rule; P3-3 → D1a; P3-4 → open question 4 (deferred); P3-5 → D1a (BOM). Added
  I7–I10 and open question 3. `BUILD_FROM_SOURCE.md` no longer says the Community builds include Web Image.
