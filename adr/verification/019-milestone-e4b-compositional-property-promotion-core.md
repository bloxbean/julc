# ADR-019: Milestone E.4b Compositional Property Promotion Core

- **Status:** Implemented experimentally, manually reviewed, and integrated
- **Date:** 2026-08-20
- **Feature branch:** `feat/typed-verification-dsl-e4b-composition`
- **Parent:**
  [ADR-016 — Typed Verification DSL and Foundational Profile Catalog](016-typed-verification-dsl-and-profile-catalog.md)
- **Related:**
  [ADR-011 — `@RequiresSigner` Vertical Slice](011-milestone-c5-requires-signer.md),
  [ADR-012 — Stateful Spending Profile](012-milestone-c6-stateful-spending-profile.md),
  [ADR-013 — Controlled Minting Profile](013-milestone-c7-controlled-minting-profile.md),
  [ADR-015 — Strict On-Chain Data Boundaries](015-strict-on-chain-data-boundaries.md),
  [ADR-017 — Purpose-Indexed Multi-Validator Blueprints](017-purpose-indexed-multivalidator-blueprints.md),
  [ADR-018 — Typed Minting Verification DSL](018-milestone-e4a-typed-minting-dsl.md)

## Context

ADR-016 introduced a typed Java property DSL and a sealed, versioned property
AST. E.2 established the worker, codec, authoritative parent validation, and
deterministic Lean expression renderer. E.3 and E.4a then used that foundation
for seller-payment and minting vertical slices.

Those slices deliberately promote only fixed reviewed formulas:

- `SellerPaymentDsl.resolve` compares the complete canonical AST with one
  expected seller-payment tree;
- `MintingDsl.resolve` decomposes the tree and recognizes only controlled-mint
  or one-shot-mint shapes; and
- `VerifyDslCommand` converts those shapes into template-specific Java
  property records and template-specific workspace generators.

This was appropriate while establishing exact-artifact linkage, strict type
validation, solver-domain bridges, raw mint semantics, result classification,
and certificate integrity. It is not an adequate general DSL product. A Java
developer can construct a well-typed AST from supported operations yet still
receive an error because the tree is not one of the hard-coded templates.
Reordering independent conjunctions or adding another supported guarantee can
also trigger that rejection.

Fixed formulas remain useful as reviewed annotations and convenience profiles.
They are not useful as the authorization mechanism for a compositional DSL.
Adding another resolver and workspace generator for every formula would
recreate the annotation-per-theorem scaling problem that ADR-016 rejected.

E.4b therefore makes the existing typed AST authoritative through promotion,
Lean generation, runner planning, and certification. Purpose-specific surface
expansion such as rewarding follows only after this compositional core exists,
so new purposes add semantic nodes and adapters rather than new exact-tree
recognizers.

## Problem statement

JuLC needs to accept any property made by valid composition of the currently
supported typed DSL nodes without requiring a template-specific Java resolver.
It must do so without permitting arbitrary Lean, unchecked assumptions,
purpose confusion, solver-domain laundering, or certificates that summarize a
different formula from the one proved.

The desired distinction is:

- **closed compositional DSL:** users freely combine reviewed typed nodes; and
- **open code injection:** users introduce new Lean definitions, axioms, or
  assumptions.

E.4b implements the first and continues to reject the second.

## Current behavior

The repository already has:

- a sealed `PropertyNode` inventory;
- explicit `DslType` result types;
- schema-aware roots and fields;
- Boolean composition and implication;
- typed comparisons and bounded literals;
- signatory membership, consumed-input, seller-payment, and raw exact
  own-policy mint predicates;
- bounded existential output selection;
- strict canonical JSON and AST-size limits;
- authoritative validation after the Java worker exits; and
- deterministic Lean expression rendering.

The missing path is:

```text
validated DslPropertySet
    -> generic certificate-facing property
    -> generic semantic dependency plan
    -> generic Lean theorem and non-vacuity queries
    -> generic per-property runner plan
    -> generic per-property certificate entries
```

Today that path branches through `SellerPaymentProperty`,
`ControlledMintProperty`, or `OneShotMintProperty` and their corresponding
generators.

## Goals

E.4b will:

1. admit every well-typed composition of supported nodes that satisfies the
   purpose and theorem-envelope rules in this ADR;
