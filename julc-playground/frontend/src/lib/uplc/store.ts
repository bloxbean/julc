import { derived, get, writable, type Writable } from 'svelte/store';
import { post } from '../api/client';
import { currentTransport, type Transport } from '../api/transport';
import { engine, wasmFeatures } from '../stores/engine';
import { EXAMPLES, type UplcExample } from './examples';
import { localsIdentityKey, localsResponseMatches } from './locals-identity.js';
import { defaultTransaction, PRESETS } from './mock';
import type {
  Breakpoints, DataInput, DebugAction, DebugResponse, DecodeResponse, DecompileResponse, EvaluateResponse,
  JavaChildrenResponse, JavaLocalsResponse, MockTransaction, PurposeType, ScriptInput, Snapshot,
  SourceDebugOpenResponse, Span, Timeline,
} from './types';

// ---------------------------------------------------------------- persisted inputs

function persisted<T>(key: string, fallback: T): Writable<T> {
  let start = fallback;
  try {
    const raw = localStorage.getItem(key);
    if (raw) start = JSON.parse(raw);
  } catch {
    // storage unavailable or corrupt
  }
  const store = writable<T>(start);
  store.subscribe((value) => {
    try {
      localStorage.setItem(key, JSON.stringify(value));
    } catch {
      // storage unavailable or full
    }
  });
  return store;
}

const first = EXAMPLES[0];
export const scriptText = persisted<string>('julc.uplc.script', first.script);
export const params = persisted<DataInput[]>('julc.uplc.params', first.params);
export const language = persisted<string>('julc.uplc.language', first.language);
export const validator = writable<string>('');
export const transaction = persisted<MockTransaction>('julc.uplc.transaction', defaultTransaction(first.purpose));
export const presetIndex = persisted<number>('julc.uplc.preset', 0);
export const breakpoints = persisted<Breakpoints>('julc.uplc.breakpoints', {
  lines: [], onTrace: false, onError: true, builtins: [], javaLines: [],
});
breakpoints.update((value) => ({ ...value, javaLines: value.javaLines ?? [] }));
export const sourceDebugSource = persisted<string>('julc.sourceDebug.source', '');
export const sourceDebugLibrary = persisted<string>('julc.sourceDebug.library', '');
export const sourceDebugParams = writable<DataInput[]>([]);

export const preset = derived(presetIndex, (i) => PRESETS[i] ?? PRESETS[0]);

export const viewTab = writable<'uplc' | 'source' | 'java' | 'stats'>('uplc');
export const outputTab = writable<'result' | 'debugger' | 'context'>('result');
/** A span to briefly show in the viewer (e.g. a clicked frame). */
export const peekSpan = writable<Span | null>(null);

export const scriptInput = derived([scriptText, params, language, validator], ([s, p, l, v]): ScriptInput => ({
  script: s, params: p.filter((d) => d.value.trim() !== ''), language: l, validator: v || undefined,
}));

// ---------------------------------------------------------------- results

export interface DecodeState { loading: boolean; response: DecodeResponse | null; error: string | null }
export const decodeState = writable<DecodeState>({ loading: false, response: null, error: null });

export interface JavaState { loading: boolean; key: string; response: DecompileResponse | null; error: string | null }
export const javaState = writable<JavaState>({ loading: false, key: '', response: null, error: null });

export interface EvalState { loading: boolean; response: EvaluateResponse | null; error: string | null; stale: boolean }
export const evalState = writable<EvalState>({ loading: false, response: null, error: null, stale: false });

export interface DebugState {
  active: boolean;
  loading: boolean;
  timeline: Timeline | null;
  snapshot: Snapshot | null;
  error: string | null;
  stale: boolean;
}
export const debugState = writable<DebugState>({ active: false, loading: false, timeline: null, snapshot: null, error: null, stale: false });

