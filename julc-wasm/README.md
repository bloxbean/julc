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

[`src/embedderFixture`](src/embedderFixture) is a complete example: a demo language with one operation beside
JuLC's VM operations. `./gradlew :julc-wasm:wasmSmokeEmbedder -PgraalvmHome=<GraalVM 25.3>` builds it as described
below, from the jars an embedder gets, and runs it in Node.js; CI does this on every native-image build.

### Entry point

The entry point registers the VM operations and its own operations, and installs the API under its own name:

```java
public final class ElciWasm {
    public static void main(String[] args) {
        var api = new ApiRegistry();
        new VmApi().register(api);                      // vm.* and debug.*, as in JuLC's VM image
        api.add("elci.compile", CompileRequest.class, ElciWasm::compile);
        JsApi.install(api, new EngineInfo("elci", "elci", ELCI_VERSION, List.of("vm", "debug", "elci")));
    }
}
```

- Name your own variant. `full` and `vm` are JuLC's images: the client's `isFullClient` and the playground treat
  `full` as JuLC's full image.
- Operation names are `group.operation`, and every group must be listed in `EngineInfo.groups()`. Groups and the
  global are JavaScript identifiers that the client, the runtime and every JavaScript object do not already use
  (`version`, `features`, `dispose`, `rest`, `constructor`, `toString`, `JulcRuntime`, …); `ApiRegistry` and
  `EngineInfo` reject them.
- Requests and results are records; the JavaScript codec is derived from them, and integers outside the 32-bit
  range cross as `bigint`. An operation returns a `ServiceResult`; exceptions become status 400 or 500 envelopes.

### Reflection metadata

A Web Image reflects only on what is registered when it is built, and the codec reads records by reflection.
Register every request and result record, and every record their fields contain, in the image's own
`META-INF/native-image/<group>/<artifact>/reachability-metadata.json` (the fixture's
[file](src/embedderFixture/resources/META-INF/native-image/org.julclang/julc-wasm-demo/reachability-metadata.json)
shows the format). `julc-wasm` registers the records of the VM operations itself. Without the metadata the image
still builds, but `ApiRegistry.add` fails when `main` runs (`UnsupportedFeatureError: Record components not
available for record class …`), and the client fails with "The engine stopped before it was ready".

### Build

1. Build the image with GraalVM 25.3 with Web Image (Oracle GraalVM; the Community builds do not include it, see
   [BUILD_FROM_SOURCE.md](../julc-playground/BUILD_FROM_SOURCE.md)) and Binaryen on `PATH`:

   ```
   native-image --tool:svm-wasm -cp <the language's jars, julc-wasm and their runtime dependencies> \
       --exclude-config '.*[/\\](julc-tools|julc-blueprint|cardano-client-[a-z0-9-]+|json-schema-validator)-[0-9][^/\\]*\.jar' \
           'META-INF/native-image/' \
       '-H:AbortOnTypeReachable=com.github.javaparser.*' \
       -o <name> <entry class>
   ```

   native-image applies the reflection metadata of every jar on the class path, whether or not the code it
   describes is reachable from the entry point. The metadata of `julc-tools`, `julc-blueprint`, cardano-client-lib
   and the JSON schema validator registers JuLC's compiler services, JavaParser and cardano-client-lib for JuLC's
   full image; `--exclude-config` leaves it out. Measured with GraalVM 25.3.4.1: the demo language's image is
   about 15.8 MB with it, close to JuLC's VM image (14.2 MB), and 19.1 MB without it, which also contains
   JavaParser and cardano-client-lib (JuLC's full image is 24.5 MB). If the language's own
   operations use cardano-client-lib, drop it from the pattern. Keep the metadata of `julc-wasm` and `julc-bls`.
   `-H:AbortOnTypeReachable` makes the build fail if JavaParser becomes reachable again; add the packages your
   image must not contain.
2. In `<name>.js`, after the line `const config = new GraalVM.Config();`, insert the contents of
   `META-INF/julc-wasm/launcher-hook.js` from the `julc-wasm` jar. It lets the worker say where the module is, and
   reports `main` failing before the engine is ready.
3. Name the launcher `<name>-<hash>.js` and the module `<name>-<hash>.js.wasm`, with a 12-hex-digit hash of their
   contents. `<name>` is lowercase words joined by hyphens (`[a-z][a-z0-9]*(-[a-z0-9]+)*`); the worker loads no
   other launcher name, so `ElciWasm-<hash>.js` and `elci_wasm-<hash>.js` are rejected. Write `engine.json`:
   `{"version": "<hash>", "launcher": "<name>-<hash>.js", "variant": "<variant>", "api": 0, "groups": [...],
   "julcVersion": "<JuLC version>"}`. `groups` must equal `EngineInfo.groups()`; the client refuses an engine whose
   features differ.
4. Copy `META-INF/julc-wasm/js/` from the `julc-wasm` jar next to them: `julc-wasm.js` (the Promise client,
   `createJulc({baseUrl})`), `julc-worker.js`, `rest-adapter.js` and the `.d.ts` declarations.

### TypeScript

`createJulc` takes the client type, and `Operations` turns the language's synchronous signatures into the client's
Promise-returning ones:

```ts
import {createJulc, type Operations, type VmClient} from './julc-wasm.js';

type Elci = VmClient & {elci: Operations<{compile(request: CompileRequest): CompileResult}>};
const elci = await createJulc<Elci>({baseUrl: '/elci/'});
const result = await elci.elci.compile({sources: {'Main.elm': source}, module: 'Main'});
```

### BLS12-381

Keep `julc-wasm` off every other native-image class path. Its substitutions replace the BLS12-381 builtins, which
use native code WebAssembly cannot run, with evaluation failures, and native-image applies them to any image the
jar is part of.