2. remove exact guarantee-tree matching from `julc verify dsl`;
3. introduce one generic, versioned, certificate-facing DSL property model;
4. derive Lean definitions, theorem bodies, assumptions, and runner steps from
   the validated canonical IR rather than a profile name;
5. support one or more independently named properties in a property set;
6. canonicalize equivalent Boolean association and operand ordering where the
   admitted semantics are pure and total;
7. retain helper builders such as controlled mint and one-shot mint as
   ordinary AST construction conveniences with no privileged promotion path;
8. keep annotations and reviewed profiles lowering into the same node
   semantics while preserving their stable template IDs and certificate
   compatibility;
9. preserve exact UPLC, purpose, domain, fuel, tool, source, and generated-tree
   bindings; and
10. add no compiler, PIR, optimizer, wrapper, ledger API, or on-chain behavior
    change.

## Non-goals

E.4b does not:

- add rewarding, certifying, voting, or proposing roots;
- add arbitrary new ledger operations merely to demonstrate breadth;
- expose raw Lean, imports, theorem names, tactics, axioms, or arbitrary
  assumptions;
- accept arbitrary top-level formulas that omit exact UPLC execution;
- infer a desired property from validator implementation;
- claim that all well-typed properties are decidable by Blaster/Z3;
- reconstruct SMT proofs in the Lean kernel;
- stabilize the experimental Java DSL API;
- provide unbounded user recursion or unrestricted higher-order functions;
- treat successful verification of one property as proof that a contract is
  safe; or
- change validator UPLC, datum/redeemer encoding, or strict boundary behavior.

## Terminology

### Supported node

A node is supported only when it is in the sealed IR inventory for the exact
schema version, has authoritative Java type and purpose validation, has one
reviewed Lean meaning, has semantic controls, and is admitted by the pinned
capability manifest.

### Free composition

Free composition means that supported nodes may be combined using supported
typed combinators without matching a pre-enumerated whole formula. It does not
mean that unknown nodes, mismatched purposes, hidden assumptions, or raw Lean
are accepted.

### Theorem envelope

The theorem envelope separates the mandatory exact-execution premise and an
optional reviewed ledger-domain premise from the user-composed guarantee. The
envelope is protocol metadata, not an ordinary Boolean expression that may be
moved, duplicated, negated, or hidden in a branch.

### Profile helper

A profile helper is Java code that constructs an ordinary DSL property from
supported nodes. Its output receives no special privilege. The annotation form
may retain stable template metadata for compatibility, but it must share the
same canonical node semantics and renderer.

## Invariants

### Exact artifact and purpose

- Every property executes the exact current blueprint `compiledCode` selected
  by validator identity and purpose.
- Observational compiler bytes and Cardano script hash match the blueprint
  entry before the property worker runs.
- A property set has one explicit selected purpose. Every root, field, domain,
  and semantic predicate is valid for that purpose.
- Multi-validator selection never depends on entry order.

### Closed semantic input

- Only sealed, data-only IR nodes reach Lean generation.
- Unknown node kinds, schema versions, fields, result types, roots, literals,
  domains, and capability states fail in the parent process.
- No node carries Lean or Java source text, imports, declarations, tactics,
  theorem names, shell commands, or unchecked premise text.
- Worker output is a candidate until it has been decoded strictly,
  revalidated, normalized, and canonicalized by the parent.

### Generic promotion

- Admission depends on node-local type, capability, purpose, scope, domain,
  and resource rules—not equality with a complete reviewed template AST.
- Adding or reordering an otherwise valid supported guarantee cannot require a
  new Java resolver.
- Helper-built and manually composed identical ASTs produce identical
  canonical IR, Lean, runner plans, and certificate formula hashes.
- Every node admitted by validation has exactly one generic lowering path and
  every lowering dependency is derived mechanically from the node inventory.

### Honest theorem envelope

- Exact UPLC success occurs exactly once as a mandatory premise.
- At most one reviewed domain selector is attached to a property. Its purpose
  must match the selected interface.
- Domain selectors cannot occur in the guarantee and cannot be constructed by
  a generic `assume` operation.
- Exact execution cannot occur in the guarantee, be negated, or occur below a
  disjunction or quantifier.
