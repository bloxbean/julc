# ADR-028: Milestone E.5 — Exact-Artifact State-Machine Experiment

- **Status:** Rejected after the E.5.1 calibration gate
- **Date:** 2026-08-23
- **Parent:**
  [ADR-016 — Typed Verification DSL and Foundational Profile Catalog](016-typed-verification-dsl-and-profile-catalog.md)
- **Prerequisites:**
  [ADR-012 — Stateful Spending Profile](012-milestone-c6-stateful-spending-profile.md),
  [ADR-019 — Compositional Property Promotion Core](019-milestone-e4b-compositional-property-promotion-core.md),
  [ADR-022 — Generic Contract Types and Collections](022-milestones-e4e-e4f-generic-contract-types-and-collections.md),
  [ADR-023 — Typed Non-Value Transaction Context](023-milestone-e4g-typed-non-value-transaction-context.md),
  [ADR-025 — Certificate Payloads and Value Algebra](025-milestones-e4i-e4j-certificate-payloads-and-value-algebra.md), and
  [ADR-027 — Reviewed Raw-Data Adapters](027-milestone-e4l-reviewed-raw-data-adapters.md)
- **Pinned Blaster revision:** `5dab3c43f042b8735b6d067223baaa8d32ed28a1`
- **Integration branch:** `feat/typed-verification-dsl-e4`
- **Expected milestone branch:**
  `feat/typed-verification-dsl-e5-state-machine-experiment`

## Context and problem

JuLC can currently prove or refute properties of one exact validator execution.
For example, it can state that if a particular compiled spending validator
accepts one context, then the successor datum is monotonic, a signer is
authorized, or value satisfies a reviewed relation.

Many contracts need a temporal claim instead:

- a counter never decreases over any accepted sequence;
- an owner cannot change after initialization;
- a state can move only through a permitted lifecycle;
- a terminal state can never become active again;
- value locked by the state machine remains above a state-dependent minimum;
- an authorization invariant established initially is preserved by every
  transition; or
- a bad state is unreachable within a reviewed number of transitions.

The pinned Blaster revision provides a `StateMachine Input State` interface,
`#bmc`, and `#kind`. Those facilities are useful but do not automatically
connect an abstract Lean transition function to JuLC's exact compiled UPLC.
A handwritten `next : Input -> State -> State` could be proved safe while the
actual validator accepts different transitions. That would be a proof of the
model, not of the contract.

There is a second honesty problem. These statements are not equivalent:

1. no counterexample was found through depth `k`;
2. a counterexample was found through depth `k`;
3. an invariant was established by solver-backed k-induction;
4. a corresponding theorem was checked by the Lean kernel; and
5. the sequence represents transactions that can all occur on Cardano.

E.5 must keep those meanings separate in generated code, runner protocol,
certificate fields, terminal output, and documentation.

## Pinned Blaster behavior

At the pinned revision, Blaster defines:

```lean
class StateMachine (Input State : Type) where
  init        : Input -> State
  next        : Input -> State -> State
  assumptions : Input -> State -> Prop
  invariants  : Input -> State -> Prop
```

`#bmc (max-depth: k)` searches incrementally for an invariant violation from
the initial state through depth `k`. Absence of a counterexample is bounded
evidence only.

`#kind (max-depth: k)` checks base and step obligations incrementally. It may:

- establish induction at some depth;
- find a reachable base-case counterexample;
- find a counterexample to induction that is not necessarily reachable from
  the declared initial condition;
- encounter a contradictory assumption context; or
- remain undetermined through the configured bound.

The command is solver-backed and does not currently emit a proof term that
Lean's kernel independently checks. E.5 therefore must not label a successful
`#kind` result `KERNEL-PROVED`.

## Decision summary

E.5 will run a deliberately narrow state-machine experiment for spending
validators with typed datum and redeemer schemas.

It will introduce a separate, versioned state-machine specification IR rather
than pretending a trace is an ordinary single-context property. The state at
each step is the strictly decoded contract datum. The input at each step
contains the typed redeemer and exact `ScriptContext` needed to execute the
compiled validator.

