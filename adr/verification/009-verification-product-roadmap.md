# ADR-009: Verification Product Roadmap — Managed Execution and Java Properties

- **Status:** Accepted; C.4 through C.7 implemented
- **Date:** 2026-08-12
- **Related:**
  [ADR-001 — IOG Blaster Verification Strategy](001-iog-blaster-verification-strategy.md),
  [ADR-004 — Milestone C Reusable Verification Integration](004-milestone-c-reusable-verification-integration.md),
  [ADR-007 — Java-Annotation Security Properties](007-java-annotation-security-properties-and-one-command-verification.md),
  [ADR-008 — Productive Recursive ADTs](008-milestone-c3-productive-recursive-adts.md),
  [ADR-012 — Stateful Spending Profile](012-milestone-c6-stateful-spending-profile.md),
  [ADR-013 — Controlled Minting Profile](013-milestone-c7-controlled-minting-profile.md),
  [ADR-014 — Post-C.7 Hardening Roadmap](014-post-c7-verification-hardening-roadmap.md),
  [ADR-015 — Strict On-Chain Data Boundaries](015-strict-on-chain-data-boundaries.md),
  [ADR-016 — Typed Verification DSL and Profile Catalog](016-typed-verification-dsl-and-profile-catalog.md)

## Context

Milestones A through C.3 establish that JuLC can:

- import and evaluate the exact UPLC stored in a JuLC blueprint;
- bind verification evidence to the compiled-code and Cardano script hashes;
- generate strict Lean codecs for supported Java datum and redeemer types;
- represent booleans, optionals, lists, maps, and productive recursive ADTs;
- prove reviewed spending and minting properties with positive and negative
  controls; and
- reproduce the evidence on a clean GitHub Actions runner.

The remaining developer experience is not yet a JuLC product workflow. A
developer must run generated Lake scripts, understand the generated Lean
workspace, formulate a contract-specific theorem, and interpret Blaster
results. This is useful expert tooling, but it does not yet meet the intended
Java-developer experience:

```text
Java property annotations
        +
one JuLC command
        ↓
typed, artifact-bound verification result
```

Compiler certification is a separate problem. Proving a security property of
one exact UPLC artifact does not prove that the Java-to-UPLC compiler preserves
the semantics of every source program. The two tracks must not be represented
as the same assurance claim.

## Decision

JuLC will continue the reusable verification track through four ordered
milestones:

```text
C.4 managed verification runner
  → C.5 @RequiresSigner vertical slice
    → C.6 stateful spending profile
      → C.7 controlled minting profile
```

Missing PV11 builtin support will proceed as a parallel compatibility track.
Formal conformance of JuLC's ledger/value standard-library helpers will proceed
as a second parallel assurance track. Compiler certification remains Milestone
D research and does not block the first supported Java security properties.

Each milestone will receive a detailed sub-ADR before implementation. It will
be developed on a dedicated feature branch from the accepted integration
baseline, tested against a clean dependency cache where relevant, summarized
for manual review, and committed only after that review is approved. The
completed branch may then be merged into the verification integration branch.

## Common architecture and trust boundary

The target data flow is:

```text
Java validator source
  ├─ ordinary JuLC compilation ───────────────→ exact UPLC
  └─ security annotations
       ↓ compiler-owned type/path resolution
     typed security-property IR
       ↓ deterministic backend
     Lean property over Cardano ledger types
       + generated strict CIP-57 codecs
       + exact imported UPLC
       ↓ checked execution + Blaster/Z3
     structured result and certificate
```

Security annotations are verification metadata. Adding or removing them must
not change the generated UPLC bytes, compiled-code hash, or Cardano script
hash. A regression test will enforce that invariant for every annotation.

The compiler must parse annotation values and resolve field paths against its
existing type model. Annotation strings must never be interpolated directly
into Lean source. The property backend consumes typed IR nodes containing
resolved roots, fields, types, literals, relations, and ledger selections.

