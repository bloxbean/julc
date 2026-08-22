# ADR-022: Milestones E.4e–E.4f Generic Contract Types and Collection Core

- **Status:** Implemented experimentally
- **Date:** 2026-08-21
- **Integration branch:** `feat/typed-verification-dsl-e4`
- **Milestone branch:** `feat/typed-verification-dsl-e4e-e4f`
- **Parent:**
  [ADR-016 — Typed Verification DSL and Foundational Profile Catalog](016-typed-verification-dsl-and-profile-catalog.md)
- **Prerequisites:**
  [ADR-019 — Compositional Property Promotion Core](019-milestone-e4b-compositional-property-promotion-core.md),
  [ADR-020 — Typed Rewarding DSL](020-milestone-e4c-typed-rewarding-dsl.md), and
  [ADR-021 — Typed Certifying DSL](021-milestone-e4d-typed-certifying-dsl.md)

## Context and problem

ADR-019 made the schema-3 property AST freely compositional within a closed,
reviewed operation set. ADR-020 and ADR-021 demonstrated that the same path can
grow to rewarding and certifying without adding whole-formula resolvers.

The remaining constraint is no longer formula composition. It is type and
collection breadth. The generated spending metamodel currently accepts only a
record datum whose direct fields are `byte[]` or `BigInteger`. It does not
generate typed symbolic values for:

- a redeemer or validator parameter;
- nested records or newtypes;
- sealed variants and their payloads;
- booleans, optional values, lists, maps, or strings;
- productive recursive schema types; or
- generic ledger collections beyond the purpose-specific wrappers already
  implemented.

The underlying compiler and verification codec pipeline already knows more.
`ContractSchema` and `PirType` describe records, sums, newtypes, booleans,
optionals, lists, maps, nested combinations, and productive recursion. The
generated Lean workspace can strictly decode those shapes. Re-parsing Java or
inventing a second schema model would therefore add drift precisely where
verification needs one authority.

The current flat `DslType` enum is adequate for the first prototypes but cannot
distinguish `List<A>` from `List<B>`, two nominal records with different
identities, `Optional<T>`, or duplicate-preserving maps. Extending the enum
with one constant per concrete application does not scale and would make
forged worker JSON difficult to reject authoritatively.

These milestones introduce the generic type and collection foundation before
broader transaction and value operations. They do not claim that every typed
property is automatically decidable by Blaster.

## Goals

### E.4e goals

- Generate contract-specific symbolic Java types from the compiler-owned
  `ContractSchema` for every boundary shape whose nominal identity is retained
  by that schema.
- Expose typed datum and redeemer access without parsing Java source.
- Preserve nominal record and sum identities in canonical IR. Preserve
  newtype identity once the compiler-owned schema exposes it, as described in
  the implementation prerequisite below.
- Support safe nested record selection, closed sum elimination, optional
  elimination, and productive recursive references.
- Keep malformed or absent runtime `Data` fail-closed in the generated
  property.
- Preserve schema-1, schema-2, and schema-3 canonical bytes and behavior.

### E.4f goals

- Add a generic, typed collection vocabulary for lists and raw association
  maps.
- Add the missing foundational Boolean, equality, and bounded linear-integer
  operations needed to compose useful predicates.
- Define duplicate, ordering, lookup, quantifier, and equality semantics
  explicitly.
- Validate all worker-produced structural type descriptors against
  `ContractSchema` in the parent process before Lean generation.
- Reproduce the same canonical IR and Lean for generated helpers and manually
  composed equivalents.

## Non-goals

- Expose arbitrary Lean, arbitrary Java execution semantics, or an unrestricted
  `assume` operation.
- Replace exact UPLC execution with a Java or handwritten transition model.
- Add full `Value`/multi-asset algebra; that is E.4j.
- Add reference-input, datum-witness, redeemer-map, or full output-datum
  adapters; those are E.4g.
- Add threshold authorization profiles; those are E.4h.
- Add certificate payload projections; those are E.4i.
- Add voting/proposing validator selection or invent non-standard CIP-57
  purpose names.
- Add general recursion, arbitrary folds, expression-by-expression nonlinear
  multiplication, division, modulus, or exponentiation to the property DSL.
- Expose a validator parameter as a free symbolic runtime value. A parameter
  may become a property value only after the exact artifact records and binds
  its concrete application.
