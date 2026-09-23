// Types of the REST UPLC tools API (/api/uplc/*). They mirror julc-tools' MockTransaction and UplcModels.

export type DataFormat = 'auto' | 'uplc' | 'json' | 'cbor';
export interface DataInput { format: DataFormat; value: string }

export type PurposeType = 'spend' | 'mint' | 'reward' | 'certify' | 'vote' | 'propose';
export interface Purpose { type: PurposeType; index?: number; target?: string; voterType?: string }
export interface Address { payment: string; stake?: string }
export interface Asset { policyId: string; tokenName: string; quantity: string }
export interface Value { lovelace: string; assets?: Asset[] }
export interface Datum { kind: 'none' | 'hash' | 'inline'; data?: DataInput; hash?: string }
export interface TxOut { address: Address; value: Value; datum?: Datum; referenceScript?: string }
export interface TxIn extends TxOut { txId: string; index: number }
export interface Interval { from?: number | null; fromInclusive?: boolean; to?: number | null; toInclusive?: boolean }
export interface Withdrawal { credential: string; amount: string }
export interface Certificate { type: string; credential: string; deposit?: string; poolId?: string }
export interface Vote { voterType: string; voter: string; actionTxId: string; actionIndex: number; vote: string }
export interface Proposal { deposit: string; returnCredential: string; actionType: string; guardrail?: string; withdrawals?: Withdrawal[] }

export interface MockTransaction {
  purpose: Purpose;
  redeemer: DataInput;
  inputs: TxIn[];
  referenceInputs: TxIn[];
  outputs: TxOut[];
  fee: string;
  mint: Asset[];
  certificates: Certificate[];
  withdrawals: Withdrawal[];
  validRange: Interval;
  signatories: string[];
  datums: DataInput[];
  txId: string;
  votes: Vote[];
  proposals: Proposal[];
  currentTreasuryAmount?: string;
  treasuryDonation?: string;
}

export interface ScriptInput { script: string; params: DataInput[]; language: string; validator?: string }

export interface Span { startLine: number; startColumn: number; endLine: number; endColumn: number }
export interface JavaLocation { fileName: string | null; line: number; column: number; fragment: string | null }

export interface ScriptInfo {
  inputFormat: string;
  wrapping: string;
  programVersion: string;
  language: string;
  languageSource: string;
  scriptHash: string;
  compiledCode: string;
  flatBytes: number;
  termCount: number;
  builtins: string[];
  paramsApplied: number;
  validator: string | null;
  validators: string[];
  warnings: string[];
}

export interface DecodeResponse { ok: boolean; error: string | null; info: ScriptInfo | null; uplcText: string | null }
export interface DecompileResponse { ok: boolean; error: string | null; javaSource: string | null; summary: string | null }

export interface EvaluateResponse {
  ok: boolean;
  error: string | null;
  status: 'success' | 'failure' | 'budgetExhausted' | null;
  accepted: boolean;
  message: string | null;
  cpu: number;
  mem: number;
  traces: string[];
  failedSpan: Span | null;
  result: string | null;
  lastBuiltins: string[];
  scriptContext: string | null;
  scriptContextCbor: string | null;
  scriptHash: string | null;
  language: string | null;
}

export interface Breakpoints { lines: number[]; onTrace: boolean; onError: boolean; builtins: string[]; javaLines: number[] }

export interface Timeline {
  totalSteps: number;
  traceSteps: number[];
  errorStep: number | null;
  status: string;
  truncated: boolean;
  cpu: number;
  mem: number;
}

export interface EnvEntry { name: string; value: string }
export interface Frame { kind: string; detail: string; span: Span | null }

export interface Snapshot {
  step: number;
  phase: 'compute' | 'return' | 'done' | 'failed';
  span: Span | null;
  javaLocation: JavaLocation | null;
  termKind: string | null;
  value: string | null;
  environment: EnvEntry[];
  frames: Frame[];
  stackDepth: number;
  cpu: number;
  mem: number;
  cpuDelta: number;
  memDelta: number;
  traces: string[];
  finished: boolean;
  status: string | null;
  error: string | null;
  stopReason: string;
  stopGeneration: number | null;
  localsAvailability: 'available' | 'unavailable' | 'unsupported' | null;
}

export interface DebugResponse { ok: boolean; error: string | null; timeline: Timeline | null; snapshot: Snapshot | null }

export interface SourceDebugParam { name: string; type: string }
export interface SourceDebugOpenResponse extends DebugResponse {
  sessionId: string | null;
  source: string | null;
  uplcText: string | null;
  compiledCode: string | null;
  scriptHash: string | null;
  scriptSizeBytes: number;
  params: SourceDebugParam[];
  diagnostics: import('../api/client').Diagnostic[];
  executableJavaLines: number[];
  target: { language: string | null; protocol: number | null } | null;
  costModelId: string | null;
  warning: string;
  localsCapability: { available: boolean; schema: string; layouts: string[]; unavailableReason: string | null } | null;
}

export interface JavaDebugValue {
  kind: string; summary: string; typeId: string; layoutId: string; availability: string;
  truncated: boolean; truncationReason: string | null; childCount: number | null; childrenHandle: string | null;
}
export interface JavaLocalValue {
  bindingId: string; name: string; declaredType: string; resolvedType: string; shadowed: boolean;
  availability: string; reason: string | null; declaration: {
    sourceId: string; startUtf16: number; endUtf16: number; startLine: number; startColumn: number;
    endLine: number; endColumn: number;
  }; value: JavaDebugValue | null;
}
export interface JavaLocalsResponse {
  ok: boolean; error: string | null; stopGeneration: number; availability: string; reason: string | null;
  scopes: { id: string; kind: string; variables: JavaLocalValue[] }[];
}
export interface JavaChildrenResponse {
  ok: boolean; error: string | null; stopGeneration: number; handle: string; start: number;
  nextStart: number | null; children: { name: string; value: JavaDebugValue }[];
}

export type DebugAction = 'timeline' | 'goto' | 'continue' | 'over' | 'out';