export interface SourceDebugState {
  loading: boolean;
  response: SourceDebugOpenResponse | null;
  sessionId: string | null;
  error: string | null;
  stale: boolean;
  bindingKey: string | null;
  transport: Transport | null;
}
export const sourceDebugState = writable<SourceDebugState>({
  loading: false, response: null, sessionId: null, error: null, stale: false, bindingKey: null, transport: null,
});
export const javaLocalsState = writable<{
  loading: boolean; response: JavaLocalsResponse | null; error: string | null; identity: string | null;
}>({ loading: false, response: null, error: null, identity: null });

// ---------------------------------------------------------------- actions

let decodeSeq = 0;
let decodeTimer: ReturnType<typeof setTimeout> | undefined;

export async function decode(): Promise<void> {
  const input = get(scriptInput);
  const seq = ++decodeSeq;
  if (!input.script.trim()) {
    decodeState.set({ loading: false, response: null, error: null });
    return;
  }
  decodeState.update((s) => ({ ...s, loading: true }));
  try {
    const response = await post<DecodeResponse>('/api/uplc/decode', { script: input });
    if (seq === decodeSeq) decodeState.set({ loading: false, response, error: response.ok ? null : response.error });
  } catch (e) {
    if (seq === decodeSeq) decodeState.set({ loading: false, response: null, error: (e as Error).message });
  }
}

export async function decompile(): Promise<void> {
  const input = get(scriptInput);
  const key = JSON.stringify(input);
  const current = get(javaState);
  if (current.key === key && (current.loading || current.response)) return;
  javaState.set({ loading: true, key, response: null, error: null });
  try {
    const response = await post<DecompileResponse>('/api/uplc/decompile', { script: input });
    if (get(javaState).key === key) javaState.set({ loading: false, key, response, error: response.ok ? null : response.error });
  } catch (e) {
    if (get(javaState).key === key) javaState.set({ loading: false, key, response: null, error: (e as Error).message });
  }
}

function executionRequest() {
  const p = get(preset);
  return {
    script: get(scriptInput),
    transaction: get(transaction),
    protocolVersion: p.protocolVersion,
    maxCpu: p.maxCpu,
    maxMem: p.maxMem,
  };
}

function sourceDebugRequest() {
  const p = get(preset);
  return {
    source: get(sourceDebugSource),
    librarySource: get(sourceDebugLibrary) || undefined,
    params: get(sourceDebugParams),
    transaction: get(transaction),
    protocolVersion: p.protocolVersion,
    maxCpu: p.maxCpu,
    maxMem: p.maxMem,
    locals: get(engine) !== 'wasm' || !!get(wasmFeatures)?.groups.includes('sourceDebugLocals'),
  };
}

function sourceDebugBindingKey(request = sourceDebugRequest(), engineKind = get(engine)): string {
  return JSON.stringify({ engine: engineKind, request });
}

export async function evaluate(): Promise<void> {
  evalState.update((s) => ({ ...s, loading: true, error: null }));
  outputTab.set('result');
  try {
    const response = await post<EvaluateResponse>('/api/uplc/evaluate', executionRequest());
    evalState.set({ loading: false, response, error: response.ok ? null : response.error, stale: false });
  } catch (e) {
    evalState.set({ loading: false, response: null, error: (e as Error).message, stale: false });
  }
}

/** Compile an explicit, unoptimized source-debug artifact and open its bound CEK session. */
let sourceDebugSeq = 0;

