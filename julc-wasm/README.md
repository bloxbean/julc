# JuLC WebAssembly distributions

Experimental, worker-isolated JavaScript access to the existing JuLC toolchain and
Java VM. Governed by [ADR-057](../adr/057-julc-wasm-distribution.md).

For release downloads, a copy-paste HTML demo and the API reference, see the
[JavaScript & WebAssembly guide](../docs/src/content/docs/guides/javascript-wasm.md).

- **Full:** compilation, Run Test, Quick Eval, decompilation, mock transactions, VM, UPLC debugger and the
  experimental Java-source debugger described by ADR-058.
- **VM:** decoding, parameter application, hashing, pretty printing, raw-argument evaluation and debugging.

Both variants exclude BLS12-381 evaluation. Language is inferred by `ScriptDecoder`
unless overridden; protocol defaults to 11. Each evaluation/debug session gets a
private cost-model configuration. Long integers cross the SDK boundary as `bigint`.

The repository-wide `build` and this module's `test` task require Node.js 22+ for
JavaScript codec/adapter tests. No Cardano node is needed. Image verification
additionally needs GraalVM 25.3 Web Image and Binaryen:

```sh
./gradlew :julc-wasm:test
./gradlew :julc-wasm:jsTest # Node 22+
./gradlew :julc-wasm:wasmSmoke -PgraalvmHome=/path/to/graalvm
```

Bundles are in `build/wasm-bundle-full` and `build/wasm-bundle-vm`. Serve each directory
together; `julc-wasm.js` exports `createJulc` and `isFullClient`, and the `.d.ts` files
describe the Promise API. The generated model declarations are derived from Java
records during a normal build. `engine.json` and `features()` advertise the same
method groups; the full image includes `sourceDebug`, while the VM image does not.
Do not combine an SDK, manifest, frontend or engine from different builds. The
low-level `globalThis.julc` inside the worker is
synchronous; applications should use the SDK and call `dispose()` when finished.

See the [build guide](../julc-playground/BUILD_FROM_SOURCE.md#standalone-javascript-api)
for an example. A timeout terminates the worker and invalidates all its sessions;
individual session `close()` calls do not affect other sessions.

The normal JVM build tests JSON fidelity, generic integer positions and HTTP parity.
JavaScript codec/adapter tests run as part of `test` and are mandatory dependencies
of both Wasm smokes; they are never silently skipped. Build CI installs Node.js 22.
`wasmSmoke` additionally checks the actual compiled images through real Node workers,
including a fixed benchmark/conformance corpus, parameterized decoding and session
lifecycle. Release CI requires both smokes; passing them is evidence, not proof of
compiler correctness or production safety.

## Building an image for another language

`julc-wasm` and `julc-tools` are published (`org.julclang:julc-wasm`), so a language built on JuLC's backend can
compile its own WebAssembly engine without copying this module ([ADR-064](../adr/064-embeddable-wasm-engine.md)).
Use the same JuLC version the language already compiles with: the image, the JavaScript files and the compiler
then come from one build.

The entry point registers the VM operations, its own operations and installs the API under its own name:

```java
public final class ElciWasm {
    public static void main(String[] args) {
        var api = new ApiRegistry();
        new VmApi().register(api);                      // vm.* and debug.*, as in JuLC's VM image
        api.add("elci.compile", CompileRequest.class, ElciWasm::compile);
        JsApi.install(api, new EngineInfo("elci", "full", ELCI_VERSION, List.of("vm", "debug", "elci")));
    }
}
```

Requests and results are records (the JavaScript codec is derived from them; integers outside the 32-bit range
cross as `bigint`). An operation returns a `ServiceResult`; exceptions become status 400 or 500 envelopes.

The image and bundle are built as `build.gradle` here builds JuLC's:

1. `native-image --tool:svm-wasm -cp <jar and runtime classpath> -o <name> <entry class>` with GraalVM 25.3
   (Web Image) and Binaryen on `PATH`.
2. In `<name>.js`, after the line `const config = new GraalVM.Config();`, insert
   `if (globalThis.JULC_WASM_PATH) config.wasm_path = globalThis.JULC_WASM_PATH;`.
3. Name the launcher `<name>-<12 hex digits>.js` and the module `<name>-<12 hex digits>.js.wasm`, from a hash
   of their contents, and write `engine.json`:
   `{"version": "<hash>", "launcher": "<name>-<hash>.js", "variant": "<variant>", "api": 0, "groups": [...]}`.
   `groups` must equal `EngineInfo.groups()`; the client refuses an engine whose features differ.
4. Copy `META-INF/julc-wasm/js/` from the `julc-wasm` jar next to them: `julc-wasm.js` (the Promise client,
   `createJulc({baseUrl})`), `julc-worker.js`, `rest-adapter.js` and the `.d.ts` declarations.

Keep `julc-wasm` off every other native-image class path. Its substitutions replace the BLS12-381 builtins, which
use native code WebAssembly cannot run, with evaluation failures, and native-image applies them to any image the
jar is part of.
