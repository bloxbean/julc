# ADR-016: Typed Verification DSL and Foundational Profile Catalog

- **Status:** E.1–E.4l implemented; E.5 rejected at calibration; E.6 stable API implemented
- **Date:** 2026-08-13
- **Related:**
  [ADR-001 — IOG Blaster Verification Strategy](001-iog-blaster-verification-strategy.md),
  [ADR-007 — Java Annotation Security Properties](007-java-annotation-security-properties-and-one-command-verification.md),
  [ADR-009 — Verification Product Roadmap](009-verification-product-roadmap.md),
  [ADR-011 — `@RequiresSigner` Vertical Slice](011-milestone-c5-requires-signer.md),
  [ADR-012 — Stateful Spending Profile](012-milestone-c6-stateful-spending-profile.md),
  [ADR-013 — Controlled Minting Profile](013-milestone-c7-controlled-minting-profile.md),
  [ADR-014 — Post-C.7 Roadmap](014-post-c7-verification-hardening-roadmap.md),
  [ADR-015 — Strict On-Chain Data Boundaries](015-strict-on-chain-data-boundaries.md),
  [ADR-018 — Typed Minting Verification DSL](018-milestone-e4a-typed-minting-dsl.md),
  [ADR-019 — Compositional Property Promotion Core](019-milestone-e4b-compositional-property-promotion-core.md),
  [ADR-020 — Typed Rewarding Verification DSL](020-milestone-e4c-typed-rewarding-dsl.md),
  [ADR-021 — Typed Certifying Verification DSL](021-milestone-e4d-typed-certifying-dsl.md),
  [ADR-022 — Generic Contract Types and Collections](022-milestones-e4e-e4f-generic-contract-types-and-collections.md),
  [ADR-023 — Typed Non-Value Transaction Context](023-milestone-e4g-typed-non-value-transaction-context.md),
  [ADR-024 — Compositional Authorization Algebra](024-milestone-e4h-authorization-algebra.md),
  [ADR-025 — Certificate Payloads and Value Algebra](025-milestones-e4i-e4j-certificate-payloads-and-value-algebra.md),
  [ADR-026 — Typed Governance Transaction Data](026-milestone-e4k-typed-governance-transaction-data.md), and
  [ADR-027 — Reviewed Raw-Data Adapters](027-milestone-e4l-reviewed-raw-data-adapters.md)

## Context

JuLC currently exposes four verification annotations:

- `@RequiresSigner`;
- `@Monotonic`;
- `@PreservesValue`; and
- `@ControlledMint`.

They implement three deliberately narrow, versioned products:

1. signer authorization for spending;
2. a complete stateful-spending profile; and
3. fixed-authority controlled minting.

They do not represent every property expressible over the pinned
`CardanoLedgerApiBlaster` model. The V3 model includes transaction inputs,
reference inputs, outputs, fees, mint, certificates, withdrawals, validity
ranges, signatories, redeemers, datum witnesses, transaction IDs, votes,
proposals, and treasury fields. It represents spending, minting, rewarding,
certifying, voting, and proposing script purposes and exposes purpose-specific
ledger-validity predicates.

Blaster is more general than either the ledger API or JuLC's annotations. It
can reason about Lean propositions containing ordinary and mutually recursive
data/functions, polymorphism, higher-order functions, and state machines. It
also provides bounded model checking and k-induction. That does not mean every
Lean expression is supported or automatically decidable: indexed inductive
types and inductive predicates are currently unsupported, automatic induction
and case analysis are limited, and Z3 may return an undetermined result.
Blaster also does not currently reconstruct solver proofs.

The upstream capabilities are documented in:

