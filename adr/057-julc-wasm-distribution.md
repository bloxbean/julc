# ADR-057: `julc-wasm`, one WebAssembly distribution of the toolchain and the VM

**Status:** Implemented locally; independent human review and release validation pending.
Drafted from measurements on `main` 41f8eb9c; revised after review (see Revision history).
The developer approved implementation and the constructor-overflow correction below.
**Builds on:** ADR-054 (browser engine for the playground), ADR-055 (UPLC tools and
step debugger), ADR-053 draft (debug session shape).
**Supersedes:** the `julc-playground-wasm` module and the `julc-playground-engine`
release asset introduced by ADR-054. The REST contract and ADR-054's invariants stay.

## Context and problem

ADR-054 compiled the playground's request layer to WebAssembly with GraalVM Web Image
so that julc.dev can run without a server. The image is built from
`julc-playground-wasm`, which depends on `julc-playground-core`, which depends on the
compiler, `julc-core`, the VM SPI, `julc-vm-java`, stdlib, ledger API, testkit,
blueprint, the decompiler, cardano-client-lib, JavaParser, Jackson and BouncyCastle.
The payload is therefore the whole toolchain, and the JuLC VM is already inside it.

What is playground-specific is the surface. `PlaygroundWasm` installs one function,
`globalThis.julcPlayground.dispatch(method, path, body)`, which returns a JSON string.
Its routes are the playground's REST paths. Any other application embedding the engine
would be issuing fake HTTP requests against a JSON envelope. The release assets carry
that name too: `julc-playground-engine-<version>.tar.gz` and the static playground.

Two facts make a general distribution worth having:

- Every release already ships `julc-vm-java-all-<version>.jar` and
  `julc-compiler-all-<version>.jar` for JVM users who want the VM or the compiler
  without the playground. There is no equivalent for JavaScript.
- A VM-only image is much smaller, and it evaluates Plutus V3 scripts from any
  compiler, since UPLC is the same whether it came from JuLC, Aiken, Plutus or Scalus.
  Wallets, dApp frontends, explorers and JavaScript test harnesses have compiled
  scripts and no use for a Java compiler.

Measured on 2026-09-22 with GraalVM 25.3.4.1, both images built from `main` 41f8eb9c:

| Image | Reachable modules | `.wasm` | Launcher | Total | gzip |
|---|---|---|---|---|---|
| VM probe (decode FLAT, evaluate; no debugger, no pretty printer) | julc-core, julc-vm, julc-vm-java, julc-bls, cbor-java, BouncyCastle | 11.58 MB | 112 kB | 11.69 MB | 4.32 MB |
| Playground engine | everything above plus compiler, stdlib, ledger API, testkit, blueprint, decompiler, cardano-client-lib, JavaParser, Jackson | 21.29 MB | 113 kB | 21.40 MB | 7.47 MB |

The probe built in 25.5 s. In Node 22.12 it was ready in 129 ms and returned
`Failure`, `Success`, `Success` for `coop-1`, `auction_1-1` and `escrow-redeem_1-1`
from the benchmark corpus, identical to the JVM with the same classpath. The probe
covers less than the VM variant promised below, so 11.6 MB is a lower bound, not the
size of the deliverable; see Verification.

The Web Image interop API in GraalVM 25.3.4.1 supports `@JS` glue snippets,
`@JS.Coerce` for primitives, `String` and `BigInteger`, `JSObject` with typed `get`
and `set` for structured values, `JSBigInt`, and functional interfaces as callables.
The GraalVM API guide states that `@JS.Export`, `@JS.Import` and typed `JSObject`
subclasses are not implemented yet and recommends installing application functions
from `main()` through a small `@JS` bootstrap. That is the pattern the playground uses.

Five facts about the current code shape the API contract below:

- `JulcVm`'s language-only path defaults to PV10 when no cost model is configured.
  The UPLC tools take the language from `ScriptDecoder`, whose sources are, in order,
  a user override, a matching blueprint hash, an envelope or blueprint hint, the program version, the
  builtins used, and a default; only the protocol defaults, to PV11. The named
  cost-profile catalogue holds V3/PV11 profiles only; V1, V2 and PV10 requests use
  the provider's built-in default model for their target.
- Evaluation failure is a result, not an error: `EvaluateResponse` and the UPLC
  `EvaluateResponse` come back with HTTP 200 carrying a success or acceptance flag,
  consumed budget, traces, error text, diagnostics and the failed span. Compile
  diagnostics during Run Test are also a 200 result. `ServiceResult` uses non-200
  statuses for invalid requests (400), unknown names (404), blueprint-generation
  validation inside compile (422), and a compile crash (500 through
  `ServiceResult.failed`, which also carries the cause for logging).
- Plutus Data enters as `DataInput(format, value)`: encoded text in JSON, UPLC data
  notation or CBOR hex, detected when the format is `auto`. Integers inside that text
  are exact by construction. `DataInputs.parse` decodes the JSON form through
  cardano-client-lib's `PlutusDataJsonConverter` and `PlutusDataAdapter`; the CBOR and
  UPLC forms use `julc-core` only. `ScriptDecoder` applies script parameters through
  `DataInputs`, so decoding a parameterised script reaches cardano-client-lib today.
- `UplcToolsService.prepare` always calls `MockContextBuilder.build`, for evaluation
  and for debugging, so today's debugger cannot take caller-supplied arguments.
  Debug sessions are cached by a key of script, transaction, protocol and budget, and
  every request is stateless from the client's point of view.
