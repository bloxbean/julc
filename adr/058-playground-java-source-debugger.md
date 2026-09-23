# ADR-058: Experimental Java-source debugging in the playground

**Status:** Milestone 1 is implemented on the stacked branch and has passed the
full/VM GraalVM Web Image, reachability and cross-platform playground build gates.
Milestone 2 is implemented for the explicit scalar/raw-Data coverage described
below and has completed an independent agent correctness review. A fresh real Web
Image/reachability run and human review remain pending.
The developer approved a separate, visibly labeled source-debug compilation whose
script bytes, hash and budget may differ from normal compilation (2026-09-23).
**Parent:** ADR-057, PR #169 (`feat/julc-wasm-distribution`).
**Builds on:** ADR-055's UPLC debugger and the existing compiler source maps.

## Context and current behavior

Before milestone 1, the playground could compile Java in Contract mode and transfer
compiled CBOR to UPLC / Debugger, but could not show the original Java source or
set Java line breakpoints. The stacked branch now implements those operations and
the bounded, fail-closed Java-locals subset documented below.

Verified at parent commit `0a7e5024`:

- `julc-core`'s `SourceMap` maps exact `Term` identities to file, line, column
  and fragment. Its indexed representation can be rebound after serialization
  only to a tree with the same traversal structure.
- `JulcCompiler` skips the **UPLC optimizer** when source maps are enabled. This
  is not a promise that all earlier compiler transformations are disabled.
- Normal playground compilation does not return source maps. The UPLC service
  decodes the script and uses `SteppingEvaluation` with `EvalOptions.DEFAULT`.
- UPLC breakpoints use pretty-printed UPLC positions. The current environment
  display contains UPLC binder names and raw CEK values, not Java locals.
- Names in in-memory lambda terms do not survive FLAT serialization. Source
  locations do not encode binding identities, lexical scopes or Java types.
- Existing `SteppingEvaluationTest` cases check observation is read-only and
  compare stepped results, budgets, traces and failures with plain evaluation.

The local, untracked ADR-053 draft describes a broader backend-neutral debugger,
typed metadata and IDE integration. Its unconditional normal/debug artifact
identity requirement does not match today's compiler. This tracked ADR specifies
a bounded playground feature, without importing or modifying that draft. It does
not authorize the larger VM SPI, Truffle or IDE changes.

## Goals and non-goals

Goals:

- An optional editable Java panel in UPLC / Debugger: paste source or choose an
  existing playground example, explicitly compile for debugging, then inspect
  generated UPLC alongside the captured Java source.
- Java and UPLC breakpoints stop the **same** CEK session. Both views highlight
  the current location when an exact mapping exists.
- Run on the JVM server and full Wasm engine with matching behavior.
- Add accurate Java variable inspection as a separate milestone, with explicit
  unavailable/ambiguous states instead of guessed names or values.
- Preserve default compiler output and existing VM behavior.

Non-goals for this feature:

- Debugging optimized deployment artifacts using a different source-debug map.
- Java/JVM execution, reflection over running Java objects, watch expression
  execution, variable mutation, conditional breakpoints or arbitrary Java input.
  Source still goes through JuLC's existing safe-subset validation.
- Java statement step-over/out, reconstructed Java call stacks, DAP/IDE support,
  new VM SPI, Truffle or Scalus stepping. Existing controls retain CEK semantics.
- Java compilation in the VM-only Wasm image.

## Invariants

1. Normal compilation, FLAT/CBOR serialization, ledger encoding, evaluation order
   and default compiler options remain unchanged. Source-debug compilation is
   explicit and never replaces Contract mode's normal compilation result.
2. Debug metadata is observational. Adding/removing metadata for a **fixed debug
   program** must not change its bytes, hash, evaluation result or budget.
3. For that same program, arguments, target, cost model and budget, stepping to
   completion and plain evaluation agree in value/failure, consumed budget,
   traces and failed term. This does not assert normal/debug build budget parity.
4. Source, metadata, executable program and pretty-print mapping belong to one
   compilation/session revision. No mapping is attached to a different program.
