// @julclang/wasm-client: the client's API and plain names for the records it exchanges (ADR-077 D5). The generated
// <Outer>_<Inner> names are under the types-only sub-path @julclang/wasm-client/models.
export {
  createJulc, isFullClient,
  type ArchiveOptions, type PackageOptions, type Options, type Manifest, type WorkerLike, type DefaultClient,
  type PackageClient, type EngineClient, type VmClient, type FullClient, type VmPackageClient, type FullPackageClient,
  type VmGroups, type FullGroups, type Operations, type Input,
  type Features, type FeatureGroup, type JulcFeatureGroup, type Variant, type JulcError,
  type VmRequest, type TransactionRequest, type ResolvedTarget, type Evaluation, type DebugReply, type DebugSession,
  type SourceDebugSession,
  type PlutusData, type DataInput, type ScriptInput, type DecodeRequest, type DecodeResponse, type ScriptInfo,
  type DecompileRequest, type DecompileResponse, type EvaluationResponse, type DebugResponse,
  type Breakpoints, type Snapshot, type Timeline, type Span, type Frame, type EnvEntry, type JavaLocation,
  type CostModel, type Target,
  type MockTransaction, type ScriptPurpose, type TxIn, type TxOut, type Address, type Value, type Asset, type Datum,
  type Certificate, type Withdrawal, type Interval, type Vote, type Proposal,
  type CheckRequest, type CheckResponse, type CompileRequest, type CompileResponse, type TestRunRequest,
  type TestRunResponse, type EvalExpressionRequest, type EvalExpressionResponse, type DiagnosticDto, type FieldDto,
  type VariantDto, type ScenarioOverrides, type RedeemerInput,
  type SourceDebugOpenRequest, type SourceDebugOpenResponse, type SourceDebugActionResponse,
  type SourceDebugLocalsResponse, type SourceDebugChildrenResponse, type SourceScope, type SourceLocal,
  type SourceValue, type SourceChild, type SourceRange, type LocalsCapability,
} from './julc-wasm.js';