The generated transition relation is owned by JuLC:

1. the current step's spending datum strictly decodes to the current state;
2. the exact hash-bound UPLC succeeds for that datum, redeemer, and context
   within the recorded CEK fuel;
3. the selected valid-context domain holds when requested;
4. exactly one continuing output is selected by the reviewed full-address
   rule;
5. its inline datum strictly decodes as the successor state; and
6. the next step's current datum is equal to that successor state.

User Java code supplies only reviewed predicates for:

- the initial state condition;
- per-step environmental assumptions;
- the state invariant; and optionally
- a transition invariant over current state, redeemer, successor state, and
  the current context.

User code does **not** supply the transition function and cannot inject Lean,
raw UPLC, arbitrary decoders, or an alternative execution predicate.

## Calibration outcome and final decision

E.5 stopped at the mandatory E.5.1 feasibility gate. JuLC does not ship the
experimental state-machine Java API, CLI commands, result classes, or runner
protocol described below.

The calibration first confirmed the pinned Blaster command behavior and then
generated an exact-artifact-linked state machine. The final minimum case used:

- a 396-byte spending validator whose Java body returns `true` (with strict
  generated argument boundaries still present);
- a typed record datum and redeemer;
- no `validSpendingContext` domain premise;
- BMC depth 1 and induction depth 1;
- 3,000 CEK fuel per transition;
- Lean 4.24.0, Z3 4.15.2, and pinned CardanoLedgerApiBlaster revision
  `5dab3c43f042b8735b6d067223baaa8d32ed28a1`; and
- a five-minute per-step timeout.

The generated Lean workspace and all pinned dependencies compiled, but the
first target-depth reachability query did not finish in five minutes. Its
authenticated result was `COULD-NOT-EVALUATE: verification-timeout`; BMC and
k-induction never ran. Earlier trials with the real counter transition at
depths 2 and 1 also failed to produce a result before manual termination.

This establishes that the combined direct exact-artifact transition encoding
is impractical even without a complex validator or ledger-domain premise. The
query still includes strict decoding, `ScriptContext`, continuing-output
selection, datum linkage, and exact CEK execution, so the calibration does not
attribute the timeout to CEK alone. Depth 1 is not a useful temporal bound, and
exceeding five minutes for that minimum case fails E.5.1's explicit "feasible
at a useful depth" requirement.

The prototype was therefore removed before commit. Existing property schemas
1 through 10, verification result meanings, CLI commands, compiler/runtime
modules, and emitted UPLC remain unchanged. A future attempt requires a new
ADR and a materially different execution strategy, such as a reviewed
transition summary whose equivalence to exact UPLC is established separately;
it must not silently revive the rejected direct symbolic-CEK composition.

The retained calibration summary and minimal hash-bound audit workspace are in
[`verification/e5`](../../verification/e5/README.md).

## Goals

- Determine whether Blaster BMC and k-induction can operate on an
  exact-artifact-linked JuLC state transition at useful depths.
- Provide a typed Java state-machine vocabulary generated from the
  compiler-owned datum and redeemer graph.
- Derive every transition from exact validator acceptance and a reviewed
  continuing-output decoder.
- Preserve strict datum/redeemer/output decoding.
- Make initialization, transition assumptions, state invariants, and
  transition invariants distinct in canonical IR.
- Produce readable counterexample traces with depth-indexed current state,
  redeemer, successor state, and relevant context summaries where available.
- Authenticate BMC and k-induction runner outcomes with exit-code/marker pairs.
- Add explicit reachability/non-vacuity controls for the requested trace depth.
- Bind artifact, state-machine IR, generated Lean, strategy, bounds, domains,
  and dependency revisions into the certificate.
- Keep bounded, inductive, and kernel-checked evidence classes distinct.
- Preserve all existing DSL canonical bytes and verification behavior.
- Keep the experiment outside compiler/runtime modules and leave validator
  UPLC byte-identical.

## Non-goals

