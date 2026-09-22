# JuLC WebAssembly distributions

Experimental, worker-isolated JavaScript access to the existing JuLC toolchain and
Java VM. Governed by [ADR-057](../adr/057-julc-wasm-distribution.md).

- **Full:** compilation, Run Test, Quick Eval, decompilation, mock transactions, VM and debugger.
- **VM:** decoding, parameter application, hashing, pretty printing, raw-argument evaluation and debugging.

Both variants exclude BLS12-381 evaluation. Language is inferred by `ScriptDecoder`
unless overridden; protocol defaults to 11. Each evaluation/debug session gets a
private cost-model configuration. Long integers cross the SDK boundary as `bigint`.

Build and verify with GraalVM 25.3 Web Image, Binaryen and Node 22+:

```sh
./gradlew :julc-wasm:test
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

The normal build tests JSON fidelity, generic integer positions and HTTP parity.
`wasmSmoke` additionally checks the actual compiled images through real Node workers,
including a fixed benchmark/conformance corpus, parameterized decoding and session
lifecycle. Release CI requires both smokes; passing them is evidence, not proof of
compiler correctness or production safety.
