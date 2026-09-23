import { get } from 'svelte/store';
import { availableEngines, engine, wasmError, wasmStatus } from '../stores/engine';

export interface TransportResponse {
  status: number;
  statusText: string;
  json(): Promise<unknown>;
}

/** Carries a playground API request to an engine. Status codes and JSON bodies are the same for every engine. */
export interface Transport {
  request(method: 'GET' | 'POST', path: string, body?: unknown, signal?: AbortSignal): Promise<TransportResponse>;
}

const BASE = import.meta.env.VITE_API_URL || '';

export const serverTransport: Transport = {
  async request(method, path, body, signal) {
    return fetch(`${BASE}${path}`, method === 'POST'
      ? { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal }
      : { method, signal });
  },
};

// ---------------------------------------------------------------- WebAssembly engine (Web Worker)

const REQUEST_TIMEOUT_MS = 30_000;
const LOAD_TIMEOUT_MS = 120_000;
// The engine files are content-addressed (see wasmBundle); the version is fixed when the frontend is built.
const WASM_ENGINE = __JULC_WASM_ENGINE__;
const ENGINE_BASE = new URL(`${import.meta.env.BASE_URL}wasm/`, location.href);
interface EngineClient {
  rest(method: string, path: string, body: string | null): Promise<{status: number; body: unknown}>;
  dispose(): void;
}

const REASONS: Record<number, string> = { 400: 'Bad Request', 404: 'Not Found', 408: 'Request Timeout', 422: 'Unprocessable Entity', 500: 'Internal Server Error' };

interface Pending {
  id: number;
  method: string;
  path: string;
  body: string | null;
  signal?: AbortSignal;
  resolve: (response: TransportResponse) => void;
  reject: (error: unknown) => void;
}

function abortError() {
  return new DOMException('The operation was aborted.', 'AbortError');
}

function response(status: number, body: unknown, statusText?: string): TransportResponse {
  const error = body && typeof body === 'object' && 'error' in body ? String((body as { error: unknown }).error) : null;
  return { status, statusText: statusText ?? error ?? REASONS[status] ?? '', json: async () => body };
}

/**
 * Runs the playground engine compiled with GraalVM Web Image in a dedicated worker, so compilation never blocks
 * the editor. Requests run one at a time; a newer check supersedes queued checks, and a request that exceeds the
 * server's 30 s limit terminates the worker (it restarts on the next request).
 */
class WasmTransport implements Transport {
  private worker: EngineClient | null = null;
  private ready: Promise<EngineClient> | null = null;
  private queue: Pending[] = [];
  private inFlight: Pending | null = null;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private nextId = 1;

  /** Starts loading the engine in the background. */
  warmUp() {
    this.start().catch(() => {});
  }

  request(method: 'GET' | 'POST', path: string, body?: unknown, signal?: AbortSignal): Promise<TransportResponse> {
    if (signal?.aborted) return Promise.reject(abortError());
    return new Promise((resolve, reject) => {
      if (path === '/api/check') {
        // Only the latest check matters while typing.
        this.queue = this.queue.filter((p) => {
          if (p.path !== '/api/check') return true;
          p.reject(abortError());
          return false;
        });
      }
      const pending: Pending = {
        id: this.nextId++, method, path, body: body === undefined ? null : JSON.stringify(body), signal, resolve, reject,
      };
      signal?.addEventListener('abort', () => {
        this.queue = this.queue.filter((p) => p !== pending);
        reject(abortError());
      }, { once: true });
      this.queue.push(pending);
      this.pump();
    });
  }

