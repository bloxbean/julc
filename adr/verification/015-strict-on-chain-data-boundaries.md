# ADR-015: Strict On-Chain Data Boundaries by Default

- **Status:** Implemented; manually reviewed
- **Date:** 2026-08-13
- **Related:**
  [ADR-005 — Compiler-Owned Blueprint Schema](005-milestone-c1-compiler-owned-blueprint-schema.md),
  [ADR-008 — Productive Recursive ADTs](008-milestone-c3-productive-recursive-adts.md),
  [ADR-011 — `@RequiresSigner` Vertical Slice](011-milestone-c5-requires-signer.md),
  [ADR-012 — Stateful Spending Profile](012-milestone-c6-stateful-spending-profile.md),
  [ADR-013 — Controlled Minting Profile](013-milestone-c7-controlled-minting-profile.md),
  [ADR-014 — Post-C.7 Roadmap](014-post-c7-verification-hardening-roadmap.md)

## Context

JuLC records are represented as constructor-encoded Plutus `Data`. Before this
decision was implemented, field projection lowered approximately to:

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
property rejected. Earlier positive evidence fixtures performed explicit raw
`Data` checks to close that gap.

Weakening the verification property would hide a real representational
boundary. It would also leave non-verification users exposed to multiple
on-chain encodings being interpreted as the same declared Java value. JuLC is
still a preview compiler, so this is the appropriate point to correct the
language semantics rather than preserve permissive decoding as a permanent
mode.

Changing the compiler behavior will alter affected script hashes, size, cost,
and behavior. That change must be explicit in release notes, measured, and
covered by reviewed golden differences, even though the preview release does
not promise byte-compatible recompilation.

## Decision

Typed datum and redeemer boundaries are strict by default. JuLC will not expose
a `@StrictDataBoundary` annotation, a public legacy annotation, or a public
compiler switch that selects permissive leading-field projection.

The completed compiler will contain no executable legacy-boundary path,
including a package-private or test-only compiler mode. Historical
`legacy-leading-field-v0` metadata remains readable only so tooling can
classify artifacts produced by older JuLC versions; it is not an instruction
that the current compiler can execute. Reproducing an old script requires its
original compiler version.

A declared type describes the complete accepted on-chain representation. For
example:

```java
@SpendingValidator
class StateMachine {
    record Datum(byte[] owner, BigInteger state) {}
    record Redeemer(BigInteger nextState) {}
    // ...
}
```

The `Datum` and `Redeemer` roots must use their declared constructor tags,
exact arities, and recursively valid field shapes before the typed entrypoint
may run. An explicitly declared opaque `PlutusData`/`Data` boundary remains
opaque; strict mode does not invent a schema for a value the developer chose
to handle as raw data.

Verification annotations such as `@RequiresSigner`, `@Monotonic`,
`@PreservesValue`, and `@ControlledMint` continue to have zero effect on
lowering. Strictness is compiler semantics, not a proof and not a property
annotation.

### Preview migration contract

- Recompiling an affected validator with the new compiler may intentionally
  change its UPLC, serialized size, execution budget, and script hash.
- Already deployed scripts remain unchanged on-chain.
- A project that must reproduce an old script hash must retain the old compiler
  version; the new compiler will not provide a permissive compatibility mode.
- Every affected golden change must be classified as an expected boundary
  change. Unrelated compiler output changes remain regressions.
- Adding or removing only a verification annotation must retain byte-identical
  UPLC before and after strict-boundary activation.
- Release notes and migration output must identify the boundary-semantics
  version and the expected script-hash change.
- Migration guidance must cover state as well as code. Before moving UTxOs to
  a newly compiled script, projects must decode and re-encode existing datums
  canonically. A datum accepted by a permissive-era script—for example one with
  a wrong tag or trailing fields—will be rejected at the new strict script if
  copied unchanged.

### Development and activation gate

S.1–S.3 are implemented incrementally behind an internal, test-only compiler
gate. That gate exists so each implementation slice can be reviewed and tested;
it is not a supported source annotation, CLI option, build option, or runtime
compatibility mode.

During S.1-S.3, the gate must be a package-private compiler construction path
whose capability is passed explicitly by compiler tests. It must not be a
system property, environment variable, service-loader selection,
reflection-discovered option, or other ambient setting. In particular,
`JAVA_TOOL_OPTIONS` and equivalent user-controlled process configuration must
not be able to activate a partial checker. S.4 deletes the gate and its
permissive lowering branch, making the completed checker unconditional.