- The playground transport times a request out after 30 s and terminates the worker,
  which restarts on the next request. Objects that carry functions cannot cross a
  worker boundary: `structuredClone` of a method-bearing object throws
  `DataCloneError`.

No issue or ADR covers a standalone WebAssembly build or a JavaScript SDK.

## Goals and non-goals

Goals:

- One module, `julc-wasm`, owns the JavaScript API and the Web Image build. It does
  not depend on any module named playground.
- A typed JavaScript API: one method per operation, plain objects in and out, `BigInt`
  for every integer that may exceed 2^53, lossless across the boundary, errors thrown
  only for invalid requests, TypeScript declarations.
- Every current endpoint has a named replacement, or is explicitly moved to the
  playground; nothing is dropped silently.
- Two build variants from the same source: `julc-wasm` (toolchain) and `julc-vm-wasm`
  (VM only). The VM variant is a strict subset with the same method names.
- Evaluation and debugging take an explicit ledger target and cost model with stated
  defaults, identical between the two operations.
- The playground consumes `julc-wasm` through the typed API; browser and server keep
  returning identical results, including failure results.
- Both variants are release assets with checksums and content-addressed file names,
  and the documentation deploy keeps working across the rename.
- The VM variant runs any Plutus V3 script within the supported capability set.

Non-goals:

- BLS12-381 builtins in WebAssembly. They keep failing with the documented message in
  both variants, and every parity claim in this ADR is qualified by that exclusion.
- Publishing to npm. Deferred until the API has had at least one release of use.
- A compiler-only variant. Nothing needs it.
- Any change to the REST API, compiler output, VM semantics, stdlib or ledger types.
- Truffle or Scalus in WebAssembly. Only the Java CEK machine compiles to it.
- Migrating to `@JS.Export`. It is not implemented and has no date; see Compatibility.

## Current behavior

- `julc-playground-core` (`org.julclang.playground.*`) holds the request and response
  records, `PlaygroundService` (check, compile, evaluate, expression eval),
  `UplcToolsService` with `ScriptDecoder`, `MockContextBuilder`, `DataInputs` and
  `DebugValues` (ADR-055), `ScenarioContextBuilder`, `JavaMetadataExtractor`,
  `InputValidator`, `ServiceResult` (HTTP status mapping), and the playground-only
  `ExampleCatalog`, `ScenarioRegistry` and ten example contracts under resources.
- The server exposes `POST /api/check`, `/api/compile`, `/api/evaluate`, `/api/eval`,
  `/api/uplc/decode`, `/api/uplc/decompile`, `/api/uplc/evaluate`, `/api/uplc/debug`
  and `GET /api/examples`, `/api/examples/{name}`, `/api/scenarios/{purpose}`,
  `/api/health`, plus static routes. `PlaygroundDispatcher` serves the same paths in
  the browser.
- `julc-playground-wasm` holds `PlaygroundDispatcher`, `PlaygroundWasm` (the `@JS`
  bootstrap), two substitutions that keep native BLS out of the image
  (`Bls12381Builtins.bls` and `BlsConstantValidator`), reachability metadata, the
  parity fixtures (39 requests), `PlaygroundDispatcherTest` and the Node smoke
  `wasm-smoke.mjs`, which special-cases the BLS fixture. Its Gradle tasks are
  `wasmImage`, `wasmBundle`, `wasmParityExpected` and `wasmSmoke`; the build refuses
  any GraalVM outside the 25.3 line by reading `GRAALVM_VERSION` from `<home>/release`.
- The playground worker (`frontend/public/wasm/playground-worker.js`) imports the
  launcher, waits for `onJulcPlaygroundReady`, and forwards every request to
  `engine.dispatch(method, path, body)`. `transport.ts` is the single call site.
- CI: the `playground-wasm` job in `native-image-release.yml` and
  `native-image-dev.yml` builds the engine with `graalvm/setup-graalvm` 25.3, runs
  `wasmSmoke`, builds the static playground, uploads the `julc-playground-engine`
  workflow artifact for the platform native-image jobs, and attaches
  `julc-playground-engine-<v>.tar.gz`, `julc-playground-static-<v>.tar.gz` and
  `julc-playground-wasm-<v>-SHA256SUMS.txt` to the GitHub release.
- `docs-deploy.yml` downloads, by exact name, the static bundle and
  `julc-playground-wasm-<v>-SHA256SUMS.txt` for the release pinned in
  `docs/playground-release`, and verifies the bundle against that file.
- `julc-playground`, `julc-playground-core` and `julc-playground-wasm` are all in the
  root build's non-publishable list, so none of them reaches Maven Central.

## Invariants

1. Within the supported capability set, both WebAssembly variants return the same
   value, consumed budget, traces and failed term as `julc-vm-java` on the JVM for the
   same script, arguments, target, budget and cost model. The capability set is every
   Plutus V3 builtin except BLS12-381; a BLS builtin call fails in both variants with
   the documented message, and the parity fixtures carry that one exception, as they
   do today.
2. The playground's browser engine and server return identical response bodies and
   statuses for identical requests. The browser side reconstructs the REST envelope
   from typed results and thrown errors through one adapter, and the parity tests run
   through that adapter, including failure results with budgets and spans.
3. Compiler output, script hashes, VM semantics and ledger encodings do not change.
   The sole approved behavior correction is exact JSON constructor tags instead of
   signed-long overflow (see the correctness gate below). Both engines apply it.