- Stabilize the experimental Java DSL as a public compatibility promise.

## Current behavior

The schema-3 IR has a closed flat `DslType` enum and purpose-specific wrappers.
It supports Boolean `and`/`or`/`implies`, integer comparisons, byte equality,
signer membership, output and withdrawal `exists`, input consumption, exact
current-policy mint structure, rewarding credentials, and certificate
kind/index predicates.

`ContractMetamodelGenerator` accepts a spending record datum only when every
direct field resolves to `ByteStringType` or `IntegerType`. It exposes no typed
redeemer. Collection wrappers are concrete (`TxOutListExpr`,
`WithdrawalsExpr`, and similar) and quantifier binder names are fixed by the
wrapper rather than allocated canonically.

The compatibility inventory currently records 28 pinned capabilities as
`TYPED`, 47 as `UNSUPPORTED_IR`, 4 as `RAW_DATA_ONLY`, and 5 as
`UNSUPPORTED_SOLVER`. `TYPED` means the admitted capability has reviewed
semantics; it does not mean every nested contract type or ergonomic operation
is already available through the Java DSL.

### Implementation prerequisite discovered during E.4e audit

The current compiler deliberately erases `@NewType` to its underlying
`PirType` in `TypeResolver` and omits newtypes from
`ContractSchema.namedDefinitions`. Verification therefore cannot distinguish
two nominal newtypes with the same representation, or a newtype from that
representation, using the authorized compiler-owned schema. Inferring
newtypes from one-field records or reparsing Java would violate invariants 1
and 2.

E.4e consequently cannot recognize a source `@NewType` after compilation. It
projects the compiler-owned erased representation (for example an `Amount`
newtype over `BigInteger` appears as an integer) and makes no claim that nominal
newtype identity was retained. Forged nominal-newtype references still fail
closed because no such identity exists in `ContractSchema`. Nominal newtype
wrappers, `.value()`, and cross-newtype compatibility rules are deferred until
a separate compiler-schema ADR adds stable identity and representation metadata
to `ContractSchema`. That prerequisite can be merged independently; this
milestone does not change compiler, PIR, or UPLC behavior to obtain metadata.

## Invariants

1. `ContractSchema`/`PirType` remains the sole source-type authority for
   boundary values. Verification owns only a deterministic projection of that
   graph, never a second compiler type registry.
2. Parent-process validation re-derives every result type, field, constructor,
   container element, and nominal identity. Worker JSON is never trusted to
   declare its own valid type.
3. Existing schema-1, schema-2, and schema-3 canonical byte fixtures remain
   byte-identical. Existing nodes retain their meanings.
4. New generic operations are admitted only in explicit schema 4. Existing
   `DslPropertySet.composed(...)` continues producing schema 3.
5. A schema value is decoded strictly. Wrong constructor tag, arity, field
   shape, container shape, or recursive payload makes a guarantee using that
   value false; it cannot become an assumption or a default Java value.
6. Sum and optional payloads have no unchecked projection operation. Payload
   access occurs only through a generated predicate/eliminator that accounts
   for the constructor or presence case.
7. Lists and maps preserve input order and duplicates. No operation silently
   converts an association list to a unique-key mathematical map.
8. Quantifier binders are generated deterministically and alpha-normalized.
   Nested quantifiers cannot capture a root, helper, or another binder.
9. No property declaration, metamodel, type descriptor, or new IR node changes
   validator compilation, UPLC, script size, cost, or script hash.
10. Unsupported type, operation, solver translation, or malformed canonical
    value fails closed before any result can be promoted to `SMT-VALID`.
11. Exact UPLC success, fuel, ledger-domain predicates, and other theorem
    premises stay in the generator-owned envelope. Schema-4 user guarantees
    cannot move, duplicate, or synthesize them.
12. The DSL remains Cardano/property-specific. It does not become a raw Lean
    metaprogramming interface.

## Decision

### 1. Add explicit canonical schema 4

Add an explicit schema-4 property-set factory for the generic typed surface.
The provisional Java name is `DslPropertySet.typedV4(...)`; the implementation
may choose a clearer final name before the first schema-4 evidence is committed.

