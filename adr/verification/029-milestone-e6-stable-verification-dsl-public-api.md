# ADR-029: E.6 stable verification DSL and annotation convergence

- **Status:** Accepted; implemented on feature branch, awaiting review
- **Date:** 2026-08-23
- **Integration branch:** `feat/typed-verification-dsl-e4`
- **Schema-reset branch:** `feat/verification-dsl-schema-v1`
- **Governing roadmap:** [ADR-016](016-typed-verification-dsl-and-profile-catalog.md)

## Context

E.1 through E.4l established a closed Java property language, deterministic
canonical IR, compiler-owned contract metamodels, purpose-specific Cardano
ledger expressions, exact-artifact obligations, and hash-bound result
certificates. E.5 deliberately did not graduate: bounded temporal reasoning over
an exact UPLC transition relation failed its calibration gate.

The E.4 API is still described as experimental, however, and the original
annotation profiles are not uniformly implemented through the same semantic
path:

- `@ControlledMint` already lowers to canonical DSL IR and the generated Lean
  predicate is rendered from that IR;
- `@RequiresSigner` has a canonical lowering helper, but workspace generation
  still emits a separate handwritten Lean predicate; and
- the combined `@RequiresSigner`, `@PreservesValue`, and `@Monotonic` stateful
  profile still has only a profile-specific property record and handwritten
  Lean predicate.

Declaring both annotations and the DSL stable while retaining parallel theorem
definitions would permit semantic drift. Stability also needs to say which Java
types are supported construction APIs, which JSON versions remain readable,
and what is *not* promised by a successful verification run.

## Decision

JuLC declares the reviewed E.4 typed verification DSL a stable public API at
API version 1 with canonical DSL schema version 1. E.5 state-machine work is
excluded.

The E.1-E.4 implementation used numeric schemas 1 through 10 as internal
milestone feature gates. None was released. Before merge, JuLC resets the
complete reviewed language to the only public canonical format:

```json
{
  "format": "julc.verification.dsl",
  "schemaVersion": 1
}
```

The explicit format identifier prevents an old experimental milestone document
whose numeric version happened to be `1` from being interpreted as the public
schema. Experimental schemas 1 through 10 are removed from the public reader,
generator, CLI selector, and compatibility contract. Historical certificates
remain records of the development runs that produced them, but their embedded
experimental DSL payloads are not supported public replay inputs.

Stable means:

1. Java source compatibility is maintained for the documented builder and
   generated-metamodel surface within API version 1.
2. Canonical `julc.verification.dsl` schema-1 meanings and serialization are
   frozen. Additive or changed semantics require a new public property schema.
3. `@RequiresSigner`, `@ControlledMint`, and the complete stateful annotation
   profile lower to canonical DSL IR before Lean generation. Profile-specific
   CLI wording, proof names, and certificate classification may remain, but the
   security guarantee itself must be rendered from the canonical IR.
4. The stable construction surface is the expression wrappers,
   `VerificationDsl`, `VerificationSpecification`, `DslProperty`,
   `DslPropertySet`, purpose/domain enums, and compiler-generated metamodel.
   Raw `dsl.ir` node constructors are serialization infrastructure, not a
   supported user construction API.
5. `julc verify dsl-init` generates public schema 1. The unreleased milestone
   schemas are neither selectable nor accepted by the public reader.
6. The bounded worker remains a trusted-source boundary: it executes project
   Java. Stable API does not mean sandboxed untrusted code.
7. Stable semantics does not promise solver termination, ledger-validity
   coverage beyond the selected domain, or that a validator is generally
   “safe.” Certificates continue to qualify the exact artifact, property,
   domain, bounds, backend inputs, and outcome.

“No handwritten annotation theorem” does not mean the trusted backend contains
no Java templates that emit Lean imports, shared helper definitions,
obligations, or runner modules. Those templates implement the DSL backend. It
means no annotation owns a second profile-specific security formula whose
meaning can drift from its canonical DSL guarantee.

## Explicit invariants

- No E.6 change may alter compiler lowering or emitted UPLC.
- Annotation and direct-DSL forms of the same guarantee have byte-identical
  canonical guarantee IR and byte-identical rendered Lean predicates.
- Canonical IR contains no arbitrary Java, Lean, or shell fragments.
- Property construction remains closed to admitted node types and pinned
  ledger helpers.
- Unsupported purposes, raw payloads, invalid type graphs, malformed binders,
  and unknown schemas fail closed before Lean generation.
- A profile-specific theorem generator must not redefine its guarantee outside
  canonical DSL IR.
- Exact-artifact, contract-schema, property-IR, generated-Lean, runner-plan,
  and capability-inventory bindings remain intact.

## Stable public surface

### Supported