- A generated certificate lists the normalized guarantee and the exact
  derived domain assumptions separately.

### Result honesty

- Each property receives its own non-vacuity and proof result.
- Vacuity or an undetermined non-vacuity result for one property skips only
  that property's proof and can never become success.
- `SMT-VALID`, `REFUTED`, `UNDETERMINED`, and `COULD-NOT-EVALUATE` retain their
  existing meanings and recorded bounds.
- Overall command success requires every requested property to reach an
  accepted success outcome. The certificate always preserves each individual
  result.
- A solver-domain counterexample is not described as ledger-valid unless the
  existing independent witness gate establishes that fact.

### Zero on-chain effect

- Verification source and IR changes produce byte-identical validator UPLC.
- No E.4b implementation dependency flows into `julc-core`, `julc-compiler`,
  `julc-ledger-api`, `julc-stdlib`, PIR generation, optimization, wrappers, or
  normal blueprint generation.
- Existing strict boundary semantics remain compiler-owned and unconditional.

## Decision

### 1. Introduce property-IR schema 3

Schema 1 and schema 2 remain readable with their existing meaning. E.4b does
not reinterpret them. Generic composition uses schema 3 with an explicit
property-set purpose and an explicit theorem envelope.

Conceptually:

```text
DslSpecificationInvocation
  sourceReference: recorded provenance shared by returned claims
DslPropertySetV3
  schemaVersion: 3
  purpose: spending | minting
  properties:
    - id
      domain: none | valid-spending-v3-pinned | valid-minting-v3-pinned
      guarantee: PropertyNode<BOOL>
```

The exact Java record spelling may follow existing codec conventions. The
canonical IR does not store a user-authored implication containing execution
and domain nodes. The parent constructs the theorem envelope mechanically:

```text
domain(ctx) && exactUplcSucceeds(ctx) -> guarantee(ctx)
```

or, without a ledger domain:

```text
exactUplcSucceeds(ctx) -> guarantee(ctx)
```

This makes generic guarantee composition possible while making premise
placement non-configurable. Schema-1 and schema-2 adapters may translate their
existing normalized implications into schema 3 internally, but their original
canonical bytes and interpretation remain available for old evidence.

### 2. Keep the node inventory closed and compositional

E.4b initially admits the semantic nodes already demonstrated by E.3 and E.4a:

- strict datum field selection for supported primitive fields;
- context, transaction information, outputs, inputs, signatories, and mint;
- output address, credential, value, and lovelace selection;
- current minting policy and strict redeemer decoding;
- Boolean `and`, `or`, and nested implication within guarantees;
- integer comparisons and supported byte/credential equality;
- signatory membership;
- bounded output existential;
- consumed transaction-output reference; and
- raw exact own-policy asset shape.

E.4b does not mark a capability supported merely because the AST could model
its type. New nodes continue to require a reviewed semantic adapter and tests.

`exactUplcSucceeds`, `validSpendingContext`, and `validMintingContext` become
envelope concepts in schema 3 rather than freely usable guarantee roots.
Legacy schemas retain their frozen interpretation.

### 3. Canonicalize pure Boolean composition

Canonicalization runs after authoritative validation and before hashing or
Lean generation. For schema-3 guarantees it:

- recursively normalizes children;
- flattens nested `AND` trees and nested `OR` trees;
- orders operands by their canonical node encoding;
- removes exact duplicate operands;
- rebuilds one deterministic association shape; and
- preserves the structure and scope of quantifiers and nested implications.

It does not apply Boolean-algebra rewrites such as distribution, De Morgan's
law, implication elimination, tautology removal, or solver simplification.
Those rewrites can expand formulas, obscure user intent, or complicate source
mapping. `AND` and `OR` canonicalization is sound because admitted expressions
are pure, total Lean `Bool` terms; no admitted node exposes traces, exceptions,
or host-language effects.

Property IDs remain stable user identities and are not derived from the
formula. Two properties with different IDs may have equal guarantees and are
reported as two explicit independent claims; neither silently disappears.

### 4. Add one generic certificate-facing property model

Add a versioned generic property record, provisionally
`ComposedDslProperty`, containing:

- property schema and property ID;
- selected validator, interface, entrypoint, and purpose identities;
- source reference;
- explicit reviewed domain selector;
- canonical guarantee IR and hash;
- mechanically constructed theorem-envelope IR and hash;
- exact artifact and script hash;
- required semantic capabilities and definitions;
- guarantee summary generated from node kinds, not supplied prose; and
- ledger-validity and counterexample-domain metadata.

`template` for a custom DSL property is a stable generic protocol identifier,
not a claim about its semantics, for example `julc.dsl-composed/v1`. The exact
formula hash and property ID distinguish claims.

Reviewed annotation profiles retain their existing template IDs. Their DSL
lowerings pass through the same validator, canonicalizer, dependency planner,
and renderer. Profile-specific configuration validation may remain at the
annotation boundary, but it cannot own a second Lean semantics.

### 5. Generate Lean from node dependencies, not profile classes

The generic generator performs these stages:

```text
canonical schema-3 property set
    -> collect roots, fields, node kinds, types, domains, and binders
    -> verify every required capability is admitted
    -> emit only deterministic reviewed semantic definitions required by nodes
    -> render each canonical guarantee
    -> wrap it in the exact execution/domain envelope
    -> emit per-property non-vacuity and proof commands
```

The dependency collector must be exhaustive over the sealed node inventory.
Adding a node without its capability, validator, dependency, renderer, and
test mappings must fail compilation or fail closed before workspace
publication. There is no default "unknown node" lowering.

The generator may reuse E.3/E.4a Lean definitions and ledger bridges. It must
not keep a separate authoritative copy selected only by template ID.

### 6. Support multiple independent properties

A schema-3 property set contains one or more properties with unique stable
IDs. Each property receives:

- an independently rendered named guarantee;
- an independently constructed theorem envelope;
- a non-vacuity step;
- a proof/counterexample step when non-vacuity is established;
- separate logs and retained models; and
- a separate certificate result.

The runner plan uses normalized property IDs only after collision-safe encoding
to step and file names. Raw IDs never become shell fragments or filesystem
paths.

The overall result is an aggregate for command exit behavior, not a replacement
for per-property outcomes. All requested properties must succeed for exit zero.
Refutation, undetermined, vacuity, timeout, unsupported semantics, or tamper in
any requested property produces a nonzero command result while leaving every
completed per-property result visible.

E.4b does not introduce cross-property logical implication or a profile-level
claim that conjunction of independently proved properties is a complete
security proof. A later reviewed profile may define such aggregation.

### 7. Preserve explicit purpose and domain compatibility

Schema-3 validation uses a purpose-specific capability table. At minimum:

- datum roots and `valid-spending-v3-pinned` require spending;
- own-policy, mint, strict minting redeemer decode, and
  `valid-minting-v3-pinned` require minting;
- common transaction fields are admitted only where their existing adapters
  are demonstrated; and
- combining roots from different purposes fails before Lean generation with
  an actionable diagnostic naming the offending node and selected purpose.

Ledger domains remain allow-listed enums whose Lean predicates and kernel
bridges are pinned. A property cannot supply a predicate or add assumptions.
No-domain remains explicit rather than inferred.

### 8. Keep helpers, remove privileged shape recognition

`SellerPaymentDsl.propertySet`, `MintingDsl.controlledMintPropertySet`, and
`MintingDsl.oneShotPropertySet` may remain temporarily as convenience builders
and regression fixtures. Their output is promoted through the generic path.

`SellerPaymentDsl.resolve` and `MintingDsl.resolve` must no longer authorize a
custom DSL property by matching its complete guarantee shape. Template-specific
extraction may remain only at annotation/profile compatibility boundaries
where stable template fields must be populated, and must be tested against the
canonical generic semantics.

A manually constructed AST equal to a helper's AST and the helper-built AST
must produce identical semantic artifacts. A novel combination of supported
nodes must succeed without adding a resolver branch.

### 9. Preserve worker and publication boundaries

E.4b retains:

- explicit trusted-source worker execution;
- sanitized environment, memory, time, output, and AST bounds;
- strict JSON with unknown-field and unknown-subtype rejection;
- parent-process validation and canonicalization;
- hash binding of candidate input, canonical IR, metamodel, generated Lean,
  runner plan, exact UPLC, dependency pins, capability inventory, and bounds;
- atomic workspace and certificate publication; and
- local and Docker proof backends with the existing network policy.