5. No changes to the Java VM evaluation loop, builtin implementations, cost
   accounting, environment mutation or failure handling are required or permitted
   by this design. If implementation discovers otherwise, stop and revise it.
6. Missing/ambiguous mappings and unavailable variables are displayed honestly;
   neither a neighboring line nor a plausible variable name is silently used.
7. Each session retains its resolved target, cost model and arguments. Replay,
   server and Wasm must agree. ADR-057's BigInt, lifecycle, error and BLS limits
   remain in force.

## Decision

### Explicit source-debug compilation

Add an opt-in tools operation, exposed through a thin server route and a typed
full-image SDK operation, using a fresh compiler configured for source maps.
Keep the existing compile/evaluate/debug requests compatible. Milestone 1 exposes
this through `SourceDebugService.open/act/close` and `SourceDebugModels`, with thin
server and full-image adapters.

Compilation produces diagnostics or a debug artifact containing the generated
program, its source map, captured source and compiler target. A session binds
these together with applied parameters, evaluation arguments and cost settings.
Use the existing mock-transaction and raw-argument preparation where applicable.
Do not introduce a second CEK evaluator or a second cost-model resolver.

Persistent UI warning while a debug artifact is displayed:

> Experimental source-debug build — the UPLC optimizer is disabled. Script bytes,
> hash and execution budget may differ from normal compilation. Do not use this
> build's budget as an estimate for your normally compiled contract.

Show the debug artifact's own hash, size and budget. Label any byte download as
a source-debug artifact. Never silently export or deploy it as the normal build.

### Exact association and lifecycle

Prefer retaining the program and map together in the tools session, avoiding an
unnecessary CBOR decode that would discard identity and names. If reconstruction
is required, validate the artifact identity and rebind indexed metadata to the
exact decoded tree before starting the machine. Index agreement alone is not
sufficient to authenticate that a sidecar belongs to a script.

Bind source content digest, compiler options/target, unparameterized artifact
digest and applied parameter identity to the session. Record the resulting
parameterized script identity too. Parameter application can add or transform
terms: preserve mappings for retained identities or explicitly rebuild and test
the association. Reject unsupported cases rather than attaching a stale map.

Editing Java, selecting an example, replacing UPLC or changing parameters marks
the displayed compilation stale and disables debugging until rebuilt. Changing
arguments, transaction, target or cost model starts a new evaluation session.
Late responses from an older revision cannot replace current state. Worker loss,
timeout and close invalidate the session and its mappings as in ADR-057.

### Breakpoints and stepping

Build Java file/line-to-term and UPLC position-to-term indexes for the same
session. Breakpoints in either view match exact executable terms, not a lossy
Java-line-to-UPLC-line conversion. Existing UPLC pretty-print text may put several
terms on one line; that must not make a Java breakpoint hit unrelated terms.

Report each Java breakpoint as resolved or unbound. Comments, blank lines and
source constructs without mapped executable terms cannot be advertised as bound.
Shared terms or multiple source origins require an ambiguous indicator; do not
invent an exact origin. Continue advances from the suspended transition so it
does not immediately re-hit the same stopped state. Re-entering that source line
later in a loop may hit again.

Existing step/over/out remain CEK actions, labeled accordingly. Java highlighting
may stay on a line over several transitions or be unavailable in generated code.
Java method frames must not be inferred from CEK stack depth.

### Variable metadata: a separate, reviewed milestone

Yes, Java variable inspection needs more than source locations. Record optional,
versioned debug metadata outside the on-chain bytes:

- Stable binding identity and original Java name, including distinct shadowed
  bindings and captured variables.
- Declared/inferred JuLC type and the actual lowered value representation.
- Source range, lexical scope and availability information.
- The exact lowered binder/term association needed to resolve the binding to a
  CEK environment entry **at the current suspension point**.

Generate this information where frontend bindings and types are known, carry it
through applicable PIR/lowering transformations, and attach it to the final
debug term tree. A source name, lambda name or textual suffix is not sufficient
to recover binding identity. Disabling the UPLC optimizer alone does not remove
the need to account for earlier lowering, captured environments and loop binders.