- `VerificationSpecification`
- `VerificationDsl` literal and property factories
- typed expression wrappers in `com.bloxbean.cardano.julc.verification.dsl`
- `DslProperty`, `DslPropertySet`, `DslPurpose`, and `DslDomain`
- generated API-v1/schema-1 contract metamodel source
- `julc verify dsl-init` and `julc verify dsl`
- the four annotation profiles as concise frontends over the same IR

Generated metamodel source is reproducible build output. Its public methods are
stable for a fixed compiler-owned contract schema and DSL API version, while
generated class/type names remain deterministically derived from that schema.

### Not supported as a direct construction API

- concrete classes in `verification.dsl.ir` other than the property envelope
  and documented enums;
- renderer, validator, promotion, worker-protocol, and semantic-dependency
  internals;
- arbitrary Lean expressions;
- user-defined AST node kinds; and
- the rejected E.5 temporal prototype.

These types may remain Java-public where Jackson, generated source, or module
composition requires it. Public visibility alone is not an API-stability
promise.

## Annotation convergence

### `@RequiresSigner`

Resolve the annotation against `ContractSchema`, project the contract types,
construct the typed signer guarantee, canonicalize and validate it, bind the
canonical IR and projected-type hash into the annotation property record, and
render `SecurityProperty.lean` through `TypedPropertyLeanRenderer`.

### `@ControlledMint`

Retain the existing canonical minting lowering. E.6 adds an explicit invariant
test that generator input, canonical JSON, and rendered Lean are shared with
the stable DSL form.

### `@PreservesValue` + `@Monotonic`

The complete stateful profile remains an all-or-nothing profile. Its guarantee
is lowered to typed public schema-1 IR containing:

- strict current datum and redeemer decoding;
- authority membership in the complete signatory list;
- exactly one full-address continuing output;
- structural preservation of the own-input value;
- strict decoding of the inline successor datum;
- preserved authority;
- successor state equal to the redeemer commitment; and
- strict state increase.

A general reviewed strict-`Data` decode expression is added to the DSL rather
than a stateful-only escape hatch. It accepts a `Data` expression, a
compiler-projected target type, and a typed predicate; malformed data makes the
predicate false. No raw Lean or user-selected decoder name enters the IR.

Public schema 1 also includes a closed `whenSingleton` list eliminator. It matches only a
one-element list and binds that element to a typed predicate. The stateful
profile uses it for the continuing-output invariant instead of encoding the
same meaning as recursive `count == 1` plus `at(0)`. This preserves the direct
`[successor]` Lean shape and avoids an unnecessary solver-performance
regression without admitting general pattern syntax.

## Compatibility

No external compatibility promise applies to the experimental milestone
schemas because they were never released. Their numeric progression is removed
rather than exposed as public history. New users receive the complete reviewed
E.4 surface as schema 1, and `dsl-init` no longer exposes `--schema-version`.

Historical milestone ADRs and certificates may retain their original schema
numbers as provenance. They are not accepted as current canonical DSL input.

Annotation source syntax, property IDs, CLI exit codes, and certificate outcome
classes remain unchanged. Adding canonical DSL/type bindings to annotation
property records is an additive property-IR change with compatibility
constructors/read handling where retained evidence requires it.

## Affected modules

- `julc-verification`: API marker/version, annotation lowerings, strict decoded
  data node, validation/canonicalization/rendering, API contract tests.
- `julc-cli`: common annotation rendering path, schema-1 generation, stable CLI
  wording, hash/certificate integrity checks, generation tests.
- `verification`: stable getting-started documentation and retained convergence
  evidence where hashes change.
- `adr/verification`: roadmap status and this decision.

No compiler, core, ledger API, stdlib, blueprint, or VM implementation changes
are authorized by E.6.

## Milestones

### E.6.1 — Audit and freeze

- inventory public DSL entry points and direct-IR leakage;
- publish API version and current stable property schema constants;
- document supported versus internal surfaces;
- freeze schema-1 canonical fixtures and generated metamodel signatures.

### E.6.2 — Annotation convergence

- make required-signer generation consume canonical typed DSL IR;
- make the stateful profile consume canonical typed DSL IR;
- retain and strengthen controlled-mint equivalence tests;
- reject any canonical/profile metadata disagreement before publication.

### E.6.3 — Stable CLI and guide

- make public schema 1 the only `dsl-init` output;
- remove “experimental” from the stable E.4 command and guide wording;
- keep prominent trusted-source, solver-bound, and certificate-scope caveats;
- provide annotation and direct-DSL examples that state their equivalence.

### E.6.4 — Evidence and release gate

- run focused verification and CLI suites;
- run the repository build;
- prove zero compiler/UPLC source impact by diff scope and existing byte
  identity tests;
- review generated Lean for all annotation profiles;
- record any solver or evidence regeneration limitation without promotion.

## Verification strategy

Tests must cover:

- stable API/version constants and canonical schema 1;
- frozen schema-1 canonical JSON and metamodel output;
- annotation-versus-DSL canonical JSON equality;
- annotation-versus-DSL rendering through the same Lean backend path;
- strict decoded-data success and malformed/wrong-type rejection;
- singleton-list empty/one/many semantics, canonicalization, and schema gate;
- stateful profile positive and adversarial compositions;
- tampered annotation canonical IR/type projection rejection;
- experimental milestone schemas rejected by the public reader;
- native-image reachability for any newly serialized node;
- unchanged annotation CLI result meanings; and
- repository-wide build success.

## Risks

- Freezing too much implementation detail would make safe evolution expensive.
  The supported surface is therefore explicit and narrower than all Java-public
  classes.
- A new decode node could become a raw type escape hatch. Parent validation
  must re-derive its target from the compiler-owned projection.
- Regenerating annotation evidence may change property/Lean hashes even when
  semantics are intentionally identical. Such drift must be explained and
  evidence regenerated before claiming parity.
- Solver incompleteness may be mistaken for API instability. Semantic/API
  stability and automated proof completion are separate claims.

## Alternatives rejected

- **Keep the DSL experimental indefinitely.** E.4 now has multiple purposes,
  deterministic IR, exact evidence, malformed controls, and independent
  review; withholding a supported expert API no longer reflects reality.
- **Stabilize annotations only.** This prevents composition and preserves the
  semantic duplication E.6 is intended to remove.
- **Stabilize every Java-public IR class.** Visibility is partly required by
  serialization and generated source; treating it all as supported API would
  freeze implementation machinery.
- **Retain handwritten annotation predicates and test them for equivalence.**
  Tests can regress or miss a branch. One canonical guarantee IR is the stronger
  architectural invariant.
- **Include E.5.** Its exact-artifact temporal calibration failed the accepted
  exit gate and its product prototype was removed.

## Open questions

None for API version 1. Additional Cardano surface, voting/proposing exact
selection, parameter-derived authorities, and any future temporal verification
require separate ADRs and property-schema versions.

## Implementation outcome

The pre-release schema reset is implemented on
`feat/verification-dsl-schema-v1`.

- `VerificationDslApi` declares Java construction API version 1 and canonical
  property schema 1. No older canonical DSL schema is readable.
- Canonical payloads carry `format: "julc.verification.dsl"` so the new schema
  1 cannot be confused with the former experimental schema 1.
- `julc verify dsl-init` emits schema 1 and no longer exposes a schema-selection
  option. The user guides distinguish the supported builder/metamodel surface
  from serialization internals.
- `@RequiresSigner` and the complete stateful annotation profile now carry
  canonical schema-1 DSL plus compiler-projected contract types. Workspace
  generation re-derives both from the typed profile fields and rejects a
  mismatch before writing Lean.
- `@ControlledMint` retains its canonical closed minting DSL lowering and its
  annotation/direct-DSL identity test. The former profile-specific required-
  signer, stateful-spending, and controlled-mint Lean guarantee methods were
  removed; annotation guarantees are rendered from DSL IR.
- Strict inline-`Data` decoding and exact singleton-list elimination are closed
  schema-1 operations. Both are type-authorized by the parent,
  canonicalized, rendered without raw Lean input, and included in native-image
  reachability metadata.
- No compiler, core, ledger API, stdlib, blueprint, or VM source changed. The
  existing annotation-neutrality tests continue to assert byte-identical UPLC.

Verification completed for the reset:

- `:julc-verification:test --rerun-tasks`: 89 tests, zero failures;
- `:julc-cli:test --rerun-tasks`: 433 tests, zero failures;
- repository-wide `./gradlew build`: successful (213 tasks in the recorded run);
- documentation production build: 28 pages generated successfully;
- GraalVM 25.0.2 `:julc-cli:nativeCompile`: successful, followed by native
  `dsl-init` generation reporting API v1/schema 1 with no schema selector;
- current generated spending, minting, rewarding, and certifying metamodels
  compile with the migrated E.3/E.4 specifications; and
- all retained E.4 reproduction scripts parse without the removed
  `--schema-version` option and assert canonical schema 1 on regeneration.

The capability-inventory hash changes because its remaining historical
schema-5 wording was replaced with the canonical schema-1 policy. This is a
documentation-only capability note change. Existing retained certificates
remain historical hash-bound records; they are intentionally not replayable as
current canonical DSL inputs and must be regenerated for a current result.

The schema-reset release gate does not claim that every historical SMT run was
re-solved. JVM/native model generation, authoritative admission, rendering,
runner integrity, compiler UPLC-neutrality, and repository integration were
rerun. Historical solver certificates remain provenance under the explicit
pre-release migration decision above.

One bounded-solver residual is retained explicitly. Re-running the C.6
missing-signer refutation did not complete within a ten-minute review ceiling,
even after restoring the direct singleton-list theorem shape. The process was
stopped without a certificate and no classification was promoted or changed.
This is solver incompleteness/performance, not evidence of a counterexample to
the authorized theorem; the API and guide continue to make non-termination an
explicit non-guarantee.