Strict decoding becomes the public compiler behavior only after the checker
covers every typed datum/redeemer schema admitted by the supported compiler
boundary model: primitives, nonrecursive records and variants, containers,
optionals, and productive recursion. Until activation, ordinary compiler output
must not change merely because a partial checker has been merged into the
strict-boundary integration branch.

Any typed boundary without an auditable strict checker fails compilation after
activation at its Java source location. The compiler must never silently fall
back to permissive decoding or validate only a supported prefix.

### Boundary placement

The generated guard runs before the typed entrypoint:

- a three-argument spending validator validates the attached datum and the
  redeemer;
- a two-argument minting validator validates the redeemer;
- future script purposes must define their typed roots before gaining support;
  and
- validator parameters remain governed by their own parameter application and
  encoding rules, not implicitly treated as datum/redeemer roots.

`ScriptContext` does not receive a recursive schema guard. On chain it is
constructed by the ledger from the transaction rather than supplied as an
arbitrary datum by a transaction author, and traversing the complete context
again would add substantial cost. Purpose-specific wrapper matching and
ordinary field destructors still reject shapes they actually inspect. Formal
claims that rely on ledger-valid context structure must state and check the
corresponding pinned ledger-domain premise; they must not infer it from the
absence of a context guard.

Validator parameters are applied by the deployer when constructing the final
script, not supplied as adversarial transaction-time datum or redeemer input.
They are therefore outside this ADR's runtime-boundary guard. This carve-out
does not excuse parameter decoding defects: any mismatch in which raw `Data`
reaches a typed parameter, including integer parameters, is separate compiler
correctness work and must be rejected or fixed in the parameter-application
pipeline rather than hidden inside datum/redeemer strictness.

Failure to validate any boundary root causes validator failure. It must not
substitute a default value, truncate fields, or convert the root to opaque
`Data`.

### Strict schema semantics

The compiler-generated guard mirrors the compiler-owned schema and generated
Lean codec:

- records require the declared constructor index and exact field count;
- sealed variants require a known constructor index and its exact arity;
- integers and byte strings require the corresponding `Data` shape;
- booleans require the canonical constructor and arity encoding;
- optionals require canonical `Some`/`None` tags and arities;
- lists validate every item;
- maps validate every raw key and value while preserving duplicate entries;
- nested combinations recurse structurally;
- productive recursive types use the reviewed terminating strategy from S.3;
- a concrete record that implements a sealed interface is rejected when used
  independently as a boundary root or nested field until its nominal sum tag
  is represented consistently by construction, schema generation, and the
  guard; declaring the sealed-interface type remains supported; and
- any unsupported type fails compilation at its Java boundary location.

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

The checker and the typed entrypoint must share boundary destruction by
design. S.1 must settle the checked-value representation before expected-change
goldens are approved: constructor unpacking, exact-arity inspection, and field
bindings produced while checking a root are reused by entrypoint projections.
The implementation must not unconditionally traverse a complete datum once to
validate it and then traverse the same representation again from scratch for
ordinary field access. Later optimization may improve sharing further, but it
may not redefine the checked representation or weaken eager validation after
goldens have been accepted.

Blueprint generation remains descriptive and does not become the source used
by compiler lowering. The intended flow is:

```text
Java source
    -> compiler type model
         -> ordinary PIR/UPLC with strict boundary guards
         -> CIP-57 schema
         -> verification property/schema generation
```

The production compiler implementation must not depend on `julc-verification`,
Lean, Blaster, or the typed verification DSL. Verification depends on the
compiler in the other direction and supplies additional agreement evidence.

### Relationship to formal verification

`julc verify` never treats strict compiler decoding as proof. It still imports
the exact changed UPLC and establishes the selected property. This gives two
independent checks:

- compiler/VM tests show the generated guard rejects malformed values; and
- Blaster checks that successful exact-artifact execution implies the property
  decoder accepted the same boundary shape.

The certificate records the exact artifact and boundary-semantics version, but
the established claim continues to come from the theorem, not from that
metadata field. The same semantics identifier must also be tooling-visible in
the CIP-57 blueprint's standard compiler identity. The pinned CIP-57 schema
does not allow an arbitrary new preamble field, so JuLC must version this
through the existing `preamble.compiler.version` release identity (or a future
standard schema field if CIP-57 adds one), with an explicit documented mapping
from compiler versions to boundary-semantics versions.