- [Lean-blaster](https://github.com/input-output-hk/Lean-blaster/tree/083bae7971414d894b56b5bbf4108c63e17bc42a);
  and
- [CardanoLedgerApiBlaster](https://github.com/input-output-hk/CardanoLedgerApiBlaster/tree/5dab3c43f042b8735b6d067223baaa8d32ed28a1).

Adding one unrelated annotation and one custom Lean generator for every useful
property would not scale. Conversely, an unrestricted annotation such as
`@Ensures("...")` would lose Java type checking, permit source injection or
ambiguous coercions, produce poor diagnostics, and make certificate semantics
hard to review.

This ADR explores a third surface: a typed Java verification DSL that lowers
to the same canonical property IR as high-level annotations. The DSL should
make most of the modeled Cardano ledger API available to Java developers while
retaining exact-UPLC linkage, fail-closed generation, explicit assumptions,
and reviewable certificates.

## Terminology and three different meanings of support

Claims about "supporting all Cardano ledger API" must separate three layers:

1. **Model coverage** — the DSL can construct typed expressions for every
   supported type, field, constructor, and script purpose in a pinned
   CardanoLedgerApi model.
2. **Property expressiveness** — the property IR and Lean semantic library can
   represent the desired predicate over those expressions.
3. **Automated discharge** — the pinned Blaster/Z3 stack can establish or
   refute the generated obligation within recorded bounds.

Full model coverage is a reasonable engineering goal. Universal automated
discharge is not. A property can be well typed and faithfully generated yet
end in `UNDETERMINED` or `COULD-NOT-EVALUATE`. JuLC must never describe layer
one as proof at layer three.

Similarly, coverage of all fields in the pinned Lean model is not proof that
the model completely or correctly implements the Cardano ledger. Dependency
commits, semantics variant, validity predicates, known raw-`Data` fields, and
model assumptions remain certificate inputs.

## Decision

JuLC will explore a two-level verification frontend:

1. reviewed, versioned annotations and profiles for common security goals;
   and
2. an optional typed Java DSL for contract-specific composition.

Both frontends lower to one small, sealed, versioned semantic property IR.
They share Lean definitions, exact-artifact execution, non-vacuity checks,
negative controls, result classification, tamper binding, and certificate
generation.

The DSL is compositional rather than a collection of verbose fixed profiles.
Once an operation is admitted into the closed typed IR, a developer may
combine it with other purpose-compatible admitted operations without JuLC
requiring a resolver for that complete formula shape. Reviewed annotations
and profile helpers may construct common formulas, but their ASTs receive no
privileged proof-generation path. Exact UPLC execution and allow-listed ledger
domains remain a separate mechanically controlled theorem envelope rather
than freely movable assumptions.

The DSL belongs in `julc-verification` or a new optional module depending on
it. Core compiler, PIR generation, optimization, and ordinary UPLC lowering
must not depend on it. Adding, removing, or changing a verification
specification has zero effect on emitted validator UPLC.

This ADR approves architectural exploration and a prototype. It does not yet
stabilize a public Java API or authorize a claim that the entire ledger API is
verified.

## Proposed user model

JuLC generates a contract-specific verification metamodel from the
compiler-owned `ContractSchema`. A specification uses typed symbolic values,
not Java runtime ledger values:

```java
public final class AuctionProperties
        implements SpendingVerification<AuctionModel> {

    @Override
    public PropertySet properties(AuctionModel contract) {
        var execution = contract.execution();
        var context = contract.context();
        var datum = contract.datum();

        var sellerPaid = context.outputs()
                .exists(output ->
                        output.address().credential()
                                .eq(datum.seller())
                        .and(output.value().lovelace()
                                .ge(datum.price())));

        return PropertySet.of(
                property("seller-is-paid",
                        execution.succeeds().implies(sellerPaid)));
    }
}
```

The exact names are provisional. Important semantic properties of this shape
are:

- `datum.seller()` is generated from the authoritative contract schema and
  has a ledger-compatible symbolic type;
- `outputs()` is a typed symbolic ledger collection;
- `exists`, `eq`, `ge`, `and`, and `implies` construct IR nodes;
- a new supported combination of those nodes does not require a new
  template-specific Java resolver;
- `execution.succeeds()` refers to the exact imported UPLC under recorded CEK
  fuel, not to a reimplementation of the validator;
- an `Expr<Bool>` cannot be used as a Java `boolean`, preventing accidental
  host-language branching on a symbolic value; and
- generated Java is never copied into generated Lean as text.

Common cases should continue to use annotations:

```java
@RequiresSigner("datum.owner")
@SpendingValidator
class Treasury { ... }
```

The annotation resolver and a DSL property with the same meaning must lower to
the same semantic IR nodes and Lean definition. This creates one semantics
rather than two approximately similar implementations.

## Frontend construction options

Java does not expose a lambda's expression tree through its ordinary runtime
type system. A typed DSL therefore needs an explicit construction mechanism.
The prototype must compare these approaches.

### Option A: execute a symbolic AST builder in a worker JVM

User methods operate only on `Expr<T>` values and return sealed property-IR
builders. JuLC compiles the property module, runs it in a separate worker JVM,
accepts only a bounded serialized AST, and independently validates the result.

Advantages:

- natural, type-safe Java syntax;
- ordinary IDE completion and refactoring;
- no dependence on javac's internal source-tree representation; and
- loops and helper methods may generate repeated typed clauses.

Risks:

- the specification class is still arbitrary user Java and may perform I/O,
  start processes, loop forever, or inspect environment state;
- modern Java does not provide a reliable in-process security sandbox; and
- executing property code from an untrusted pull request is an explicit code
  execution boundary.

Mitigations include an explicit command, a distinct worker process, timeout,
memory and AST-size limits, a sanitized environment, an optional networkless
Docker backend, atomic output, and full post-execution IR validation. Local JVM
execution must be documented as executing project code. It must never happen
merely while inspecting an untrusted blueprint.

### Option B: parse a restricted Java source subset

An annotation processor or source frontend reads method syntax and translates
only approved DSL calls and lambdas.

Advantages:

- no user property code execution; and
- source-level diagnostics can point directly to expressions.

Risks:

- a substantial second Java language frontend;
- dependence on javac tree APIs and source availability;
- difficult semantics for imports, overloads, helpers, loops, generics, and
  separate compilation; and
- risk of accepting Java syntax whose execution meaning differs from its
  translated meaning.

This is not the preferred initial implementation.

### Option C: separate declarative property files

A schema-aware JSON/YAML/custom-language document can lower directly to IR.
This is safer to parse but sacrifices the requested Java type system and IDE
experience. It may be useful as an interchange or generated inspection format,
not as the primary Java DSL.

### Preferred prototype

Prototype Option A behind an explicit `julc verify dsl` preparation step. The
worker returns canonical property IR through a strict protocol. JuLC then
re-resolves every contract path and ledger operation against the authoritative
contract and ledger capability manifests. Execution of the Java builder is not
evidence; only the validated IR, generated Lean, exact UPLC execution, and
classified solver result appear in the verification certificate.

Before making Option A generally available in hosted CI, evaluate a
networkless Docker worker and document the trust boundary. If acceptable
isolation cannot be achieved, retain the DSL as a local trusted-source feature
or revisit a restricted source frontend.

## Generation and build phases

A likely pipeline is:

```text
validator Java
    -> ordinary JuLC compilation
    -> compiler-owned ContractSchema + exact UPLC
    -> generated verification metamodel
    -> compile/run typed property builder in bounded worker
    -> untrusted candidate property AST
    -> authoritative type/capability revalidation
    -> canonical versioned property IR
    -> deterministic Lean
    -> admission scan + exact-UPLC/manifest checks
    -> Blaster/Z3 + non-vacuity/negative controls
    -> classified, hash-bound certificate
```

The two-phase build avoids making core compilation depend on the DSL. Generated
metamodel sources belong under a verification build directory and are safe to
regenerate. User-owned specification files are never overwritten by
`julc verify init` or later regeneration.

For source types and ledger types, generated names are conveniences rather
than authority. The canonical IR stores stable structural identifiers and
resolved types; the validator title, blueprint, exact compiled code, script
hash, contract-schema hash, ledger capability version, and generated
metamodel hash are all bound into the manifest.

## Typed semantic core

The property IR should grow through reviewed sealed node families rather than
through raw Lean fragments.

### Roots and decoding

- exact artifact acceptance and rejection;
- datum, redeemer, validator parameters, and script context;
- strict decode with explicit absent/malformed cases;
- constructor/variant testing and typed field selection; and
- optional, list, map, bytes, integer, Boolean, and supported recursive data.

### Ledger selections

- own input and consumed inputs;
- reference inputs;
- outputs and explicitly selected continuing outputs;
- fee and minted value;
- signatories, redeemers, and datum witnesses;
- withdrawals and certificates;
- transaction ID and script purpose;
- votes and proposal procedures; and
- typed treasury/validity fields only when their pinned model representation
  has a reviewed adapter. Raw `Data` remains visibly raw until then.

### Predicates and operations

- equality and inequality with an explicit structural/extensional mode;
- integer ordering and bounded arithmetic;
- bytes and hash equality;
- signatory and collection membership;
- value, policy, token-name, and asset-quantity projections;
- Boolean composition and implication;
- `all`, `exists`, `none`, uniqueness, count, filter, and bounded lookup over
  supported ledger collections;
- input/output/value balance predicates;
- purpose-specific constructors and matching; and
- explicit calls to versioned, reviewed semantic-library predicates.

Unbounded general recursion is not introduced as an arbitrary DSL operation.
Supported folds, bounded traversal, recursive schema decoding, state-machine
relations, and induction each require explicit semantics and solver evidence.

### Domain assumptions

Assumptions are first-class IR nodes, visually distinct from guarantees. For
example:

```java
domain(CardanoDomain.v3()
        .requireLedgerValidSpendingContext())
```

This may lower to the pinned `validSpendingContext` predicate. The certificate
must list the precise predicate and model revision. JuLC must not provide a
quiet `assume(...)` escape hatch capable of assuming the desired conclusion.
Initially, only reviewed, allow-listed domain predicates should be admitted.

## CardanoLedgerApi coverage strategy

The DSL should pursue complete *typed surface coverage* of the pinned V3 model
incrementally. Coverage is recorded in a machine-readable capability manifest:

| Surface | Initial target | Important qualification |
|---|---|---|
| Spending | Full typed context access | Exact UPLC and optional explicit ledger-valid premise |
| Minting | Full typed context access | Own-policy selection remains explicit |
| Rewarding | Typed root, withdrawals and credential predicates | Requires a reviewed profile and fixtures |
| Certifying | Typed certificate and index/root access | Certificate variants need exhaustive mapping |
| Voting | Typed voter, vote and governance-action access | V3-only and model-revision sensitive |
| Proposing | Typed proposal, deposit and return-credential access | V3-only and model-revision sensitive |
| Inputs/outputs | Inputs, reference inputs, datum and values | Selection and uniqueness must be explicit |
| Value/mint | Raw association structure plus reviewed projections | Structural and extensional equality differ |
| Validity interval | Raw `Data` initially | Typed time properties require a reviewed adapter |
| Treasury fields | Raw `Data` initially | No typed claim until representation is modeled |
| Ledger validity | Purpose-specific predicates | Always a recorded theorem premise, never hidden |

The capability manifest must identify each upstream structure field,
constructor, helper predicate, and encoding adapter as one of:

- `TYPED`;
- `RAW_DATA_ONLY`;
- `UNSUPPORTED_IR`;
- `UNSUPPORTED_SOLVER`; or
- `NOT_MODELED_UPSTREAM`.

A dependency upgrade fails compatibility tests if an upstream field or
constructor is added, removed, or changes type without a reviewed manifest
update. This prevents a statement such as "V3 supported" from silently
excluding new governance data.

Model coverage does not require exposing every upstream helper name directly.
JuLC should expose stable semantic operations and map them to reviewed pinned
definitions. Upstream helpers such as `findOwnInput`, `findRedeemer`,
`ownCurrencySymbol`, `valueSpent`, `valueProduced`, `txSignedBy`, withdrawal,
certificate, proposal, and voter predicates are initial conformance targets.

## Foundational annotations and profiles

Annotations remain the recommended interface for recurring security goals.
The following is a product backlog, not an approved public API.

### Foundation

- explicit ledger/model domain selection;
- any-signer, all-signers, and threshold authorization;
- explicit own-input, continuing-output, and reference-input selection;
- explicit structural versus extensional equality; and
- inline-datum, datum-hash, and strict-decoding requirements.

### Spending and payment

- pay-at-least and pay-exactly to an address or credential;
- preserve one asset or an allow-list of assets;
- prevent value leakage;
- immutable fields and permitted state transitions;
- increase/decrease-by, bounded state, and terminal states; and
- global multi-input/output linkage against double satisfaction.

### Minting

- one-shot minting tied to a consumed UTxO;
- NFT quantity/name restrictions;
- mint-only, burn-only, and quantity ranges;
- no other tokens under the current policy; and
- parameter-, redeemer-, or threshold-selected authority.

### Time and reference data

- valid-after, valid-before, and interval containment after typed range support;
- required reference inputs;
- oracle reference and freshness profiles; and
- datum/redeemer action-variant constraints.

### Other purposes

- authorized withdrawal;
- authorized certificate operation;
- authorized vote and permitted governance action;
- authorized proposal and proposal constraints; and
- reviewed multi-purpose dispatch.

### Multi-step verification

- bounded reachability and counterexample search;
- transition invariants; and
- k-inductive state-machine invariants.

Multi-step results require a separate profile and certificate vocabulary. A
bounded-model-checking result must not be presented as an unbounded invariant,
and a one-step exact-validator property must not be presented as induction.

## Annotation and DSL composition rules

Profiles are more than bags of independent annotations. A versioned profile
defines compatible roots, output selection, assumptions, conjunctions, and
result claims. JuLC therefore applies these rules:

- an annotation lowers to the same IR used by the DSL;
- every well-typed, purpose-compatible composition of supported DSL nodes is
  promoted generically rather than matched against a complete template AST;
- reviewed helper methods are ordinary AST builders and do not authorize
  otherwise unsupported formulas;
- exact artifact execution and reviewed ledger-domain predicates are placed
  by a fixed theorem envelope and cannot be moved into, duplicated in, or
  hidden inside a user guarantee;
- duplicate equivalent properties are canonicalized or reported clearly;
- contradictory domains or guarantees fail before Lean generation;
- partial mandatory profiles fail rather than prove a weaker subset;
- combining profiles requires an explicitly supported composition contract;
- every property has a stable ID and source reference; and
- a certificate reports each property separately before computing any profile
  aggregate.

The DSL is the escape hatch for unusual predicates, not an escape hatch from
validation. It cannot introduce raw Lean, new axioms, `sorry`, `admit`,
`unsafe`, `partial`, unpinned imports, shell commands, or unchecked theorem
premises.

## Result and trust semantics

The existing classifications remain:

- `SMT-VALID` — the pinned Blaster/Z3 path established the named generated
  obligation under its listed model, assumptions, and bounds;
- `KERNEL-PROVED` — a separate Lean-kernel-checked result without the Blaster
  solver admission path, where available;
- `REFUTED` — a retained model violates the obligation;
- `UNDETERMINED` — the solver could neither prove nor refute it; and
- `COULD-NOT-EVALUATE` — provisioning, unsupported semantics, tamper,
  execution, non-vacuity, or another required check did not complete.

Typed DSL compilation is not a sixth success class. Nor does successful model
coverage mean "formally verified and safe." A certificate states only named
properties of exact UPLC under explicit assumptions and recorded CEK/solver
bounds.

## Exploration milestones

### E.1: Capability inventory

**Implementation status:** complete on the E.1 feature branch. The bundled
schema-1 inventory classifies the pinned revision, all six V3 purposes, the
transaction/context fields used or planned by the DSL, governance surfaces,
reviewed helpers, ledger predicates, known builtin gaps, and solver gaps. A
revision and normalized Lean-signature compatibility gate fails closed on an
unreviewed dependency change.

1. Generate or hand-audit a machine-readable inventory of the pinned V3
   structures, constructors, fields, helper predicates, and validity rules.
2. Classify every item using the coverage states in this ADR.
3. Add a compatibility test that detects unclassified upstream changes.
4. Document raw-`Data`, builtin, SMT, and model-fidelity gaps.

No public DSL is required for E.1.

### E.2: Minimal typed AST prototype

**Implementation status:** complete as an experimental API on the E.2 feature
branch. It includes a sealed generic AST, deterministic contract metamodel
generation, strict canonical JSON, a bounded separate-JVM worker, authoritative
post-worker validation against `ContractSchema`, and a deterministic Lean
renderer. The equivalence fixture compiles and executes generated DSL Java and
checks that its signer property is canonical-IR-identical and Lean-identical
to `@RequiresSigner` lowering. The worker remains trusted-source only; process
separation is not claimed as an OS security sandbox.

1. Define sealed generic expression types and a minimal versioned IR for
   Boolean composition, integer comparison, signatories, outputs, values, and
   bounded list quantification.
2. Generate one contract-specific metamodel from `ContractSchema`.
3. Implement the bounded worker protocol and authoritative IR revalidation.
4. Reproduce `@RequiresSigner` byte-for-byte at the canonical IR and generated
   Lean levels.
5. Verify that DSL source changes never change contract UPLC.

### E.3: Payment vertical slice

**Implementation status:** complete as an experimental vertical slice and
refreshed against the `strict-data-v1` compiler boundary on 2026-08-16.
`julc verify dsl-init` generates a contract-specific Java metamodel and
`julc verify dsl` executes a trusted-source property builder in the bounded
worker, revalidates its canonical IR, binds it to exact UPLC, and runs the
established certificate workflow. The reviewed v1 property requires a strict
datum, a public-key seller output, and at least the datum price in lovelace. A
weaker or differently shaped AST is rejected before Lean generation.

The positive, unpaid, and multi-satisfaction fixtures now use normal typed
datum parameters without raw `Data` tag/arity boilerplate. The compiler guard
rejects malformed datum encodings before user code runs, while the generated
Lean property independently performs its strict decode. The evidence driver
rejects reintroduction of handwritten boundary checks and requires both the
manifest and certificate to record `boundarySemantics: strict-data-v1`.

The pinned V3 validity predicate contains model clauses that this Blaster
revision cannot translate when placed directly in the SMT theorem. E.3 does
not drop those clauses and then claim equivalence. The SMT obligation uses a
reviewed, solver-compatible *superset* of ledger-valid contexts; a separate
Lean-kernel theorem establishes that every pinned V3 ledger-valid spending
context is in that superset, and a kernel-checked corollary bridges the SMT
result back to `validSpendingContext`. The result certificate records the
ledger domain and fuel bound. Positive, unpaid, vacuous, and deliberately
multi-satisfaction-vulnerable controls pass with the expected classifications.
The local payment expression should remain experimental rather than graduate
to `@PaysAtLeast` until global multi-input/output linkage is designed.

1. Express a seller-paid-at-least property in the DSL.
2. Check exact UPLC with an explicit ledger-valid spending domain.
3. Include a correct validator, an unpaid-seller validator, an
   always-failing validator, and a multi-satisfaction control.
4. Retain readable counterexamples and bind all generated artifacts.
5. Decide whether the reviewed expression should graduate into a
   `@PaysAtLeast` profile.

This slice tests whether the DSL genuinely reduces one-off generator work.

### E.4: Purpose and surface expansion

Add minting, rewarding, certifying, voting, and proposing metamodel roots one
at a time. Each purpose requires:

- codec and context-construction evidence;
- positive, vulnerable, malformed, and vacuous controls;
- explicit valid-context behavior;
- certificate fixtures; and
- compatibility tests against the pinned model.

**E.4a implementation:**
[ADR-018](018-milestone-e4a-typed-minting-dsl.md) adds the reviewed minting
slice: exact purpose selection, schema-2 minting IR, shared controlled-mint
semantics, raw current-policy asset structure, consumed-anchor one-shot minting,
and a kernel bridge from pinned V3 minting validity to the solver domain. The
remaining purposes and broader value operations are still proposed work.

**E.4b compositional core (implemented experimentally):**
[ADR-019](019-milestone-e4b-compositional-property-promotion-core.md) makes
the validated typed AST authoritative through promotion, generic Lean
generation, runner planning, and per-property certification. It replaces the
E.3/E.4a whole-formula recognizers with a closed but freely compositional
guarantee language, while keeping exact execution and reviewed domains in a
fixed theorem envelope. E.4b is a prerequisite for adding another purpose.
Schema-3 evidence now demonstrates novel spending and minting compositions,
independent multi-property results, conservative counterexample-domain
metadata, and local, Docker, and native-CLI execution. It introduces no
on-chain compiler dependency or UPLC change.

**E.4c rewarding slice (implemented experimentally):**
[ADR-020](020-milestone-e4c-typed-rewarding-dsl.md) adds exact `withdraw`
selection, the current rewarding credential, duplicate-preserving raw
withdrawal traversal, and a kernel bridge from pinned rewarding validity to
the reviewed solver domain. The vertical slice composes strict redeemer
decoding, an authority signer, and a minimum matching withdrawal. Local,
Docker, native, refuted, and vacuous evidence uses the generic schema-3 path;
no fixed-formula resolver or compiler dependency was added.

**E.4d certifying slice (implemented experimentally):**
[ADR-021](021-milestone-e4d-typed-certifying-dsl.md) adds exact CIP-57
`publish` selection, authoritative certificate/index roots, the ordered raw
certificate list, all 11 pinned certificate-kind recognizers, and the pinned
indexed-membership relation. Its reviewed certifying solver domain has a
kernel-checked inclusion bridge and per-claim corollary. Exact VM, local,
Docker, native, refuted, malformed, index, strengthened-domain, and vacuity
controls pass through the generic schema-3 path without a compiler dependency.

**Later purpose slices:**
Voting and proposing proceed only where exact artifact selection, the pinned
model, and solver support permit. CIP-57 currently has no truthful standard
purpose vocabulary for those interfaces, so they remain fail-closed rather
than being assigned invented purpose names. Later slices extend the generic
capability and semantic inventories; they do not add fixed-formula promotion
paths.

### E.4e–E.4f: Generic contract types and collection core

[ADR-022](022-milestones-e4e-e4f-generic-contract-types-and-collections.md)
implements this foundational phase. E.4e projects the compiler-owned
`PirType` graph into a schema-4 structural symbolic type reference and
generates typed wrappers for datum/redeemer records, variants,
optionals, lists, maps, nested combinations, and productive recursive values.
E.4f adds safe optional elimination, generic duplicate-preserving collections,
canonical quantifiers/binders, complete foundational Boolean/equality
operations, and reviewed linear integer arithmetic. Existing schema-1–3
canonical bytes and the schema-3 `.composed(...)` API remain frozen.
Compiler-erased source newtypes appear as their underlying representation;
nominal newtype identity remains deferred until `ContractSchema` exposes it.

### E.4g: Complete non-value transaction context

[ADR-023](023-milestone-e4g-typed-non-value-transaction-context.md) specifies
and implements this phase as opt-in property schema 5. It exposes generic input and
reference-input traversal, `TxInInfo` fields, current spending reference, fee,
transaction ID, output datum/reference script, complete address/staking
credentials, ordered duplicate-preserving datum witnesses and redeemer maps,
continuing-output selection, and reviewed first/all/count input-selection
helpers. It reuses the E.4e–E.4f type and collection core, freezes schema-1–4
canonical evidence, and does not introduce value aggregation, governance
payloads, certificate payloads, or raw validity/treasury adapters.

### E.4h: Authorization algebra

[ADR-024](024-milestone-e4h-authorization-algebra.md) specifies an opt-in
schema-6 authorization algebra. It adds any/all/none/at-least/exactly-N,
no-unexpected-signers, and exact-authorized-set predicates over **distinct**
public-key-hash identities. Fixed and compiler-owned typed contract sources
are admitted through parent validation. Deployment-parameter sources are
admitted only when JuLC reconstructs parameter application and proves that the
result is the exact verified artifact. Threshold and allow-list meanings stay
separate and compose through the generic IR; common formulas may later
graduate to annotations without receiving a separate proof path.

### E.4i: Certificate payload surface

[ADR-025](025-milestones-e4i-e4j-certificate-payloads-and-value-algebra.md)
specifies this phase as opt-in property schema 7. It extends E.4d constructor
recognition with guarded payload access for deposits, refunds, credentials,
delegation targets, DReps, pools, epochs, and committee credentials. It pins
all 11 V3 `TxCert` constructors plus nested `Delegatee` and `DRep` sums. No
unchecked constructor projection is admitted.

Implemented experimentally on the dedicated E.4i branch with inner property
schema 7, role-preserving credential wrappers, exact VM controls, pinned Lean
codec controls, and positive/refuted/vacuous exact-artifact evidence.

### E.4j: Value and multi-asset algebra

[ADR-025](025-milestones-e4i-e4j-certificate-payloads-and-value-algebra.md)
specifies this phase as opt-in property schema 8. It adds raw policy/token
entry traversal, explicitly distinct upstream-first-match and strict-summed
quantity projections, structural and normalized/extensional relations, checked
value arithmetic/order, spent/produced value, balancing, asset
preservation/leakage, payments, and generalized mint/burn constraints. Every
operation states its malformed-data, duplicate, aggregation, and payment scope;
raw, first-match, strict-summed, and extensional meanings are never conflated.

Implemented experimentally on the dedicated E.4j branch with opt-in property
schema 8, closed value IR and parent validation, kernel semantic controls,
exact-VM tests, result metadata that records meaning and aggregation scope, and
positive/refuted/vacuous exact-artifact evidence. Strict-summed and
whole-value extensional formulas are faithfully generated but retain explicit
bounded-solver limitations rather than being weakened to obtain a result.

### E.4k: Governance transaction data

Add typed voters, votes, governance action IDs, proposals, protocol versions,
governance-action constructors, and known-voter/proposal relations for
supported script contexts. Voting/proposing validator selection remains
fail-closed until a truthful exact-artifact convention exists; the pinned
CIP-57 vocabulary currently has no standard purpose names for those entries.

Implemented experimentally as property schema 9. Positive JVM, Docker, and
native runs bind identical semantic hashes; retained refuted and vacuous
controls cover the other result classes. Strict proposal-action decoding is
kernel-supported, while the first solver calibration exposed that
non-proposing valid-context domains do not globally validate proposal actions.
See [ADR-026](026-milestone-e4k-typed-governance-transaction-data.md).

### E.4l: Reviewed raw-data adapters

[ADR-027](027-milestone-e4l-reviewed-raw-data-adapters.md) implements this
phase under the unreleased milestone property schema 10. It separates
pinned-decoder from
canonical validity-range semantics, models treasury optionals as the three
distinct states present/absent/malformed, and permits only narrow
duplicate-preserving views of changed-parameter IDs and structurally pinned
quorum data. Each adapter has an independent evidence gate; unresolved raw
payloads remain visibly `RAW_DATA_ONLY` rather than being promoted as a group.
The JVM, Docker, and native positive runs bind identical semantic hashes; a
separate non-vacuous treasury refutation records the pinned helper/strict-codec
discrepancy instead of weakening the adapter.

### E.5: State-machine experiment

[ADR-028](028-milestone-e5-exact-artifact-state-machine-experiment.md)
specified a deliberately small spending-state experiment over Blaster BMC and
k-induction. It was rejected at the mandatory calibration gate: even an
396-byte validator whose Java body returns true, with no ledger-domain premise,
could not establish depth-1 target reachability within five minutes under the
combined direct exact-artifact transition encoding. The product-facing
prototype was removed, so the then-current milestone schemas 1 through 10 and
CLI/result meanings remained unchanged. E.6 subsequently replaced those
unreleased milestone gates with the public canonical
`julc.verification.dsl` schema 1 contract.
Any future temporal-verification attempt needs a materially different,
separately reviewed execution-linkage strategy.

### E.6: Public API decision

Stabilize the DSL only if the experiments demonstrate:

- materially broader useful properties than annotations alone;
- stable Java IDE/type-checking behavior;
- a defensible property-builder execution boundary;
- deterministic canonical IR;
- manageable solver behavior and diagnostics;
- no core compiler dependency or UPLC regression; and
- certificates understandable without reading generated Lean.

Otherwise retain annotations/profiles as the product interface and the
canonical IR or generated Lean workspace as the expert extension surface.

Decision: the complete reviewed E.4 construction surface is stable as
verification DSL API version 1 and public canonical
`julc.verification.dsl` schema 1 under
[ADR-029](029-milestone-e6-stable-verification-dsl-public-api.md).
Annotations are concise frontends over the same canonical DSL guarantee IR;
profile-specific handwritten Lean predicates are not retained. E.5 is excluded.

## Testing requirements

Every admitted IR node and ledger adapter requires:

- Java type-safety and invalid-composition tests;
- canonical serialization and hash stability tests;
- positive and malformed Lean elaboration tests;
- semantic positive and negative controls;
- exact-artifact and non-vacuity evidence;
- timeout, unknown-result, unsupported-builtin, and tamper tests;
- contract-schema/metamodel mismatch tests;
- worker crash, oversized AST, nondeterministic output, and protocol-tamper
  tests; and
- zero-UPLC-effect golden tests.

For quantified collections, test empty, singleton, duplicate, reordered, and
multiple-matching cases. Value properties must test duplicate policy/token
entries and explicitly state whether equality is structural or extensional.

## Consequences

### Positive

- Common users retain concise, reviewed annotations.
- Advanced Java users can compose contract-specific properties without
  learning Lean for supported operations.
- Adding a novel combination of already supported operations does not require
  a JuLC release or a template-specific generator.
- One typed IR and semantic library prevent annotation-specific theorem drift.
- Generated contract models replace fragile string paths with IDE-checkable
  field access.
- A capability manifest makes ledger-model coverage measurable and auditable.
- Novel DSL expressions can be exercised before promotion into stable
  profiles.

### Negative

- JuLC becomes responsible for a symbolic Java API, code generation, IR
  evolution, and solver-facing semantics.
- Executing a property builder introduces an explicit untrusted-code boundary
  that cannot be solved with ordinary in-process Java sandboxing.
- Full typed ledger-surface coverage will expose properties Blaster cannot
  decide automatically.
- Upstream ledger-model changes require capability and conformance review.
- Generated models introduce a two-phase build and new IDE/build integration.
- Users may still need Lean for mathematical concepts outside the sealed IR.

## Rejected alternatives

- **One annotation per possible theorem.** The space of Lean propositions is
  open-ended and annotations cannot encode all useful composition cleanly.
- **Raw Lean strings in Java annotations.** This loses types, source safety,
  stable semantics, and meaningful diagnostics.
- **Translate arbitrary validator Java into a property.** A validator describes
  acceptance behavior, not the security goal it should satisfy.
- **Treat every Java predicate as symbolic automatically.** Ordinary Java
  control flow and values do not carry a faithful expression tree.
- **Execute the property builder in the CLI process.** A separate bounded
  process is required even for the prototype.
- **Claim all Cardano support when every field has a Java getter.** Typed access
  is not solver support, model correctness, ledger admission, or proof.
- **Replace exact-UPLC checking with a Java or Lean transition model.** Such a
  model can support additional reasoning only when its relationship to the
  deployable artifact is separately established.

## Exit condition

The E.4b compositional exit condition and the later purpose/type/value adapter
gates have been met. ADR-029 freezes the resulting API version 1 and public
canonical `julc.verification.dsl` schema 1 without claiming complete
CardanoLedgerApi coverage. New semantic vocabulary requires a new reviewed
public property schema; the unreleased milestone schemas 1 through 10 are
historical evidence formats, not supported compatibility inputs.