- Stabilize a public state-machine API in E.5.
- Support minting, rewarding, certifying, voting, or proposing state machines.
- Accept a handwritten Java or Lean `nextState` function as proof authority.
- Prove that an arbitrary sequence of symbolic contexts is a realizable
  Cardano transaction chain.
- Model block production, slot progression, rollbacks, mempool ordering,
  concurrency, or off-chain transaction construction.
- Infer an initial state from constructor names or a Java default value.
- Select an arbitrary output or accept zero/multiple continuing outputs.
- Decode output datum hashes through an unmodeled external datum store.
- Turn a BMC no-counterexample result into an unbounded safety claim.
- Turn a k-induction counterexample-to-induction into a reachable contract
  exploit.
- Call solver-backed k-induction a Lean-kernel proof.
- Add hidden invariants merely to make induction succeed.
- Change compiler lowering, boundary semantics, ledger encoding, UPLC, script
  hash, size, or execution cost.

## Exact transition model

### State and input

The first experiment uses:

```text
State := strictly decoded spending datum

Input := {
  datumData    : Data,
  redeemerData : Data,
  context      : ScriptContext
}
```

Generated helpers derive typed `current`, `event`, and `successor` views only
after their strict decoders succeed. The raw fields are never exposed through
the Java DSL.

The state type must initially be a non-recursive record or sealed sum whose
complete Lean codec is already supported. Containers may appear in fields
only after a calibration shows acceptable solver behavior. Productive
recursive state is deferred even though JuLC can generate its Lean codec.

### Initialization

`init input` returns the decoded current datum using a total generated helper.
The assumptions require that decode to succeed and require the user-declared
initial predicate at depth zero.

Because Blaster's `assumptions` function does not receive the depth directly,
the generated state may include a private phase/depth marker used solely to
distinguish initialization from later transitions. That marker is generated
and cannot be referenced as a contract field.

An omitted initial predicate means `true`, but the certificate must then say
`initialStateScope: ANY_STRICTLY_DECODED_DATUM`. The CLI must not silently
invent a constructor or zero value.

### Successor extraction

The successor is taken from exactly one continuing output selected by the
reviewed full-address rule already used by the typed ledger DSL. E.5 requires:

- `findOwnInput` succeeds;
- exactly one output has the complete same address as the consumed script
  input;
- the output carries an inline datum;
- that datum strictly decodes to the state type; and
- the generated `next` value is that decoded state.

Any total-function fallback in generated Lean is unreachable under these
assumptions and must be documented next to the definition. Kernel controls
must show the assumptions imply the selected decode succeeds.

Datum hashes, absent datums, malformed inline datums, zero continuing outputs,
and multiple continuing outputs make the transition inadmissible. They are
not converted into a default state.

### Exact-artifact binding

Every admitted transition assumption includes exact UPLC success for the
current datum, redeemer, and context. The same compiled bytes, script hash,
builtin inventory, protocol version, semantics variant, and CEK fuel binding
used by single-step verification remain in force.

Fuel exhaustion is not validator rejection. A state-machine result covers
only steps whose exact execution completes within the per-step recorded fuel
bound. The certificate records both `fuelPerStep` and `maxDepth`; it must not
present their product as a ledger execution budget.

### Trace linkage boundary

The initial experiment links steps by strict datum equality:

```text
successorDatum(step n) = currentDatum(step n + 1)
```

It does not initially prove that the next context consumes the exact output
reference created by the previous context, that transaction values/fees form
one realizable chain, or that slots are monotonic. Therefore the certificate
records:

```text
traceLinkageModeled: DATUM_CONTINUITY_ONLY
ledgerRealizableTraceEstablished: false
```

Proving a safety invariant over this superset can be stronger than proving it
over realizable traces. A counterexample remains conservatively qualified and
must not be called an executable chain without separate VM/ledger evidence.

## Java DSL sketch

The following is illustrative and not a frozen API:

```java
public StateMachineSpecification machine(GeneratedContract contract) {
    var machine = contract.stateMachine();

    return machine.specification()
            .initial(state -> state.sequence().eq(integer(0)))
            .assume(step -> step.context().txInfo().fee().ge(integer(0)))
            .invariant(state -> state.sequence().ge(integer(0)))
            .transitionInvariant(step ->
                    step.successor().sequence()
                        .eq(step.current().sequence().add(integer(1)))
                    .and(step.successor().owner()
                        .eq(step.current().owner())))
            .build();
}
```

The generated API supplies typed current, successor, and redeemer wrappers
from the same compiler-owned type projection used by schema 10. All expressions
come from the existing closed typed DSL.

`build()` produces a separate `StateMachineSpecification`, not a
`DslPropertySet`. Ordinary property schemas 1 through 10 stay frozen.

## Canonical state-machine IR

The proposed IR has its own schema version 1 and includes:

- exact purpose (`SPENDING` only in E.5);
- contract-schema hash;
- datum and redeemer type identities;
- initial predicate;
- environmental assumption predicate;
- state invariant;
- optional transition invariant;
- continuing-output selection mode;
- successor-datum mode;
- valid-context domain;
- trace-linkage mode;
- requested BMC depth;
- requested k-induction depth; and
- capability/rule dependencies for every expression.

The IR must not contain:

- Java class names as semantic type authority;
- arbitrary field paths;
- Lean identifiers or source;
- executable method references;
- a user-provided transition function;
- an output-selection callback; or
- a user-provided exact-execution predicate.

Canonicalization reuses schema-10 expression normalization for predicates but
does not merge their roles. Identical Boolean expressions used as `initial`
and `invariant` remain separately labeled and hashed.

Parent validation re-derives types, fields, capabilities, generated names,
and output-selection rules against the exact `ContractSchema`. Runner
preflight re-derives all metadata that does not require executing trusted Java
and binds the canonical state-machine IR hash.

## Evidence classifications

E.5 introduces explicit result classes rather than reusing `SMT-VALID` for
every strategy.

### BMC

- `BOUNDED-COUNTEREXAMPLE` — a violation was found at a recorded depth.
- `BOUNDED-NO-COUNTEREXAMPLE` — no violation was found through the recorded
  maximum depth and depth reachability was established.
- `COULD-NOT-EVALUATE` — timeout, unknown result, unsupported translation,
  contradictory/unreachable target depth, protocol mismatch, or other
  fail-closed outcome.

`BOUNDED-NO-COUNTEREXAMPLE` is not `SMT-VALID` and is never described as an
unbounded proof.

### K-induction

- `SMT-K-INDUCTIVE` — Blaster established both base and step cases at a
  recorded induction depth.
- `BASE-COUNTEREXAMPLE` — a reachable base obligation failed.
- `INDUCTION-STEP-COUNTEREXAMPLE` — the induction step failed; this is not
  necessarily reachable from initialization.
- `COULD-NOT-EVALUATE` — induction was not established through the bound,
  translation failed, assumptions were contradictory, or execution timed out.

### Lean kernel

`KERNEL-PROVED` remains reserved for a separately generated theorem that Lean
actually elaborates without `sorry`, `admit`, `axiom`, `unsafe`, or an
unreviewed oracle. A successful `#kind` command alone never produces this
classification.

## Reachability and vacuity controls

A no-counterexample result is useful only if a path reaches the requested
depth. E.5 will generate a separate reachability control whose state includes
a private step counter. Its invariant remains true before the target and false
at the target, causing BMC to produce a witness only when a complete admitted
path of that length exists.

The runner requires an authenticated target-depth witness before publishing
`BOUNDED-NO-COUNTEREXAMPLE`. If the witness cannot be found, the result is
`COULD-NOT-EVALUATE/target-depth-unreachable`, not a safe result.

For k-induction, the runner separately records:

- initialization satisfiability;
- at least one admitted transition;
- target-depth reachability for the configured base bound; and
- whether the induction assumptions become contradictory.

These controls do not prove that every symbolic trace is ledger-realizable.

## Domain and theorem honesty

Each step may use one reviewed domain:

- `NONE`; or
- `VALID_SPENDING_V3_PINNED`.

The domain is applied identically at every step. Users cannot add an arbitrary
Lean assumption; Java `assume(...)` accepts only closed DSL expressions and is
visible in the certificate as a user assumption.

