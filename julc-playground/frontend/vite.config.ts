import { readFileSync } from 'node:fs';
import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';

// Version of the WebAssembly engine in public/wasm (written by :julc-playground-wasm:wasmBundle); empty when absent.
function wasmEngineVersion(): string {
  try {
    return JSON.parse(readFileSync(new URL('./public/wasm/engine.json', import.meta.url), 'utf8')).version ?? '';
  } catch {
    return '';
  }
}

// `--mode static` builds a backend-free playground (Browser/WebAssembly engine only, see .env.static) into
// dist-static/ with relative asset paths, so it can be hosted from any static file server or sub-path.
export default defineConfig(({ mode }) => ({
  plugins: [svelte()],
  define: {
    __JULC_WASM_ENGINE__: JSON.stringify(wasmEngineVersion()),
  },
  base: mode === 'static' ? './' : '/',
  build: {
    outDir: mode === 'static' ? 'dist-static' : '../src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    port: 3000,
    proxy: {
      '/api': 'http://localhost:8085',
    },
  },
}));