### Composable semantic property core

High-level annotations must lower into a small, versioned semantic property
core. JuLC must not implement every annotation as an unrelated custom Lean
source generator. The initial core should be capable of representing:

- typed datum, redeemer, validator-parameter, and ledger field selection;
- strict decoding and explicit absence/decoding failure;
- signatory membership;
- input, output, continuing-output, and own-input selection;
- value and asset-quantity projection;
- mint and burn projection for the current policy;
- equality, ordering, arithmetic, and bounded state relations;
- Boolean composition and implication; and
- explicit universal/existential quantification over bounded ledger
  collections where supported by Blaster.

For example, `@RequiresSigner("datum.owner")` lowers conceptually to typed
field selection, strict datum decoding, signatory-list membership, and an
implication from exact-UPLC acceptance. `@PreservesValue` and `@PaysAtLeast`
will reuse value projection, output selection, arithmetic, and comparison
nodes. The property-IR version and canonical serialization are certificate
inputs.

The core is an internal verification IR, not a second contract language. A
future typed Java DSL may be another frontend over the same core, but arbitrary
Java methods must not be executed or stringified as verification properties.

The backend generates the property in terms of the pinned Lean Cardano ledger
API and the generated JuLC schema types. It does **not** reimplement the Java
validator in Lean. The theorem must be connected to the acceptance result of
the exact imported UPLC through the checked-execution API; otherwise JuLC could
prove a model that is unrelated to the deployed validator.

JuLC will reuse the pinned Cardano ledger types and UPLC semantics instead of
creating a second transaction model in the annotation processor. JuLC still
owns the versioned mathematical definitions of higher-level concepts that the
upstream model does not provide, such as continuing-output selection,
`paidAtLeast`, or a stateful-spending profile. Those definitions belong in a
reviewed Lean semantic library shared by all property backends, not in
annotation-specific generated fragments.

The current Lean ledger API is a ledger-facing context model; it does not by
itself prove that every solver-generated `ScriptContext` is a transaction the
Cardano ledger would admit. Every property must therefore state its context
domain and ledger-validity assumptions. Counterexamples are model witnesses,
not automatically constructible Cardano transactions, unless a profile
explicitly models and checks the required ledger-validity rules.

Every result must keep these claims distinct:

- `SMT-VALID`: established through Blaster's SMT translation and Z3;
- `KERNEL-PROVED`: checked by Lean without the Blaster solver axiom;
- `REFUTED`: a model violates the stated property;
- `UNDETERMINED`: the solver could not decide the obligation; and
- `COULD-NOT-EVALUATE`: an artifact, tool, schema, builtin, resource bound, or
  verification prerequisite is unsupported or incomplete.

No milestone may turn workspace compilation, absence of a counterexample,
validator failure, or fuel/depth exhaustion into a successful proof result.

## Milestone C.4: managed verification runner

### Goal

Make generated verification workspaces executable through JuLC without asking
the developer to operate Lake, install Blaster repositories manually, or
interpret shell-script exit behavior.

### User interface

The initial command is:

```bash
julc verify run verification/<artifact-id>
```

`julc verify init` remains the explicit workspace-generation command. A later
milestone may compose build, initialization, and execution behind unqualified
`julc verify` after the property source is compiler-owned.

### Required behavior

C.4 will:

1. Load the workspace manifest and revalidate the exact artifact and hashes.
2. Verify the Lean, Z3, Blaster, PlutusCore, and Cardano ledger API pins.
3. Acquire pinned dependencies in a distinct online preparation phase.
4. Run artifact generation and proof checking with dependency downloads
   disabled.
5. Preserve the explicit semantics variant, CEK fuel, recursive depth, and
   builtin inventory in the result.
6. Parse proof outcomes into a versioned JSON result plus a concise terminal
   report.