The existing `DslPropertySet.composed(...)` factory remains schema 3 so an
upgrade cannot silently change an existing specification's canonical bytes,
hash, manifest, or certificate. Schema 4 may reuse existing node meanings and
add new sealed node kinds, but schema-1–3 decoders and canonicalizers remain
frozen.

Changing the meaning of an existing operation is not an additive schema-4
change; it requires a new semantic schema version.

### 2. Project `PirType` into a structural symbolic type reference

Introduce one closed verification type-reference family capable of expressing:

- builtins: Boolean, integer, byte string, source string, unit, and raw data;
- nominal record and sum references by stable compiler identity;
- `Optional<T>`;
- `List<T>`; and
- duplicate-preserving `Map<K,V>`.

Recursive named values use stable nominal back-references rather than infinitely
expanding JSON. The property manifest binds the complete projected definition
graph and the originating contract-schema hash.

This type reference is a verification projection, not another resolver. It is
created only from the already-resolved `PirType` graph. The parent validator
looks up every nominal stable ID and recursively compares every container
application against that graph.

Legacy flat `DslType` values map deterministically to builtin type references
inside validation. That compatibility adapter does not authorize schema-4
types in a legacy property and does not rewrite legacy canonical JSON.

Source `String` and `byte[]` may share the pinned bytes wire schema, but their
symbolic type references remain distinct so Java source meaning is not erased
accidentally. An explicit reviewed coercion may be added later if required.

The type-reference family reserves a nominal-newtype variant, but parent
admission rejects it until `ContractSchema` carries authoritative newtype
metadata. It must not guess identity from shape or source names.

### 3. Generate a complete contract metamodel

Generate one symbolic Java wrapper per reachable retained nominal type.
Generated names
are conveniences; stable compiler identities in the IR are authoritative.

The schema-4 metamodel exposes:

- spending attached datum as an optional typed root;
- typed redeemer decoding for every script purpose;
- context and purpose roots already admitted by E.4a–E.4d;
- nested record fields;
- newtype value access after the compiler-schema prerequisite above is met;
- sum constructors through safe eliminators;
- optional, list, and map wrappers; and
- productive recursive references with bounded property construction.

Illustrative API shape:

```java
var contract = new AuctionModel();

var ownerAuthorized = contract.datum().exists(datum ->
        contract.context().txInfo().signatories().contains(datum.owner()));

var validAdvance = contract.redeemer().exists(redeemer ->
        redeemer.whenAdvance(advance ->
                advance.nextState().gt(contract.datum().value().state())));
```

`whenAdvance` means “the value is `Advance` and the payload predicate holds.”
It returns false for every other constructor. The generator does not emit an
unchecked `asAdvance()` that could project a payload under the wrong tag.

For a spending validator, the ledger-supplied datum is optional independently
of the Java attached-datum schema. Schema 4 represents that fact explicitly.
Using `datum().exists(...)` requires an attached, strictly decoded datum.
Absence or malformed data makes the predicate false.

A typed redeemer is similarly exposed through strict decoding. A property can
also state decoding separately when that distinction is useful. No decode
failure is converted into a zero, empty collection, or arbitrary constructor.

Validator parameter type definitions may be generated for reuse, but no
parameter-value root is admitted until exact parameter application is bound in
the artifact manifest. Schema metadata alone is not evidence of a concrete
parameter value.

### 4. Define safe nominal and recursive operations

Records support typed field selection and structural equality when all fields
have admitted equality.

Sums support:

- constructor predicates;
- `when<Constructor>(payload -> predicate)` eliminators; and
- exhaustive generated matching only when every constructor returns the same
  symbolic type.

Newtypes support explicit `.value()` projection while comparisons retain
nominal compatibility checks.

Recursive values support finite field selection and the generic collection/
optional operations defined below. Schema 4 does not introduce arbitrary
recursive Java callbacks or folds. A dedicated recursive traversal requires a
versioned combinator plus termination and solver evidence.

### 5. Complete foundational scalar operations

Add closed nodes and wrappers for:

- Boolean `true`, `false`, `not`, `and`, `or`, and `implies`;
- equality and inequality for compatible admitted scalar and nominal types;
- integer `<`, `<=`, `==`, `!=`, `>=`, and `>`;
- integer negation, addition, and subtraction; and
- multiplication by a canonical bounded integer literal where it remains
  linear for the solver.