4. `julc-tools` and `julc-wasm` never depend on `julc-playground`. The dependency
   direction is playground to tools, and wasm to tools.
5. The VM variant exposes a subset of the full API under the same names. Method groups
   absent from a variant are undefined, and `julc.features()` reports what is present.
6. Public values are plain JavaScript objects. Every field whose Java type is `long`,
   `Long` or `BigInteger`, including elements of collections and arrays of those
   types, is a `BigInt` and crosses the boundary without loss. Fields whose Java type
   is `int` stay `Number`. Plutus Data is accepted as encoded text, exact by
   construction, or as a structured object whose integers are `BigInt`. No JSON
   string crosses the public boundary.
7. A script that ran and failed is a result, not an exception. Exceptions are thrown
   only for requests the service would answer with a non-200 status, and they carry
   the status and body that the REST layer would have returned.
8. Every evaluation and debugging request resolves to one explicit ledger target and
   one identified cost model, echoed in the result. The language comes from the
   script exactly as `ScriptDecoder` resolves it today unless the caller overrides
   it; the protocol defaults to 11; the cost model defaults to the provider's model
   for the resolved target. The WebAssembly API never uses the JVM's language-only
   default path, and existing V1 and V2 requests keep their current behavior.
9. Each variant ships content-addressed file names and an `engine.json` manifest that
   records the variant, the API version and the julc version. Release assets carry
   SHA-256 sums, and the documentation deploy accepts the checksum file of any release
   from pre17 onward.
10. Both variants are built with the same GraalVM line, enforced by the existing
    version assertion, and verified against the JVM before release.
11. A debug session is identified by a descriptor that can cross the worker boundary;
    the client holds a proxy whose methods send actions. A session never outlives the
    worker that created it, and a request against a lost session fails with
    `not-found` rather than replaying silently.

## Decision

### Modules

**`julc-tools`**, renamed from `julc-playground-core`, packages `org.julclang.tools.*`.
Keeps: the request and response records, `PlaygroundService` renamed `ToolsService`,
`UplcToolsService`, `ScriptDecoder`, `MockContextBuilder`, `DataInputs`,
`DebugValues`, `ScenarioContextBuilder` (a general mock ScriptContext builder),
`JavaMetadataExtractor`, `InputValidator`, `PlaygroundEvaluator` renamed
`ExpressionEvaluator`, and `ServiceResult`. Leaves: `ExampleCatalog`,
`ScenarioRegistry`, the example resources, and the DTOs used only by them
(`ExampleDto`, `ScenarioDto`, `ScenariosResponse`), which move to
`julc-playground`. Its dependencies are unchanged, including the strict
cardano-client-lib 0.8.0-pre5 constraint from ADR-054.
`VariantDto` stays in `julc-tools`: `CheckResponse`, `JavaMetadataExtractor`, and
`ScenarioContextBuilder` use it for redeemer metadata independently of the catalogue.

One behavioral addition in `julc-tools`, required by the VM variant:
`UplcToolsService.prepare` is split into `prepareScript(script, target, budget)`,
shared by evaluation and debugging, and two argument sources: `withTransaction(mock)`
(the current path through `MockContextBuilder`) and `withArguments(args)`, which takes
caller-supplied Plutus Data arguments and touches nothing from cardano-client-lib.
Both produce the same `Prepared` value, so the debugger and the evaluator share one
preparation regardless of source. The REST endpoints keep using the transaction path
and are unaffected.

A second addition, required for the same reason: `julc-tools` gains a
dependency-free `PlutusDataJson` codec for the detailed JSON form of Plutus Data
(`int`, `bytes`, `list`, `map` with `k` and `v`, and `constructor` with `fields`),
built on Jackson's tree model, which is already in every image. `DataInputs.parse`
uses it for the JSON branch on the server and in both WebAssembly variants;
cardano-client-lib is then reachable only from `MockContextBuilder` and the
blueprint path. A test parses a corpus of Data values through the new codec and
through cardano-client-lib and asserts identical `PlutusData`, except for the explicitly
approved constructor-overflow correction below. With this, `ScriptDecoder`, including parameter application, sits
inside the VM variant's reachability gate.

**`julc-wasm`**, new, replaces `julc-playground-wasm`. Contents:

- `JulcWasm` and `JulcVmWasm`: the two `main` entry points. Each installs its method
  groups and then signals readiness. The wrapper measures elapsed load time externally. `JulcVmWasm`
  references only the raw-argument preparation, the decoder with the
  dependency-free Data codec, the pretty printer and the stepping machine, never the
  mock-transaction builder, the decompiler or cardano-client-lib.
- `JsApi`: the only class containing `@JS` annotations. One bootstrap installs a
  registry of method groups. This isolation is deliberate; see Compatibility.
- `JsMarshalling`: converts JavaScript objects to the `julc-tools` records and back
  through the codec defined below. Individual methods may later move to direct
  `JSObject` mapping without changing the surface.
- The two BLS substitutions and the reachability metadata, moved as-is, with a
  per-variant metadata file so the VM variant registers only what it reaches.
- `julc-wasm.d.ts` (TypeScript declarations) and `julc-wasm.js`, a small wrapper that
  runs a chosen variant in a Web Worker and exposes a Promise-based client,
  including the debug session proxies defined below. The playground uses the
  wrapper; other applications may use the wrapper or the raw globals.
