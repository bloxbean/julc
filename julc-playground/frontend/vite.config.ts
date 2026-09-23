import { readFileSync } from 'node:fs';
import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';

interface WasmManifest {
  version?: string;
  variant?: string;
  api?: number;
  groups?: string[];
}

// Manifest of the WebAssembly engine in public/wasm (written by :julc-wasm:wasmBundleFull).
// A server build may omit it and fall back to REST. A static build has no such fallback, so accepting a stale
// engine would expose UI operations that can only fail at runtime.
function wasmEngineManifest(staticBuild: boolean): WasmManifest | null {
  try {
    const manifest = JSON.parse(readFileSync(new URL('./public/wasm/engine.json', import.meta.url), 'utf8')) as WasmManifest;
    if (!manifest.version) throw new Error('engine.json has no version');
    if (staticBuild && (manifest.api !== 0 || manifest.variant !== 'full'
      || !manifest.groups?.includes('sourceDebug'))) {
      throw new Error('the engine does not advertise the full sourceDebug capability');
    }
    return manifest;
  } catch (error) {
    if (staticBuild) {
      throw new Error('Cannot build the static playground with a missing or stale WebAssembly engine. '
        + 'Run :julc-wasm:wasmBundleFull from this checkout or use its complete static-playground artifact. '
        + `Cause: ${String(error)}`);
    }
    return null;
  }
}

// `--mode static` builds a backend-free playground (Browser/WebAssembly engine only, see .env.static) into
// dist-static/ with relative asset paths, so it can be hosted from any static file server or sub-path.
export default defineConfig(({ mode }) => {
  const staticBuild = mode === 'static';
  const manifest = wasmEngineManifest(staticBuild);
  return {
    plugins: [svelte()],
    define: {
      __JULC_WASM_ENGINE__: JSON.stringify(manifest?.version ?? ''),
    },
    base: staticBuild ? './' : '/',
    build: {
      outDir: staticBuild ? 'dist-static' : '../src/main/resources/static',
      emptyOutDir: true,
    },
    server: {
      port: 3000,
      proxy: {
        '/api': 'http://localhost:8085',
      },
    },
  };
});