The result separates:

- ledger/model domain premises;
- user-declared environmental assumptions;
- exact validator success;
- generated transition-link conditions; and
- the invariant being checked.

An induction-strengthening predicate, if later needed, must be explicitly
declared as an auxiliary invariant and reported. It cannot be silently moved
from the invariant into assumptions.

## Certificate and runner protocol

The certificate records at least:

- `verificationMode: STATE_MACHINE_EXPERIMENTAL`;
- state-machine IR schema and SHA-256;
- exact artifact and generated Lean hashes;
- datum/redeemer type identities and contract-schema hash;
- strategy (`BMC` or `K_INDUCTION`);
- maximum depth and established/failing depth;
- per-step CEK fuel and recursive decoding depth;
- initial-state scope;
- valid-context domain;
- user-assumption hash;
- invariant and transition-invariant hashes;
- output selection and successor decode modes;
- trace linkage mode;
- reachability/non-vacuity outcome;
- solver result class and raw log path;
- `ledgerRealizableTraceEstablished`;
- `leanKernelProofEstablished`;
- pinned Blaster/CardanoLedgerApi/Z3/Lean revisions; and
- backend identity.

Generated scripts emit versioned markers containing strategy, result class,
depth, and property ID. The runner accepts only reviewed exit-code/marker pairs.
Unknown or mixed output fails closed. Solver stdout/stderr remains in bounded
hash-associated logs and is not interpreted as trusted Lean source.

## Explicit invariants

1. **Exact artifact at every step.** An admitted transition includes exact
   execution of the hash-bound UPLC, not a Java reimplementation.
2. **Generated transition relation.** User code cannot replace successor
   extraction or exact execution with a handwritten transition function.
3. **Strict state decoding.** Current datum, redeemer, and continuing-output
   datum decode strictly; malformed data never becomes a default state.
4. **Unique successor.** Zero or multiple continuing outputs are not admitted.
5. **Visible assumptions.** Initial, domain, environmental, linkage, and
   execution premises remain distinct and certificate-bound.
6. **Bounded honesty.** BMC no-counterexample results state their maximum depth.
7. **Induction honesty.** Solver k-induction is not labeled kernel proof.
8. **Counterexample honesty.** A step-case counterexample is not labeled a
   reachable exploit, and a datum-linked trace is not labeled ledger-realizable.
9. **Reachability before bounded success.** A target-depth witness is required
   for `BOUNDED-NO-COUNTEREXAMPLE`.
10. **Fuel honesty.** Per-step CEK exhaustion remains outside the claim.
11. **One type authority.** State/redeemer projections come from the
    compiler-owned `ContractSchema`.
12. **Closed IR.** No arbitrary Lean, decoder, UPLC, output selector, type name,
    or raw-data escape hatch is accepted.
13. **Determinism.** Canonical IR, generated Lean, scripts, and certificates are
    deterministic for identical inputs and bounds.
14. **Old behavior is frozen.** Property schemas 1 through 10 and existing
    certificate meanings do not change.
15. **No on-chain effect.** The experiment changes neither validator
    compilation nor emitted UPLC.

## Milestones

### E.5.1 — Pinned Blaster calibration

- Add handwritten Lean calibrations for BMC success, bounded
  counterexample, k-inductive success, base counterexample, induction-step
  counterexample, contradiction, target-depth unreachability, and timeout.
- Record exact exit codes and stable output markers at the pinned revision.
- Test the target-depth reachability-control construction.
- Attempt one exact-UPLC-linked two-step state machine before defining Java API.
- Measure solver time and memory at depths 1, 2, 3, 5, and 10 where practical.

**Exit gate:** the runner can distinguish every outcome without parsing
unstructured model prose, and exact execution inside the state machine is
feasible at a useful depth. If not, stop E.5 and record the experiment as
unsupported rather than designing an unusable DSL.

### E.5.2 — Closed Java and canonical IR prototype