- `RestAdapter` (JavaScript, in the wrapper): maps a typed result or thrown error back
  to the `{status, body}` envelope of the REST contract. The playground's worker uses
  it, and the parity tests exercise it.
- Tests: the parity fixtures and `ApiTest` (renamed dispatcher test) against the full
  variant through the adapter; `VmApiTest` and a Node smoke for the VM variant
  against benchmark and conformance scripts, asserting JVM-identical outcome, budget
  and traces; codec tests at and beyond 2^53 and at the `long` boundaries; a
  reachability test on the VM image.

**`julc-playground`** keeps the server, the frontend, the examples and the scenario
catalogue. The worker imports the `julc-wasm` launcher and calls typed methods; the
static build bundles examples and scenarios as files instead of asking the engine.
The Javalin controllers stay thin adapters over `julc-tools`.

### Endpoint mapping

Every current route has exactly one destination. Nothing is dropped.

| Current route | Replacement | Variant |
|---|---|---|
| `POST /api/check` | `julc.compiler.check(request)` | full |
| `POST /api/compile` | `julc.compiler.compile(request)` | full |
| `POST /api/evaluate` (Run Test: source, parameters, datum/redeemer/context from metadata, signers) | `julc.compiler.evaluate(request)`, backed by `ToolsService.evaluate` | full |
| `POST /api/eval` | `julc.compiler.evalExpression(request)` | full |
| `POST /api/uplc/decode` | `julc.vm.decode(request)` | both |
| `POST /api/uplc/decompile` | `julc.uplc.decompile(request)` | full |
| `POST /api/uplc/evaluate` (mock transaction) | `julc.uplc.evaluateTransaction(request)` | full |
| same, caller-supplied arguments | `julc.vm.evaluate(request)` | both |
| `POST /api/uplc/debug` (mock transaction) | `julc.uplc.debugTransaction(request)` returning a session | full |
| same, caller-supplied arguments | `julc.vm.debug(request)` returning a session | both |
| `GET /api/examples`, `/api/examples/{name}`, `/api/scenarios/{purpose}` | static files bundled by `julc-playground`; not part of `julc-wasm` | playground |
| `GET /api/health` | `julc.version()` and `julc.features()` | both |
| `GET /`, `/assets/*`, `/wasm/*`, `/{path}` | server-only static routes; unchanged | server |

Additional methods with no current route: `julc.vm.hash({script})`,
`julc.vm.prettyPrint({script, width})`, both from the decoder and pretty printer
already used by decode, and `julc.uplc.defaultTransaction({purpose, scriptHash})`,
which returns the prefilled mock transaction the playground builds client-side today.

### JavaScript API, version 0, experimental

All methods live on `globalThis.julc`. Requests and results are the current records
with the same field names, so the playground's payloads are the schema.

**Target and cost model.** `julc.vm.evaluate`, `julc.vm.debug`,
`julc.uplc.evaluateTransaction` and `julc.uplc.debugTransaction` take an optional
`target: { language?, protocol? }` and an optional `costModel`. Resolution is the
same on every path and matches today's tools:

- `language`, when absent, is what `ScriptDecoder` resolves from the script: the
  override in the script input, a matching blueprint hash, an envelope or blueprint hint, the program version, the
  builtins, or the default, in that order. A caller-supplied `language` behaves like
  the existing user override. Raw FLAT with no hint resolves as it does today.
- `protocol` defaults to 11; 10 is accepted; anything else is `invalid-request`.
- `costModel`, when absent, is the provider's built-in model for the resolved
  target, which is what V1, V2, PV10 and PV11 requests get today. A named profile
  from the cost-profile catalogue is accepted only when its target matches the
  resolved target; the catalogue currently holds V3/PV11 profiles only. An explicit
  parameter array must state its target. A mismatch is `invalid-request`, mirroring
  the VM's `UnsupportedLedgerTargetException`.

Results echo the resolved `target` and a `costModelId` (`default:<target>`, the
profile name, or `explicit`). The JVM's language-only PV10 default is never used. A
debug session is opened with the same fields as an evaluation and replays with them,
so stepping a script and evaluating it use identical inputs. `RestAdapter` maps the
existing `protocolVersion` field and the script input's language field onto these
fields and back, so existing V1 and V2 requests resolve exactly as before.

**Integers.** Public values follow invariant 6. Concretely, `budgetCpu`, `budgetMem`,
`maxCpu`, `maxMem`, `cpu`, `mem`, `cpuDelta`, `memDelta`, `step`, `totalSteps`,
`errorStep`, every element of `traceSteps`, and every element of an explicit cost
model parameter array are `BigInt`; `scriptSizeBytes`, `flatBytes`, `termCount`,
`paramsApplied`, `stackDepth`, line and column fields, and breakpoint lines are
`Number`.

**Plutus Data.** Version 0 accepts Data in two forms. The text form is today's
`{ format, value }` with `json`, `uplc`, `cbor` or `auto`; integers inside the text
are exact by construction and the value is passed through untouched. The structured
form is a plain object in the detailed JSON shape, `{ int }`, `{ bytes }`,
`{ list }`, `{ map: [{ k, v }] }` or `{ constructor, fields }`, recursively, with
`int` and `constructor` as `BigInt`; constructor tags must fit unsigned Word64
(`0` through `2^64 - 1`). The glue renders it to detailed JSON text with decimal digits, so
it is exact as well. Data in results (`result`, environment values, snapshot values)
stays text in version 0. The `BigInt` guarantee therefore covers record fields and
the structured form; it is not claimed for integers inside text the caller supplied.

