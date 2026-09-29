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
export type FeatureGroup = 'vm' | 'debug' | 'compiler' | 'uplc' | 'sourceDebug' | 'sourceDebugLocals';
export interface Features {api: 0; variant: 'full' | 'vm'; groups: FeatureGroup[]; defaultProtocol: 11; bls: false;}
export interface JulcError extends Error {code: string; status: number; body: unknown;}
export interface VmClient {
  version(): string;
  features(): Features;
  dispose(): void;
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
  variant: 'full' | 'vm';
  groups?: FeatureGroup[];
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
/** Narrow with isFullClient before accessing compiler and transaction helpers. */
export function createJulc(options?: Options): Promise<VmClient | FullClient>;
export function isFullClient(client: VmClient | FullClient): client is FullClient;
