# Building JuLC Playground from Source

## Prerequisites

- **JDK 24+** (GraalVM recommended for native image)
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

The Vite dev server runs on port 5173, the backend on port 8085.

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

## Troubleshooting

**npm not found during build**

On macOS with Homebrew, ensure `/usr/local/bin` (Intel) or `/opt/homebrew/bin` (Apple Silicon) is on your PATH. The Gradle build invokes npm through a shell (`sh -c`), so it inherits your shell's PATH.

**Frontend not updating**

The frontend build output goes to `src/main/resources/static/`. If you see stale content, rebuild with `-PwithFrontend` or run `npm run build` manually in the `frontend/` directory.