Expression-by-expression multiplication, division, modulus, exponentiation,
implicit numeric narrowing, and silent bytes/string coercion remain rejected.

Map equality never uses the generic `.eq()` spelling. Schema 4 uses explicit
names such as `structurallyEquals` so later extensional/value semantics cannot
be confused with raw association-list equality.

### 6. Introduce generic optional and list expressions

An `OptionExpr<T>` supports:

- `isPresent()`;
- `isEmpty()`; and
- `exists(value -> predicate)`.

Optional payload projection is not available outside the guarded eliminator.

A `ListExpr<T>` supports:

- `isEmpty()` and non-empty testing;
- structural equality;
- `contains(value)` when `T` has admitted equality;
- `exists`, `all`, and `none`;
- `count(predicate)`;
- `exactlyOne(predicate)`; and
- `at(index)`, returning `OptionExpr<T>` for negative or out-of-range indices.

`filter(predicate)` may be admitted as a derived canonical operation only if
its canonicalization and solver evidence are at least as strong as the direct
quantifier forms. Arbitrary user folds are not admitted.

Quantifier construction allocates canonical binder IDs independently of Java
lambda parameter names. Canonicalization alpha-normalizes binders before
sorting, hashing, rendering, or certificate generation.

### 7. Preserve raw map semantics explicitly

An `AssocMapExpr<K,V>` is an ordered list of pairs and permits duplicate keys.
It supports:

- raw `entries()` traversal;
- `existsEntry`, `allEntries`, and `countEntry`;
- `countKey(key)`;
- `containsKey(key)`;
- `lookupFirst(key)`, returning `OptionExpr<V>`;
- `lookupAll(key)`, returning `ListExpr<V>`; and
- `structurallyEquals(other)`.

`lookupFirst` must match JuLC/Cardano model first-match behavior. `lookupAll`
and entry traversal retain duplicates. A map with `[(k,a),(k,b)]` is not
structurally equal to `[(k,a)]`, even if a first-match lookup returns `a` for
both.

Generic extensional equality, key normalization, and value summation are not
part of E.4f. Value-specific aggregation is defined in E.4j because policy and
token duplicates require domain-specific semantics.

### 8. Keep authoritative validation outside the worker

The bounded worker may construct schema-4 JSON, but the CLI parent process:

1. decodes only allow-listed sealed node and type-reference subtypes;
2. enforces the AST node/depth budgets;
3. re-derives every type and nominal stable ID from `ContractSchema`;
4. checks purpose/root/domain compatibility;
5. alpha-normalizes binders and canonicalizes commutative nodes;
6. recomputes semantic capabilities and canonical hashes; and
7. revalidates the admitted property against a fresh compiler-owned
   `ContractSchema` at workspace publication;
8. cross-checks the carried projected graph against the selected blueprint's
   names, tags, arities, field order, roots, and container shapes; and
9. renders Lean only from the admitted normalized value.

The standalone workspace runner does not reconstruct `ContractSchema`. Its
preflight authenticates the already-published canonical IR, projected graph,
plan, generated Lean, and exact artifact by their bound hashes and checks their
internal consistency. Authoritative compiler-schema and blueprint agreement is
therefore enforced before publication by the CLI, not recreated from mutable
workspace files during `verify run`.

No Java class name, field name, variant name, binder, literal, or stable ID is
copied into Lean without identifier validation and deterministic generated-name
collision checks.

### 9. Preserve the theorem envelope and result vocabulary

Schema-4 properties continue using the ADR-019 envelope:

```text
reviewed domain (optional) → exact bounded UPLC success → user guarantee
```

Each claim retains an independent non-vacuity control, proof/refutation step,
capability list, canonical guarantee/envelope hashes, and conservative
counterexample-domain qualification. Generic types do not promote an SMT result
to a kernel proof or a whole-contract safety statement.

## Affected modules and stages

- `julc-verification`
  - structural verification type references;
  - schema-4 sealed IR nodes;
  - canonicalization and alpha-normalization;
  - authoritative type/purpose validation;
  - generic symbolic wrappers;
  - complete contract metamodel generation; and
  - capability planning/tests.
- `julc-cli`
  - schema-4 worker protocol admission;
  - Lean generation for nominal types and generic collections;
  - manifest/preflight bindings;
  - metamodel CLI behavior; and
  - local/Docker/native evidence tests.