**Codec.** Before text serialization, `JsMarshalling` walks Jackson's typed tree,
replacing every `LongNode` and `BigIntegerNode` with decimal text and recording its
path in the private envelope. The glue restores these paths to `BigInt` on output.
On input it uses schemas derived from record generic types and converts `BigInt`
to decimal strings accepted by Jackson. This covers `List<Long>`, `long[]`, and
dynamically assembled response maps without a hand-maintained output field table.
The normal build generates TypeScript declarations from every tools model record
and validates its recursive codec schema, failing on unsupported field types.
Boundary tests independently exercise scalar, list, array and nested values. The JavaScript side rejects a
non-integer or out-of-range value for a `long` position with `invalid-request`
before the call reaches Java. Codec tests cover 2^53 - 1, 2^53 + 1, 2^63 - 1,
-2^63, a `traceSteps` list and a cost array with elements above 2^53, and a
structured Data value with an integer above 2^64 nested in a list, a map and a
constructor. Plain `JSON.stringify` rejects `BigInt` and `JSON.parse` rounds
integers above 2^53, so this codec is the only path across the boundary. The REST
layer keeps its JSON numbers; `RestAdapter` converts `BigInt` back to `Number` for
the REST body, reproducing the rounding of the existing JSON responses when parsed
by JavaScript. This legacy conversion is deliberately separate from the exact typed
API. Budgets are not assumed to fit the safe-integer range: parity tests explicitly
cover values above `2^53`, while typed API tests require exact round trips.

**Results and errors.** A method returns a result object whenever the service would
answer with HTTP 200. That includes every failed evaluation and every Run Test that
stops on compile diagnostics: `success: false` or `accepted: false`, consumed
budget, traces, error text, diagnostics and `failedSpan` are fields of the result,
exactly as in the REST bodies today. A method throws only when the service would
answer with a non-200 status. The thrown `Error` carries `code`, `status` and
`body`:

| Service status | `code` | Where it arises today |
|---|---|---|
| 400 | `invalid-request` | malformed request, missing parameters, bad data, bad target or cost model; Run Test's `IllegalArgumentException` branch |
| 404 | `not-found` | unknown name; a debug session that no longer exists |
| 422 | `blueprint-error` | blueprint-generation validation inside `compile` when the blueprint is enabled; `body` is the compile error form |
| 500 | `internal` | a compile crash reported through `ServiceResult.failed`; message sanitised as today, cause logged on the server only |

The wrapper serialises a worker reply as `{ id, result }` or
`{ id, error: { code, status, body, message } }`. `RestAdapter` turns either into the
`{status, body}` envelope, so the playground and the parity tests see the REST contract
unchanged. `ServiceResult` remains the source of truth for statuses; the API's codes
are derived from it, never the reverse.

**Debug sessions.** Session objects carry methods, so they cannot be posted across
the worker boundary. The protocol is therefore:

- Inside the image, `julc.vm.debug` and `julc.uplc.debugTransaction` return a
  serialisable descriptor `{ sessionId, timeline, snapshot }`, and a companion
  `julc.debug.act({ sessionId, action, step, breakpoints })` returns
  `{ timeline, snapshot }` or throws `not-found`. `julc.debug.close({ sessionId })`
  discards the replay cache entry.
- The wrapper builds the client-side session from the descriptor: a proxy whose
  `step`, `goto`, `continue`, `over`, `out`, `snapshot` and `close` methods post
  `{ id, method: 'debug.act', body: { sessionId, action, ... } }` messages and resolve with the reply.
  Only descriptors and action messages ever cross the boundary.
- Sessions live in the worker. A worker restart, whether after the 30 s timeout or
  an explicit client `dispose()`, invalidates every session; session `close()` only
  closes that session. The next action against a lost session rejects with
  `not-found`, and the proxy exposes `isOpen()`. Reopening replays from step zero,
  as ADR-055 does today, with the same target, cost model and budget as the
  corresponding evaluation.
- `RestAdapter` translates the existing stateless `DebugRequest` into these
  operations: it opens or reuses a session keyed exactly as the server cache is,
  by script, transaction, protocol and budget, sends the request's `action`, `step`
  and `breakpoints` as one `act`, and reconstructs the `{ ok, error, timeline,
  snapshot }` body. The parity fixtures for `/api/uplc/debug` therefore run through
  the real worker protocol.

**Capabilities.** `julc.features()` returns the present method groups, the default
protocol, the API version, and `bls: false`.

### Build

Gradle registers the tasks per variant: `wasmImageFull`, `wasmImageVm`,
`wasmBundleFull`, `wasmBundleVm`, `wasmSmokeFull`, `wasmSmokeVm`, with `wasmBundle`
and `wasmSmoke` as aggregates. Output names are `julc-<sha>.js[.wasm]` and
`julc-vm-<sha>.js[.wasm]`, each with its own `engine.json`. `-PwithWasm` on the
playground depends on the full bundle only. The GraalVM version assertion and the
launcher patch that lets a worker choose the module URL are unchanged. Each image
build also writes the reachable-class report that Web Image can emit, and the VM
build fails if any class from cardano-client-lib, the decompiler, JavaParser or the
compiler module is reachable.

### CI and release

