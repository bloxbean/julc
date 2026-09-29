// Generated from Java records. Do not edit.
export type PlutusData = {int: bigint} | {bytes: string} | {list: PlutusData[]} | {map: {k: PlutusData; v: PlutusData}[]} | {constructor: bigint; fields: PlutusData[]};
export interface CheckRequest {
  source: string | null;
  language: string | null;
}

export interface CheckResponse {
  valid: boolean;
  contractName: string | null;
  purpose: string | null;
  params: Array<FieldDto> | null;
  datumName: string | null;
  datumFields: Array<FieldDto> | null;
  redeemerVariants: Array<VariantDto> | null;
  redeemerFields: Array<FieldDto> | null;
  diagnostics: Array<DiagnosticDto> | null;
}

export interface FieldDto {
  name: string | null;
  type: string | null;
}

export interface VariantDto {
  name: string | null;
  tag: number;
  fields: Array<FieldDto> | null;
}

export interface DiagnosticDto {
  level: string | null;
  code: string | null;
  message: string | null;
  startLine: number | null;
  startCol: number | null;
  endLine: number | null;
  endCol: number | null;
  suggestion: string | null;
}

export interface CompileRequest {
  source: string | null;
  language: string | null;
  librarySource: string | null;
  blueprint: boolean | null;
}

export interface CompileResponse {
  uplcText: string | null;
  javaSource: string | null;
  pirText: string | null;
  blueprintJson: string | null;
  compiledCode: string | null;
  scriptHash: string | null;
  scriptSizeBytes: number;
  scriptSizeFormatted: string | null;
  params: Array<FieldDto> | null;
  diagnostics: Array<DiagnosticDto> | null;
}

export interface EvalExpressionRequest {
  expression: string | null;
}

export interface EvalExpressionResponse {
  success: boolean;
  result: string | null;
  type: string | null;
  budgetCpu: bigint;
  budgetMem: bigint;
  traces: Array<string> | null;
  error: string | null;
  uplc: string | null;
}

export interface EvaluateRequest {
  source: string | null;
  language: string | null;
  librarySource: string | null;
  paramValues: Record<string, string> | null;
  scenario: ScenarioOverrides | null;
  datum: Record<string, string> | null;
  redeemer: RedeemerInput | null;
}

export interface ScenarioOverrides {
  signers: Array<string> | null;
  validRangeAfter: bigint | null;
  validRangeBefore: bigint | null;
}

export interface RedeemerInput {
  variant: number;
  fields: Record<string, string> | null;
}

export interface EvaluateResponse {
  success: boolean;
  budgetCpu: bigint;
  budgetMem: bigint;
  traces: Array<string> | null;
  error: string | null;
  diagnostics: Array<DiagnosticDto> | null;
}

export interface MockTransaction {
  purpose: MockTransaction_Purpose | null;
  redeemer: (MockTransaction_DataInput | PlutusData) | null;
  inputs: Array<MockTransaction_TxIn> | null;
  referenceInputs: Array<MockTransaction_TxIn> | null;
  outputs: Array<MockTransaction_TxOut> | null;
  fee: string | null;
  mint: Array<MockTransaction_Asset> | null;
  certificates: Array<MockTransaction_Certificate> | null;
  withdrawals: Array<MockTransaction_Withdrawal> | null;
  validRange: MockTransaction_Interval | null;
  signatories: Array<string> | null;
  datums: Array<(MockTransaction_DataInput | PlutusData)> | null;
  txId: string | null;
  votes: Array<MockTransaction_Vote> | null;
  proposals: Array<MockTransaction_Proposal> | null;
  currentTreasuryAmount: string | null;
  treasuryDonation: string | null;
}

export interface MockTransaction_Purpose {
  type: string | null;
  index: number | null;
  target: string | null;
  voterType: string | null;
}

export interface MockTransaction_DataInput {
  format: string | null;
  value: string | null;
}

export interface MockTransaction_TxIn {
  txId: string | null;
  index: bigint | null;
  address: MockTransaction_Address | null;
  value: MockTransaction_Value | null;
  datum: MockTransaction_Datum | null;
  referenceScript: string | null;
}

export interface MockTransaction_Address {
  payment: string | null;
  stake: string | null;
}

export interface MockTransaction_Value {
  lovelace: string | null;
  assets: Array<MockTransaction_Asset> | null;
}

export interface MockTransaction_Asset {
  policyId: string | null;
  tokenName: string | null;
  quantity: string | null;
}

export interface MockTransaction_Datum {
  kind: string | null;
  data: (MockTransaction_DataInput | PlutusData) | null;
  hash: string | null;
}

export interface MockTransaction_TxOut {
  address: MockTransaction_Address | null;
  value: MockTransaction_Value | null;
  datum: MockTransaction_Datum | null;
  referenceScript: string | null;
}