- `verification/e4e` and `verification/e4f`
  - nested contract-data fixtures;
  - generic collection semantics;
  - malformed, recursive, refuted, and vacuous controls; and
  - reproducible evidence.
- verification ADRs and user documentation.

No `julc-core`, `julc-compiler`, PIR, optimizer, ledger API, stdlib, VM, testkit,
blueprint, or ordinary annotation-processor dependency on the verification DSL
is authorized. Repository reality requiring such a change stops that part and
requires an ADR revision.

## Compatibility

- Schema-1–3 wire values, canonical bytes, hashes, renderings, and certificates
  remain frozen.
- Existing Java `.composed(...)` specifications remain schema 3.
- Schema 4 is opt-in and experimental.
- Old readers reject schema 4 and unknown node/type subtypes fail closed.
- Generated schema-4 metamodels may add source files and APIs, but never alter
  validator source or compiled artifacts.
- Adding or removing verification source must remain UPLC- and script-hash
  neutral.
- A future public stabilization may rename provisional wrappers, but cannot
  silently reinterpret a committed canonical schema.

## Implementation milestones

### E.4e.1 — Structural type projection and compatibility freeze

- Freeze schema-1–3 canonical fixtures and renderer output.
- Add schema-4 property-set admission.
- Project supported `PirType` graphs to stable structural type references.
- Bind projected definitions and `ContractSchema` hash into the manifest.
- Reject forged nominal IDs, container applications, recursive references,
  and schema-version mixing before Lean generation.

### E.4e.2 — Nested records and typed roots

- Generate wrappers for reachable records. For compiler-erased source
  newtypes, expose only the authoritative underlying representation and do not
  synthesize nominal identity until the compiler-schema prerequisite exists.
- Add nested typed field selection.
- Add explicit optional attached-datum and strict typed-redeemer roots.
- Keep parameter values unavailable unless exact application is bound.
- Prove helper/manual canonical-IR and Lean equivalence.

### E.4e.3 — Closed variants and productive recursion

- Generate constructor predicates and guarded payload eliminators.
- Reject unchecked variant projection.
- Support productive recursive nominal back-references without expanding
  canonical JSON recursively.
- Add direct, nested, mutual, optional/list/map recursive controls.

### E.4e.4 — Type evidence and integration

- Compile real Java contracts through `JulcCompiler` and blueprint generation.
- Kernel-check positive round trips and malformed rejection for every admitted
  type family.
- Run exact UPLC positive/refuted/vacuous fixtures.
- Update capability and native-image compatibility gates.

### E.4f.1 — Scalar and binder core

- Add Boolean literals/not and complete compatible equality/inequality.
- Add reviewed linear integer operations.
- Add canonical binder allocation and alpha-normalization.
- Test binder shadowing, nested quantifiers, node/depth budgets, and canonical
  idempotence.

### E.4f.2 — Generic optional and list semantics

- Add guarded optional elimination.
- Add list membership, quantifiers, count, exactly-one, and safe indexing.
- Test empty, singleton, duplicate, reordered, multiple-match, negative-index,
  and out-of-range cases.
- Verify solver behavior and fail closed on unsupported generic applications.

### E.4f.3 — Duplicate-preserving map semantics

- Add raw entry traversal, key counts, first/all lookup, and structural equality.
- Test duplicate keys/values, malformed entries, ordering, and lookup contrast.
- Prohibit implicit unique-map or extensional interpretation.

### E.4f.4 — Cross-backend evidence and documentation

- Add at least one property that was impossible in schema 3 and combines a
  nested variant, optional/list/map traversal, and an existing ledger predicate.
- Add a deliberately vulnerable validator and a vacuous validator.
- Reproduce the positive property through local, Docker, and GraalVM-native
  CLI backends with identical exact-artifact, canonical IR, property IR,
  generated Lean, pins, and bounds.
- Run affected-module tests and the full repository build.
- Document the experimental API and precise collection semantics.

## Verification strategy and required tests

### Compatibility and determinism

- byte-frozen schema-1, schema-2, and schema-3 canonical values;
- old annotation/DSL canonical and Lean equivalence fixtures;
- repeated metamodel generation and property execution produce identical
  files and hashes;
