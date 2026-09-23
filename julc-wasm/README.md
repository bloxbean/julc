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
records during a normal build. The low-level `globalThis.julc` inside the worker is
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
