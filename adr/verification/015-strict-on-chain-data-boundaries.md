# ADR-015: Opt-In Strict On-Chain Data Boundaries

- **Status:** Proposed
- **Date:** 2026-08-13
- **Related:**
  [ADR-005 — Compiler-Owned Blueprint Schema](005-milestone-c1-compiler-owned-blueprint-schema.md),
  [ADR-008 — Productive Recursive ADTs](008-milestone-c3-productive-recursive-adts.md),
  [ADR-011 — `@RequiresSigner` Vertical Slice](011-milestone-c5-requires-signer.md),
  [ADR-012 — Stateful Spending Profile](012-milestone-c6-stateful-spending-profile.md),
  [ADR-013 — Controlled Minting Profile](013-milestone-c7-controlled-minting-profile.md),
  [ADR-014 — Post-C.7 Roadmap](014-post-c7-verification-hardening-roadmap.md)

## Context

JuLC records are represented as constructor-encoded Plutus `Data`. Current
field projection lowers approximately to:

```text
HeadList(TailList^fieldIndex(SndPair(UnConstrData(value))))
```

This extracts required leading fields but does not itself compare the record
constructor tag or require the field list to end at the declared arity. For
example, a one-field datum may still expose its first field when encoded with a
different tag or with trailing fields.

The generated Lean codecs deliberately use the stricter CIP-57 interpretation:
record constructor index and arity must match exactly, and every selected field
must decode according to its declared schema. C.5 first exposed the mismatch:
an ordinary signer validator accepted a malformed datum that the generated
property rejected. The positive evidence fixtures currently perform explicit
raw `Data` checks to close that gap.

Weakening the verification property would hide a real representational
boundary. Changing all existing compiler output would alter script hashes,
size, cost, and behavior. Strict decoding therefore needs an explicit,
separately tested compatibility contract.

## Decision

Introduce a compiler-owned, opt-in strict-boundary mode for new or deliberately
migrated validators. The proposed source surface is an orthogonal validator
annotation in the compiler/stdlib annotation namespace:

```java
@StrictDataBoundary
@SpendingValidator
class StateMachine {
    record Datum(byte[] owner, BigInteger state) {}
    record Redeemer(BigInteger nextState) {}
    // ...
}
```

The final annotation name may change during implementation review, but its
ownership and behavior may not: it is a compiler feature that intentionally
changes UPLC. Verification annotations such as `@RequiresSigner`,
`@Monotonic`, `@PreservesValue`, and `@ControlledMint` continue to have zero
effect on lowering.

### Compatibility contract

- Validators without the strict annotation retain byte-identical UPLC.
- Adding or removing only a verification annotation retains byte-identical
  UPLC in both legacy and strict modes.
- Adding the strict annotation intentionally changes the script and therefore
  its script hash.
- Existing projects are never migrated automatically.
- New project templates may recommend or include explicit strict mode only
  after its budget and compatibility review; the compiler default remains
  legacy until a separate major-version decision.

### Boundary placement

The generated guard runs before the typed entrypoint:

- a three-argument spending validator validates the attached datum and the
  redeemer;
- a two-argument minting validator validates the redeemer;
- future script purposes must define their roots before gaining support; and
- validator parameters remain governed by their own parameter application and
  encoding rules, not implicitly by this annotation.

Failure to validate any boundary root causes validator failure. It must not
substitute a default value, truncate fields, or convert the root to opaque
`Data`.

### Strict schema semantics

For the initially supported subset, the compiler-generated guard mirrors the
compiler-owned schema and generated Lean codec:

- records require the declared constructor index and exact field count;
- sealed variants require a known constructor index and its exact arity;
- integers and byte strings require the corresponding `Data` shape;
- booleans require the canonical constructor and arity encoding;
- optionals require canonical `Some`/`None` tags and arities;
- lists validate every item;
- maps validate every raw key and value while preserving duplicate entries;
- nested combinations recurse structurally; and
- any type without an implemented strict checker fails compilation at its Java
  boundary location.

The initial implementation may reject productive recursive schemas if the
compiler cannot yet emit an auditable terminating on-chain guard. It must not
silently validate only the nonrecursive prefix. Recursive admission requires
its own termination, size, cost, malformed-input, and verification evidence.

UPLC data destructors may fail when the outer `Data` kind is wrong; at a
validator boundary this is rejection. The generated guard must nevertheless
make constructor index and exact-arity checks explicit and must be arranged so
that it cannot accept a malformed value through an unused or lazily skipped
field.

### Compiler architecture

Strict validation is generated from the same `PirType`/named-definition graph
used for UPLC and blueprint generation. Java source must not be reparsed into a
second schema model.