- Add the version-1 state-machine IR and strict codec.
- Generate typed current-state, successor-state, redeemer, and context views.
- Add separate initial, assumption, invariant, and transition-invariant roles.
- Parent-validate all expressions and reject user-defined transition logic.
- Add canonicalization, alpha-normalization, collision, AST-budget, unknown
  JSON, impersonation, and schema-mismatch tests.
- Keep the API experimental and trusted-source execution boundary explicit.

**Exit gate:** Java can express the reviewed slice, while forged state,
successor, transition, field, and envelope nodes fail before Lean generation.

### E.5.3 — Exact transition and generated Lean

- Generate the state-machine input/state types and total helpers.
- Bind exact UPLC success at every transition.
- Implement strict current/redeemer/successor decoding and unique continuing
  output selection.
- Add datum-continuity linkage between steps.
- Generate BMC, reachability, and k-induction scripts.
- Add kernel controls for output uniqueness, strict decode, fallback
  unreachability, and manual/generated transition equivalence.
- Extend admission scans and native reachability metadata.

**Exit gate:** the generated state-machine relation is demonstrably derived
from the exact artifact and reviewed ledger helpers, not from user transition
code.

### E.5.4 — Runner and certificate classification

- Add state-machine manifest and plan schemas.
- Authenticate all BMC/k-induction/reachability exit-marker pairs.
- Classify base versus induction-step counterexamples separately.
- Require target-depth reachability for bounded no-counterexample results.
- Record trace-linkage and ledger-realizability limitations.
- Add timeout, killed process, output cap, tamper, reordered plan, stale hash,
  unknown marker, mixed marker, and partial-result tests.
- Print depth progress without exposing unbounded subprocess output.

**Exit gate:** no solver or runner outcome can be promoted to a stronger claim
than its authenticated evidence class.

### E.5.5 — Exact-artifact evidence and decision

- Add a small counter/lifecycle spending fixture with typed datum and redeemer.
- Retain bounded-safe, reachable-counterexample, k-inductive or honestly
  undetermined, unreachable-depth, and vacuous controls.
- Run exact VM controls for one-step transitions and malformed/ambiguous output.
- Run local, Docker, and GraalVM-native workflows and compare semantic hashes.
- Measure depth scaling, CEK preprocessing cost, solver time, memory, and trace
  readability.
- Update the getting-started guide and capability inventory.
- Decide whether E.5 should proceed toward a product API, remain expert-only,
  or be rejected under E.6.

**Exit gate:** evidence demonstrates a useful exact-artifact temporal claim at
a documented cost, or the experiment ends with a precise fail-closed reason.

## Required test matrix

### Transition binding

- valid current datum, redeemer, exact execution, unique continuing output,
  and strict successor;
- wrong current datum tag/arity;
- malformed redeemer;
- exact validator rejection;
- CEK fuel exhaustion;
- no continuing output;
- two matching continuing outputs;
- output at same payment credential but different staking credential;
- absent, hashed, malformed, and wrong-type successor datum; and
- successor/current mismatch at the next step.

### State and invariant

- record and sealed-sum state;
- wrong state/redeemer type identity;
- guarded variant transition;
- initial predicate true and false;
- state invariant preserved and violated;
- transition invariant preserved and violated;
- explicit environmental assumption; and
- attempted hidden envelope/exact-execution node rejection.

### BMC and reachability

- depth-zero violation;
- violation first appearing at depths 1, 2, and configured maximum;
- no counterexample through a reachable bound;
- target depth unreachable;
- contradictory assumptions;
- unknown/timeout/unsupported translation; and
- model trace retained without being called ledger-realizable.

### K-induction

- 1-inductive property;
- property requiring `k > 1`;
- reachable base counterexample;
- induction-step-only counterexample;
- induction not established through maximum depth;
- contradictory induction assumptions;
- stable result classification and depth; and
- no `KERNEL-PROVED` claim without a separate Lean theorem.

### Compatibility and integrity

- property schemas 1 through 10 retain canonical bytes;
- exact validator UPLC is byte-identical with and without the specification;
- state-machine IR, plan, scripts, and generated Lean tampering fail before
  any process runs;