export async function compileSourceDebug(): Promise<void> {
  const seq = ++sourceDebugSeq;
  localsSeq++;
  const old = get(sourceDebugState);
  if (old.sessionId) {
    void post('/api/source-debug/close', { sessionId: old.sessionId }, undefined,
      old.transport ?? currentTransport()).catch(() => undefined);
  }
  sourceDebugState.set({
    loading: true, response: null, sessionId: null, error: null, stale: false, bindingKey: null, transport: null,
  });
  javaLocalsState.set({ loading: false, response: null, error: null, identity: null });
  debugState.set({ active: false, loading: true, timeline: null, snapshot: null, error: null, stale: false });
  outputTab.set('debugger');
  const request = sourceDebugRequest();
  const engineKind = get(engine);
  const transport = currentTransport();
  const bindingKey = sourceDebugBindingKey(request, engineKind);
  try {
    const response = await post<SourceDebugOpenResponse>('/api/source-debug/open', request, undefined, transport);
    if (seq !== sourceDebugSeq) {
      if (response.sessionId) {
        void post('/api/source-debug/close', { sessionId: response.sessionId }, undefined, transport)
          .catch(() => undefined);
      }
      return;
    }
    if (response.params.length && get(sourceDebugParams).length !== response.params.length) {
      sourceDebugParams.set(response.params.map(() => ({ format: 'uplc', value: '' })));
    }
    if (!response.ok || !response.sessionId || !response.compiledCode) {
      sourceDebugState.set({
        loading: false, response, sessionId: response.sessionId, error: response.error, stale: false,
        bindingKey, transport: response.sessionId ? transport : null,
      });
      debugState.set({ active: false, loading: false, timeline: null, snapshot: null, error: response.error, stale: false });
      return;
    }
    if (bindingKey !== sourceDebugBindingKey()) {
      void post('/api/source-debug/close', { sessionId: response.sessionId }, undefined, transport)
        .catch(() => undefined);
      const message = 'Source-debug inputs changed while compiling. Compile again to bind the current revision.';
      sourceDebugState.set({
        loading: false, response, sessionId: null, error: message, stale: true, bindingKey, transport: null,
      });
      debugState.set({ active: false, loading: false, timeline: null, snapshot: null, error: message, stale: true });
      return;
    }
    // The returned code already has source parameters applied. Keep raw UPLC params empty.
    params.set([]);
    language.set('V3');
    validator.set('');
    scriptText.set(response.compiledCode);
    sourceDebugState.set({
      loading: false, response, sessionId: response.sessionId, error: null,
      stale: false, bindingKey, transport,
    });
    debugState.set({
      active: true, loading: false, timeline: response.timeline, snapshot: response.snapshot,
      error: null, stale: false,
    });
    void loadJavaLocals();
  } catch (e) {
    if (seq !== sourceDebugSeq) return;
    const message = (e as Error).message;
    sourceDebugState.set({
      loading: false, response: null, sessionId: null, error: message, stale: false, bindingKey: null, transport: null,
    });
    debugState.set({ active: false, loading: false, timeline: null, snapshot: null, error: message, stale: false });
  }
}

async function debugRequest(action: DebugAction, step: number): Promise<void> {
  debugState.update((s) => ({ ...s, loading: true, error: null }));
  try {
    const source = get(sourceDebugState);
    const isBoundSourceSession = !!source.sessionId && source.response?.compiledCode === get(scriptText);
    if (isBoundSourceSession && source.stale) {
      debugState.update((s) => ({ ...s, loading: false, error: 'Java source, script, transaction or engine changed. Recompile the source-debug build.' }));
      return;
    }
    const response = isBoundSourceSession
      ? await post<DebugResponse>('/api/source-debug/act', {
          sessionId: source.sessionId, action, step, breakpoints: get(breakpoints),
        }, undefined, source.transport ?? currentTransport())
      : await post<DebugResponse>('/api/uplc/debug', {
          ...executionRequest(), action, step, breakpoints: get(breakpoints),
        });
    if (!response.ok) {
      debugState.update((s) => ({ ...s, loading: false, error: response.error }));
      return;
    }
    debugState.update((s) => ({
      active: true,
      loading: false,
      timeline: response.timeline ?? s.timeline,
      snapshot: response.snapshot,
      error: null,
      stale: action === 'timeline' ? false : s.stale,
    }));
    if (isBoundSourceSession) void loadJavaLocals();
  } catch (e) {
    debugState.update((s) => ({ ...s, loading: false, error: (e as Error).message }));
  }
}

