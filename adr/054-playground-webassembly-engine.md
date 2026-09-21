# ADR-054: In-browser (WebAssembly) engine for the playground

- Status: Implemented on `feat/playground-wasm`; independent review pending
- Date: 2026-09-15

## Context / Problem

The JuLC playground (`julc-playground`) is a Svelte + Monaco frontend backed by a Javalin server. Every check,
compile, evaluate and Quick Eval call is a REST request, so the playground cannot run without a server. The server
limits concurrency and rate, and every keystroke-driven check goes over the network.

GraalVM Web Image (`native-image --tool:svm-wasm`) compiles Java to WebAssembly (WasmGC) with a JavaScript launcher.
The compiler, `julc-vm-java` and the playground services use no threads, JNI or dynamic class loading on their
request paths. The one exception is BLS12-381, whose builtins call the native blst library through JNI and FFM.

## Goals and non-goals

Goals:

- The same playground UI can run requests either on the server or in the browser, selectable by the user.
- The browser engine runs the same Java code as the server and returns the same JSON for the same request.
- The playground can be published as a static site with no backend.
- The default Gradle build and CI stay unchanged; the WebAssembly build is opt-in.

Non-goals:

- BLS12-381 builtins in the browser engine (known limitation).
- Changes to compiler semantics, generated UPLC, the VM, or the REST API contract.
- JRL (already removed from the playground UI and routes).

## Current behavior (before this change)

- Controllers contain the request logic directly, mixed with Javalin `Context`, SLF4J logging and `CompilationSandbox`.
- The frontend `api` object (`frontend/src/lib/api/client.ts`) calls `fetch` for every endpoint.

## Invariants

1. **Same contract.** For every request, both engines return the same HTTP status code and the same JSON body.
   This is enforced by parity fixtures on the JVM and in Node.js.
2. **REST behavior is preserved.** Routes, constructors, status mapping (200/400/404/408/422/429/500) and sandbox
   timeouts stay the same. The existing controller tests are unchanged and pass.
3. **Generated scripts are identical.** UPLC, FLAT/CBOR, script hash, blueprint JSON, budgets and traces are
   byte-identical across engines, apart from BLS (invariant 4).
4. **BLS fails explicitly.** In the browser engine, BLS builtins fail as a normal evaluation failure with a clear
   message, never with a crash or an opaque linkage error.

## Decision

1. **`julc-playground-core`**
   - Transport-neutral logic moves here without package changes: model records, `InputValidator`,
     `JavaMetadataExtractor`, the scenario builder and registry, `PlaygroundEvaluator`, examples.
   - `PlaygroundService` and `ExampleCatalog` return `ServiceResult(status, body, failure)`, carrying the exact
     status the REST server uses.
   - Native-image reachability metadata for these types and their resources lives in this module.
   - Also added: the `EvalExpressionRequest`/`EvalExpressionResponse` records and the `julc-version.properties`
     resource, both previously missing from the playground metadata.
2. **`julc-playground`** (server)
   - The controllers become thin adapters: validation, then `CompilationSandbox`, then the service, then status and
     JSON. Validation runs outside the sandbox, as before.
   - Native-image static routes gain `/wasm/{path}` with `application/wasm`.
3. **`julc-playground-wasm`**
   - `PlaygroundDispatcher` maps `(method, path, body)` to the service using the REST routes. It returns
     `{"status", "body"}` serialized with a default Jackson `ObjectMapper`, as Javalin does.
   - `PlaygroundWasm` installs `globalThis.julcPlayground.dispatch` through a `@JS` bootstrap. `@JS.Export` is not
     available in Web Image 25.3.
   - A Web Image substitution replaces `Bls12381Builtins.bls(name, call)` with a `BuiltinException`. That keeps
     blst, JNI and FFM out of the image and satisfies invariant 4.
   - Opt-in Gradle tasks: `wasmImage`, `wasmBundle` (patches the launcher so a worker can set `wasm_path` and copies
     the engine to `frontend/public/wasm`), `wasmParityExpected` and `wasmSmoke`.
4. **Frontend**
   - `client.ts` keeps its types and `api` object and delegates to a transport chosen by the `engine` store:
     `serverTransport` (fetch) or `wasmTransport`.
   - `wasmTransport` runs the engine in a classic Web Worker (`public/wasm/playground-worker.js`) so compilation never
     blocks Monaco.
     - Requests run one at a time.
     - A newer check supersedes queued checks (aborted with `AbortError`).
     - A 30 s timeout terminates and restarts the worker and reports 408, mirroring the server sandbox.
   - The toolbar `EngineToggle` offers the engines from `VITE_ENGINES`. The choice is persisted in localStorage;
     `?engine=` overrides it.
   - When the engine changes, the editor re-checks and examples and scenarios reload.
   - `vite build --mode static` produces `dist-static/`, which is WebAssembly-only and uses relative paths.
   - `-PwithWasm` makes `buildFrontend` depend on `wasmBundle`.
   - Engine files are content-addressed (`julc-playground-<sha256 prefix>.js[.wasm]`, recorded in `engine.json`); the
     frontend build embeds the version and starts `playground-worker.js?engine=<version>`. Static hosts cache by
     URL, so a redeploy cannot combine a cached launcher with a new module.