- changed bounds alter the appropriate hashes/certificate fields;
- local, Docker, and native semantic hashes agree; and
- compiler, ledger, stdlib, blueprint, annotation, Gradle, playground, and
  ordinary CLI workflows do not regress.

## Evidence fixture

The first fixture should be intentionally small:

```java
record State(BigInteger sequence, byte[] owner, Status status) {}
record Action(BigInteger nextSequence, Status nextStatus) {}

sealed interface Status permits Active, Closed {}
record Active() implements Status {}
record Closed() implements Status {}
```

The authorized validator should require:

- strict boundaries;
- owner signature;
- exactly one continuing output;
- owner preservation;
- sequence increment by one;
- only `Active -> Active`, `Active -> Closed`, or `Closed -> Closed`; and
- an explicit value floor that composes existing value semantics.

Controls include validators that permit sequence decrease, owner replacement,
terminal-state reopening, ambiguous continuing outputs, and no successful
execution. The fixture must remain small enough that exact execution at every
step is diagnostically useful.

## Affected modules

Expected changes are limited to:

- `julc-verification` — experimental state-machine IR, generated Java wrappers,
  admission, canonicalization, and dependency inventory;
- `julc-cli` — Lean workspace, scripts, runner protocol, certificate metadata,
  native-image metadata, and diagnostics;
- `verification/e5` — calibrations, fixtures, kernel/VM/solver controls, and
  retained evidence;
- `verification/GETTING_STARTED.md`; and
- verification ADR/capability documentation.

The following must not change for E.5:

- `julc-core`;
- `julc-compiler`;
- `julc-ledger-api`;
- `julc-stdlib`;
- `julc-blueprint`;
- validator lowering or boundary semantics; and
- emitted UPLC, CBOR, script hashes, sizes, and execution costs.

## Compatibility

The state-machine IR and CLI surface are new and explicitly experimental.
Existing annotations, property schemas 1 through 10, generated workspaces, and
certificate meanings remain unchanged.

State-machine result classifications are additive and must not reuse existing
`SMT-VALID` wording where bounded or induction-specific wording is required.
No source-compatibility promise is made for the experiment until E.6.

## Risks and mitigations

### The proof checks a handwritten model instead of the validator

Mitigation: generate successor extraction and require exact UPLC success at
every step; reject user-provided transition functions.

### A bounded result is presented as an invariant proof

Mitigation: use `BOUNDED-NO-COUNTEREXAMPLE`, always record maximum depth, and
keep it distinct from k-induction and kernel proof.

### K-induction succeeds only because assumptions are contradictory

Mitigation: separate initialization, one-step, and target-depth reachability
controls and classify contradictions as could-not-evaluate.

### An induction-step counterexample is presented as an exploit

Mitigation: classify it separately and record `reachableFromInitial: false`
unless independent evidence establishes reachability.

### Symbolic traces are not ledger-realizable

Mitigation: record datum-only linkage and conservatively qualify every
counterexample. Do not claim full transaction-chain realization.

### Exact UPLC at each depth is too expensive

Mitigation: calibrate before public API design, keep bounds explicit, reuse
hash-bound preprocessing where sound, and stop the experiment if useful depths
are impractical.

### Nondeterministic output selection is hidden by a total fallback

Mitigation: require exactly one reviewed continuing output and kernel-check
that selection/decode assumptions make the fallback unreachable.

### Users hide invariants in assumptions

Mitigation: label and hash assumptions separately, show them prominently in
the certificate, and never call assumption-dependent results unconditional.

## Rejected alternatives

### Let users implement `nextState` in Java

Rejected because proving that function says nothing about transitions accepted
by the compiled validator unless a separate equivalence theorem is supplied.

### Generate a state machine only from datum/redeemer types

Rejected because types do not define transition semantics or output selection.

### Unroll ordinary property checks in shell code

Rejected because it loses Blaster's state-machine semantics, canonical trace
IR, and reliable base/step classification.

### Call BMC success `SMT-VALID`

Rejected because no-counterexample-through-`k` is a bounded search result.