The tools layer reads existing CEK state and projects bounded, immutable display
values. Decoding uses recorded representation/type information, with tested
handling for primitives, Data, records, lists/maps and NewType. Missing metadata,
ambiguous bindings, non-materialized values and unsupported representations show
explicit unavailable states or labeled raw UPLC values. Inspection must never
evaluate closures, force delayed terms, call user code or mutate machine state.

Milestone 1 keeps the existing panel labeled **UPLC environment**, without claiming
Java names/types/scopes. Milestone 2 adds a distinct Java locals presentation only
for representations with proven mappings. Rich metadata remains opt-in; default
compilation does not collect it.

### Milestone 2 design: exact locals, reusable outside the playground

This section records the implementation contract. Milestone 2 implements the
bounded immutable-scalar/raw-Data subset and the fail-closed proof described
below; the broader provenance and composite-layout cases are retained as explicit
future coverage, not as current support. The metadata and inspection services are
transport-neutral and suitable for a later IntelliJ or DAP adapter. Implementing
an IDE plugin, DAP transport, Java call stacks or Java step-over/out remains
outside this milestone.

#### Repository reality and ownership

The reviewed branch has these constraints:

- `SymbolTable` stores scope-local name/type pairs; `PirTerm` binders and uses
  contain strings, not declaration identities. `PirGenerator`, `LoopBodyGenerator`
  and `LoopDesugarer` create/rebind locals, loop accumulators and synthetic names.
  Suffix stripping (including `sourceName`) is diagnostic formatting, not proof
  of a Java declaration's identity.
- Both validator and method compilation run `ValueLiteralFoldPass`,
  `ArrayLiteralFoldPass`, `ValueConversionSharingPass`, `ListIndexPromotionPass`
  and `PairDestructuringPass` before `UplcGenerator`. Source maps do not disable
  those passes. Substitution, match lowering, recursive binding expansion and
  generated boundary wrappers also create or copy terms.
- `UplcGenerator` resolves names using its lexical binder stack. Source positions
  propagate to descendants by inheritance; that inheritance is insufficient to
  establish source scope or binding availability. Its synthetic match/recursion
  lowering must participate in binding metadata generation too.
- `SourceDebugService` currently applies parameters, indexes locations, encodes
  CBOR, prepares/decodes that CBOR, and reconstructs `SourceMap`. It does not
  retain the original term objects. Its map-size check is not an artifact digest
  check. `Program.applyParams` currently adds outer applications and retains the
  old subtree, so indices must be assigned again after application.
- `CekMachine.currentEnvironment()` and `currentTerm()` are documented as
  meaningful in the compute phase. Lambda application uses the closure's
  environment extended with its argument, and forcing a delay restores its
  captured environment. `CekEnvironment.lookup` uses one-based de Bruijn indices.
  These existing APIs suffice; no VM instrumentation is authorized.
- `DebugValues` is a raw string formatter, not a typed decoder. Its final string
  truncation does not establish a bound on traversal/allocation of a large
  constant. Typed display needs a bounded walker before formatting.

`julc-core` owns immutable, compiler-independent metadata records, type/layout
descriptors and structural validation. Keep them separate from `SourceMap` v1
and from `Term`, `Constant`, FLAT and CBOR. `julc-compiler` owns the optional
metadata collector, declaration resolution, transformation provenance and final
location verifier. `julc-tools` owns strict sidecar transport encoding, artifact
validation, CEK observation, bounded value projection and session handles.
`julc-playground` and `julc-wasm` remain adapters/presentation. The VM modules,
ledger encoders and stdlib semantics remain unchanged. Do not introduce a second
type resolver or compiler symbol table in the tools layer.

#### Opt-in compilation and compatibility

Add a dedicated compiler operation returning `DebugCompileResult(CompileResult,
DebugMetadata)` and using an internal metadata collection mode. Keep the existing
`CompileResult` record, its constructors and existing compile methods unchanged.
The operation requires source-debug compilation and fails explicitly if its
options cannot be honored. Normal compilation and source-map-only compilation
allocate no binding metadata and retain their existing behavior. The fresh
compiler instance in `SourceDebugService` selects this operation only when locals
are requested; source-map-only clients continue to work.

