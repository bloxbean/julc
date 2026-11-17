import * as M from './julc-models.js';
import type {rest as RestDefault} from './client-defaults.js';
// The generated names (<Outer>_<Inner>) for code written against the archives; @julclang/wasm-client keeps them under
// its types-only ./models sub-path (ADR-077 D5).
export * from './julc-models.js';

// Plain names for the generated records an operation takes or returns (ADR-077 D5). Generated records whose name is
// already plain (PlutusData, MockTransaction, CompileRequest, CheckRequest, …) keep it.
export type ScriptInput = M.UplcModels_ScriptInput;
export type DecodeRequest = M.UplcModels_DecodeRequest;
export type DecodeResponse = M.UplcModels_DecodeResponse;
export type ScriptInfo = M.UplcModels_ScriptInfo;
export type DecompileRequest = M.UplcModels_DecompileRequest;
export type DecompileResponse = M.UplcModels_DecompileResponse;
export type EvaluationResponse = M.UplcModels_EvaluateResponse;
export type DebugResponse = M.UplcModels_DebugResponse;
export type Breakpoints = M.UplcModels_Breakpoints;
export type Snapshot = M.UplcModels_Snapshot;
export type Timeline = M.UplcModels_Timeline;
export type Span = M.UplcModels_Span;
export type Frame = M.UplcModels_Frame;
export type EnvEntry = M.UplcModels_EnvEntry;
export type JavaLocation = M.UplcModels_JavaLocation;
export type CostModel = M.VmModels_CostModel;
export type Target = M.VmModels_Target;
export type DataInput = M.MockTransaction_DataInput;
export type ScriptPurpose = M.MockTransaction_Purpose;
export type TxIn = M.MockTransaction_TxIn;
export type TxOut = M.MockTransaction_TxOut;
export type Address = M.MockTransaction_Address;
export type Value = M.MockTransaction_Value;
export type Asset = M.MockTransaction_Asset;
export type Datum = M.MockTransaction_Datum;
export type Certificate = M.MockTransaction_Certificate;
export type Withdrawal = M.MockTransaction_Withdrawal;
export type Interval = M.MockTransaction_Interval;
export type Vote = M.MockTransaction_Vote;
export type Proposal = M.MockTransaction_Proposal;
/** The compiler's Run Test request and response (compiler.evaluate). */
export type TestRunRequest = M.EvaluateRequest;
export type TestRunResponse = M.EvaluateResponse;
export type SourceDebugOpenRequest = M.SourceDebugModels_OpenRequest;
export type SourceDebugOpenResponse = M.SourceDebugModels_OpenResponse;
export type SourceDebugActionResponse = M.SourceDebugModels_ActionResponse;
export type SourceDebugLocalsResponse = M.SourceDebugModels_LocalsResponse;
export type SourceDebugChildrenResponse = M.SourceDebugModels_ChildrenResponse;
export type SourceScope = M.SourceDebugModels_ScopeValue;
export type SourceLocal = M.SourceDebugModels_LocalValue;
export type SourceValue = M.SourceDebugModels_DebugValue;
export type SourceChild = M.SourceDebugModels_ChildValue;
export type SourceRange = M.SourceDebugModels_SourceRange;
export type LocalsCapability = M.SourceDebugModels_LocalsCapability;

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
export interface Features {
  api: 0;
  variant: Variant;
  groups: FeatureGroup[];
  defaultProtocol: 11;
  bls: false;
  /** The JuLC version the engine was built with; the client runs only an engine of its own release (ADR-077 D1a). */
  julcVersion: string;
}
export interface JulcError extends Error {code: string; status: number; body: unknown;}
/** What every engine has, in a client without the REST adapter (the npm packages, ADR-077 D4). */
export interface PackageClient {
  version(): string;
  features(): Features;
  dispose(): void;
}
/** What every engine has, with the REST adapter the archives load by default. */
export interface EngineClient extends PackageClient {
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
/** The operations of JuLC's VM image. */
export interface VmGroups {
  vm: {
    decode(request: Input<M.UplcModels_DecodeRequest>): Promise<M.UplcModels_DecodeResponse>;
    hash(request: Input<M.UplcModels_DecodeRequest>): Promise<{scriptHash: string}>;
    prettyPrint(request: {script: Input<M.UplcModels_ScriptInput>; width?: number}): Promise<{uplcText: string}>;
    evaluate(request: VmRequest): Promise<Evaluation>;
    debug(request: VmRequest): Promise<DebugSession>;
  };
}
/** The operations JuLC's full image adds. */
export interface FullGroups extends VmGroups {
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
export interface VmClient extends EngineClient, VmGroups {}
export interface FullClient extends VmClient, FullGroups {}
export interface VmPackageClient extends PackageClient, VmGroups {}
export interface FullPackageClient extends VmPackageClient, FullGroups {}
export interface Manifest {
  api: 0;
  version: string;
  launcher: string;
  variant: Variant;
  groups?: FeatureGroup[];
  /** The JuLC version the image was built with. */
  julcVersion: string;
}
/**
 * The worker a client drives. In browsers a DOM Worker is one; in Node.js the package's node entry adapts a
 * worker_threads Worker.
 */
export interface WorkerLike {
  postMessage(message: unknown): void;
  terminate(): unknown;
  onmessage: ((event: any) => unknown) | null;
  onerror: ((event: any) => unknown) | null;
}
interface CommonOptions {
  catalogue?: unknown;
  /** Required of the engine's version(), for an engine whose release is not JuLC's (ADR-077 D1a). */
  expectedVersion?: string;
  timeoutMs?: number;
  loadTimeoutMs?: number;
}
/** Archive mode: the files of a release archive, or an engine built on julc-wasm, served from one directory. */
export interface ArchiveOptions extends CommonOptions {
  baseUrl?: URL | string;
  manifest?: Manifest;
  workerFactory?: (url: URL) => WorkerLike;
  /** Loads rest-adapter.js from beside the manifest and adds rest(); the default depends on the distribution. */
  rest?: boolean;
  launcherUrl?: never;
  moduleUrl?: never;
}
/** Package mode: an engine package's files, named by its engine.js (ADR-077 D2). */
export interface PackageOptions extends CommonOptions {
  launcherUrl: URL | string;
  moduleUrl: URL | string;
  manifest: Manifest;
  /** Starts this package's julc-worker.js. */
  workerFactory?: () => WorkerLike;
  rest?: false;
  baseUrl?: never;
}
export type Options = ArchiveOptions | PackageOptions;
/** The client type when rest is not given: the archives load the REST adapter, @julclang/wasm-client does not. */
export type DefaultClient = typeof RestDefault extends true ? VmClient | FullClient : VmPackageClient | FullPackageClient;
/**
 * Narrow with isFullClient before accessing compiler and transaction helpers. An image another language builds on
 * julc-wasm names its client type, for example
 * `createJulc<VmClient & {elci: Operations<{check(r: CheckRequest): CheckResult}>}>()`.
 */
export function createJulc<C extends PackageClient = VmPackageClient | FullPackageClient>(options: PackageOptions): Promise<C>;
export function createJulc<C extends EngineClient = VmClient | FullClient>(options: ArchiveOptions & {rest: true}): Promise<C>;
export function createJulc<C extends PackageClient = VmPackageClient | FullPackageClient>(options: ArchiveOptions & {rest: false}): Promise<C>;
/** rest omitted: the distribution's default. */
export function createJulc<C extends PackageClient = DefaultClient>(options?: ArchiveOptions & {rest?: undefined}): Promise<C>;
/**
 * rest not known when compiling (a boolean), or options typed as Options and perhaps not given at all, as a function
 * that forwards its own optional options passes them: rest() is there only after a check.
 */
export function createJulc<C extends PackageClient = VmClient | FullClient | VmPackageClient | FullPackageClient>(options?: Options): Promise<C>;
export function isFullClient(client: EngineClient): client is FullClient;
export function isFullClient(client: PackageClient): client is FullPackageClient;