The `playground-wasm` job becomes `wasm`. It builds both variants, runs both smokes,
builds the static playground, uploads the full bundle as the `julc-wasm-full` workflow
artifact the platform jobs consume, and attaches `julc-wasm-<v>.tar.gz`,
`julc-vm-wasm-<v>.tar.gz`, `julc-playground-static-<v>.tar.gz` and
`julc-wasm-<v>-SHA256SUMS.txt` to the release. The `julc-playground-engine` asset
name is retired, not kept alongside. The job summary prints both image sizes so growth
is visible per release.

`docs-deploy.yml` changes in the same milestone: it downloads the checksum file with
the glob `*-SHA256SUMS.txt` instead of the exact old name, and still verifies the
static bundle against whichever file it received. A release from pre17 onward
therefore deploys with either checksum name, so `docs/playground-release` may point at
an older release without breaking. This change ships before the rename, in milestone 1,
so there is never a workflow revision that only understands one name.

## Alternatives

- **Keep `julc-playground-wasm` and add a separate VM module.** Rejected: two glue
  layers and two builds that drift apart, for code that is the same.
- **One full image only.** Rejected: VM consumers would download almost twice the
  bytes for a compiler they never call. The measured saving on the probe is 45 percent.
- **Rename the module without splitting `julc-playground-core`.** Rejected: a module
  named wasm depending on a module named playground misleads readers and drags the
  example catalogue into every image.
- **Keep the JSON-string envelope as the public API.** Rejected: callers would parse
  strings and have no types. JSON stays an internal marshalling detail.
- **Plain `JSON.stringify` and `JSON.parse` inside the glue.** Rejected: it throws on
  `BigInt` and rounds integers above 2^53, so the public `BigInt` contract could not
  be met.
- **Throw on every failed evaluation.** Rejected: a script that ran and failed is a
  result with a budget and a span; throwing would lose that and would not reconstruct
  the REST bodies the parity tests compare.
- **Infer the target from the script's language, as the JVM language-only path does.**
  Rejected: it defaults to PV10 while the tools default to PV11, so the same script
  could evaluate differently between the JVM helper and the browser.
- **Keep the debugger on the mock-transaction path only.** Rejected for the VM
  variant: it would pull cardano-client-lib into the VM image and could not debug
  caller-supplied arguments.
- **Keep cardano-client-lib for Plutus Data JSON.** Rejected: it makes every
  parameterised script decode reach cardano-client-lib, so the VM variant could not
  exclude it; the detailed JSON form is small and Jackson is already present.
- **Force PlutusV3 as the default target on every path.** Rejected: today's tools
  take the language from the script, so a V2 envelope would silently evaluate as V3.
- **Return session objects from the worker.** Rejected: method-bearing objects fail
  structured cloning; the descriptor-and-proxy protocol is the only shape that
  crosses a worker boundary.
- **Wait for `@JS.Export`.** Rejected: the GraalVM guide says it is not implemented,
  and the helper pattern is what the guide recommends today.
- **A compiler-only third variant.** Rejected until a consumer asks for it.
- **Direct `JSObject` marshalling from day one.** Deferred: it means hand-written
  mapping for every record before the schemas have settled. It can replace the codec
  path per method later with no public change.
- **Publish the WebAssembly bundle to Maven Central.** Rejected: it is not a JVM
  artifact; GitHub release assets now, npm later.

## Affected stages and modules

`settings.gradle` and the root `build.gradle` non-publishable list;
`julc-playground-core` renamed to `julc-tools` with package moves and the preparation
split; `julc-playground` (worker, transport, examples, scenarios, controllers'
imports); `julc-wasm` new; `julc-playground-wasm` deleted;
`native-image-release.yml`, `native-image-dev.yml`, `docs-deploy.yml`;
`julc-playground/BUILD_FROM_SOURCE.md`; ADR-054 and ADR-055 gain a status note
pointing here. No compiler, VM, stdlib, ledger or serializer module changes.

## Compatibility

- The REST API and the static playground are unchanged for users, including failure
  bodies and statuses, which the adapter reproduces and the parity tests check.
- Maven Central is unaffected: every module touched is non-publishable.
- Release asset names change. The documentation deploy is the only consumer of an
  asset; it is updated first and accepts both checksum names.
- The JavaScript API is experimental for its first two releases. `engine.json` carries
  an `api` version so a wrapper can refuse an engine it does not understand.
- When `@JS.Export` is implemented, only `JsApi` changes: the bootstrap snippets are
  replaced by annotations and the wrapper reads the VM's exports object. Method names,
  records, the codec and the wrapper's Promise interface are unaffected.
- The `org.julclang.playground` to `org.julclang.tools` package move breaks nothing
  outside the repository, since those packages were never published.

## Risks

- Web Image is preview technology. Its interop API may change between GraalVM lines.
  Mitigation: the single GraalVM line and the version assertion, plus `JsApi` as the
  only file that touches the interop annotations.
- Browser floor: WebAssembly garbage collection and exception handling, tested with
  the GraalVM 25.3 output. Unsupported runtimes report a worker load error; support
  is not inferred from a browser version string.
- BLS builtins remain unsupported; `features()` says so, the error message is kept,
  and every parity claim is qualified accordingly.
- The VM variant may not reach the probe's size once the debugger, pretty printer and
  codec are included. The reachability check bounds what can leak in; milestone 3
  records the real size before the saving is claimed anywhere.
- Precision loss in integer positions is silent if the codec table is incomplete,
  and collections are the easy place to miss. Mitigation: the reflection test walks
  generic types to any depth, and the boundary tests include a list and an array.
- The Data JSON codec could diverge from cardano-client-lib's parser on edge cases.
  Mitigation: the equivalence corpus test, kept as a normal-build test.