export interface MockTransaction_Certificate {
  type: string | null;
  credential: string | null;
  deposit: string | null;
  poolId: string | null;
}

export interface MockTransaction_Withdrawal {
  credential: string | null;
  amount: string | null;
}

export interface MockTransaction_Interval {
  from: bigint | null;
  fromInclusive: boolean | null;
  to: bigint | null;
  toInclusive: boolean | null;
}

export interface MockTransaction_Vote {
  voterType: string | null;
  voter: string | null;
  actionTxId: string | null;
  actionIndex: bigint | null;
  vote: string | null;
}

export interface MockTransaction_Proposal {
  deposit: string | null;
  returnCredential: string | null;
  actionType: string | null;
  guardrail: string | null;
  withdrawals: Array<MockTransaction_Withdrawal> | null;
}

export interface SourceDebugModels_ChildValue {
  name: string | null;
  value: SourceDebugModels_DebugValue | null;
}

export interface SourceDebugModels_DebugValue {
  kind: string | null;
  summary: string | null;
  typeId: string | null;
  layoutId: string | null;
  availability: string | null;
  truncated: boolean;
  truncationReason: string | null;
  childCount: number | null;
  childrenHandle: string | null;
}

export interface SourceDebugModels_SourceRange {
  sourceId: string | null;
  startUtf16: number;
  endUtf16: number;
  startLine: number;
  startColumn: number;
  endLine: number;
  endColumn: number;
}

export interface SourceDebugModels_LocalValue {
  bindingId: string | null;
  name: string | null;
  declaredType: string | null;
  resolvedType: string | null;
  shadowed: boolean;
  availability: string | null;
  reason: string | null;
  declaration: SourceDebugModels_SourceRange | null;
  value: SourceDebugModels_DebugValue | null;
}

export interface SourceDebugModels_ScopeValue {
  id: string | null;
  kind: string | null;
  variables: Array<SourceDebugModels_LocalValue> | null;
}

export interface SourceDebugModels_ChildrenResponse {
  ok: boolean;
  error: string | null;
  stopGeneration: bigint;
  handle: string | null;
  start: number;
  nextStart: number | null;
  children: Array<SourceDebugModels_ChildValue> | null;
}

export interface SourceDebugModels_LocalsResponse {
  ok: boolean;
  error: string | null;
  stopGeneration: bigint;
  availability: string | null;
  reason: string | null;
  scopes: Array<SourceDebugModels_ScopeValue> | null;
}

export interface SourceDebugModels_ChildrenRequest {
  sessionId: string | null;
  stopGeneration: bigint;
  handle: string | null;
  start: number;
  count: number;
}

export interface SourceDebugModels_LocalsRequest {
  sessionId: string | null;
  stopGeneration: bigint;
}

export interface SourceDebugModels_LocalsCapability {
  available: boolean;
  schema: string | null;
  layouts: Array<string> | null;
  unavailableReason: string | null;
}

export interface SourceDebugModels_CloseRequest {
  sessionId: string | null;
}

export interface SourceDebugModels_ActionResponse {
  ok: boolean;
  error: string | null;
  timeline: UplcModels_Timeline | null;
  snapshot: UplcModels_Snapshot | null;
}

export interface UplcModels_Timeline {
  totalSteps: bigint;
  traceSteps: Array<bigint> | null;
  errorStep: bigint | null;
  status: string | null;
  truncated: boolean;
  cpu: bigint;
  mem: bigint;
}

export interface UplcModels_Snapshot {
  step: bigint;
  phase: string | null;
  span: UplcModels_Span | null;
  javaLocation: UplcModels_JavaLocation | null;
  termKind: string | null;
  value: string | null;
  environment: Array<UplcModels_EnvEntry> | null;
  frames: Array<UplcModels_Frame> | null;
  stackDepth: number;
  cpu: bigint;
  mem: bigint;
  cpuDelta: bigint;
  memDelta: bigint;
  traces: Array<string> | null;
  finished: boolean;
  status: string | null;
  error: string | null;
  stopReason: string | null;
  stopGeneration: bigint | null;
  localsAvailability: string | null;
}

export interface UplcModels_Span {
  startLine: number;
  startColumn: number;
  endLine: number;
  endColumn: number;
}

export interface UplcModels_JavaLocation {
  fileName: string | null;
  line: number;
  column: number;
  fragment: string | null;
}

export interface UplcModels_EnvEntry {
  name: string | null;
  value: string | null;
}

export interface UplcModels_Frame {
  kind: string | null;
  detail: string | null;
  span: UplcModels_Span | null;
}

export interface SourceDebugModels_ActionRequest {
  sessionId: string | null;
  action: string | null;
  step: bigint | null;
  breakpoints: UplcModels_Breakpoints | null;
}