Do not switch optimization profiles, disable existing PIR passes, keep otherwise
dead bindings alive, add tracing, or insert semantic UPLC nodes to improve locals.
For identical source-debug options, collecting locals must produce identical
FLAT bytes to source-map-only compilation. Earlier boolean return guards already
belong to source-debug behavior; metadata collection must not add another change.
An unsupported transformation may lose a local with an explicit reason, but may
not fabricate a mapping. A contradictory or invalid mapping is an internal
debug-compilation error, not a silently accepted partial map.

#### Versioned metadata contract

Use a distinct envelope `format: julc-java-debug`, `major: 1`, `minor: 1`, with
required capability identifiers. Unknown major versions, required features or
layout kinds fail closed for locals; an older client may still offer explicitly
labeled source-map-only debugging. Additive optional fields may be ignored.
Do not reuse the permissive hand-written `SourceMapSerializer` JSON reader for
this envelope. Validate duplicate IDs, references, numeric bounds, scope cycles,
layout cycles, occurrence kinds and indices, and cap all input collections.
Recursive nominal types use explicit references, not recursively expanded JSON.

The envelope contains these tables (names are proposed API names):

| Table | Required information |
|-------|----------------------|
| Artifact | Compiler/build identity, target, complete options and optimization provenance, canonical FLAT SHA-256, UPLC version, traversal version and occurrence count |
| Sources | Logical source ID/URI, exact UTF-8 content digest, captured content or immutable reference, line table |
| Scopes | Scope ID, parent, source range, kind (class, method, lambda, block, loop, pattern), owning method ID |
| Bindings | Binding ID, original name, declaration range, scope ID, kind (parameter, local, pattern, captured origin, `@Param`), declared spelling and resolved type ID |
| Types/layouts | Nominal source types, field order/tags and references; separate physical representation descriptors with child layouts |
| Lowered bindings | Lowered binder ID, source binding ID if any, generated role, assignment/version origin, physical layout ID and final lambda occurrence anchor |
| Suspension points | Final term occurrence, exact/ambiguous/generated source context, scope chain, expected lexical binder vector, visible bindings and location/unavailability records |

Ranges are half-open offsets into the exact captured Java string in UTF-16 code
units; line/column conversion is explicit and one-based. No line-ending or Unicode
normalization is permitted after digesting. Logical file IDs distinguish pasted
source, supplied library and bundled source even if all parser filenames happen
to be `<source>`. Absolute workstation paths are not required or exported.

Binding IDs are deterministic within an artifact: logical source ID, owning
method/lambda declaration path and declaration ordinal from the original AST.
They do not use object hash codes, mutable counters shared across compilations,
display names or generated-name suffixes. Edits need not preserve IDs; artifact
identity namespaces them. Source binding identity is distinct from lowered binder
identity: a loop assignment/copy may have several lowered binders for one source
declaration. Captures retain their originating binding ID. Generated binders have
no source binding unless lowering explicitly certifies the association.

#### Propagation and final environment proof

Create declarations/scopes before AST rewriting, then attach provenance to the
existing symbol-resolution operations through an optional collector. Attach the
resolved binding identity to each PIR use and binder site through identity-based
side tables, including binder slots for multi-binder nodes. Avoid adding debug
fields to public PIR records or changing their structural equality. Identity keys
are temporary compiler bookkeeping, never persistent IDs.

Every rebuilding transformation must return metadata transfer information with
its output. Preserve, clone, alias, replace and drop are explicit operations.
Clones retain source identity but receive deterministic lowered occurrence IDs.
Generated helper expressions have generated scope unless the transformation can
prove an exact source context. Merge conflicting provenance to ambiguous, never
last-writer-wins. Audit all five PIR passes above, `PirSubstitution`, loop AST
clones/renames, joins, early return lowering, lambda/HOF captures, pattern binds,
boundary wrappers, method/library wrapping and `UplcGenerator`'s recursive and
match expansions. Attach types before alias/NewType erasure and record the actual
physical layout at each resulting binder, not merely the initial Java type.