/** Start debugging: run once to build the timeline, then stop at step 0 (or at the failure when asked). */
export async function startDebug(atFailure = false): Promise<void> {
  outputTab.set('debugger');
  const source = get(sourceDebugState);
  if (source.response?.compiledCode === get(scriptText)) {
    if (!source.sessionId || source.stale) {
      await compileSourceDebug();
      return;
    }
    await debugRequest('goto', 0);
    return;
  }
  await debugRequest('timeline', 0);
  const timeline = get(debugState).timeline;
  if (atFailure && timeline?.errorStep != null) await debugRequest('goto', timeline.errorStep);
}

export function stopDebug(): void {
  sourceDebugSeq++;
  localsSeq++;
  const source = get(sourceDebugState);
  if (source.sessionId) {
    void post('/api/source-debug/close', { sessionId: source.sessionId }, undefined,
      source.transport ?? currentTransport()).catch(() => undefined);
  }
  sourceDebugState.update((s) => ({ ...s, sessionId: null, stale: !!s.response, transport: null }));
  debugState.set({ active: false, loading: false, timeline: null, snapshot: null, error: null, stale: false });
  javaLocalsState.set({ loading: false, response: null, error: null, identity: null });
}

let localsSeq = 0;
interface LocalsRequestIdentity {
  key: string;
  sessionId: string;
  generation: number;
  transport: Transport;
}

function currentLocalsRequestIdentity(): LocalsRequestIdentity | null {
  const source = get(sourceDebugState);
  const generation = get(debugState).snapshot?.stopGeneration;
  if (!source.sessionId || source.stale || generation == null || !source.response?.localsCapability?.available) {
    return null;
  }
  const key = localsIdentityKey({
    sessionId: source.sessionId,
    bindingKey: source.bindingKey,
    artifactIdentity: source.response.scriptHash,
    revision: sourceDebugSeq,
    generation,
  });
  if (!key) return null;
  return { key, sessionId: source.sessionId, generation,
    transport: source.transport ?? currentTransport() };
}

export async function loadJavaLocals(): Promise<void> {
  const seq = ++localsSeq;
  const source = get(sourceDebugState);
  const identity = currentLocalsRequestIdentity();
  if (!identity) {
    javaLocalsState.set({ loading: false, response: null,
      error: source.response?.localsCapability?.unavailableReason ?? null, identity: null });
    return;
  }
  javaLocalsState.set({ loading: true, response: null, error: null, identity: identity.key });
  try {
    const response = await post<JavaLocalsResponse>('/api/source-debug/locals', {
      sessionId: identity.sessionId, stopGeneration: identity.generation,
    }, undefined, identity.transport);
    const current = currentLocalsRequestIdentity();
    if (seq === localsSeq && localsResponseMatches(identity.key, current?.key,
      identity.generation, response.stopGeneration)) {
      javaLocalsState.set({ loading: false, response, error: response.ok ? null : response.error,
        identity: identity.key });
    }
  } catch (e) {
    const current = currentLocalsRequestIdentity();
    if (seq === localsSeq && current?.key === identity.key) {
      javaLocalsState.set({ loading: false, response: null, error: (e as Error).message,
        identity: identity.key });
    }
  }
}

export async function loadJavaLocalChildren(handle: string, start = 0,
  count = 50): Promise<JavaChildrenResponse> {
  const identity = currentLocalsRequestIdentity();
  if (!identity) throw new Error('Java locals reference is stale');
  const response = await post<JavaChildrenResponse>('/api/source-debug/children', {
    sessionId: identity.sessionId, stopGeneration: identity.generation, handle, start, count,
  }, undefined, identity.transport);
  const current = currentLocalsRequestIdentity();
  if (!localsResponseMatches(identity.key, current?.key, identity.generation, response.stopGeneration)) {
    throw new Error('Java locals reference is stale');
  }
  return response;
}