- Debug sessions vanish on a worker restart. Mitigation: `not-found` plus `isOpen()`,
  and the adapter's reopen-from-zero behavior, which is what the server does today.
- Full-image growth as tools are added. The job summary reports sizes each release.
- The package rename touches many imports. It is one mechanical commit with no logic
  change, verified by unchanged test counts.
- Reachability metadata differs per variant; a missing service file shows up as a
  runtime error only in the image. Both smokes cover every method group.
- Whether Web Image output is byte-reproducible across builds is unknown. Content
  addressing keeps caching safe regardless; reproducibility is an open question.

## Implementation milestones

### Correctness gate discovered before implementation

The existing JSON boundary does not preserve all constructor tags. Verified against
the pinned cardano-client-lib 0.8.0-pre5 dependency:

```text
DataInputs.parse({format: "json", value:
  '{"constructor":18446744073709551615,"fields":[]}'})
  -> ConstrData(constructorTag = -1, fields = [])
```

`DataInputCompatibilityTest` records this existing behavior and separately verifies
that ordinary Data integers above `2^64` remain exact. The adapter reads the client
library's signed `long` alternative, so replacing this path with exact decoding
changes the meaning of an existing request. Equality with the old parser and exact
unsigned-Word64 constructor decoding cannot both hold for this input.

**Approved by the developer:** a narrowly documented compatibility correction for
constructor-tag overflow. The correction is
to retain the exact JSON constructor integer instead of wrapping through signed
`long`; the new structured API additionally validates the unsigned-Word64 range.
Parser equivalence excludes this defect explicitly; exact-tag regression tests
replace the legacy characterization assertion. Parameterized script bytes/hashes may change for
affected JSON inputs; ordinary tags and the compiler/VM semantics do not change.

The decision unblocks the parser replacement and dependent WebAssembly migration.
It does not change the compiler, VM, or ledger representations. Parity compares both
engines after this correction; it does not preserve the old overflow defect.

### Milestones

1. **Split and prepare.** Rename `julc-playground-core` to `julc-tools`, move the
   playground-only pieces into `julc-playground`, package moves, the preparation split
   in `UplcToolsService` with tests for the raw-argument path, the `PlutusDataJson`
   codec with its equivalence corpus, and the `docs-deploy.yml` checksum glob. No
   WebAssembly change. All existing tests keep their counts.
2. **`julc-wasm`, full variant.** Typed API over the codec, `JsApi` bootstrap,
   substitutions and metadata moved, `RestAdapter` including target resolution and
   the debug session protocol, parity fixtures and smoke ported through the adapter
   and the real worker, TypeScript declarations, wrapper with session proxies,
   playground worker switched, `julc-playground-wasm` deleted.
3. **VM variant.** Second entry point, second bundle, per-variant metadata, the
   reachability check, VM smoke against benchmark and conformance scripts with
   JVM-identical results, and the measured size recorded in this ADR's evidence.
4. **CI and docs.** Job rename, new assets and checksum file, build guide, ADR-054
   and ADR-055 notes.
5. **Optional.** npm publication; direct `JSObject` marshalling for hot methods.

### Implementation evidence and remaining release checks

Milestones 1–4 are implemented in the working tree. The Java compiler, core, VM,
stdlib and ledger modules have no source changes. Implementation naming is
`EvaluationPreparation.script/raw`, `Prepared.withArguments`, and
`UplcToolsService.prepareTransaction`; these are the two preparation paths above.
Each prepared request owns a separate provider so custom cost parameters cannot
leak into a later request or an already-open debug replay.

Local verification uses GraalVM 25.3.4.1, Node 22.12.0, and the Gradle wrapper:

- `build -PskipSigning=true`: passed; external-devnet tests remain opt-in and were not enabled.
- `:julc-wasm:wasmSmoke`: both variants passed the 39 full-image REST fixtures
  (documented BLS exception), shared benchmark/conformance evaluation and replay
  comparisons, structured/text Data, parameterized decoding in all three text
  formats, explicit cost arrays, target resolution, error envelopes and worker/session lifecycle.
- `:julc-tools:test`, `:julc-playground:test`, `:julc-wasm:test`: passed, including
  recursive schema/declaration generation and the legacy Data equivalence corpus.
- Frontend and static builds passed. Generated declarations passed `tsc --noEmit
  --strict --target es2020 --moduleResolution node`.
- Manual static-playground check in Chrome: catalogue load, compilation, successful
  signed Run Test, Quick Eval, UPLC run, debug initialization, step and continue to
  failure with trace and budget displayed. Server contracts are also covered by
  the Javalin parity suite; a fresh manual server-UI matrix has not been run.
- The VM image passed the build-time forbidden-reachability gate. Measured raw
  `.wasm` sizes: full **21,272,932 bytes**, VM **14,102,925 bytes**, excluding launchers
  and SDK assets. These replace the earlier incomplete probe as deliverable evidence.
  Rebuilds can differ in bytes; byte reproducibility is not claimed.

Still required before a release: independent human review, execution of the updated
GitHub release workflow on its Linux runner, and documentation-deploy download/checksum
dry runs against pre17 and the first release carrying the new asset names. No release
was published and no external devnet was modified during implementation. npm publication
and direct interop marshalling remain deferred, as planned.

## Verification strategy