In `UplcGenerator`, extend optional bookkeeping alongside the actual scope stack;
do not infer it later from emitted binder names. Tag each emitted lambda binder
with its lowered identity. After the final debug UPLC tree is fixed, walk every
term occurrence with a lexical lambda-binder vector, innermost first. This includes
all generated lambdas, so synthetic binders contribute to de Bruijn indices.
Verify the emission associations against this independent final walk. For a
source binding materialized at position `i`, emit only `EnvironmentSlot(i,
loweredBinderId, layoutId)` for that suspension point. No expression evaluation,
arbitrary projection program or search through similarly named slots is allowed.
Other locations are explicit unavailable records in version 1; literal/alias
rematerialization can be proposed later with separate proofs.

Schema 1.1 requires `anchored-lambda-occurrences-v1`. Validation against the
exact decoded program independently rewalks structural occurrences, requires a
bijection between lambda occurrences and lowered-binder anchors, reconstructs
the actual innermost-first lexical ancestry, checks every suspension vector and
available slot against it, and validates every variable's one-based de-Bruijn
index. Lambda display names are never evidence. Parameter application additionally
checks each outer `Apply(_, Const(Data))`, parameter digest and order, then shifts
both suspension and lambda-anchor occurrences by the proven wrapper count.

For `let x = initializer in body`, the initializer executes in the outer
environment; `x` becomes available only at mapped terms in `body` after application
has installed its value. A lambda's parameter is unavailable while creating its
closure and available in its entered body. Captured variables resolve through
the lexical vector of the entered body; do not traverse closure environments to
invent caller locals. Loop continuations/accumulator arguments require explicit
version association at conditions, body, updates and post-loop joins. If lowering
cannot prove which version is current, report ambiguity. Recursive invocation
uses the same static location against its current environment, never a cached
value from a previous invocation. No Java activation stack is asserted.

Persist occurrence IDs using the existing preorder child ordering with a separate
versioned traversal contract, counting repeated object identities as separate
occurrences. At runtime, build `Term` identity to candidate occurrence mappings.
If one identity has incompatible metadata at different occurrences, locals are
ambiguous; identity sharing must not select whichever mapping was visited last.
Compatible repeated occurrences may share an entry only after comparing the
complete context and location records. A decoded tree can have distinct objects
where the original tree shared identities; tests must cover both forms.

#### Availability at a stop

Locals are a projection of the exact suspended state, not of the highlighted
line. Only a nonterminal compute phase with a validated current-term context may
read environment slots. Validate index bounds and expected environment shape;
a mismatch invalidates that point's locals and emits a diagnostic. Return phase,
startup failure, terminal success/failure and generated/unmapped context report a
snapshot-level unavailable reason. Do not pair an exception's failed term with a
possibly unrelated retained environment. Failure-point locals can be added later
only after an explicit VM-observation contract proves that association.

Visibility follows explicit source scopes and dominance of completed bindings,
not source-line intervals alone. Pattern variables appear only on the matching
branch, body locals disappear on exit, and a future declaration is not presented
as initialized. In-scope bindings without a materialized slot remain visible with
`notYetBound`, `notMaterialized`, `unsupportedLowering`, `unsupportedRepresentation`,
`ambiguous` or `representationMismatch`. Out-of-scope bindings are omitted.
Shadowed bindings retain separate IDs and declaration locations; display the
innermost binding normally and any accessible outer entry as explicitly shadowed.
Only source constructs already supported by JuLC are accepted.

#### Typed, bounded values

The decoder consumes a CEK value plus a validated physical layout descriptor.
The source type supplies labels; it is not sufficient to choose a decoder.
Version 1 recognizes exact scalar constants (integer, bytes, string, boolean,
unit) and raw Data. Decode integers losslessly (decimal strings at the JSON
boundary), bytes as bounded hexadecimal and Data with its explicit variant/tag.
Never interpret arbitrary bytes as a ledger hash solely because their length fits.