export interface UplcModels_Breakpoints {
  lines: Array<number> | null;
  onTrace: boolean | null;
  onError: boolean | null;
  builtins: Array<string> | null;
  javaLines: Array<number> | null;
}

export interface SourceDebugModels_OpenResponse {
  ok: boolean;
  error: string | null;
  sessionId: string | null;
  source: string | null;
  uplcText: string | null;
  compiledCode: string | null;
  scriptHash: string | null;
  scriptSizeBytes: number;
  params: Array<FieldDto> | null;
  diagnostics: Array<DiagnosticDto> | null;
  executableJavaLines: Array<number> | null;
  timeline: UplcModels_Timeline | null;
  snapshot: UplcModels_Snapshot | null;
  target: VmModels_Target | null;
  costModelId: string | null;
  warning: string | null;
  localsCapability: SourceDebugModels_LocalsCapability | null;
}

export interface VmModels_Target {
  language: string | null;
  protocol: number | null;
}

export interface SourceDebugModels_OpenRequest {
  source: string | null;
  librarySource: string | null;
  params: Array<(MockTransaction_DataInput | PlutusData)> | null;
  transaction: MockTransaction | null;
  protocolVersion: number | null;
  maxCpu: bigint | null;
  maxMem: bigint | null;
  target: VmModels_Target | null;
  costModel: VmModels_CostModel | null;
  locals: boolean | null;
}

export interface VmModels_CostModel {
  profile: string | null;
  target: VmModels_Target | null;
  parameters: Array<bigint> | null;
}

export interface TranspileRequest {
  source: string | null;
  language: string | null;
}

export interface TranspileResponse {
  javaSource: string | null;
  diagnostics: Array<DiagnosticDto> | null;
}

export interface UplcModels_DebugResponse {
  ok: boolean;
  error: string | null;
  timeline: UplcModels_Timeline | null;
  snapshot: UplcModels_Snapshot | null;
}

export interface UplcModels_DebugRequest {
  script: UplcModels_ScriptInput | null;
  transaction: MockTransaction | null;
  protocolVersion: number | null;
  maxCpu: bigint | null;
  maxMem: bigint | null;
  action: string | null;
  step: bigint | null;
  breakpoints: UplcModels_Breakpoints | null;
}

export interface UplcModels_ScriptInput {
  script: string | null;
  params: Array<(MockTransaction_DataInput | PlutusData)> | null;
  language: string | null;
  validator: string | null;
}

export interface UplcModels_EvaluateResponse {
  ok: boolean;
  error: string | null;
  status: string | null;
  accepted: boolean;
  message: string | null;
  cpu: bigint;
  mem: bigint;
  traces: Array<string> | null;
  failedSpan: UplcModels_Span | null;
  result: string | null;
  lastBuiltins: Array<string> | null;
  scriptContext: string | null;
  scriptContextCbor: string | null;
  scriptHash: string | null;
  language: string | null;
}

export interface UplcModels_EvaluateRequest {
  script: UplcModels_ScriptInput | null;
  transaction: MockTransaction | null;
  protocolVersion: number | null;
  maxCpu: bigint | null;
  maxMem: bigint | null;
}

export interface UplcModels_DecompileResponse {
  ok: boolean;
  error: string | null;
  javaSource: string | null;
  summary: string | null;
}

export interface UplcModels_DecompileRequest {
  script: UplcModels_ScriptInput | null;
}

export interface UplcModels_DecodeResponse {
  ok: boolean;
  error: string | null;
  info: UplcModels_ScriptInfo | null;
  uplcText: string | null;
}

export interface UplcModels_ScriptInfo {
  inputFormat: string | null;
  wrapping: string | null;
  programVersion: string | null;
  language: string | null;
  languageSource: string | null;
  scriptHash: string | null;
  compiledCode: string | null;
  flatBytes: number;
  termCount: number;
  builtins: Array<string> | null;
  paramsApplied: number;
  validator: string | null;
  validators: Array<string> | null;
  warnings: Array<string> | null;
}

export interface UplcModels_DecodeRequest {
  script: UplcModels_ScriptInput | null;
}

export interface VmModels_Request {
  script: UplcModels_ScriptInput | null;
  args: Array<(MockTransaction_DataInput | PlutusData)> | null;
  target: VmModels_Target | null;
  costModel: VmModels_CostModel | null;
  maxCpu: bigint | null;
  maxMem: bigint | null;
}

export interface FullApi_TransactionRequest {
  script: UplcModels_ScriptInput | null;
  transaction: MockTransaction | null;
  protocolVersion: number | null;
  maxCpu: bigint | null;
  maxMem: bigint | null;
  target: VmModels_Target | null;
  costModel: VmModels_CostModel | null;
}