7. Preserve counterexamples and proof logs as evidence artifacts.
8. Fail closed when tool provisioning, identity validation, compilation, or
   result parsing is incomplete.

Dependency downloads must be revision- and checksum-pinned. The design should
provide two backends:

- a locally provisioned backend for development; and
- an optional JuLC-owned Docker backend for a no-host-Lean/Z3 user experience.

The Docker image must be built from pinned inputs and record its content ID in
the result. Its proof phase runs with Docker networking disabled. Command and
result semantics must not depend on the selected backend.

### Evidence and exit criteria

C.4 is complete when:

- the existing Milestone A/B suite runs through `julc verify run`;
- a newly generated C.3 workspace can be prepared and run from a clean cache;
- the proof phase succeeds with network access disabled;
- all five result classifications have unit or integration coverage;
- hash, revision, unsupported-builtin, timeout, and exhaustion failures remain
  non-success outcomes; and
- the same structured result is produced locally and in GitHub Actions.

## Milestone C.5: `@RequiresSigner` vertical slice

### Goal

Deliver one complete Java-only security-property workflow for a spending
validator before generalizing the property language.

### Java surface

The initial form is intentionally narrow:

```java
@RequiresSigner("datum.owner")
@SpendingValidator
class AuthorizedStateValidator {
    // validator implementation
}
```

The detailed [C.5 ADR](011-milestone-c5-requires-signer.md) decides the final
module boundary and repeatability rules. The semantic meaning is fixed here:
for every context in the declared
spending-domain model, successful execution of the exact compiled validator
implies that the resolved owner key hash occurs among the transaction
signatories.

Conceptually, the generated obligation is:

```text
domainAssumptions(ctx)
  ∧ exactUplcAccepts(ctx)
  ⇒ datumOwner(ctx) ∈ ctx.txInfo.signatories
```

The actual Lean code will use the pinned Cardano ledger API's
`ScriptContext`, `TxInfo`, spending `ScriptInfo`, and signatory representation,
together with JuLC-generated strict datum decoding. Names in this conceptual
formula are not a commitment to a particular upstream Lean field spelling.

Datum presence and decoding are part of the guarantee, not convenient domain
assumptions. If the spending context has no attached datum, the datum is
malformed, or the generated strict decoder cannot recover the annotated owner,
the generated `requiresSigner` predicate is false. Consequently, a validator
that accepts such a context does not pass the authorization theorem. Domain
assumptions may describe ledger-valid context coherence, but may not assume
that the required signer exists or signed.

### Compilation and generation

C.5 will:

1. Parse `@RequiresSigner` in the optional verification module beside an
   ordinary JuLC compilation; the core compiler does not lower the annotation.
2. Resolve `datum.owner` through compiler-owned validator parameter and
   `PirType` metadata.
3. Require a supported signer-compatible byte-string/key-hash type.
4. Report missing roots, missing fields, ambiguous fields, and incompatible
   types at the annotation's Java source location.
5. Serialize a versioned typed property IR beside, or as an explicitly
   namespaced extension to, the exact blueprint artifact.
6. Generate a deterministic Lean property over the Cardano ledger API.
7. Connect that property to checked execution of the exact imported UPLC.
8. Run a non-vacuity obligation so an always-failing validator cannot satisfy
   authorization vacuously without being reported.
9. Include a reviewed vulnerable control in the C.5 evidence fixture.
10. Emit a certificate containing the property IR, domain assumptions,
    generated-source hash, script hashes, dependency pins, and result class.

The signatory predicate must test membership across the complete
`txInfo.signatories` collection. It must not reuse the existing expert
`firstSignerAuthorized` template, which intentionally checks only the first
entry and is not equivalent to required-signer membership.

The first release may support only a datum field. Validator parameters,
redeemer fields, nested optional owners, and multiple acceptable authorities
require an explicit later extension rather than implicit coercion.

### User interface

For an annotated validator, the target command is:

```bash
julc verify --validator AuthorizedStateValidator
```