Next add layouts for Data-backed records/sums, erased NewType wrappers, optional,
lists/maps and pairs, using compiler-recorded tags, arity, field order and nested
representation. Distinguish Data containers from native UPLC constant lists/pairs,
`VConstr` from `PlutusData.ConstrData`, and pair-list maps from Data maps. Preserve
map order and duplicate entries in the display. A decoder must validate shape
before applying field names; malformed values show a mismatch and optional raw
view, never a partially invented typed record. Recursive types use bounded lazy
children; a summary cannot claim validation of unseen descendants.

Native Value, arrays and native lists require separately reviewed layouts/tests;
BLS points and Miller-loop results initially remain opaque. Closures, delays and
partially applied builtins are opaque summaries with no evaluation, forcing,
compression builtin, user constructor, reflection or VM builtin invocation.
Inspection is not ledger boundary validation and cannot change evaluation failure.

Implement iterative or depth-guarded traversal with explicit maximum depth, nodes,
children per page, bytes read and output characters. Check limits before visiting
or formatting children; truncate with a reason and continuation only when valid.
Do not print a complete constant and truncate afterward. Return immutable value
DTOs with kind, summary, type/layout IDs, availability, truncation and optional
child counts/handle. Never expose mutable byte arrays or CEK objects to clients.

#### Artifact, session and API binding

Retain the current encode/decode flow initially. Finalize the indexed metadata
after parameter applications, digest the exact canonical program, then require
the decoded canonical FLAT digest, version, term count and occurrence kinds to
match before rebinding. Bind the source manifest and metadata digest as well as
the program digest: identical scripts may originate from different Java sources.
Record both unparameterized and applied-program identities and parameter order
and values; evaluation argument wrappers receive generated metadata only. These
checks prevent accidental mismatches, not malicious replacement of a script and
its sidecar together. External untrusted metadata needs its own provenance policy.

Use an immutable `DebugArtifact` and per-evaluation session retaining the resolved
target, cost-model parameter digest, arguments and budget. A session-local stop
generation increments on every movement/replay/restart, even returning to the
same step. Child handles are scoped to session, artifact and stop generation;
eviction, close, movement or worker replacement invalidates them. No global
value cache and no client-supplied environment indices. Keep existing session
limits and add explicit metadata/value memory and paging limits.
Each locals-service instance also contributes an unguessable opaque nonce, so a
same-shaped handle at the same numeric generation cannot be accepted by another
session. Browser commits of locals/children responses require the captured
session, compilation revision, source/artifact binding and generation still to
match after the asynchronous request; replacement sessions clear child caches
even when their numeric generations coincide.

Add a negotiated `sourceDebugLocals` capability independently of `sourceDebug`,
including schema and supported layout versions. Preserve existing open/action
responses through additive optional DTO fields and compatibility constructors.
Expose a transport-neutral `locals(sessionId, stopGeneration)` result and a
bounded `children(sessionId, stopGeneration, handle, start, count)` operation;
return stale-reference errors rather than reading a newer stop. Snapshots include
the generation, locals capability and context availability. Projection and
stepping run under the same session synchronization boundary.

The Java locals panel is separate from **UPLC environment**. It shows scope,
name, declared/resolved type, value or explicit reason, declaration link and
bounded expandable children. No local value survives a stale compilation or
unavailable stop. Unsupported engines retain the source view and clearly state
that locals are unavailable. Source content and summaries render as text.

An eventual DAP/IntelliJ adapter can map source IDs to source references, scope
IDs to scope groups and bounded child handles to variable references. It reuses
the same artifact, session and value service, without browser DTO parsing or
frontend type reconstruction. Current APIs promise a suspended source context,
not Java frames, evaluate/watch, assignment or step-over/out. Those capabilities
need a later ADR. Keep VM-specific CEK access inside the tools inspection adapter;
do not introduce a general VM SPI before a second implementation needs it.

#### Implementation sequence and acceptance tests

1. Add core schema/validator and compiler debug-result entry point. Pin byte
   parity with source-map-only compilation, default compiler noninterference and
   deterministic IDs/serialization across fresh processes. Test unknown versions,
   missing fields, duplicate IDs, dangling references, cycles and input limits.