  private start(): Promise<EngineClient> {
    if (this.ready) return this.ready;
    wasmStatus.set('loading');
    wasmError.set(null);
    if (!WASM_ENGINE) {
      const message = 'WebAssembly engine failed to load: this build does not include it (build with -PwithWasm)';
      wasmError.set(message);
      wasmStatus.set('error');
      return Promise.reject(new Error(message));
    }
    this.ready = (async () => {
      try {
        const sdkUrl = new URL('julc-wasm.js?engine=' + encodeURIComponent(WASM_ENGINE), ENGINE_BASE).href;
        const [{createJulc}, manifest, catalogue] = await Promise.all([
          import(/* @vite-ignore */ sdkUrl),
          fetch(new URL('engine.json?engine=' + encodeURIComponent(WASM_ENGINE), ENGINE_BASE)).then(r => r.json()),
          fetch(new URL('catalogue.json?engine=' + encodeURIComponent(WASM_ENGINE), ENGINE_BASE)).then(r => r.json()),
        ]);
        if (manifest.version !== WASM_ENGINE) throw new Error('Engine version does not match this frontend; reload the page');
        const client = await createJulc({baseUrl: ENGINE_BASE, manifest, catalogue,
          timeoutMs: REQUEST_TIMEOUT_MS, loadTimeoutMs: LOAD_TIMEOUT_MS});
        this.worker = client;
        wasmStatus.set('ready');
        return client as EngineClient;
      } catch (e) {
        this.worker = null;
        this.ready = null;
        const message = 'WebAssembly engine failed to load: ' + String(e);
        wasmError.set(message);
        wasmStatus.set('error');
        throw new Error(message);
      }
    })();
    return this.ready;
  }

  private async pump() {
    if (this.inFlight || this.queue.length === 0) return;
    const next = this.queue.shift()!;
    this.inFlight = next;
    let worker: EngineClient;
    try {
      worker = await this.start();
    } catch (e) {
      this.inFlight = null;
      next.reject(e);
      this.failQueued(e);
      return;
    }
    if (next.signal?.aborted) {
      this.inFlight = null;
      this.pump();
      return;
    }
    this.timer = setTimeout(() => this.onTimeout(), REQUEST_TIMEOUT_MS);
    worker.rest(next.method, next.path, next.body).then(envelope =>
      this.onResponse({id: next.id, response: JSON.stringify(envelope)})
    ).catch((error: {status?: number; message?: string}) => {
      if (this.inFlight?.id !== next.id) return;
      if (error.status === 408) this.onTimeout();
      else this.onCrash(error.message || String(error));
    });
  }

  private onResponse(data: { id: number; response?: string; error?: string }) {
    const pending = this.inFlight;
    if (!pending || pending.id !== data.id) return;
    clearTimeout(this.timer);
    this.inFlight = null;
    if (data.error !== undefined) {
      // A JavaScript-level failure inside the engine. The browser's WebAssembly stack is much smaller than a JVM
      // thread stack, and a stack overflow surfaces as one of these messages.
      const stackHint = /type incompatibility|call stack/i.test(data.error)
        ? ' (the source is probably nested too deeply for the browser stack; try the Server engine)'
        : '';
      pending.resolve(response(500, { error: `Engine error: ${data.error}${stackHint}` }));
    } else {
      const envelope = JSON.parse(data.response!);
      pending.resolve(response(envelope.status, envelope.body));
    }
    this.pump();
  }

  private onTimeout() {
    const pending = this.inFlight;
    this.restart();
    pending?.resolve(response(408, { error: 'Compilation timed out (30s limit)' }));
    this.pump();
  }

  private onCrash(message: string) {
    const pending = this.inFlight;
    this.restart();
    wasmError.set(`WebAssembly engine crashed: ${message}`);
    wasmStatus.set('error');
    pending?.resolve(response(500, { error: `Engine error: ${message}` }));
    this.pump();
  }

  private restart() {
    clearTimeout(this.timer);
    this.worker?.dispose();
    this.worker = null;
    this.ready = null;
    this.inFlight = null;
  }

  private failQueued(error: unknown) {
    const queued = this.queue;
    this.queue = [];
    queued.forEach((p) => p.reject(error));
  }
}

export const wasmTransport = new WasmTransport();

engine.subscribe((kind) => {
  if (kind === 'wasm') wasmTransport.warmUp();
});

wasmStatus.subscribe((status) => {
  // If the engine assets are missing (built without WebAssembly) fall back to the server when it is offered.
  if (status === 'error' && get(engine) === 'wasm' && availableEngines.includes('server') && get(wasmError)?.includes('failed to load')) {
    engine.set('server');
  }
});

export function currentTransport(): Transport {
  return get(engine) === 'wasm' ? wasmTransport : serverTransport;
}