The Java worker remains required for native-CLI DSL use because project Java
must execute in a JVM. This milestone does not claim that process isolation is
an OS sandbox.

Runner preflight re-parses the hash-bound canonical IR and re-derives claim
identities, capabilities, domains, formula hashes, and theorem envelopes. It
does not repeat compiler-schema field/type validation because `ContractSchema`
is intentionally not published into the standalone workspace. That validation
is authoritative during CLI promotion; subsequent modification is detected by
the candidate, canonical-IR, property, manifest, runner-plan, and generated-tree
hash bindings.

## Affected modules

### `julc-verification`

- schema-3 property envelope and codec;
- generic property record;
- canonicalizer;
- purpose/domain-aware validator;
- semantic dependency inventory;
- generic AST promotion;
- profile/helper adapters; and
- Java unit and compatibility tests.

### `julc-cli`

- generic DSL command path;
- generic workspace and Lean theorem generation;
- per-property runner-plan and certificate protocol;
- result aggregation, progress, diagnostics, and tamper validation;
- native-image reachability metadata; and
- integration/evidence tests.

### `verification/e4b`

- supported-composition fixtures;
- helper/manual equivalence controls;
- positive, refuted, vacuous, malformed, unsupported, and multi-property
  evidence;
- local and Docker reproducibility scripts; and
- user-facing examples.

No compiler/core/ledger/stdlib source module is expected to change. Discovery
of such a requirement stops that part of implementation until this ADR is
revised.

## Implementation milestones

### E.4b.1 — Schema-3 envelope, canonicalization, and admission

- Add explicit purpose, reviewed domain, guarantee, invocation-level source
  provenance, and multiple-property representation.
- Freeze schema-1 and schema-2 canonical compatibility fixtures.
- Implement authoritative purpose/domain validation for every existing node.
- Move exact execution and domain roots into the parent-owned theorem envelope.
- Implement bounded `AND`/`OR` canonicalization without broad Boolean
  simplification.
- Add duplicate ID, normalized-name collision, wrong-purpose, hidden-domain,
  hidden-execution, unknown-node, unknown-field, forged-type, binder, and AST
  resource tests.
- Demonstrate that reordered and differently associated pure conjunctions
  produce identical canonical schema-3 bytes.

### E.4b.2 — Generic semantic dependency and Lean generation

- Add an exhaustive node-to-capability and node-to-Lean-dependency plan.
- Refactor existing E.3/E.4a semantic definitions into reusable reviewed
  emitters without changing their meaning.
- Generate generic guarantees and theorem envelopes without inspecting a
  template ID or whole-tree shape.
- Preserve spending and minting ledger-domain bridge behavior.
- Make helpers and annotations use the shared canonicalizer and renderer.
- Add tests that every sealed node has validator, canonicalizer, dependency,
  renderer, native-image, and semantic-control coverage.
- Kernel-compile generated workspaces for novel supported compositions.

### E.4b.3 — Generic runner and multi-property certificates

- Generate independent non-vacuity/proof steps, logs, and result entries for
  every property.
- Define and test deterministic aggregate command outcome and exit behavior.
- Bind property order, IDs, canonical guarantees, envelopes, capabilities,
  domains, and generated files into manifest preflight.
- Keep vacuous properties from executing their proof without skipping other
  properties.
- Retain raw counterexamples per property and preserve domain qualification.
- Add unknown exit, mixed marker, timeout, under-fuel, missing log, path
  collision, result omission, result duplication, and tamper tests.

### E.4b.4 — Migration, evidence, and product documentation

- Route `julc verify dsl` through generic promotion for spending and minting.
- Retain existing E.3/E.4a command compatibility where practical and document
  any schema-3 regeneration requirement.
- Reproduce E.3 seller-payment and E.4a controlled/one-shot classifications.
- Reproduce C.5 and C.7 annotation/DSL semantic equivalence.
- Add at least one novel spending and one novel minting formula that were
  representable but rejected before E.4b.
- Add a two-property run with independent success/refutation/vacuity controls.
- Run focused module tests, complete affected build, exact UPLC/VM controls,
  kernel checks, local proof evidence, Docker proof evidence, and GraalVM
  native CLI coverage.