2. Implement declaration/scoping collection, transfer contracts and final binder
   verifier for parameters and immutable scalar locals. Test initializer-before-
   binding, nested scopes, same names in different methods, synthetic binders,
   helper calls, captures and raw/decoded parameter boundaries. Unsupported paths
   must produce reasons, never guessed slots. Include mutation tests that drop or
   corrupt transfer records and require rejection/unavailability.
3. Cover all active PIR passes and control-flow lowering: nested loops, multiple
   accumulators, assignments and joins, break, early returns, both conditional
   branches, pattern match success/failure, recursion and HOF captures. Test
   generated-only terms, shared term identities, cloned PIR nodes and eliminated
   bindings. Assert hand-specified Java values at exact compute points, including
   successive iterations; nonempty metadata alone is not a semantic assertion.
4. Add the tools projector and scalar/raw Data layouts, then reviewed composite
   layouts. Test every CEK phase, pre-start budget exhaustion, builtin failure,
   missing environment slots, type/layout mismatches, malicious sidecars, deep
   recursive/large values, duplicate map keys, huge integers and opaque closures.
   Repeated inspection/expansion at every transition must leave CEK state,
   result/failure, traces, failed term and CPU/memory identical to plain evaluation
   of the same artifact. Compare both retained and FLAT-round-tripped trees.
5. Add transport capability, generation-scoped paging and UI. Test stale handles,
   repeated seek to the same step, parameters, custom cost models, concurrent
   sessions, eviction/close and worker restart. Match JVM and real full-Wasm
   worker values including integer precision; run both image smokes, forbidden
   VM-only reachability and frontend/static build gates. Test Unicode ranges,
   duplicate filenames, library sources and late responses after edits.

Each step is independently reviewable and may ship with explicit coverage limits.
Do not declare milestone 2 complete while a supported promised construct silently
loses metadata. Start narrow with `:julc-core:test`, `:julc-compiler:test` and
`:julc-tools:test` as applicable, then run affected modules (`:julc-vm-java:test`,
`:julc-playground:test`, `:julc-wasm:test`), compiler/Blaster and repository build
gates per existing CI. External devnet mutation is unnecessary for observational
metadata; this milestone must not change on-chain bytes or execution semantics.

## Affected modules and stages

- Milestone 1: `julc-tools` compilation/session orchestration, models, source and
  breakpoint projection; `julc-playground` Java editor, examples, routes and UI;
  `julc-wasm` full-image API, generated declarations, adapter and worker tests;
  user documentation. Reuse current compiler source maps without changing lowering.
- Milestone 2: opt-in metadata in `julc-compiler` frontend/PIR/lowering,
  compiler-independent schema in `julc-core`, projection/session services in
  `julc-tools`, and additive playground/full-Wasm adapters. Separate review required.
- `julc-vm-java`: regression tests; no production VM changes. No changes to
  builtin semantics, cost models, ledger modules, stdlib or on-chain serialization.
- Preserve the VM-only image's forbidden-reachability gate: no compiler,
  JavaParser, decompiler or Cardano Client Lib pulled in by source-debug support.

## Alternatives and compatibility

- **Preserve maps through every optimizer now:** deferred; materially larger
  correctness surface than the explicitly approved source-debug build.
- **Treat metadata-enabled output as normal deployment output:** rejected;
  current source-map compilation can change bytes, hashes and budgets.
- **Guess locals from Java text, synthetic UPLC names or environment positions:**
  rejected; shadowing, captures, lowering and runtime scope make this misleading.
- **Instrument the CEK loop or insert tracing builtins:** rejected; existing
  read-only stepping supports observation without changing execution or costs.
- **Implement ADR-053's entire SPI/IDE architecture first:** deferred; not needed
  for this bounded playground feature.
- **Add debug fields to every public PIR record:** rejected for this milestone;
  optional identity side tables and explicit transformation transfers avoid
  changing structural equality or normal IR construction. This requires complete
  transfer auditing and fail-closed verification, not best-effort name recovery.