- Repository-wide `build` and `:julc-wasm:test` require Node.js 22+ for JavaScript
  codec/adapter tests. Build CI installs Node.js 22 explicitly. Both Wasm smoke
  tasks also require these tests; missing Node fails rather than silently skipping.
- Parity: the 39 request fixtures through the Javalin controllers and through the
  typed API plus `RestAdapter` produce equal `{status, body}` envelopes, including the
  failure fixtures with their budgets and spans; the Node smoke repeats this on the
  full image with the BLS fixture as the one documented exception.
- VM variant: a fixed set of benchmark and conformance scripts evaluated and debugged
  in Node and on the JVM with identical outcome, budget, traces and failed term, using
  the raw-argument path on both sides.
- Target and cost model: tests that a mismatched cost model is rejected, that an
  explicit array must state its target, and that a debug session and an evaluation
  of the same request report the same budget and `costModelId`.
- Codec: the reflection test over every record at any depth, the boundary values
  listed above including the list and array cases, and a structured Data round trip
  with an integer above 2^64 nested in a list, a map and a constructor.
- Data JSON: the equivalence corpus against cardano-client-lib, and parameterised
  script decoding in the VM variant with `json`, `uplc` and `cbor` parameters.
  Structured constructor tags include `2^53 + 1` and `2^64 - 1`, plus invalid tags.
- Legacy resolution: a V2 envelope with no target evaluates as V2, `protocolVersion`
  10 resolves to PV10, and a V3 script with no fields resolves as PlutusV3 PV11 with
  the provider default model, each compared with the server's response.
  A blueprint with a matching hash and a conflicting language hint must prefer the
  hash, delegating resolution to `ScriptDecoder` rather than duplicating its rules.
- Worker protocol: a test through the real wrapper in a browser context (or Node
  with a Web Worker shim), opening a session, stepping, closing, and observing
  `not-found` after a forced restart; a direct-global smoke does not count for this.
- API contract tests in Node per method: happy path, failed evaluation returned as a
  result, thrown error with `code`, `status` and `body`, `BigInt` types, `features()`
  on both variants, absent groups undefined.
- Reachability: the VM image build fails if a class from cardano-client-lib, the
  decompiler, JavaParser or the compiler is reachable.
- Playground: the ADR-054 and ADR-055 browser flows on both engines, and the static
  build, unchanged in behavior.
- Documentation deploy: a dry run of the download step against the pre17 release and
  against the first release with the new checksum name.
- Existing suites: `julc-tools` and `julc-playground` test counts unchanged after the
  split; the full repository build stays green.
- Sizes: both variants printed in the CI job summary; milestone 3 records the baseline
  in this ADR's evidence and replaces the probe number.

## Open questions

1. npm package names and cadence, for example `@julclang/wasm` and
   `@julclang/vm-wasm`, decided after one release of use.
2. Hex strings versus `Uint8Array` for scripts and data in a later API version.
3. Whether the debugger session methods should adopt the ADR-053 debug SPI names now
   so the browser and a future IDE adapter share vocabulary.
4. Byte reproducibility of Web Image output across identical builds.
5. Whether `julc-tools` should become a published JVM library, since a server-side
   consumer might want the same services without the playground.
6. Whether `ServiceResult`'s HTTP status codes belong in `julc-tools` or should become
   typed exceptions mapped to status only in the Javalin layer. The error table above
   works with either.

## Revision history

- Review follow-up: document the accepted Node.js build dependency and pin Node 22
  in build CI, add adapter-level REST rounding assertions above `2^53`, and correct
  the obsolete bundle-task comment. No Cardano node is required.
- Implementation: module split, both images, typed SDK and declarations, static
  catalogue, worker adapter, request-local cost configuration, release workflow and
  validation gates added. Actual marshalling and session lifecycle are documented
  above; constructor-overflow correction explicitly approved by the developer.

- Implementation review: corrected language-resolution precedence, specified
  lossless unsigned-Word64 structured constructor tags, and separated exact typed
  integers from legacy REST number conversion, including budgets above `2^53`.

- 2026-09-22: initial draft.
- 2026-09-23: review revision. Added the endpoint mapping table and
  `julc.compiler.evaluate` for Run Test; defined the lossless `BigInt` codec and its
  tests; made the ledger target and cost model explicit with a PV11 default and
  mismatch rejection; corrected the release section, which had renamed the checksum
  file that `docs-deploy.yml` downloads by name, and added the glob change ahead of
  the rename; separated failure results from thrown errors and added `RestAdapter`
  for parity; specified the raw-argument preparation path so the VM variant can
  evaluate and debug without cardano-client-lib, added the reachability check, and
  marked the probe size a lower bound; qualified every parity claim by the BLS
  exclusion.
- 2026-09-23, second round: replaced cardano-client-lib's Plutus Data JSON parsing
  with a dependency-free codec so parameterised decoding fits the VM variant, and
  added parameterised decoding to its tests; corrected the target rule so the
  language comes from the script as today and only the protocol defaults, with the
  provider default model for V1, V2 and PV10 requests and catalogue profiles
  restricted to matching targets; defined the two Plutus Data input forms and scoped
  the `BigInt` guarantee to record positions and the structured form, extended the
  codec table to collections and arrays, and specified the adapter's conversion back
  to REST numbers; replaced method-bearing session objects with a descriptor and
  proxy protocol over the worker, with the adapter mapping stateless debug requests
  onto it and a wrapper-level test; corrected the 422 entry, which belongs to
  blueprint validation inside compile, and the current-behavior text that had
  attributed it to Run Test.