- alpha-equivalent nested lambdas canonicalize identically;
- different nominal types with identical wire shapes remain type-distinct; and
- generated-name collision rejection is deterministic.

### Type authority

- forged field result type, owner type, nominal ID, container element, optional
  payload, map key/value, and recursive reference fail in the parent process;
- renamed, missing, stale, or mismatched contract schemas fail before Lean;
- strings and bytes cannot be mixed implicitly;
- forged nominal-newtype identities fail while the compiler schema cannot
  authorize them; source newtypes currently project as their erased underlying
  representation; after the prerequisite lands, newtypes cannot be compared
  to their representation without explicit unwrap;
- parameter schema metadata cannot create a free property value; and
- worker protocol unknown fields/subtypes fail strict decoding.

### Runtime encoding and elimination

- correct and malformed record tags/arities;
- every sealed constructor and wrong-constructor payload;
- option none/some, wrong tags, and wrong arities;
- empty/nested lists and malformed list data;
- duplicate/malformed maps and ordering;
- productive direct/mutual/container recursion; and
- typed datum absence and redeemer decode failure.

### Operations and solver behavior

- complete Boolean truth controls;
- integer boundary and linear-arithmetic controls;
- list/map quantifier controls over empty, duplicate, and reordered inputs;
- safe index negative/out-of-range behavior;
- first-match versus all-match map lookup;
- structural equality versus lookup-equivalence examples;
- unsupported nonlinear/general-recursive operations rejected before rendering;
- solver timeout/unknown remains non-success; and
- generated proof scripts invoke every required kernel bridge.

### End-to-end and module boundary

- exact VM execution for positive and malformed contract data;
- non-vacuity, refuted, vacuous, and unknown classifications;
- hash-breaking tamper rejection for canonical IR, generated Lean, plan,
  inventory, and artifact files at runner preflight, plus authoritative
  schema/blueprint graph validation before workspace publication;
- local/Docker/native semantic-input hash equality;
- full `julc-verification` and `julc-cli` test reruns;
- repository-wide Gradle build; and
- no compiler/core/ledger/stdlib/blueprint source changes and byte-identical
  UPLC when only verification files change.

## Risks and mitigations

- **A second type system drifts from the compiler.** The verification type
  reference is projection-only; every value is re-derived from `PirType` and
  bound to the contract-schema hash.
- **Schema 4 breaks existing evidence.** Existing factories remain on their
  old schemas and frozen canonical fixtures gate every change.
- **Unsafe sum/optional projection invents values.** Only guarded eliminators
  expose payloads; wrong constructors and absence evaluate to false.
- **Java generics hide forged element types.** Generic signatures improve the
  authoring API, but the parent validator checks structural type references
  independently of erased Java types.
- **Nested binders capture names or hash nondeterministically.** Allocate
  canonical IDs, alpha-normalize, and reject collisions before rendering.
- **Map operations imply uniqueness.** Preserve raw entries and use explicit
  first/all/count/structural method names.
- **Generic recursion overwhelms the solver.** Admit finite selectors and
  reviewed combinators only; apply node/depth/time budgets and classify unknown
  or timeout as non-success.
- **Arithmetic becomes nonlinear.** Limit the milestone to linear operations
  and constant multiplication with solver evidence.
- **Generated APIs become prematurely stable.** Keep schema 4 experimental and
  version canonical semantics independently of Java convenience names.
- **Typed coverage is mistaken for proof coverage.** Certificates continue to
  distinguish admitted model surface from actual solver outcome.

## Alternatives considered

- **Keep adding one `DslType` enum value per concrete collection/type.**
  Rejected because nested applications and nominal identity do not scale and
  forged worker values become ambiguous.
- **Parse Java source to regenerate the type graph.** Rejected because the
  compiler-owned `ContractSchema` already provides the authority.
- **Use raw `DataExpr` for every nested value.** Rejected because it moves tag,
  arity, container, and coercion correctness back to users.
- **Generate unchecked `.asVariant()` accessors.** Rejected because payload
  projection under the wrong constructor can make a property partial or
  misleading.
- **Treat maps as unique-key Java maps.** Rejected because chain data and the
  pinned model preserve duplicate association-list entries.
- **Change `.composed(...)` to emit the latest schema automatically.** Rejected
  because a dependency upgrade would silently change canonical evidence.