### Call `#kind` success `KERNEL-PROVED`

Rejected because the pinned command is solver-backed and does not provide a
kernel proof term.

### Add full transaction-reference linkage immediately

Rejected for the first experiment because it substantially enlarges the state
and solver problem. Datum-only linkage is useful if stated honestly and proves
over a trace superset.

### Permit arbitrary output selectors

Rejected because selector semantics would become user-controlled proof code.
Additional reviewed selectors require separate versions and controls.

### Make initialization implicit

Rejected because constructor names and zero values do not define deployed
state. An omitted predicate is explicitly recorded as any strictly decoded
datum.

## Open questions

These questions may narrow or terminate the experiment; they may not weaken
the invariants:

1. Can exact UPLC symbolic execution be reused across depths without changing
   semantics, or must it be independently translated at every step?
2. What depth remains practical for the first fixture under local and Docker
   memory limits?
3. Does Blaster expose sufficiently stable exit codes/markers for base versus
   induction-step failures, or must JuLC add pinned wrapper commands?
4. Can target-depth reachability be established reliably with a generated
   private counter state?
5. Should environmental assumptions be prohibited in the first public
   experiment, or permitted with prominent certificate disclosure?
6. Is datum-continuity-only linkage strong enough for useful safety theorems,
   or should E.5 stop until output-reference linkage is modeled?
7. Can a separate Lean theorem compose a solver-established transition lemma
   into a kernel-checked induction proof, given the lack of proof
   reconstruction?
8. Should containers in state be admitted initially or deferred after scalar
   record/sum calibration?

## Acceptance criteria

The implementation criteria below describe the product path that was not
entered. E.5 is considered concluded, rather than implemented, because the
earlier E.5.1 stop condition was exercised and documented honestly.

E.5 is complete only when:

- the pinned BMC/k-induction protocol is calibrated for every result class;
- a closed typed state-machine IR exists without a user transition function;
- every transition is linked to exact hash-bound UPLC success;
- current, redeemer, and successor data decode strictly;
- continuing-output selection is unique and reviewed;
- initialization, assumptions, invariants, and transition invariants remain
  separate;
- target-depth reachability is required before bounded-safe classification;
- BMC, k-induction, and kernel results use distinct terminology;
- trace linkage and ledger-realizability limitations are certificate-visible;
- positive, counterexample, unreachable, undetermined, and vacuous evidence is
  retained;
- local, Docker, and native semantic hashes agree;
- performance and maximum useful depth are documented;
- property schemas 1 through 10 remain frozen;
- affected-module and repository-wide builds pass; and
- compiler/runtime modules and emitted UPLC remain unchanged.

## Permitted claims after completion

Depending on the authenticated outcome, JuLC may claim one of:

- no counterexample was found for the exact artifact through depth `k` under
  the recorded assumptions and bounds;
- a bounded counterexample was found at depth `k` in the recorded symbolic
  trace domain;
- the recorded invariant was established by solver-backed k-induction at
  depth `k`; or
- the experiment could not evaluate the claim.

JuLC may not claim from E.5 alone that:

- a bounded no-counterexample result is an unbounded proof;
- solver-backed k-induction is a Lean-kernel proof;
- an induction-step counterexample is reachable;
- a datum-linked symbolic trace is a ledger-realizable transaction chain;
- every accepted execution completes within the recorded CEK fuel;
- every contract state machine is supported; or
- the contract is formally verified and safe in all respects.

## Review and merge sequence

1. Complete and review E.4l, then merge it only into
   `feat/typed-verification-dsl-e4`.
2. Create `feat/typed-verification-dsl-e5-state-machine-experiment` from the
   updated integration branch.
3. Complete E.5.1 calibration before committing to the Java API design. The
   calibration failed its feasibility gate, so the prototype API was removed.
4. Retain the rejected-experiment decision and independent review rather than
   implementing E.5.2 through E.5.5.
5. Merge only the reviewed calibration decision into the E.4 integration
   branch. Merge to
   `main` remains deferred until the intended E.* integration series and E.6
   public-API decision are complete.
