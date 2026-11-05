import * as M from './julc-models.js';
export * from './julc-models.js';

/** Missing record fields use the Java service's documented defaults. */
export type Input<T> = T extends ReadonlyArray<infer E> ? Input<E>[]
  : T extends object ? {[K in keyof T]?: Input<T[K]>} : T;
export type VmRequest = Input<Omit<M.VmModels_Request, 'costModel'>> & {
  costModel?: string | Input<M.VmModels_CostModel> | null;
};
export type TransactionRequest = Input<Omit<M.FullApi_TransactionRequest, 'costModel'>> & {
  costModel?: string | Input<M.VmModels_CostModel> | null;
};
export interface ResolvedTarget {target: M.VmModels_Target; costModelId: string;}
export type Evaluation = M.UplcModels_EvaluateResponse & ResolvedTarget;
export type DebugReply = M.UplcModels_DebugResponse & ResolvedTarget;
export interface DebugSession extends ResolvedTarget {
  sessionId: string;
  timeline: M.UplcModels_Timeline | null;
  isOpen(): boolean;
  step(): Promise<DebugReply>;
  goto(step: bigint): Promise<DebugReply>;
  continue(breakpoints?: Input<M.UplcModels_Breakpoints>): Promise<DebugReply>;
  over(breakpoints?: Input<M.UplcModels_Breakpoints>): Promise<DebugReply>;
  out(breakpoints?: Input<M.UplcModels_Breakpoints>): Promise<DebugReply>;
  snapshot(): Promise<DebugReply>;
  close(): Promise<{closed: boolean}>;
}
export type SourceDebugSession = M.SourceDebugModels_OpenResponse & {
  sessionId: string;
  isOpen(): boolean;
  step(): Promise<M.SourceDebugModels_ActionResponse>;
  goto(step: bigint): Promise<M.SourceDebugModels_ActionResponse>;
  continue(breakpoints?: Input<M.UplcModels_Breakpoints>): Promise<M.SourceDebugModels_ActionResponse>;
  over(breakpoints?: Input<M.UplcModels_Breakpoints>): Promise<M.SourceDebugModels_ActionResponse>;
  out(breakpoints?: Input<M.UplcModels_Breakpoints>): Promise<M.SourceDebugModels_ActionResponse>;
  snapshot(): Promise<M.SourceDebugModels_ActionResponse>;
  close(): Promise<{closed: boolean}>;
  locals(): Promise<M.SourceDebugModels_LocalsResponse>;
  children(handle: string, start?: number, count?: number): Promise<M.SourceDebugModels_ChildrenResponse>;
};
/** JuLC's groups; an image another language builds on julc-wasm adds its own (ADR-064). */
export type JulcFeatureGroup = 'vm' | 'debug' | 'compiler' | 'uplc' | 'sourceDebug' | 'sourceDebugLocals';
export type FeatureGroup = JulcFeatureGroup | (string & {});
/** `full` and `vm` are JuLC's images; another language's image names its own variant. */
export type Variant = 'full' | 'vm' | (string & {});
export interface Features {api: 0; variant: Variant; groups: FeatureGroup[]; defaultProtocol: 11; bls: false;}
export interface JulcError extends Error {code: string; status: number; body: unknown;}
/** What every engine has. */
export interface EngineClient {
  version(): string;
  features(): Features;
  dispose(): void;
  /** The playground's HTTP routes, answered by the engine. */
  rest(method: string, path: string, body?: unknown): Promise<{status: number; body: unknown}>;
}
/**
 * The operations of an engine group as the client exposes them: one Promise per operation. Integers outside the
 * 32-bit range cross as `bigint`.
 */
export type Operations<T extends Record<string, (request: any) => unknown>> = {
  [K in keyof T]: (request: Parameters<T[K]>[0]) => Promise<Awaited<ReturnType<T[K]>>>;
};
export interface VmClient extends EngineClient {
  vm: {
    decode(request: Input<M.UplcModels_DecodeRequest>): Promise<M.UplcModels_DecodeResponse>;
    hash(request: Input<M.UplcModels_DecodeRequest>): Promise<{scriptHash: string}>;
    prettyPrint(request: {script: Input<M.UplcModels_ScriptInput>; width?: number}): Promise<{uplcText: string}>;
    evaluate(request: VmRequest): Promise<Evaluation>;
    debug(request: VmRequest): Promise<DebugSession>;
  };
}
export interface FullClient extends VmClient {
  compiler: {
    check(request: Input<M.CheckRequest>): Promise<M.CheckResponse>;
    compile(request: Input<M.CompileRequest>): Promise<M.CompileResponse>;
    evaluate(request: Input<M.EvaluateRequest>): Promise<M.EvaluateResponse>;
    evalExpression(request: Input<M.EvalExpressionRequest>): Promise<M.EvalExpressionResponse>;
  };
  uplc: {
    decompile(request: Input<M.UplcModels_DecompileRequest>): Promise<M.UplcModels_DecompileResponse>;
    defaultTransaction(request?: {purpose?: 'spend' | 'mint' | 'reward' | 'certify' | 'vote' | 'propose'; scriptHash?: string}): Promise<Input<M.MockTransaction>>;
    evaluateTransaction(request: TransactionRequest): Promise<Evaluation | M.UplcModels_EvaluateResponse>;
    debugTransaction(request: TransactionRequest): Promise<DebugSession | M.UplcModels_DebugResponse>;
  };
  sourceDebug: {
    open(request: Input<M.SourceDebugModels_OpenRequest>): Promise<SourceDebugSession | M.SourceDebugModels_OpenResponse>;
  };
}
export interface Manifest {
  api: 0;
  version: string;
  launcher: string;
  variant: Variant;
  groups?: FeatureGroup[];
  /** The JuLC version the image was built with. */
  julcVersion: string;
}
export interface Options {
  baseUrl?: URL | string;
  manifest?: Manifest;
  catalogue?: unknown;
  workerFactory?: (url: URL) => Worker;
  timeoutMs?: number;
  loadTimeoutMs?: number;
}
/**
 * Narrow with isFullClient before accessing compiler and transaction helpers. An image another language builds on
 * julc-wasm names its client type, for example
 * `createJulc<VmClient & {elci: Operations<{check(r: CheckRequest): CheckResult}>}>()`.
 */
export function createJulc<C extends EngineClient = VmClient | FullClient>(options?: Options): Promise<C>;
export function isFullClient(client: EngineClient): client is FullClient;