- Update ADR-016, verification getting-started documentation, DSL examples,
  capability notes, integration-branch ledger, and roadmap issue.

## Required tests

### Type, purpose, and schema validation

- every sealed node succeeds in at least one valid purpose and fails in every
  invalid purpose;
- result-type forgery and field/root forgery fail in the parent;
- schema-1 and schema-2 bytes retain their established meaning;
- schema-3 unknown fields and subtypes fail strict decoding;
- domain and execution concepts cannot occur inside guarantees;
- invalid or unavailable domains fail before generation;
- worker output remains bounded and nondeterministic/tampered output fails;
  and
- adding a sealed node without complete mapping fails a compatibility gate.

### Composition and canonicalization

- nested supported `AND`, `OR`, and implication combinations are admitted;
- arbitrary supported guarantee clauses can be added without profile matching;
- `AND`/`OR` association and ordering normalize deterministically;
- duplicate operand behavior is fixed and tested;
- quantifier scopes are preserved and never reordered across binders;
- no distributive or solver-driven rewrite changes the stated formula;
- helper-built and manually built equivalent formulas have identical hashes;
  and
- different formulas never share a certificate formula hash.

### Generic Lean generation

- every admitted node renders through the generic path;
- generated semantic dependencies are complete and minimal enough to be
  deterministic;
- missing mappings fail closed rather than emit placeholder Lean;
- E.3 and E.4a property meanings remain equivalent after regeneration;
- spending and minting domain bridges kernel-compile without admissions;
- generated source passes the existing `sorry`, `admit`, `axiom`, `unsafe`,
  `partial`, and unpinned-import admission scans; and
- exact UPLC success appears exactly once in each generated theorem premise.

### Runner and certificates

- one and multiple properties receive stable independent step identities;
- all-success produces overall success;
- one refuted, vacuous, undetermined, timed-out, malformed, or tampered
  property produces non-success without hiding other results;
- a vacuous property skips only its own proof;
- reordered property input does not change per-property semantic hashes;
- path/name collisions fail before process execution;
- certificate formulas match canonical schema-3 bytes and generated Lean;
- counterexample domain and ledger-valid witness fields remain conservative;
  and
- fuel exhaustion is never classified as proof.

### Regression and zero effect

- `julc-verification` and `julc-cli` affected suites pass;
- repository build passes in proportion to the final diff;
- C.5–C.7, E.3, and E.4a expected classifications reproduce;
- annotation helpers remain UPLC-neutral;
- changing only DSL source leaves compiled code and script hash byte-identical;
- normal compiler and blueprint APIs do not depend on DSL classes;
- local and Docker positive evidence share identical semantic inputs; and
- a real native CLI executes generic DSL verification with the documented JVM
  worker prerequisite.

## Compatibility and migration

- The DSL remains experimental; schema-3 API spelling is not yet stable.
- Schema 1 and schema 2 are never silently reinterpreted. Existing committed
  evidence remains readable and receives explicit adapters or regeneration.
- Existing annotation syntax and stable template IDs remain unchanged.
- Template-generated Lean/file hashes may change when regenerated through the
  generic generator, but semantic equivalence and classifications must be
  demonstrated before migration.
- Custom E.3/E.4a DSL source may require changing from an implication-shaped
  property to an explicit domain plus guarantee builder. Diagnostics and the
  getting-started guide must show the mechanical migration.
- No validator script hash change is expected. Any observed UPLC difference is
  a blocker, not an accepted migration cost.
- Purpose-indexed blueprint identity remains governed by ADR-017.

## Risks and mitigations

### A generic formula accidentally admits a hidden assumption

Schema 3 stores reviewed domain selection separately and constructs exact
execution mechanically. Guarantee validation rejects domain/execution roots.
There is no generic `assume` node.

### Renderer coverage drifts from validator coverage

One exhaustive compatibility gate covers sealed node validation,
canonicalization, capabilities, dependencies, rendering, and native metadata.
No default lowering exists.

### Canonicalization changes semantics

E.4b canonicalizes only association, ordering, and exact duplicates for pure,
total `AND`/`OR` terms. It does not distribute, eliminate implications, move
quantifiers, or invoke the solver. Semantic equivalence controls compare the
pre-normalized and normalized terms in Lean.

### Generic properties are misread as complete security proofs