### Cost and operational visibility

Strict traversal adds script bytes and execution cost, especially for nested
containers and recursive values. The implementation must report or make
testable:

- old-compiler and strict-compiler script hashes;
- serialized-size delta;
- representative CPU/memory budget delta;
- representative Blaster CEK-fuel and solver wall-time delta;
- whether a root is traversed once or repeatedly; and
- the exact strict-boundary semantics version.

The measurements must include large, deeply nested, and container-heavy datum
cases near practical ExUnit limits. An explicitly raw `PlutusData` boundary
remains the honest escape for a cost-critical contract that chooses a smaller
manual representation check; declaring a typed record while silently receiving
permissive projection is not an escape.

Strict guards also simplify verification. They remove malformed-value paths
that previously produced surprising refutations, allow C.5–C.7 and E.3
fixtures to remove handwritten raw-shape checks, and reduce that portion of the
symbolic counterexample space. These benefits do not guarantee a lower proof
cost: the larger exact UPLC can require more CEK fuel and solver time, which is
why both on-chain and verification costs are measured.

## Implementation stages

### S.1: Nonrecursive records and variants

- Introduce the internal test-only development gate.
- Design and implement the checked-value/field-binding representation so the
  guard and typed projections share boundary destruction.
- Generate exact tag/arity checks for datum and redeemer roots.
- Support primitive and nested nonrecursive record/variant fields.
- Fail closed with source locations for boundary shapes not yet supported by
  this slice when the internal gate is exercised.
- Add expected-change goldens and malformed-input VM tests.
- Replace handwritten raw-shape checks in a copied verification fixture and
  demonstrate the same C.5/C.6 theorem on the exact guarded artifact.

S.1 does not change the public compiler default by itself.

### S.2: Containers and optional values

- Add structurally recursive list/map/optional guards.
- Confirm duplicate map entries remain representable and validated.
- Exercise arbitrary supported nesting.
- Add size, execution-budget, Blaster fuel, and solver-time regression
  thresholds for representative C.5–C.7 profiles.

S.2 remains behind the internal development gate.

### S.3: Productive recursion

- Define the emitted recursion strategy and termination argument.
- Exercise direct, mutual, and through-container recursion.
- Reject nonproductive or unsupported cycles at source.
- Establish agreement with C.3 strict Lean codecs on bounded concrete cases
  and reviewed general properties where available.
- Complete the supported typed-boundary coverage matrix required for public
  activation.

### S.4: Default activation and project experience

- Enable strict typed datum/redeemer boundaries as the compiler default.
- Delete the internal development gate, its mode enum/field/constructor, and
  every permissive lowering branch. Do not retain a private executable legacy
  compiler for tests or evidence.
- Preserve legacy hashes, sizes, and cost measurements as immutable historical
  evidence rather than regenerating them through the current compiler.
- Update all intentionally affected goldens with reviewed classifications.
- Add migration and build output showing changed script hash, size, and budget.
- Document canonical datum re-encoding for state migration to a new script.
- Coordinate expected hash, artifact, and fixture updates in
  `bloxbean/julc-examples` and other known downstream consumers; the in-repo
  golden manifest alone is not sufficient release evidence.
- Update scaffolding and documentation to teach the single strict semantics.
- Publish the breaking preview change prominently in release notes.

## Required tests

The feature is not complete without:

- a reviewed manifest of expected changed and expected unchanged UPLC goldens;
- byte-identical UPLC when only verification annotations change;
- source/reflection checks that the completed compiler contains no executable
  legacy-boundary mode, constructor, or lowering branch;
- positive canonical datum and redeemer VM evaluations;
- wrong outer `Data` kinds;
- wrong record/variant constructor indexes;
- missing and trailing fields;
- malformed nested optional/list/map elements;
- duplicate map entries;
- malformed direct, mutual, and through-container recursive values;
- direct and nested standalone sealed-variant records rejected at source while
  the corresponding sealed-interface boundary accepts canonical variant tags;
- malformed inputs through minting and three-argument spending auto-dispatch;
- direct and nested unsupported-schema diagnostics at Java source locations;
- non-vacuous C.5–C.7 proof runs using strict compiler output where applicable;
- negative controls proving that removal or weakening of a guard is observable;
- an ordering test showing the guard rejects before any user-code trace or
  other observable validator behavior;
- large-datum and nested-container cases measured against practical ExUnit
  limits;
