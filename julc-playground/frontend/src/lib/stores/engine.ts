import { writable } from 'svelte/store';

/** Where playground requests run: the REST backend or the WebAssembly build of the same Java code. */
export type EngineKind = 'server' | 'wasm';
export type WasmStatus = 'idle' | 'loading' | 'ready' | 'error';
export interface WasmFeatures {
  api: number;
  variant: 'full' | 'vm';
  groups: string[];
  defaultProtocol: number;
  bls: boolean;
}

const STORAGE_KEY = 'julc.playground.engine';
const ALL_ENGINES: EngineKind[] = ['server', 'wasm'];

export const ENGINE_LABELS: Record<EngineKind, string> = {
  server: 'Server',
  wasm: 'Browser (WebAssembly)',
};

function isEngine(value: unknown): value is EngineKind {
  return value === 'server' || value === 'wasm';
}

/** Engines offered by this build, from VITE_ENGINES (comma separated). Defaults to both. */
export const availableEngines: EngineKind[] = (() => {
  const configured = String(import.meta.env.VITE_ENGINES ?? '')
    .split(',')
    .map((e) => e.trim())
    .filter(isEngine);
  return configured.length > 0 ? configured : ALL_ENGINES;
})();

function readStored(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

function initialEngine(): EngineKind {
  const fromUrl = new URLSearchParams(window.location.search).get('engine');
  for (const candidate of [fromUrl, readStored(), import.meta.env.VITE_DEFAULT_ENGINE]) {
    if (isEngine(candidate) && availableEngines.includes(candidate)) return candidate;
  }
  return availableEngines[0];
}

export const engine = writable<EngineKind>(initialEngine());

engine.subscribe((value) => {
  try {
    localStorage.setItem(STORAGE_KEY, value);
  } catch {
    // Storage may be unavailable (private mode); the selection then lasts for this page only.
  }
});

export const wasmStatus = writable<WasmStatus>('idle');
export const wasmError = writable<string | null>(null);
export const wasmFeatures = writable<WasmFeatures | null>(null);