Certificates serialize the exact named guarantee and assumptions and retain
the experimental warning. CLI language says that the named property was
established or refuted for the exact artifact under recorded bounds; it never
says the contract is safe.

### Multi-property aggregation hides a failure

Overall success requires every requested property to succeed. Per-property
results remain primary certificate data and cannot be replaced by an aggregate
summary.

### Solver cost grows with composition

Node, binder, literal, worker, CEK-fuel, solver-time, and output limits remain
enforced. Timeout or undetermined is non-success. Timings and bounds are
recorded; the DSL is not narrowed to make demonstrations pass.

### Template compatibility keeps two semantics

Template records may remain as certificate compatibility envelopes, but their
formulas lower through and are compared against the generic canonical AST and
renderer. A template-specific Lean generator is not retained as an authority.

## Alternatives considered

### Continue adding exact formula recognizers

Rejected. It makes the DSL another collection of annotations with more verbose
syntax and requires code changes for every useful combination.

### Accept any Boolean AST including execution and assumptions

Rejected. Users could move execution under disjunction, negate it, duplicate
it, or assume the desired conclusion. The fixed theorem envelope is a soundness
boundary, not a restriction on guarantee composition.

### Expose raw Lean for unsupported combinations

Rejected. It bypasses type checking, capability review, deterministic
semantics, admission controls, and certificate interpretation.

### Canonicalize with a full Boolean algebra optimizer

Rejected. Distribution and solver-oriented rewriting can cause exponential
growth and obscure what the developer stated. Minimal pure `AND`/`OR`
normalization is sufficient for stable composition.

### Add rewarding before generic promotion

Rejected. It would likely add another fixed formula path and repeat the same
architectural debt. Rewarding becomes E.4c and consumes the generic E.4b core.

### Remove reviewed helpers and annotations

Rejected. Concise, versioned profiles remain valuable for common security
goals. They become convenience frontends over shared semantics rather than
the only formulas JuLC can promote.

## Resolved implementation decisions

1. **Domain placement:** schema 3 uses
   `property(id, domain, guarantee)`. A set has one purpose, while each claim
   selects either no reviewed domain or the compatible pinned purpose domain.
2. **Equal formulas:** distinct property IDs remain distinct claims even when
   their normalized guarantees and hashes are equal. Exact duplicate operands
   inside one `AND` or `OR` are removed by canonicalization; claims never are.
3. **Aggregate JSON:** the existing schema-2 runner/result protocol is reused.
   `properties` remains the authoritative ordered list, with two entries per
   claim: non-vacuity followed by proof. The top-level outcome is derived and
   cannot replace or hide those entries.
4. **Legacy schemas:** schema 1 and schema 2 remain accepted unchanged for the
   E.3/E.4a compatibility commands. New generic specifications use schema 3.
   Removal or a long-term support promise requires a later public-API decision.
5. **Source provenance:** `--source` identifies the trusted Java specification
   invocation shared by all claims returned by that worker. It is recorded
   provenance, not a semantic input whose file contents are trusted. Canonical
   schema-3 IR is the hash-bound semantic input.
6. **Canonical identities:** property IDs are sorted deterministically and
   encoded into collision-checked Lean/file identifiers. Case-insensitive
   filesystem collisions fail before generation.
7. **Counterexample claims:** every generic claim records its symbolic domain
   plus `ledgerValidCounterexampleEstablished=false` and
   `concreteVmCounterexampleReproduced=false`. A solver model is not promoted
   to either stronger witness without a future independent gate.
8. **Derived semantic identifiers:** capability names and `guaranteeRules`
   are authenticated derived-property inputs, even though they are not part of
   the canonical user-authored DSL JSON. Once evidence has been published,
   changing one requires either a compatible spelling for existing nodes or
   an explicit workspace-regeneration note (and a schema/protocol revision
   when backward re-verification is promised). Preflight must continue to
   reject stale or mixed identifiers rather than accepting aliases silently.

### Post-E.4c workspace compatibility note

