# ADR-059: Generic backend revision 2

Status: Accepted for implementation in stacked milestones (#185, #180, #184, #183, #181, #182)

Extends [ADR-056](056-language-neutral-compiler-backend.md) (neutral backend) and
[ADR-050](050-library-source-type-descriptions.md) (library source descriptions). Public
text describes generic DSL frontends and does not name any consumer.

## Context

Revision 1 of the neutral backend accepts one closed, specialized PIR term with a FUNCTION,
SPEND or MINT entrypoint, checks lexical closure and handler shape, and exposes concrete
Java-source library exports. A typed DSL built on it currently has to:

- inline `LibraryProvider.materialize(symbol)` at every use, duplicating library code;
- build imported record projections itself from `LibraryType.fields`, and guess equality
  from erased representations;
- give up on withdrawal/certifying/voting/proposing handlers, multi-purpose scripts,
  deployment parameters and datum policies, although the Java frontend has them;
- trust that PIR which passes the closure check also agrees with its declared types.

Repository facts that constrain the design (verified at `c95ee610`):

- `UplcGenerator` erases PIR types except `DataConstr.dataType` (fields are encoded by
  their declared types; a count mismatch is not detected) and `MatchBranch.bindingTypes`
  (eager field decoding). `DataMatch` dispatches by **branch position**; one branch
  performs no tag check. It injects fixed `__match_*` and `__rest_N` binders, and a
  `Var` whose name starts with `.` is a field-accessor pseudo-variable.
- Under the PV11 profile `IfThenElse` lowers to `Case`. A non-Bool condition silently
  selects a branch instead of failing. A single-binding `LetRec` uses a Z combinator and
  its value must be a function.
- Java-generated PIR uses `DataType` as a placeholder for polymorphic slots (for example
  fold accumulators holding integers). Library bodies produced by the Java compiler
  therefore cannot pass a strict type checker.
- Single-purpose Java wrappers do not inspect the ScriptInfo tag;
  `ValidatorWrapper.wrapMultiValidator` does and decodes only the selected handler.
- No generic on-chain library method exists; the Java compiler rejects type variables.
- Programmatic stdlib builders inline their arguments, re-evaluate function arguments per
  element and bind fixed names around them (see #186).

## Goals

- One explicit, versioned revision policy and capability discovery for all new descriptors.
- A practical structural verifier for producer PIR, run before any lowering.
- Purpose-indexed validators, deployment parameters and datum profiles through the
  existing Julc wrapper and parameter machinery.
- Explicit, verified operations for imported types.
- Generic library exports specialized through the actual Java compiler, and programmatic
  PIR exports through the same consumer contract.

## Non-goals

- A proof system, semantic equivalence checking, or a sandbox for untrusted PIR.
- Arbitrary JVM generics, persistent caches or a serialized PIR interchange format.
- CIP-57 generation, source-position transport, or changes to Java validator semantics.
- Changes to ledger encodings, the VM, or existing Java script bytes.

## Invariants

1. Existing Java compilation and revision-1 neutral compilation produce identical bytes.
2. Strict evaluation order, captures and failure behaviour of producer PIR are preserved.
   Julc-owned boundary code runs exactly once and only for the selected purpose.
3. Nominal identities, constructor tags, field order and Data encodings are unchanged.
   No unsupported type or operation is approximated through erased `Data`.
4. Every new check fails before provider materialization or lowering, with a catalogued
   diagnostic that names the producer symbol or descriptor.
5. Generated artifacts are deterministic; handler order in a descriptor never matters.

## Decision

### Revision and capabilities (#185)

`BackendContract.REVISION = 2`. Revision 1 remains supported. Every revision-2
descriptor states the revision it was produced for and the capabilities it requires.
`CompilerBackend.capabilities(CompilerOptions)` reports the revision range, the resolved
target, boundary policies, supported entry kinds, purposes, datum profiles, library
request kinds and target protocol features (`target.*`). Capabilities are stable string
identifiers (`BackendCapability`) rather than enum constants, so a producer built for a
newer backend receives an actionable diagnostic from an older one instead of a linkage
error. The backend checks, in order: revision, required capabilities, target and
boundary policy, descriptor shape, verification, then lowering.

Diagnostics are catalogued in `diagnostics.json` and thrown as `BackendException`, which
extends `IllegalArgumentException` for revision-1 compatibility:

| Code | Meaning |
| --- | --- |
| JULC0044 | unsupported descriptor or provider revision |
| JULC0045 | required capability unavailable |
| JULC0046 | producer target differs from the backend target |
| JULC0047 | invalid descriptor (names, duplicates, conflicting types, signatures) |
| JULC0048 | invalid PIR binding (reserved name, unbound or duplicate symbol) |
| JULC0049 | PIR type disagreement |
| JULC0050 | PIR structure unsupported by lowering |
| JULC0051 | unsupported library request (operation or instantiation) |

### Revision-2 programs and trusted imports (#185)

A revision-2 program has four parts:

- **imports**: `LibraryImports` groups returned by providers. A group is closed as a
  unit, is linked once, and is trusted at the declared types of its exports.
- **definitions**: producer definitions, each with a declared type. They are strict,
  linked in encounter order with `PirLinker`, and may be mutually recursive functions.
- **entry**: a `FunctionProgram` term and type (#185), or `ValidatorProgram` handlers (#180).
- **namedTypes**: named definitions for the producer's types. Imported named types come
  from their import groups; an id defined twice must have equal definitions.

A provider materializes a list of `LibraryRequest`s as one group, so private dependencies
shared by several exports are linked once. Each group exposes a map from request to
binding name. The producer references imports only through those names. The existing
`LibraryProvider.materialize(String)` remains; a default adapter builds a group from it.

Linking is a single `PirLinker` pass over import definitions followed by producer
definitions, so dependencies are bound before their users. Imports and definitions are
evaluated once per script execution, before parameter decoding and purpose dispatch.
Work that must run only for one purpose belongs inside that handler's lambda.

Revision-1 `FrontendProgram` keeps its existing path and checks unchanged. A revision-1
producer cannot separate trusted library bodies from its own PIR, so it cannot receive
the structural verifier without false rejections.

### Structural verifier contract (#185)

`PirVerifier` checks producer-authored terms: definitions, entries and handlers.
Imported bodies are trusted and are checked only at their exported types.

Checked invariants:

- **Names:** binders are non-blank and do not use reserved forms. Reserved forms are a
  `.` or `__` prefix, a `$julc$` prefix, or `#` anywhere. Every free reference resolves
  to an import export, a definition, or an enclosing binder. Program-level names are
  pairwise disjoint.
- **Variables and applications:**
  - A `Var` annotation agrees with its binder's type.
  - `Lam` has type `param -> body`.
  - `App` applies a function type to an agreeing argument.
- **Builtins:** each application agrees with a representation-level signature. The table
  has an entry for every `DefaultFun`; a test anchors its value arity and type-variable
  count to `BuiltinSemantics`. Polymorphic builtins are fully applied. Unreleased
  builtins (`MultiIndexArray`) are rejected.
- **Conditionals and traces:**
  - `IfThenElse` conditions are exactly `Bool`, and both branches agree.
  - `Trace` messages are `String`.
- **Recursion and generator-local terms:**
  - `LetRec` binding values are lambdas.
  - `IntegerCase` is generator-local and is rejected.
- **`DataConstr`:**
  - Its type resolves to a record, a sum, or an `Optional` whose payload is already
    Data-represented. Records use tag 0; a sum constructor must exist for the tag.
  - The arity is exact, field terms agree, and field types are Data-encodable. Unit,
    function, pair, array and native types are not.
- **`DataMatch`:**
  - The scrutinee resolves to a record or sum whose tags are dense (`constructors[i].tag() == i`).
  - There is exactly one branch per constructor, in tag order, with matching names.
  - There are no more bindings than fields, and binding types agree with field types.
- **`ListMatch` and `PairMatch`:**
  - A `ListMatch` scrutinee is a Data list. Its head has the element's raw view; its
    tail has the scrutinee type.
  - A `PairMatch` scrutinee agrees with the declared pair.
- **Named types and declarations:**
  - Every `NamedTypeRef` resolves, and its kind agrees with its definition.
  - Each declared entry or definition type agrees with its term.
  - Imported named types are opaque to `DataMatch`/`DataConstr` unless their descriptor
    approves the operation (#183).

Agreement is equality after `NamedTypeRef` resolution, plus documented Data views:

- At the top level, `Data` agrees with Data-represented types (records, sums, `Optional`).
- In Data-encoded positions (list, map and array elements, `Optional` payloads), `Data`
  agrees with any Data-encodable type.
- `Map k v` agrees with `List (Pair Data Data)`.

Native versus Data, `Bool` versus Data, `Int` versus Data, distinct nominal types, and
`List Int` versus `List Bytes` are rejected.

Producer obligations, which are not checked:

- the provenance of opaque Data viewed as a typed value;
- that elements pushed into a typed list are correctly encoded;
- semantic equivalence and termination;
- purity of imports from custom providers.

### Typed multi-purpose handlers (#180)

`ValidatorProgram` adds these fields to the program parts: identity, boundary policy
(`julc-strict-v1`), parameters (#184) and handlers.

A `Handler` has a `ContractSchema.Purpose` (MINT, SPEND, WITHDRAW, CERTIFY, VOTE,
PROPOSE; ledger tags 0–5, mapped by an explicit switch), a producer symbol, a term, a
declared type and a `DatumProfile`. A descriptor produces one script; independent
descriptors produce independent scripts.

The backend binds each handler term to a fresh `$julc$handler$…` name outside the
wrapper, so wrapper binders cannot capture producer names. It always uses
`wrapMultiValidator`, even for one handler:

- unconfigured purposes fail explicitly;
- handlers dispatch in tag order, so descriptor order is irrelevant;
- only the selected handler's datum and redeemer are decoded and strictly checked.

The legacy single-entrypoint API keeps its single-purpose wrapper and its hashes. A
one-handler descriptor therefore does not reproduce a legacy script hash; that is
intentional.

Each handler must have the type `parameters -> [datum ->] redeemer -> context -> Bool`.
The context is `Data` or the ledger `ScriptContext` type. Datum and redeemer types must
be accepted by `StrictBoundaryGenerator.ensureSupported`, and native types are rejected.

`Unit` datum and redeemer values keep the existing strict `Constr 0 []` check, but the
handler receives native `()`. Julc's shared decoder passes the raw Data through for
`Unit`, which would contradict the native representation elsewhere.

The result carries a `ValidatorAbi`: identity, target, boundary, purpose-indexed
handlers with tags, datum profiles and boundary types, parameters and named types.

### Parameters and datum profiles (#184)

`Parameter(name, type)` values are listed in ABI order. Every handler receives all
parameters as leading arguments. Roles come from the descriptor, never from names.

The wrapper has the Java `@Param` shape, applied once outside the context lambda:
`\$julc$param$i$raw -> let $julc$param$i = decode(raw) in …`.

Parameters are decoded but not validated on-chain, exactly as in Java. Their types are
restricted to types with a Data decoding: Int, Bytes, String, Bool, Data, records, sums,
`Optional`, lists and maps. `BoundaryPrograms.check` compiles the strict boundary check
for a type as a standalone program, so a consumer can validate parameter Data with any
VM before `Program.applyParams`, at no on-chain cost.

`DatumProfile` values for SPEND handlers:

- `REQUIRED` keeps the existing force-unwrap: a missing datum fails.
- `OPTIONAL` passes the ledger `Maybe` as `Optional D`, strictly checked.
- `ABSENT` passes no datum argument and never inspects the datum.

Other purposes use `ABSENT`. The profile is recorded explicitly in the ABI, so an
`Optional` datum type is never ambiguous.

### Imported-type operations and codecs (#183)

`LibraryType` gains `operations`, derived only from metadata the provider has verified:

| Kind | Enabled for |
| --- | --- |
| CONSTRUCT | regular records, constructor-encoded sums, newtypes, ledger hashes |
| PROJECT | regular record fields and newtype values |
| MATCH | constructor-encoded sums and regular records with dense tags |
| EQUALS | Java `equals` semantics: `EqualsInteger`/`EqualsByteString`/`EqualsString`, Bool comparison, and `EqualsData` for constructor-encoded types |
| ENCODE, DECODE_STRICT, BOUNDARY | types accepted by `StrictBoundaryGenerator` |

Special layouts, such as the map-backed ledger `Value` or records with custom codecs,
get no operations. `LibraryRequest.Operation(type, kind, member)` materializes closed,
Julc-built PIR, which the verifier treats as trusted imports:

- constructors are built with `DataConstr`;
- projections use the Java field-access lowering;
- strict decoders use `StrictBoundaryGenerator`;
- encoders use `wrapEncode`;
- explicit container adapters convert between computational and serialized forms.

Unsupported requests fail with JULC0051. Type descriptions carry a representation revision.

### Generic export schemes and specialization (#181)

`LibraryExport` adds a stable identity, target requirements (`ProtocolCapability`) and a
`LibraryScheme`. A scheme has type parameters with explicit constraints, ordered source
parameters and a result. `LibraryType.Reference` can name a type variable.

Java generic `@OnchainLibrary` static methods become templates. Julc compiles them only
when a request supplies concrete type arguments:

- `TypeResolver` binds the method's type variables during compilation.
- The specialization is registered under a provider-local key, never in a Java
  validator's registry, so Java output is unchanged.
- The rejected forms are bounds, wildcards and raw types; calls from one template to
  another template; and native, function, pair or `Unit` type arguments.
- Java validators that call a template receive a diagnostic.

`SpecializationKey` combines the library content hash, export identity, nominal type
arguments, representation revision, API revision and target profile. It deduplicates
materializations within one provider instance.

### Programmatic providers (#182)

`StdlibLibraryProvider` (julc-stdlib) describes a catalogue of real programmatic exports
with schemes. It starts with the list higher-order functions (`map`, `filter`, `any`,
`all`, `find`, `foldl`).

- **Hygiene and evaluation:** each export materializes as a closed lambda over fresh
  `$julc$arg$i` parameters. Arguments are evaluated once, strictly, and cannot be
  captured.
- **Calling convention:** Julc-generated adapters decode Data elements for typed function
  arguments and encode results. The frontend passes ordinary typed functions.
- **Deliberate difference from Java:** Java inlines the function argument, so it is
  evaluated per element, and never for an empty list. Here it is evaluated once, before
  the traversal.

`LibraryProviders.compose` owns symbols deterministically. It rejects duplicate symbols
and conflicting type identities, and checks provider revisions before materialization.

## Alternatives rejected

- **Verify inlined library bodies.** Java PIR uses `Data` placeholders, so either every
  program would be rejected or the checks would be weakened for everything.
- **Share producer definitions per handler,** as the Java multi-validator does. That
  duplicates code; program-level definitions make the eager rule explicit instead.
- **Reuse the single-purpose wrapper for one-handler descriptors.** It cannot reject
  unconfigured purposes.
- **Validate parameters on-chain by default.** Every execution would pay the cost, and
  it diverges from Java. The off-chain check program covers this need.
- **Erase generics to `Data` and add call-site coercions.** Forbidden by #181; it
  changes the equality and decoding semantics of the source.
- **Rewrite the Java AST for specialization.** A type-variable environment at the single
  `TypeResolver` choke point is smaller and avoids string reconstruction of types.
- **Represent capabilities as an enum.** Unknown capabilities from newer producers would
  become linkage errors rather than diagnostics.

## Affected modules and stages

- `julc-compiler`: backend package, verifier, `ValidatorWrapper` reuse,
  `JavaLibraryProvider`, `LibraryCompiler`/`TypeResolver` (templates only), and the
  diagnostics catalog.
- `julc-stdlib`: the programmatic provider.
- No changes to the VM, the ledger API or the core.

## Compatibility

All additions are additive. `FrontendProgram`, `CompilerBackend.compile(FrontendProgram,
CompilerOptions)`, `LibraryProvider.materialize(String)` and existing records keep their
constructors and behaviour. Java script bytes are unchanged; each milestone compares its
artifacts. A generic method in a library source changes from a compile error to a
template that Java validators cannot call.

## Risks

- A verifier that is too strict rejects valid producer output. Test it against realistic
  DSL-shaped PIR and the existing neutral fixtures.
- A verifier that is too permissive misses representation errors. Give every rule a
  negative test.
- Builtin signature drift. The table is anchored to `BuiltinSemantics` and the VM tables.
- Specialization and Java type resolution interact. Environment state is scoped and
  restored, and Java validator output is compared.
- Eager evaluation of shared definitions makes a failing definition fail every purpose.
  This is documented, and producers keep per-purpose work inside handlers.

## Milestones and verification

| PR | Issue | Deliverable | Evidence |
| --- | --- | --- | --- |
| 1 | #185 | contract, capabilities, imports, verifier, `FunctionProgram` | negative test per invariant, valid recursive/imported/higher-order fixtures, revision-1 byte stability, overhead measurement |
| 2 | #180 | `ValidatorProgram`, six purposes, ABI | every purpose on full ledger contexts, wrong-purpose rejection, inactive-schema isolation, Java `@MultiValidator` parity, order independence |
| 3 | #184 | parameters, datum profiles, boundary programs | Java `@Param` parity, parameter order and hashes, present/missing/malformed datum per profile |
| 4 | #183 | type operations and codecs | record/sum round trips, wrong tag/arity/kind rejection, container adapter, special layouts stay opaque |
| 5 | #181 | schemes, templates, specialization | real Java generic source at two types, invalid substitutions, nominal separation, dedup keys |
| 6 | #182 | programmatic provider, composition | HOF exports through the same contract, strict capture/ordering, duplicate ownership |

Each milestone runs the compiler, stdlib, testkit, examples and annotation-processor
suites with `--rerun`, compares Java artifacts against main, and runs a DSL-consumer
build against a locally published snapshot.

## Open questions

- Source-position transport for producer diagnostics beyond symbol and descriptor names.
- Whether shared program definitions should also get a per-purpose-lazy form.
- A serialized form of descriptors for tooling; out of scope for this revision.

## Implementation evidence

### Milestone 1 (#185)

- **New code:** backend contract, capability and diagnostic types; `LibraryRequest`,
  `LibraryImports` and group materialization (default adapter plus native
  `JavaLibraryProvider` linking); `FunctionProgram`; `PirVerifier` with `BuiltinTyping`.
  Catalogue entries JULC0044–JULC0051 are added.
- **Verifier tests:**
  - `BuiltinTypingTest`: all 102 `DefaultFun` values, with value and type arity anchored
    to `BuiltinSemantics`.
  - `FunctionProgramTest`: a negative test per verifier invariant, plus recursive,
    mutually recursive, higher-order, partial-application, sum, record, list, `Optional`
    and pair fixtures evaluated on the VM.
  - Also covered: shared-helper deduplication in import groups, unreachable private
    dependencies, imported types that are opaque to `DataMatch`, and revision-1
    provider adapters.
- **Named `DataConstr` types:** testing found a latent lowering hazard. A `DataConstr`
  whose type is a `NamedTypeRef` is lowered without encoding its fields, because
  `UplcGenerator` does not resolve named references. The verifier now rejects that form.
  Revision-1 programs are unverified and keep this producer obligation.
- **Real DSL output:** a consumer frontend's full test suite was compiled a second time
  as revision-2 programs, with library functions referenced as imports instead of
  inlined. All 68 generated programs were verified and lowered, with 0 rejections. The
  54 function programs evaluate to identical results under revision 1 (inlined
  libraries) and revision 2 (shared imports).
- **Overhead:** verification takes about 2% of backend compile time and scales
  linearly: 0.5 ms for 15k nodes, 1.9 ms for 60k nodes, 19.7 ms for 242k nodes
  (JVM warm, single run).
- **Regression runs** (fresh, `--rerun`):
  - `julc-compiler` 1839/0/0 and `pairCaseTest` 71/0/0;
  - `julc-stdlib` 411/0/0, `julc-testkit` 193/0/0, `julc-examples` 81/0/0;
  - `julc-annotation-processor` 20/0/0.

  The revision-1 path and every Java compilation path are unchanged.

### Milestone 2 (#180)

- **New code:**
  - `ValidatorProgram` (with `Handler`), `DatumProfile`, `Parameter`, `ValidatorAbi` and
    `ValidatorResult`.
  - `ValidatorCompiler`, which checks the role and signature of each handler, verifies
    producer PIR, and dispatches through `ValidatorWrapper.wrapMultiValidator`.
  - Capabilities `program.validator`, `purpose.*` and `spend.datum.required`. Parameters
    and optional or absent spending datums are declared, but their capabilities are
    withheld until #184, so descriptors that use them fail with JULC0045.
- **`ValidatorProgramTest` covers:**
  - each of the six purposes on full `ScriptContextBuilder` contexts, rejecting wrong
    redeemers and every other purpose's context;
  - spending with a required datum: present, missing and malformed datums, and a
    malformed redeemer;
  - spend+mint and spend+withdraw groups;
  - inactive-schema isolation: a withdrawal with an integer redeemer succeeds although
    the spending redeemer is a record, and the reverse is rejected;
  - byte-identical scripts for reordered handlers;
  - the native `Unit` redeemer adaptation;
  - a context typed as the ledger `ScriptContext`, the ABI in tag order, and ledger tags
    matching the ScriptInfo encodings;
  - descriptor, signature, boundary, capability and verifier rejections.
- **Java parity:** a Java `@MultiValidator` with SPEND (record redeemer), MINT and
  WITHDRAW handlers agrees with the neutral descriptor on all 81 matrix cases. The matrix
  covers purposes × redeemers (valid, wrong tag, wrong arity, wrong kind) × datums
  (present, missing, malformed), with 4 accepting cases. The neutral script is 271 bytes
  against 321 for Java. Budgets are within 15%:

  | Purpose | Java CPU/mem | Neutral CPU/mem |
  | --- | --- | --- |
  | SPEND | 5,218,511 / 20,413 | 5,955,284 / 23,509 |
  | MINT | 2,074,195 / 7,623 | 2,314,195 / 9,123 |
  | WITHDRAW | 2,749,441 / 10,627 | 2,603,818 / 10,325 |
- **Cost of explicit purpose dispatch:** a one-handler minting descriptor costs 75 bytes
  and 2,218,195 CPU / 8,523 mem, against 41 bytes and 1,128,903 / 4,462 for the legacy
  single-purpose wrapper. The difference is the ScriptInfo tag check that rejects
  unconfigured purposes.
- **Regression runs** (fresh): `julc-compiler` 1854/0/0, `pairCaseTest` 71/0/0,
  `julc-stdlib` 411/0/0, `julc-testkit` 193/0/0, `julc-examples` 81/0/0,
  `julc-annotation-processor` 20/0/0.

### Milestone 3 (#184)

- **Capabilities:** `validator.parameters`, `spend.datum.optional`, `spend.datum.absent`
  and `boundary.check-program`.
- **Parameters:**
  - Parameters become outer lambdas in ABI order with the Java `@Param` shape. Each raw
    Data argument is decoded once and passed as a leading argument to every handler.
  - Parameter types need a Data decoding. `Unit`, native, function and pair parameters
    are rejected, as are duplicate or blank names and handlers whose leading argument
    types differ from the parameter types.
  - `BoundaryPrograms.check` compiles the `StrictBoundaryGenerator` check for a type as a
    standalone one-argument program, for off-chain parameter validation.
- **Datum profiles:** `OPTIONAL` passes the strictly checked ledger `Maybe`; `ABSENT`
  compiles the two-argument spending call and never inspects the datum.
- **`ValidatorParametersTest` (12 tests):**
  - Java `@Param` parity: `threshold`/`owner` applied with `Program.applyParams`,
    including a too-short owner and parameters in the wrong order. There are 9 cases,
    2 of which accept.
  - Applied script hashes are deterministic, change with parameter values and order,
    and differ from the unapplied hash. The ABI is deterministic.
  - All handlers receive the parameters.
  - `OPTIONAL` datums, present or missing, pass while a malformed present datum is
    rejected. `ABSENT` accepts any datum or none. A missing `REQUIRED` datum still fails.
  - Inactive-handler isolation, ABI profiles, check programs for primitive, record, list
    and `Optional` types, and every rejection above.
- **Regression runs** (fresh): `julc-compiler` 1866/0/0, `pairCaseTest` 71/0/0,
  `julc-stdlib` 411/0/0, `julc-testkit` 193/0/0, `julc-examples` 81/0/0,
  `julc-annotation-processor` 20/0/0.

### Milestone 4 (#183)

- **Audit before adding metadata.** `LibraryTypeDescriptions` already verified which
  records are regular: they have constructor-encoded components and no custom codec.
  `LibraryType.operations` is derived from that fact, newtype and sum flags, and a
  `StrictBoundaryGenerator` check. Nothing is inferred from erased representations.
- **Special layouts.** A special layout is a declared record whose runtime encoding is
  not constructor data; the map-encoded ledger `Value` is the example.
  - Special layouts get no operations.
  - A type that contains one (`TxOut`, `TxInfo`, a user record with a `Value` field)
    keeps CONSTRUCT/PROJECT/MATCH/ENCODE, but loses EQUALS, DECODE_STRICT and BOUNDARY.
    The strict checker would treat the `Value` record as constructor data and reject a
    correct map encoding, and `EqualsData` would be order-sensitive.
  - Validator datum, redeemer and parameter types, and `BoundaryPrograms`, now reject
    such types at compile time with JULC0047.
- **Pre-existing Java issue found (out of scope).** A Java validator whose datum record
  contains a ledger `Value` rejects the correct map encoding under strict boundaries and
  accepts only `Constr 0 [map]`. It is reported separately; this milestone does not
  change the Java path.
- **Materialization.** `LibraryRequest.Operation` and `LibraryRequest.Codec` go through
  `TypeOperations`, which a provider exposing types reuses:
  - constructors use `DataConstr`;
  - projections use the Java field-access lowering;
  - equality follows Java `equals` semantics;
  - encoders use `wrapEncode` and strict decoders use `StrictBoundaryGenerator`;
  - MATCH and BOUNDARY are permissions rather than terms.
- **Opacity and deduplication.** Imported types are now opaque per operation: producer
  PIR can `DataMatch` or `DataConstr` an imported type only if its description approves
  MATCH or CONSTRUCT. Identical definitions requested in separate groups are linked once.
- **Capabilities:** `library.request.operation`, `library.request.codec` and
  `library.type-operations.1`. `LibraryType.REPRESENTATION_REVISION` is 1.
- **`TypeOperationsTest` (11 tests) covers:**
  - the operation audit for custom and ledger types, including `Value`, `TxOut`,
    `Credential` and `PubKeyHash`;
  - record round trips and the fixed encoding `Quote(5, true) = Constr 0 [I 5, Constr 1 []]`;
  - strict decoding rejecting a wrong tag, wrong arity, extra fields, nested kinds and
    Bool tags;
  - sum construction, matching and decoding;
  - parity of equality and projection with the Java library's own `equals` and accessor
    methods;
  - a ledger `Credential` encoding identical to `julc-ledger-api` `toPlutusData`;
  - `List Quote` and `Map Bytes Int` codecs;
  - special layouts staying opaque in every form;
  - rejection of boundaries that contain `Value`, and of incompatible nominal layouts;
  - cross-group deduplication.
- **Compatibility:** an unmodified DSL-consumer suite built against this snapshot gives
  84/86, where the 2 failures are its pinned-version string check. This matches the
  baseline, so the API is compatible.
- **Regression runs** (fresh): `julc-compiler` 1877/0/0, `pairCaseTest` 71/0/0,
  `julc-stdlib` 411/0/0, `julc-testkit` 193/0/0, `julc-examples` 81/0/0,
  `julc-annotation-processor` 20/0/0.