It composes build or artifact selection, property-IR validation, deterministic
workspace generation, managed execution, and certificate output. Users within
the supported profile do not edit Lean or invoke Lake.

### Evidence and exit criteria

C.5 is complete when:

- a correct annotated validator produces `SMT-VALID` for the named property;
- a vulnerable validator produces a source-linked refutation and retains the
  complete raw Blaster counterexample;
- an always-failing validator is identified by the non-vacuity check;
- invalid property paths fail at their Java source location;
- annotation presence has zero effect on emitted UPLC bytes;
- generated Lean passes an admission audit (`sorry`, project axioms,
  `unsafe`, and `partial` are rejected where applicable); and
- the certificate independently identifies the artifact and precise property
  that were checked, the ledger-model version, and the context-validity rules
  that were or were not included.

C.5 may claim only that the named authorization property was established under
the listed assumptions and trust model. It may not claim that the whole
contract is safe.

## Milestone C.6: stateful spending profile v1

The detailed design and acceptance evidence are specified by
[ADR-012](012-milestone-c6-stateful-spending-profile.md).

### Goal

Extend the typed property IR with a reviewed, composable stateful-spending
profile.

Candidate Java properties include:

```java
@RequiresSigner("datum.owner")
@Monotonic(
        current = "datum.state",
        next = "redeemer.nextState",
        relation = Relation.GREATER_THAN)
@PreservesValue(output = OutputSelection.SINGLE_CONTINUING_OUTPUT)
```

The profile will additionally cover:

- continuing-output selection and uniqueness;
- output datum commitment;
- consumed-input/output-reference linkage;
- asset-specific or whole-value preservation; and
- double-satisfaction defenses.

Every output selection and ledger-domain assumption must be explicit and
rendered in the certificate. An annotation must not silently choose the first
output when several continuing outputs are possible.

C.6 is complete when all mandatory properties for a versioned profile have
positive evidence, vulnerable controls, non-vacuity checks, readable
counterexamples, and independent review. Only then may JuLC report:

```text
FORMALLY VERIFIED AGAINST JULC STATEFUL SPENDING PROFILE V1
```

The report must still list the exact artifact, assumptions, individual result
classes, resource bounds, and trusted computing base.

## Milestone C.7: controlled minting profile v1

### Goal

Provide the corresponding Java-only workflow for minting policies.

The initial profile will cover:

- required authority signatures;
- linkage to the policy's own currency symbol;
- permitted token names;
- mint, burn, and quantity constraints;
- forbidden unrelated assets under the same policy; and
- minting-specific non-vacuity and vulnerable controls.

The property IR must distinguish minting context from spending context. It
must reject spending-only roots such as `datum` rather than inventing an empty
or opaque value.

C.7 is complete when a production-shaped controlled-mint policy and its
vulnerable variants produce reviewed, reproducible results and a versioned
minting-profile certificate.

## Parallel track: complete PV11 builtin compatibility

The prioritized sequence after C.7 is maintained by
[ADR-014](014-post-c7-verification-hardening-roadmap.md). In particular,
release hardening and the compatibility-safe strict-boundary work in
[ADR-015](015-strict-on-chain-data-boundaries.md) precede further profile
breadth.

The pinned Blaster stack currently does not support JuLC-emittable builtin tags
89–91 and 94–100. Until support is implemented, artifacts containing them must
remain `COULD-NOT-EVALUATE` at preflight.

Builtin work should be contributed upstream where practical. Each added
builtin requires:

- FLAT decoding and malformed-input tests;
- evaluator semantics for the selected Plutus semantics variant;
- SMT translation or an explicit unsupported symbolic case;
- conformance tests against the canonical Plutus evaluator; and
- positive, negative, and resource-exhaustion evidence.

Completing `@RequiresSigner` does not depend on these builtins. A property
milestone must not broaden its coverage declaration merely because an example
validator does not exercise the gap.