- **Expose general Java streams/folds.** Rejected because arbitrary callbacks,
  termination, canonicalization, and solver semantics would no longer be
  closed and reviewable.
- **Implement value aggregation in the generic map milestone.** Rejected
  because asset aggregation needs explicit policy/token duplicate semantics
  and belongs in E.4j.

## Open questions requiring implementation evidence

1. Whether `filter` should be a canonical node or remain a derived convenience
   over direct quantifiers.
2. Whether constant multiplication should be a distinct node or normalized to
   repeated/additive linear arithmetic.
3. Which recursive traversal combinator, if any, demonstrates sufficient
   demand and solver behavior for a later ADR.
4. Whether schema-4 generated Java wrappers should live in
   `julc-verification` or a new optional module depending on it once API size is
   measured.

None permits weakening the invariants above. If evidence cannot support an
operation, that capability remains explicit `UNSUPPORTED_IR` or
`UNSUPPORTED_SOLVER` rather than being approximated.

## Implementation outcome

E.4e and E.4f are implemented as opt-in property schema 4 without changes to
`julc-core`, `julc-compiler`, `julc-ledger-api`, `julc-stdlib`,
`julc-blueprint`, PIR, optimizer, or validator lowering.

The delivered path includes:

- a closed recursive verification type-reference graph projected only from the
  selected compiler-owned `ContractSchema`;
- strict parent revalidation against a fresh `ContractSchema`, blueprint/type
  graph agreement checks, plus canonical projection/property hashes;
- generated nested record, guarded sum, optional, list, map, and productive
  recursive Java wrappers for typed datum and redeemer roots;
- closed Boolean, compatible equality, bounded linear integer, optional,
  ordered list, and duplicate-preserving association-map operations;
- deterministic binder allocation and alpha-normalization;
- schema-4 Lean rendering and kernel-reduced semantic controls;
- exact-VM positive and malformed-data controls; and
- native-image reachability metadata and a real GraalVM 25.0.2 build/run.

The E.4f evidence pins fuel 2000 and recursive depth 8. Local results are
`SMT-VALID` for the authorized fixture, `REFUTED` for a meaningful required
`Use` redeemer property over a validator that also accepts `Stop`, and
`COULD-NOT-EVALUATE/property-vacuous` for the always-failing artifact. The
positive local, Docker, and native-CLI runs bind the same:

- compiled-code SHA-256
  `ac9ec42d618be0b453781c5318c78f6871e53c1db11005caccdf702cccfbedf7`;
- Cardano script hash
  `490703f19580339f856f448a5e9f090511c7c89a7742218e47dce835`;
- canonical DSL IR SHA-256
  `67416b7be289af7e8cb7e0667ef5eabf03a83d1964dfaf97e04130a2637f1217`;
- property IR SHA-256
  `d7806aea5e03b4cc908ac59b261f5e6a59679e56e112552091d5bc318b477ec9`;
  and
- generated Lean SHA-256
  `e36d610a155772c4a4dd1837ab2cfaa11af0146858c4f2f50c555707d67f9ea1`.

The native certificate records the authenticated local proof backend, not the
launcher binary flavor or its digest. Hash equality establishes identical
semantic inputs and that the native launcher completed schema-4 reflection and
generation; it is not independent provenance for the native executable.

The pinned ledger capability inventory count is unchanged because E.4e–E.4f
add contract-data and collection IR rather than new
`CardanoLedgerApiBlaster` surface items. New `dsl.*` semantic rules are derived
and bound per claim, while the native compatibility gate enumerates every new
sealed node and type-reference class.

Validation completed with the full `julc-verification` and `julc-cli` suites,
the reproducible local and Docker E.4f driver, the GraalVM native run, exact VM
controls, `git diff --check`, and the repository-wide `./gradlew build` (with
the repository's pre-existing skipped optional E2E/plugin tasks).

## Acceptance and result claim

E.4e–E.4f are complete only when every milestone and required control above
passes, schema-1–3 evidence remains frozen, and at least one genuinely new
nested/collection property reproduces across local, Docker, and native
backends. The strongest permitted claim remains a named property of one exact
UPLC artifact within the recorded fuel, pinned model, admitted type/operation
surface, and reviewed domain. It is not a claim that all properties solve, the
contract is safe, the compiler is verified, or the pinned model is the Cardano
ledger specification.