E.4c generalized the derived existential rule identifier from
`exists-output` to `exists:LIST_TX_OUT` so the same closed dependency planner
can distinguish output and withdrawal collections. The canonical schema-3 DSL
value and its meaning did not change, but the authenticated derived property
IR did. Consequently, an E.4b workspace generated before E.4c fails current
preflight and must be regenerated with `verification/e4b/scripts/verify.sh`
before it can be run again. This is an intentional fail-closed migration;
previously produced result certificates remain records of their original,
hash-bound runs.

## Implementation outcome

E.4b.1 through E.4b.4 are implemented on
`feat/typed-verification-dsl-e4b-composition`. No compiler, core, ledger API,
stdlib, PIR, optimizer, wrapper, or blueprint-generation source changed.

The implementation adds:

- closed schema-3 purpose/domain envelopes with strict parent validation;
- bounded pure `AND`/`OR` normalization and stable multi-property ordering;
- one generic certificate-facing property and exhaustive semantic dependency
  planner over the admitted sealed node inventory;
- generic spending and minting Lean generation from canonical nodes, including
  the previously reviewed pinned ledger-domain bridges;
- independent non-vacuity/proof scripts, progress, logs, results, vacuity
  guards, aggregation, and conservative counterexample metadata per claim;
- manifest preflight that re-derives claim hashes/capabilities from canonical
  IR and rejects omitted, reordered, duplicated, or cross-bound runner steps;
- native-image reachability metadata for the generic envelope; and
- reproducible spending, minting, mixed-result, and vacuous evidence under
  `verification/e4b`.

Two novel compositions that previously failed whole-formula recognition are
`SMT-VALID` against exact 632-byte spending and minting artifacts at CEK fuel
5000. The spending evidence contains two independently established properties.
A review fix made every selected-domain proof script invoke its generated
per-claim `LedgerCorollary.lean` after SMT success; generator tests and the
local/Docker evidence driver assert those invocations, and the corrected
corollaries elaborate successfully through local, Docker, and native runs.
A mixed run establishes its payment property and refutes its signer property,
producing aggregate `REFUTED` without hiding either result. An always-failing
fixture reports `COULD-NOT-EVALUATE/property-vacuous` and does not execute its
proof.

The positive spending run also succeeds through the Docker backend using
image
`sha256:e4fd68fd9a03e1d91bd7af14dc2cdb149a7f3e98600e5934447aef005b7df4da`
and through a real GraalVM 25.0.2 native CLI. The native CLI invokes the DSL
worker in an installed JVM with the JuLC JAR on `--spec-classpath`, then uses
the same authenticated local verification backend. Local, Docker, and native
runs bind the same canonical IR, exact UPLC, generated Lean, pins, and bounds.
Wall-clock timings remain diagnostics rather than claim inputs.

Existing schema-1/schema-2 code paths, annotation template IDs, and property
records remain compatibility paths. Their focused and complete affected-module
tests are retained; schema-1 and schema-2 canonical byte fixtures are frozen.
The generic path does not call the E.3 or E.4a whole-tree resolvers.

## Acceptance criteria

E.4b is complete only when:

- a novel well-typed composition of existing supported nodes is promoted
  without adding a formula-specific resolver;
- reordered/associated equivalent `AND`/`OR` guarantees canonicalize
  deterministically;
- purpose, domain, root, type, scope, resource, and capability violations fail
  before Lean generation;
- exact execution and reviewed domain selection are mechanically placed in the
  theorem envelope and cannot occur in guarantees;
- generic dependency planning and Lean rendering cover every admitted node;
- helper-built and manual equivalent properties generate identical semantic
  artifacts;
- E.3/E.4a and annotation-profile meanings and classifications reproduce;
- multiple properties run and report independently with fail-closed aggregate
  behavior;
- manifest and certificate bind the exact canonical formulas, generated Lean,
  exact artifact, domains, tools, pins, and bounds;
- local, Docker, and native CLI evidence passes within documented trust
  boundaries;
- DSL changes remain byte-neutral for validator UPLC;
- no compiler/core/ledger/stdlib lowering source changes are introduced; and
- implementation, evidence, counterexamples, performance, and claims receive
  manual review before commit.

## Result claim

The strongest permitted successful E.4b statement is:

> The pinned JuLC verification stack established each named, canonical typed
> DSL property for the exact recorded UPLC artifact under its recorded Cardano
> model, reviewed domain, tool revisions, and execution bounds.

E.4b does not permit:

> This contract is formally verified and safe.