- **Re-evaluate an initializer or cache the last displayed local:** rejected;
  either can misrepresent the current scope, iteration, invocation or failure.
- **Disable earlier PIR passes to recover all locals:** rejected; metadata alone
  must not select a different source-debug executable or hide provenance gaps.

All existing public operations retain their behavior. New source-debug requests
are opt-in and full-image only. Unsupported/older engines show an actionable
capability message; they must not silently debug unrelated CBOR without mappings.
The full engine advertises a `sourceDebug` capability in both `engine.json` and
`features()`. Static builds fail if the copied engine lacks it, preventing a new
frontend from being packaged with an older generated Wasm directory.
No change to the established source-map file format is required for milestone 1.
The separate milestone-2 metadata schema is versioned and leaves existing source-
map readers supported.

## Milestones and verification gates

1. **Dual-source debugging:** source-debug compilation, immutable artifact/session
   association, Java input/example picker, generated UPLC view, dual breakpoints,
   warnings and existing raw UPLC environment. Test JVM and full Wasm paths.
2. **Accurate Java locals (implemented bounded subset):** opt-in binding/type/
   scope/representation metadata and read-only scalar/raw-Data value projection.
   Assignment/loop versions, pattern/recursive/early-return provenance and richer
   composite layouts remain explicit unavailable coverage, never guessed fallback.

Required evidence before broadening either milestone beyond its explicitly
qualified status, or treating it as release-ready:

- Compare default compiler artifacts and hashes with the parent baseline, run
  compiler/Blaster regressions, and assert source-debug options cannot leak into
  later normal compilations.
- Compare plain evaluation and stepping of the same debug artifact for success,
  explicit error, traces, budget exhaustion (including before startup), parameters
  and custom cost models. Include repeated observation and deterministic replay.
- Existing Java VM tests and conformance coverage stay green. Add observational
  tests that read source/locals at every transition and assert unchanged results,
  budgets, traces and failures. Passing tests is evidence, not a zero-risk proof.
- Java/UPLC breakpoint tests cover both conditional branches, loops/re-entry,
  multiple expressions on a line, unmapped/generated terms, unsupported lines,
  parameterized scripts, stale source/sidecars and independent concurrent sessions.
- Locals tests cover shadowing, captured bindings, loop-carried values, synthetic
  binders, type/representation mismatches and nested/large values. Verify bounded
  rendering and no machine mutation or evaluation during inspection.
- Real-worker full-Wasm/JVM parity, BigInt and session timeout/disposal/restart
  tests; both image smokes and the VM-only reachability gate remain mandatory.
- Browser checks on both engines: paste and example selection, compilation
  diagnostics, breakpoint binding, synchronized highlights, recompile after edits,
  engine changes and stale-response rejection. Frontend/static builds must pass.

## Risks and open questions

Source-map coverage is partial; unbound breakpoints must be visible. Large sources,
sidecars and values require existing request limits plus bounded metadata/display
sizes. Source content must be rendered as text, not HTML. Debug compilation is
still experimental and neither its output nor normal output is production-safe.

Milestone 1 starts with one pasted compilation unit and an optional library;
multi-file project debugging is not promised. Milestone 2 must nevertheless use
distinct source identities for bundled/supplied libraries. Public schema/API names
and concrete metadata/value limits need implementation review. The highest risk
is incomplete provenance transfer through AST clones, recursive expansion, loop
joins and representation-changing PIR passes. Review those transfers independently
against runtime value assertions before broadening the advertised coverage.

Deferred questions are failure-point locals, constant rematerialization, richer
native Value/array/BLS layouts, persistent externally supplied sidecars, and Java
activation/stepping semantics for IDE integration. None is a reason to guess a
value or modify CEK execution. `julc-tools` is currently unpublished and includes
compiler/transaction dependencies; a future published IDE distribution must
review packaging/reachability separately without making the core schema depend
on those facilities.

Human review remains required. This design reduces regression risk by isolating
debug-only metadata and preserving the VM implementation; it cannot guarantee
the absence of every regression.
