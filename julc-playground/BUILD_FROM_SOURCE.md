# Building JuLC Playground from Source

## Prerequisites

- **JDK 25+** (GraalVM recommended for native image)
- **Node.js 20+** and **npm** (for frontend build only)

## Backend Only (no frontend)

By default, the build skips the frontend. This is useful when working on the backend or when npm is not available.

```bash
./gradlew :julc-playground:build
```

The resulting JAR will serve the last-built frontend from `src/main/resources/static/` (if present).

## Full Build (backend + frontend)

Pass `-PwithFrontend` to include the Svelte/Vite frontend build:

```bash
./gradlew :julc-playground:build -PwithFrontend
```

This will:
1. Run `npm install` in `julc-playground/frontend/`
2. Run `npm run build` (Vite) to produce static assets in `src/main/resources/static/`
3. Bundle the assets into the JAR

## Frontend Development

For live-reload frontend development, run the Vite dev server separately:

```bash
cd julc-playground/frontend
npm install
npm run dev
```

Then start the backend:

```bash
./gradlew :julc-playground:run
```

The Vite dev server runs on port 3000 (proxying `/api` to the backend), the backend on port 8085.

## Native Image

Build a self-contained native binary with the frontend bundled in:

```bash
./gradlew :julc-playground:nativeCompile -PwithFrontend
```

The binary is output to `julc-playground/build/native/nativeCompile/julc-playground`.

## Shadow JAR

Build a fat JAR with the frontend bundled:

```bash
./gradlew :julc-playground:shadowJar -PwithFrontend
```

Run it with:

```bash
java -jar julc-playground/build/libs/julc-playground.jar
```

## In-Browser Engine (WebAssembly)

The playground can run checks, compilation, evaluation and Quick Eval either on the server or in the browser. The
browser engine is the same Java code (`julc-playground-core`) compiled to WebAssembly with GraalVM Web Image
(`julc-playground-wasm`) and runs in a Web Worker. Users pick it with the **Engine** selector in the toolbar
(or `?engine=wasm` in the URL); the choice is remembered in the browser.

Additional prerequisites (only for the WebAssembly build):

- **GraalVM 25.3 with Web Image** (`native-image --tool:svm-wasm`), passed as `-PgraalvmHome=<path>` or `GRAALVM_HOME`.
  This is the 25.3 *innovation* release (`GRAALVM_VERSION="25.3.x"` in its `release` file; it reports JDK 25.0.4.x
  in `java -version`). Download Oracle GraalVM 25.3 from [graalvm.org/downloads](https://www.graalvm.org/downloads/)
  or the `graal-25.3.x` release of [graalvm-ce-builds](https://github.com/graalvm/graalvm-ce-builds/releases); the
  GitHub action for it is `graalvm/setup-graalvm@v1` with `version: '25.3'` (`actions/setup-java` only resolves the
  25.0 LTS line). The build checks the line and refuses others; 25.0.2 also produces a working engine, so
  `-PwasmGraalvmVersion=25.0` allows it for local experiments, but releases are built with 25.3 only.
- **Binaryen** (`wasm-opt`) on `PATH`
- Run Gradle itself with your usual JDK 25; GraalVM is only used for the image build

Build the server with both engines:

```bash
./gradlew :julc-playground:shadowJar -PwithFrontend -PwithWasm -PgraalvmHome=/path/to/graalvm
java -jar julc-playground/build/libs/julc-playground.jar
```

`-PwithWasm` runs `:julc-playground-wasm:wasmBundle`, which writes the engine to `frontend/public/wasm/`: the launcher
and module under content-addressed names (`julc-playground-<version>.js`, `julc-playground-<version>.js.wasm`, about
19 MB) and `engine.json`. The frontend build embeds that version, so a redeploy can never pair a cached launcher with
a new module. Without the engine the selector still offers the browser engine, reports that it is unavailable, and
falls back to the server.

### Static playground (no backend)

The browser engine makes a server unnecessary. After `wasmBundle`, build a static site:

```bash
./gradlew :julc-playground-wasm:wasmBundle -PgraalvmHome=/path/to/graalvm
cd julc-playground/frontend
npm install
npm run build:static        # output: frontend/dist-static/
```

`dist-static/` uses relative paths and can be served from any static host or sub-path. Its engine is fixed to
WebAssembly (`.env.static`: `VITE_ENGINES=wasm`).

### Release assets and julc.dev

The engine is platform-independent, so it is built once per release. The `playground-wasm` job in
`.github/workflows/native-image-release.yml` runs `wasmSmoke` and `npm run build:static` with GraalVM 25.3 and
attaches three assets to the GitHub release (the other native-image jobs keep their usual GraalVM and download the
engine from this job, so every `julc-playground` binary serves it at `/wasm/`):

| Asset | Contents |
|-------|----------|
| `julc-playground-engine-<version>.tar.gz` | `engine.json`, `julc-playground-<sha>.js`, `julc-playground-<sha>.js.wasm`: drop into any host's `wasm/` directory |
| `julc-playground-static-<version>.tar.gz` | the complete static playground (`dist-static/`): unpack under any path, no backend needed |
| `julc-playground-wasm-<version>-SHA256SUMS.txt` | checksums of both archives (`sha256sum --check`) |

The documentation site publishes it at [julc.dev/playground/](https://julc.dev/playground/):
`.github/workflows/docs-deploy.yml` downloads the static bundle of the release named in `docs/playground-release`,
verifies its checksum and copies it to `docs/dist/playground/`. To publish a newer playground, bump that file to a
release that carries the assets and push a `dv*` tag. `.github/workflows/native-image-dev.yml` produces the same
bundles as workflow artifacts for testing before a release. To preview the playground with `npm run dev` in
`docs/`, copy `dist-static/` to `docs/public/playground/` (ignored by git).

### Engine parity tests

`julc-playground-wasm` contains the parity fixtures (`src/test/resources/parity-requests.json`):

```bash
./gradlew :julc-playground-wasm:test                                     # JVM: dispatcher == REST controllers
./gradlew :julc-playground-wasm:wasmSmoke -PgraalvmHome=/path/to/graalvm # Node.js: WebAssembly == JVM
```

`wasmSmoke` needs Node.js 22+ (it passes `--experimental-wasm-exnref`).

### Known limitations of the browser engine

- **BLS12-381 builtins are not supported.** They use the native blst library; evaluation fails with
  `BLS12-381 builtins are not supported in the WebAssembly playground`. Use the server engine for BLS contracts.
- The first load downloads the ~19 MB engine (about 7 MB with gzip when the host compresses it).
- Very deeply nested source can exceed the browser's WebAssembly stack; the request fails with an engine error and
  the engine stays usable.
- Requires a browser with WebAssembly GC and exception handling (`exnref`). Tested with Chrome 153.

## Troubleshooting

**npm not found during build**

On macOS with Homebrew, ensure `/usr/local/bin` (Intel) or `/opt/homebrew/bin` (Apple Silicon) is on your PATH. The Gradle build invokes npm through a shell (`sh -c`), so it inherits your shell's PATH.

**Frontend not updating**

The frontend build output goes to `src/main/resources/static/`. If you see stale content, rebuild with `-PwithFrontend` or run `npm run build` manually in the `frontend/` directory.
