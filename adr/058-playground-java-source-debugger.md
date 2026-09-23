# ADR-058: Experimental Java-source debugging in the playground

**Status:** Milestone 1 is implemented on the stacked branch and has passed the
full/VM GraalVM Web Image, reachability and cross-platform playground build gates;
independent review remains pending. Milestone 2 (typed Java locals) remains proposed.
The developer approved a separate, visibly labeled source-debug compilation whose
script bytes, hash and budget may differ from normal compilation (2026-09-23).
**Parent:** ADR-057, PR #169 (`feat/julc-wasm-distribution`).
**Builds on:** ADR-055's UPLC debugger and the existing compiler source maps.

## Context and current behavior

The playground can compile Java in Contract mode and transfer compiled CBOR to
UPLC / Debugger. It cannot currently show the original Java source, set Java line
breakpoints, or inspect reliably named and typed Java locals in that debugger.

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
Keep the existing compile/evaluate/debug requests compatible. Final additive
record and method names will be reviewed with the first implementation milestone;
the current public API does not yet expose this operation.

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

## Affected modules and stages

- Milestone 1: `julc-tools` compilation/session orchestration, models, source and
  breakpoint projection; `julc-playground` Java editor, examples, routes and UI;
  `julc-wasm` full-image API, generated declarations, adapter and worker tests;
  user documentation. Reuse current compiler source maps without changing lowering.
- Milestone 2: opt-in metadata in `julc-compiler` frontend/PIR/lowering and, if a
  reusable schema is needed, `julc-core` source metadata. Separate review required.
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

All existing public operations retain their behavior. New source-debug requests
are opt-in and full-image only. Unsupported/older engines show an actionable
capability message; they must not silently debug unrelated CBOR without mappings.
The full engine advertises a `sourceDebug` capability in both `engine.json` and
`features()`. Static builds fail if the copied engine lacks it, preventing a new
frontend from being packaged with an older generated Wasm directory.
No change to the established source-map file format is required for milestone 1.
Any later metadata schema must be versioned and leave existing readers supported.

## Milestones and verification gates

1. **Dual-source debugging:** source-debug compilation, immutable artifact/session
   association, Java input/example picker, generated UPLC view, dual breakpoints,
   warnings and existing raw UPLC environment. Test JVM and full Wasm paths.
2. **Accurate Java locals:** opt-in binding/type/scope/representation metadata and
   read-only value projection. Add supported representations incrementally; no
   guessed fallback. This milestone is not implied by milestone 1's completion.

Required evidence before claiming either milestone implemented:

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

Review exact additive API names, cache ownership/limits and supported source-file
scope before milestone 1 coding. Start with one pasted compilation unit and the
existing example catalogue; multi-file projects are not promised. Review the
binding schema and each supported value representation before milestone 2 coding.

Human review remains required. This design reduces regression risk by isolating
debug-only metadata and preserving the VM implementation; it cannot guarantee
the absence of every regression.