- representative serialized-size, execution-budget, Blaster-fuel, and
  solver-time comparisons; and
- coordinated downstream fixture/hash checks for known example repositories.

## Alternatives considered

### Keep handwritten raw-data checks

Rejected as the language-level solution. Handwritten checks are repetitive,
easy to get wrong, and disconnect the Java declaration from its actual boundary
validation. Explicit raw `Data` contracts may still implement their own schema.

### Add `@StrictDataBoundary`

Rejected. It would permanently create strict and permissive dialects of the
same Java type declaration. Exact declared-type semantics are the corrected
preview language behavior, not an optional security feature.

### Preserve permissive decoding behind a public compiler flag

Rejected. It would complicate artifact reproduction, security guidance,
verification assumptions, and support. Projects requiring an old script hash
must retain the corresponding old compiler version.

### Let verification annotations enable strict lowering

Rejected. It would violate their zero-UPLC-effect guarantee and make removing
a proof declaration change deployed behavior.

### Assume ledger inputs follow CIP-57

Rejected. CIP-57 describes schemas for tooling; it is not a ledger rule that
prevents adversarial `Data` from reaching a script.

### Weaken generated properties to match leading-field projection

Rejected. That would bless unexpected constructor tags and trailing data and
would make off-chain/schema-driven interpretations disagree with the theorem.

### Activate strict decoding after only S.1

Rejected. JuLC already admits containers, optionals, and productive recursive
boundary types. Publishing a partial checker would unnecessarily break valid
preview contracts or invite an unsound fallback. S.1–S.3 are reviewed slices;
S.4 is the single public activation point.

## Acceptance criteria

Implementation is complete when:

- the supported typed-boundary matrix has a strict checker;
- unsupported cases fail closed at their Java source locations;
- affected UPLC/hash/budget changes are measured and reviewed;
- unrelated compiler outputs remain unchanged;
- verification annotations remain UPLC-neutral;
- no public permissive fallback exists; and
- exact strict artifacts satisfy the corresponding verification profiles
  without handwritten raw-shape guards.

## Implementation outcome

S.1-S.4 are implemented in the compiler-owned lowering pipeline:

- `StrictBoundaryGenerator` emits eager checks from `PirType` and the nominal
  definition graph, including productive direct, mutual, and container
  recursion;
- `StrictRecordEntrypoint` shares root record unpacking and field bindings with
  ordinary projections, while `ValidatorWrapper` shares decoded primitive,
  list, and map roots with the entrypoint;
- every `JulcCompiler` construction path emits `strict-data-v1`; the temporary
  comparison enum, field, constructor, and permissive lowering branches have
  been deleted;
- unsupported boundary types fail at the Java parameter before PIR
  transformation;
- concrete sealed-interface variant records used independently at a boundary
  fail at the Java parameter until the compiler type model unifies their
  nominal sum tag across construction, schema emission, and checking; the
  sealed-interface boundary itself remains supported;
- CIP-57 identifies the semantics through
  `+boundary.strict-data-v1` in `preamble.compiler.version`; and
- generated verification manifests and certificates bind the classified
  boundary semantics to the exact artifact.

The executable and recorded evidence is under
[`verification/strict-boundaries`](../../verification/strict-boundaries/).
It includes the complete golden classification, large/nested CEK budget
ceilings, immutable historical legacy artifact comparisons, C.3 recursive Lean
codec agreement, and successful C.5-C.7 positive/refuted/vacuous Blaster controls.
The natural positive fixtures no longer contain handwritten datum/redeemer
root-shape checks.

The cost and golden tests consume recorded historical baselines rather than
compiling a legacy mode. `DataBoundarySemantics.LEGACY_V0` remains only as a
read-only classifier for artifacts emitted by older compiler versions; it is
not accepted by any compiler construction or lowering API.

The sibling `bloxbean/julc-examples` suite was exercised from an isolated copy
against the strict compiler: 420 tests passed with no validator-source changes.
Its separate purpose-indexed `@MultiValidator` blueprint limitation is tracked
by ADR-017 and does not alter this ADR's boundary-semantics result.

Migration and release guidance is published in the
[strict data boundary guide](../../docs/src/content/docs/guides/strict-data-boundaries.md)
and [release notes](../../docs/src/content/docs/reference/release-notes.md).
The downstream audit is recorded in
[`DOWNSTREAM-MIGRATION.md`](../../verification/strict-boundaries/DOWNSTREAM-MIGRATION.md).