A dedicated boundary-check IR or code generator should:

1. collect reachable datum/redeemer definitions;
2. reject unsupported and unproductive cycles;
3. generate deterministic validation functions once per nominal type;
4. invoke them in `ValidatorWrapper` before the user entrypoint; and
5. retain source locations for unsupported-type diagnostics.

Blueprint generation remains descriptive and does not become the source used
by compiler lowering. The intended flow is:

```text
Java source
    -> compiler type model
         -> ordinary PIR/UPLC
         -> optional strict boundary guards
         -> CIP-57 schema
         -> verification property/schema generation
```

### Relationship to formal verification

`julc verify` never treats `@StrictDataBoundary` as proof. It still imports the
exact changed UPLC and establishes the selected strict property. This gives two
independent checks:

- compiler/VM tests show the generated guard rejects malformed values; and
- Blaster checks that successful exact-artifact execution implies the property
  decoder accepted the same boundary shape.

The certificate records whether the exact artifact was compiled with strict
boundary mode, but the established claim continues to come from the theorem,
not from that metadata field.

### Cost and operational visibility

Strict traversal adds script bytes and execution cost, especially for nested
containers. The implementation must report or make testable:

- legacy and strict script hashes;
- serialized-size delta;
- representative CPU/memory budget delta;
- whether a root is traversed once or repeatedly; and
- the exact strict-boundary mode/version.

The compiler should share validated/destructured data where practical, but an
optimization may not weaken exact tag, arity, or field-shape checks.

## Implementation stages

### S.1: Nonrecursive records and variants

- Add the explicit compiler-owned annotation.
- Generate exact tag/arity checks for datum and redeemer roots.
- Support primitive and nested nonrecursive record/variant fields.
- Add golden compatibility and malformed-input VM tests.
- Replace handwritten raw-shape checks in a copied verification fixture and
  demonstrate the same C.5/C.6 theorem on the new exact artifact.

### S.2: Containers and optional values

- Add structurally recursive list/map/optional guards.
- Confirm duplicate map entries remain representable and validated.
- Add size and execution-budget regression thresholds.

### S.3: Productive recursion

- Define the emitted recursion strategy and termination argument.
- Exercise direct, mutual, and through-container recursion.
- Reject nonproductive or unsupported cycles at source.
- Establish agreement with C.3 strict Lean codecs on bounded concrete cases
  and reviewed general properties where available.

### S.4: New-project experience

- Offer strict mode explicitly in scaffolding and documentation.
- Add migration output showing the changed script hash and budget.
- Do not switch existing projects or compiler defaults automatically.

## Required tests

The feature is not complete without:

- byte-identical golden UPLC for every unannotated existing fixture;
- byte-identical UPLC when only verification annotations change;
- positive canonical datum and redeemer VM evaluations;
- wrong outer `Data` kinds;
- wrong record/variant constructor indexes;
- missing and trailing fields;
- malformed nested optional/list/map elements;
- duplicate map entries;
- direct and nested unsupported-schema diagnostics;
- non-vacuous C.5–C.7 proof runs using strict mode where applicable; and
- negative controls proving that removal of the strict guard is observable.

## Alternatives considered

### Keep handwritten raw-data checks

This is compatible and remains available, but it is repetitive, easy to get
wrong, and disconnects the Java record declaration from its actual boundary
validation.

### Make all record projection globally strict

Rejected for the initial rollout because it changes every affected script hash
and budget without an explicit migration decision.

### Let verification annotations enable strict lowering

Rejected. It would violate their zero-UPLC-effect guarantee and make removing
a proof declaration change deployed behavior.

### Assume ledger inputs follow CIP-57

Rejected. CIP-57 describes schemas for tooling; it is not a ledger rule that
prevents adversarial `Data` from reaching a script.

### Weaken generated properties to match leading-field projection

Rejected. That would bless unexpected constructor tags and trailing data and
would make off-chain/schema-driven interpretations disagree with the theorem.

### Use only a project-wide compiler flag

Not selected as the primary interface because per-validator migration and
source review would be less visible. A project default may later expand to the
same explicit compiler mode, but the compiled validator and certificate must
still expose which mode was used.

## Acceptance criteria

ADR-015 may move from Proposed to Accepted only when the source surface,
supported schema subset, script-hash compatibility policy, cost evidence, and
recursive-type disposition have been reviewed. Implementation is complete
only when unsupported cases fail closed, legacy outputs remain unchanged, and
the exact strict artifacts satisfy the corresponding verification profiles
without handwritten raw-shape guards.