## Parallel track: verification semantics and JuLC stdlib conformance

The Java property frontend and the JuLC on-chain standard library expose
related concepts, but they are different artifacts:

- the property semantic library defines the mathematical predicate used in a
  verification obligation; and
- the JuLC stdlib helper is Java source compiled into UPLC and executed by a
  validator.

For high-value helpers, JuLC should define one reviewed Lean specification and
check the behavior of the compiled helper against it. Initial candidates are:

- `ContextsLib.signedBy`;
- `ContextsLib.findOwnInput`;
- `ContextsLib.getContinuingOutputs`;
- `OutputLib.lovelacePaidTo` and `OutputLib.paidAtLeast`;
- `ValuesLib.assetOf`, `ValuesLib.geq`, and `ValuesLib.add`; and
- datum lookup and mint/burn helpers used by the C.6 and C.7 profiles.

The first `@RequiresSigner` slice need not wait for the entire stdlib catalogue.
However, the signatory-membership semantic definition should be shared with
the conformance specification for `ContextsLib.signedBy`. C.6 and C.7 require
reviewed value/output/mint semantic definitions over the pinned Lean model.
Conformance of similarly named compiled JuLC helpers remains parallel evidence,
unless a profile or certificate explicitly claims that a helper implements the
same definition; that equivalence claim requires completed conformance proof.

Stdlib conformance strengthens reusable library assurance and can catch helper
regressions independently of one contract. It does not replace verification of
the final contract UPLC, and it does not automatically establish compiler-wide
semantic preservation. A detailed sub-ADR will define the standalone-helper
artifact format, equivalence relation, cost claims, and proof classification
before this track is implemented.

## Later track: Milestone D compiler certification

After the verification product workflow is usable, JuLC may pursue the
compiler-certification stages in ADR-001:

1. expose stable pre- and post-optimization UPLC artifacts;
2. translation-validate at least one optimizer pass;
3. define and validate a nontrivial PIR-to-UPLC lowering subset; and
4. define a smaller JuLC Core source semantics rather than all of Java.

Milestone D reduces trust in the compiler. Milestones C.4–C.7 instead avoid
trusting compiler semantic preservation by checking a named property against
the exact UPLC that will be deployed. Both are valuable, but their certificates
and claims remain separate.

## Documentation requirements

Each milestone must update `verification/README.md` with:

- functionality available in the current release;
- prerequisites and managed-provisioning behavior;
- one successful command sequence;
- one refuted example and how to read its counterexample;
- supported annotation roots, field types, purposes, and builtins;
- result and exit-code meanings; and
- limitations that remain `COULD-NOT-EVALUATE`.

Generated certificates and terminal messages must use the same terminology as
the guide.

## Consequences

### Positive

- C.4 separates dependable execution from property-language design.
- C.5 validates the full annotation-to-artifact proof chain with one narrow,
  reviewable security property.
- A small semantic core prevents annotation growth from multiplying trusted
  one-off Lean generators.
- Stdlib conformance gives the stateful-spending and minting profiles reviewed
  definitions for their recurring transaction/value operations.
- C.6 and C.7 expand only after the typed IR, result model, and certificate are
  proven in practice.
- Java developers can eventually use common formal-verification profiles
  without learning Lean.
- Expert users retain the generated Lean workspace as an extension and audit
  surface.

### Negative

- JuLC becomes responsible for the precise semantics and assumptions of every
  property annotation and profile.
- JuLC must maintain versioned semantic definitions and conformance evidence
  when the pinned Cardano model or on-chain stdlib changes.
- Managed toolchain provisioning introduces distribution, checksum, cache,
  platform, and lifecycle responsibilities.
- Blaster/Z3 validity remains dependent on the SMT translation and solver
  unless proof reconstruction is added.
- Unsupported contracts and novel properties will still require expert Lean
  work or return `COULD-NOT-EVALUATE`.