5. **julc.dev.** `docs-deploy.yml` builds and verifies the engine (`wasmSmoke`, GraalVM 25 from `setup-java`,
   Binaryen 132), builds the static playground and publishes it with the documentation at `/playground/`. The
   Starlight sidebar links to it; `astro dev` rewrites `/playground/` to its `index.html` for local previews.
6. **cardano-client-lib.** The playground modules pin `cardano-client-lib` to `0.8.0-pre5` with a strict constraint.
   `julc-blueprint` and `julc-cardano-client-lib` still compile against 0.7.x. Blueprint and script-hash tests and
   the parity fixtures pass on 0.8.0-pre5 on both engines.

## Alternatives considered

- **Separate browser API with its own request handling.** Rejected: two implementations of the same endpoints
  would drift. The dispatcher reuses the service and the REST routes.
- **Run the engine on the main thread.** Rejected: checks run on every edit after a 300 ms debounce and take
  hundreds of milliseconds, which would freeze the editor.
- **Hand-written JSON instead of Jackson in the image.** Rejected: Jackson works in Web Image with the existing
  record metadata and keeps JSON identical to the server.
- **Keep blst reachable and map `NoClassDefFoundError`.** Rejected: it includes native-call code that can never run,
  and the failure would be an internal error rather than an evaluation failure.
- **Build WebAssembly in the default build.** Rejected: it needs a GraalVM with Web Image plus Binaryen, which the
  default toolchain and CI do not provide.

## Affected modules

`julc-playground` (controllers, server routes, build, frontend), new `julc-playground-core`, new
`julc-playground-wasm`, `settings.gradle`, root `build.gradle` (non-publishable list), `docs/` (sidebar link,
dev rewrite) and `.github/workflows/docs-deploy.yml`. No compiler, VM, stdlib or
ledger module changes.

## Compatibility

- REST API and JSON are unchanged. `ScenariosController` now serializes a record instead of `Map.of(...)`: same
  keys, deterministic order, `message` still only present when there are no templates.
- The frontend defaults to the server engine unless the build restricts engines.
- Metadata for the playground model and JavaParser moves from the server jar to the core jar. Native images still
  receive it because it is on the image classpath.

## Risks

- Web Image and its interop API are preview technology in GraalVM 25.3; the launcher patch asserts its anchor
  and fails the build if the launcher changes.
- Engine size is about 19 MB (about 7 MB gzip). The first load is slow on poor connections.
- The browser WebAssembly stack is smaller than a JVM thread stack. Extremely nested sources fail with an engine
  error; the worker stays usable.
- Browsers must support WasmGC and exception handling (`exnref`).
- Under saturation, `/api/evaluate` now extracts metadata inside the sandbox rather than before it, so a full
  sandbox returns 429 slightly earlier.

## Implementation milestones

1. Extract core and service; controllers delegate (existing tests plus `PlaygroundServiceTest`).
2. WebAssembly module: dispatcher and parity tests on the JVM, Web Image build, BLS substitution, Node parity smoke test.
3. Frontend transport, worker, toggle, static mode; Gradle `-PwithWasm`; native static routes.
4. Browser verification and documentation.

## Verification strategy

- `:julc-playground-core:test`: service status codes and bodies, check, compile with and without blueprint,
  evaluate with params and signers, evaluation failure, bad params, eval, scenarios, examples.
- `:julc-playground:test`: the existing REST controller tests, unchanged.
- `:julc-playground-wasm:test`: 25 parity fixtures give identical status and JSON through Javalin controllers and
  the dispatcher; also routing, 404, malformed body.
- `:julc-playground-wasm:wasmSmoke`: the same fixtures in Node.js against the WebAssembly engine are identical to
  the JVM, except the BLS fixture, which must fail with the documented message. Verified with images built by
  GraalVM 25.0.2 and 25.3.4.1; the JS bridge only uses Web Image API present in both.
- Browser (Chrome 153), same UI flow on both engines:
  - Results are identical for check, compile (hash, size, CBOR, UPLC, PIR, blueprint), Quick Eval, and Run Test
    with a matching and a non-matching signer.
  - The browser engine made no `/api` requests.
  - The BLS message, deep-nesting recovery and the static build without backend were checked.

## Open questions

- A headless-browser test of the static playground in CI.
- A pure-Java BLS12-381 backend (for example BouncyCastle 1.85+) to lift the known limitation.