const currentStep = () => get(debugState).snapshot?.step ?? 0;
export const debugGoto = (step: number) => debugRequest('goto', Math.max(0, Math.round(step)));
export const debugStep = () => debugRequest('goto', currentStep() + 1);
export const debugBack = () => debugRequest('goto', Math.max(0, currentStep() - 1));
export const debugOver = () => debugRequest('over', currentStep());
export const debugOut = () => debugRequest('out', currentStep());
export const debugContinue = () => debugRequest('continue', currentStep());
export const debugRestart = () => debugRequest('goto', 0);

export function toggleBreakpointLine(line: number): void {
  breakpoints.update((b) => ({
    ...b, lines: b.lines.includes(line) ? b.lines.filter((l) => l !== line) : [...b.lines, line].sort((x, y) => x - y),
  }));
}

export function toggleJavaBreakpointLine(line: number): void {
  breakpoints.update((b) => ({
    ...b,
    javaLines: (b.javaLines ?? []).includes(line)
      ? b.javaLines.filter((value) => value !== line)
      : [...(b.javaLines ?? []), line].sort((x, y) => x - y),
  }));
}

export function setSourceDebugCode(value: string): void {
  if (value === get(sourceDebugSource)) return;
  sourceDebugSource.set(value);
  markSourceDebugStale();
}

export function useSourceDebugCode(source: string, library = ''): void {
  sourceDebugSource.set(source);
  sourceDebugLibrary.set(library);
  markSourceDebugStale();
}

export function loadExample(example: UplcExample): void {
  scriptText.set(example.script);
  params.set(example.params);
  language.set(example.language);
  validator.set('');
  transaction.set(defaultTransaction(example.purpose));
  breakpoints.update((b) => ({ ...b, lines: [] }));
  evalState.set({ loading: false, response: null, error: null, stale: false });
  stopDebug();
}

export function openScript(script: string, purpose: PurposeType): void {
  loadExample({ name: '', description: '', script, params: [], language: 'auto', purpose });
}

export function setPurpose(type: PurposeType): void {
  transaction.set(defaultTransaction(type));
}

// Re-decode when the script or engine changes; mark results stale when inputs change.
scriptInput.subscribe(() => {
  clearTimeout(decodeTimer);
  decodeTimer = setTimeout(decode, 250);
  markStale();
});
transaction.subscribe(markStale);
presetIndex.subscribe(markStale);
engine.subscribe(() => {
  clearTimeout(decodeTimer);
  decodeTimer = setTimeout(decode, 50);
  markSourceDebugStale();
});

let sourceParamsReady = false;
sourceDebugParams.subscribe(() => {
  if (sourceParamsReady) markSourceDebugStale();
  sourceParamsReady = true;
});

function markStale() {
  evalState.update((s) => (s.response ? { ...s, stale: true } : s));
  refreshSourceDebugStaleness();
  clearJavaLocalsIfStale();
  const source = get(sourceDebugState);
  const boundSource = !!source.sessionId && source.response?.compiledCode === get(scriptText);
  debugState.update((s) => (s.active ? { ...s, stale: boundSource ? source.stale : true } : s));
}

function markSourceDebugStale() {
  refreshSourceDebugStaleness();
  clearJavaLocalsIfStale();
  const source = get(sourceDebugState);
  const boundSource = !!source.sessionId && source.response?.compiledCode === get(scriptText);
  debugState.update((s) => (s.active ? { ...s, stale: boundSource ? source.stale : true } : s));
}

function clearJavaLocalsIfStale(): void {
  if (!get(sourceDebugState).stale) return;
  localsSeq++;
  javaLocalsState.set({ loading: false, response: null, error: null, identity: null });
}

function refreshSourceDebugStaleness(): void {
  sourceDebugState.update((s) => {
    if (!s.response || !s.bindingKey) return s;
    const stale = s.stale || s.bindingKey !== sourceDebugBindingKey()
      || s.response.compiledCode !== get(scriptText);
    return stale === s.stale ? s : { ...s, stale };
  });
}
