import { derived, get, writable, type Writable } from 'svelte/store';
import { post } from '../api/client';
import { engine } from '../stores/engine';
import { EXAMPLES, type UplcExample } from './examples';
import { defaultTransaction, PRESETS } from './mock';
import type {
  Breakpoints, DataInput, DebugAction, DebugResponse, DecodeResponse, DecompileResponse, EvaluateResponse,
  MockTransaction, PurposeType, ScriptInput, Snapshot, Span, Timeline,
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
export const breakpoints = persisted<Breakpoints>('julc.uplc.breakpoints', { lines: [], onTrace: false, onError: true, builtins: [] });

export const preset = derived(presetIndex, (i) => PRESETS[i] ?? PRESETS[0]);

export const viewTab = writable<'uplc' | 'java' | 'stats'>('uplc');
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

async function debugRequest(action: DebugAction, step: number): Promise<void> {
  debugState.update((s) => ({ ...s, loading: true, error: null }));
  try {
    const response = await post<DebugResponse>('/api/uplc/debug', {
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
  } catch (e) {
    debugState.update((s) => ({ ...s, loading: false, error: (e as Error).message }));
  }
}

/** Start debugging: run once to build the timeline, then stop at step 0 (or at the failure when asked). */
export async function startDebug(atFailure = false): Promise<void> {
  outputTab.set('debugger');
  await debugRequest('timeline', 0);
  const timeline = get(debugState).timeline;
  if (atFailure && timeline?.errorStep != null) await debugRequest('goto', timeline.errorStep);
}

export function stopDebug(): void {
  debugState.set({ active: false, loading: false, timeline: null, snapshot: null, error: null, stale: false });
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
engine.subscribe(() => {
  clearTimeout(decodeTimer);
  decodeTimer = setTimeout(decode, 50);
});

function markStale() {
  evalState.update((s) => (s.response ? { ...s, stale: true } : s));
  debugState.update((s) => (s.active ? { ...s, stale: true } : s));
}
