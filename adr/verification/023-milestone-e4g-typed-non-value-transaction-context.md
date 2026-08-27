# ADR-023: Milestone E.4g — Typed Non-Value Transaction Context

- **Status:** Implemented experimentally, manually reviewed, and integrated
- **Date:** 2026-08-21
- **Parent:**
  [ADR-016 — Typed Verification DSL and Foundational Profile Catalog](016-typed-verification-dsl-and-profile-catalog.md)
- **Prerequisites:**
  [ADR-019 — Compositional Property Promotion Core](019-milestone-e4b-compositional-property-promotion-core.md),
  [ADR-022 — Generic Contract Types and Collections](022-milestones-e4e-e4f-generic-contract-types-and-collections.md), and
  [ADR-015 — Strict On-Chain Data Boundaries](015-strict-on-chain-data-boundaries.md)
- **Pinned model:** `CardanoLedgerApiBlaster` V3 revision
  `5dab3c43f042b8735b6d067223baaa8d32ed28a1`
- **Expected milestone branch:**
  `feat/typed-verification-dsl-e4g-transaction-context`
- **Integration branch:** `feat/typed-verification-dsl-e4`

## Context and problem

E.4e and E.4f make JuLC contract data broadly usable in the verification DSL.
The generated schema-4 model can represent nested datum and redeemer records,
sealed variants, optionals, lists, duplicate-preserving maps, and productive
recursive types. Properties can compose those values with the smaller ledger
surface introduced by E.3–E.4d.

The ledger side remains incomplete. A property can inspect transaction
signers, outputs, inputs only through a consumption predicate, mint,
withdrawals, and certificate kinds. It cannot yet express ordinary claims such
as:

- a particular reference input was supplied;
- an input resolved to an output at a particular complete address;
- exactly one output continues the current script state;
- an output carries an inline datum rather than only a hash;
- an output has or lacks a reference script;
- a datum hash resolves in the transaction's datum witnesses;
- a script purpose resolves in the transaction's redeemer map;
- a transaction has the expected ID or a bounded fee; or
- an address uses a particular payment and staking credential shape.

These are foundational selections, not contract-specific profiles. Encoding
each complete formula in another annotation or template resolver would undo
the compositional design of ADR-019. Exposing the underlying values as
untyped `Data`, on the other hand, would move constructor, arity, map, and
selection semantics back to the user.

The pinned V3 model already defines the relevant types and helpers:

- `TxInInfo`, `TxOutRef`, `TxOut`, `Address`, `Credential`,
  `StakingCredential`, and `OutputDatum`;
- `txInfoInputs`, `txInfoReferenceInputs`, `txInfoOutputs`, `txInfoFee`,
  `txInfoRedeemers`, `txInfoData`, and `txInfoId`;
- `resolveInput`, `findOwnInput`, `findPubKeyInputs`, `findScriptInputs`,
  `findRedeemer`, `findDatum`, and `findDatumHash`.

Several of these collections are association lists encoded as `Data.Map`.
They preserve order and duplicate keys. The upstream lookup helpers return the
first matching entry. Treating them as Java `Map`, or calling all matching
entries equivalent to the first one, would change the modeled semantics.

This milestone completes the **scoped non-value transaction core** named by
ADR-016. “Complete” does not mean every V3 `TxInfo` field: governance data,
certificate payloads, multi-asset `Value`, validity intervals, and treasury
fields remain explicitly assigned to E.4i–E.4l.

## Goals

- Add typed, compositional traversal of ordinary and reference inputs.
- Expose `TxInInfo.outRef` and `TxInInfo.resolved`.
- Expose the current spending output reference and a fail-closed own-input
  selection.
- Expose transaction fee and transaction ID with their pinned V3 meanings.
- Expose output datum kind/payload and optional reference-script hash without
  adding value aggregation.
- Expose complete payment and optional staking credentials with guarded
  constructor payloads.
- Expose datum-witness and redeemer maps as ordered, duplicate-preserving
  association lists.
- Add reviewed first-match lookup and all-match/count operations.
- Add explicit continuing-output selection using complete address equality.
- Reuse the E.4e–E.4f binder, optional, list, map, scalar, canonicalization,
  and parent-validation foundations.
- Keep every property linked to exact UPLC and the existing reviewed
  purpose-specific ledger-domain bridge.
- Preserve all schema-1 through schema-4 canonical values and evidence.
- Keep the feature entirely in optional verification/CLI modules and retain
  zero effect on emitted validator UPLC.

## Non-goals

- Add `Value` aggregation, normalization, arithmetic, ordering, balance,
  preservation, or leakage predicates. Those belong to E.4j.
- Add certificate payload access. That belongs to E.4i.
- Add votes, proposals, governance actions, voting/proposing artifact
  selection, or governance-domain predicates. Those belong to E.4k.
- Interpret `txInfoValidRange`, `txInfoCurrentTreasuryAmount`, or
  `txInfoTreasuryDonation`. They remain `RAW_DATA_ONLY` until E.4l supplies
  reviewed adapters.
- Decode an arbitrary inline datum or witness as a user-selected Java type.
  A raw datum remains raw `Data` unless it is the contract's authoritative
  typed datum/redeemer root.
- Treat datum/redeemer maps as unique-key maps.
- Assume the own input, a continuing output, a datum witness, or a redeemer
  exists. Absence remains observable and must make a required predicate false.
- Add arbitrary Java collection callbacks, unrestricted recursion, raw Lean,
  or an unchecked `assume` operation.
- Stabilize the experimental Java DSL as a public compatibility promise.

## Current pinned semantics

The ADR is governed by the pinned Lean definitions, not by similarly named
Java runtime classes.

### Inputs and own input

`TxInInfo` is constructor `0` with exactly two fields:

1. `txInInfoOutRef : V3.TxOutRef`; and
2. `txInInfoResolved : V2.TxOut`.

`resolveInput` scans from the start and returns the first input whose output
reference equals the requested reference. `findOwnInput` first requires a
`SpendingScript` `ScriptInfo`, then applies that first-match lookup to ordinary
inputs. Duplicate references are not silently normalized by the DSL.

### Outputs and addresses

`TxOut` is constructor `0` with exactly:

1. address;
2. value;
3. output datum; and
4. optional reference-script hash.

`Address` is constructor `0` containing a payment `Credential` and an optional
`StakingCredential`. Payment credentials are either public-key or script
credentials. Staking credentials are either a nested credential or the exact
three-integer staking pointer.

Address structural equality compares both payment and staking components.
Continuing-output selection in this milestone means outputs whose **complete
address** equals the resolved own input's complete address. Comparing only the
payment credential would be a different, weaker operation and is not used
implicitly.

### Output datum

`OutputDatum` has exactly three cases:

- no output datum;
- datum hash; and
- inline raw datum.

The constructor predicate and payload access must be guarded. No operation may
project a hash from an inline datum or an inline datum from a hash.

### Datum and redeemer maps

The datum map is an ordered `List (DatumHash × Datum)` encoded as `Data.Map`.
The redeemer map is an ordered `List (ScriptPurpose × Redeemer)` encoded as
`Data.Map`. Duplicates are representable in both. `findDatum` and
`findRedeemer` return the first match.

Raw `Datum` and `Redeemer` are `Data`. E.4g may expose:

- presence/absence;
- structural `Data` equality under an explicitly named operation;
- ordered entry traversal;
- first lookup;
- all matching values; and
- key/entry counts.

It may not reinterpret raw `Data` as a contract record, a normalized map, or a
semantic datum without an independently reviewed adapter. Structural `Data`
equality compares the complete `Data` tree, including map order and duplicate
entries; it is not extensional map equality.

### Fee and transaction ID

In the pinned V3 model, `txInfoFee` is an `Integer`, not a V2 `Value`.
`txInfoId` is the raw V3 transaction-ID byte string. The DSL may compare the
fee using admitted linear integer operations and may compare transaction IDs
using a transaction-ID-specific symbolic wrapper. It does not infer ledger
validity, non-negativity, or byte length merely from those Lean types; any
such fact comes from an explicit reviewed domain or property.

## Invariants

1. The pinned `CardanoLedgerApiBlaster` capability inventory is the authority
   for ledger type, field, constructor, helper, and encoding identities.
2. Worker JSON cannot authorize a ledger type or operation. The parent process
   re-derives every ledger root, field result, constructor payload, collection
   element, and helper signature from the closed inventory.
3. Contract types continue to come only from compiler-owned `ContractSchema`.
   Ledger expansion does not introduce a second contract-schema parser.
4. Input, output, datum-map, and redeemer-map traversal preserves order and
   duplicates.
5. First-match, all-match, count, existence, and structural-equality operations
   have different canonical IR nodes or unambiguous derived definitions.
6. Constructor payloads for credentials, staking credentials, output datums,
   and script purposes are accessible only through guarded elimination.
7. Own-input selection returns an optional value and uses the pinned
   first-match `findOwnInput` semantics. It is never inserted as an assumption.
8. Continuing-output selection uses full address equality and returns a
   collection. `first`, `exists`, `exactlyOne`, and `all` remain explicit user
   choices.
9. Raw `Data` equality, if admitted after solver evidence, is explicitly
   structural. No implicit decode, normalization, or extensional relation is
   introduced.
10. Exact UPLC success remains the fixed theorem premise. No ledger adapter
    may replace exact artifact execution with a Java or handwritten model of
    the validator.
11. Ledger-validity predicates remain explicit, pinned theorem-envelope
    premises with kernel-checked inclusion bridges. New selectors do not
    silently strengthen the domain.
12. Schema-1, schema-2, schema-3, and schema-4 canonical bytes, hashes,
    renderings, and retained evidence remain valid and re-runnable.
13. Adding or removing a verification specification has zero effect on
    validator compilation, UPLC, CBOR, and script hash.
14. Unsupported model fields, purpose combinations, malformed values, stale
    capability inventories, unsupported solver translations, and unknown
    results fail closed.

## Decision

### 1. Introduce property schema 5

E.4g uses an explicit canonical property schema 5. Schema 5 includes the
schema-4 contract type/collection vocabulary unchanged and adds the reviewed
ledger-context vocabulary from this ADR.

The CLI will require an explicit selection such as:

```text
julc verify dsl-init ... --schema-version 5
```

Existing schema-4 sources do not upgrade automatically. Their canonical JSON,
semantic dependency rules, generated Lean, and certificates remain frozen.
Old readers reject schema 5 and all readers reject unknown node/type variants.

An additive Java convenience method is not itself a semantic version change.
Changing the meaning of a schema-4 operation, dependency rule, or renderer is
not permitted as part of E.4g.

### 2. Add a closed ledger type-reference family

The recursive verification type graph introduced by ADR-022 will gain a
closed ledger-model reference family. It is suitable as the element or payload
of existing generic optional/list/map types. Stable identifiers include, at
minimum:

- `TxInInfo`, `TxOutRef`, `TxId`, `TxOut`, and `Address`;
- `Credential` and `StakingCredential`;
- `OutputDatum`, `DatumHash`, `ScriptHash`, and opaque `Data`;
- the supported `ScriptPurpose` view; and
- datum/redeemer association-map entry types.

The exact Java names are provisional. Canonical identifiers are pinned to the
model capability inventory and model revision, not to generated Java class
names. Ledger aliases that share a Lean representation may receive distinct
symbolic wrappers when that distinction improves type checking, but their
representation and permitted comparisons must be declared explicitly.

A forged ledger type identifier, alias conversion, field, constructor, or
container application is rejected in the CLI parent before Lean generation.

### 3. Reuse generic typed nodes for ordinary structure

Records, guarded variants, optionals, lists, association maps, equality,
quantifiers, count, and safe lookup reuse the schema-4 structural node
families wherever their semantics are identical. E.4g does not add one node
per getter merely to render a field projection.

Dedicated closed nodes are allowed only where the pinned helper has semantics
not captured by an ordinary projection or collection combinator, including:

- current spending output reference;
- `findOwnInput`/`resolveInput` first-match selection;
- public-key/script input filtering;
- continuing-output selection by complete address;
- datum/redeemer first-match lookup; and
- any explicit typed-to-`Data` comparison bridge admitted by this ADR.

Every dedicated node receives a capability ID, parent validator rule,
canonicalizer rule, semantic dependency, renderer case, native-image entry,
and positive/negative controls.

### 4. Expose inputs and reference inputs uniformly

`TxInfo.inputs()` and `TxInfo.referenceInputs()` return the same typed ordered
list abstraction. Each element exposes:

```java
input.outRef()
input.resolved()
```

The list supports the E.4f operations, including `exists`, `all`, `none`,
`count`, `exactlyOne`, safe indexing, and structural ordering-sensitive
equality where admitted.

Reviewed helpers have explicit names and preserve order:

```java
inputs.resolve(ref)                 // first match: Optional<TxInInfo>
inputs.forPaymentKey(keyHash)       // all matching inputs, in input order
inputs.forScript(scriptHash)        // all matching inputs, in input order
inputs.consumes(ref)                // existing existence predicate
```

No helper claims uniqueness. A separate `.count(...)` or `.exactlyOne(...)`
must be used where uniqueness is a security requirement.

### 5. Expose own input and continuing outputs only for spending

The spending model exposes the current spending output reference and own input:

```java
contract.currentOutputRef()
contract.ownInput()                 // Optional<TxInInfo>
```

These roots are rejected for minting, rewarding, and certifying properties.

Continuing outputs are derived from a successfully resolved own input and full
address equality:

```java
contract.continuingOutputs()        // ordered List<TxOut>
```

If the script purpose is not spending or the own input cannot be resolved,
the selection is empty. Requiring a continuing output therefore fails rather
than inventing one. The property author chooses whether zero, one, or many
matches are acceptable.

### 6. Add complete address and output-metadata elimination

Typed output access includes:

```java
output.address()
output.value()              // existing value surface; no new E.4g algebra
output.datum()
output.referenceScript()
```

Addresses expose payment credential and optional staking credential. Each
credential sum exposes constructor predicates and guarded payloads. A staking
pointer exposes its three integer components only in its guarded branch.

Output datum exposes `none`, `hash`, and `inline` cases using guarded
eliminators. Reference script is a typed optional script hash. Convenience
predicates such as `hasInlineDatum()` or `hasReferenceScript()` must lower to
the same canonical IR as the corresponding guarded optional/variant formula.

### 7. Keep witness/redeemer maps raw, ordered, and explicit

The DSL presents transaction datum witnesses and redeemers as typed
association maps backed by the pinned raw list representation. Operations
include:

- ordered entry traversal;
- `containsKey`;
- `countKey` and `countEntry`;
- `lookupFirst`;
- `lookupAll`; and
- structural ordered equality.

For redeemer keys, schema 5 recognizes all six pinned `ScriptPurpose`
constructors so a valid V3 context remains representable. Payload access is
admitted only for already supported purpose surfaces. Voting and proposing
keys may be recognized and compared structurally by their complete encoded
key if solver evidence permits, but E.4g does not expose their governance
payloads or authorize voting/proposing validator verification.

If full `ScriptPurpose` translation is not supported by the pinned solver, the
implementation must retain typed map traversal while limiting constructed
lookup keys to the currently selected supported purpose. It must record the
remaining capability as `UNSUPPORTED_SOLVER`; it must not erase voting or
proposing entries or approximate them as another purpose.

### 8. Admit raw-Data structural equality only with explicit evidence

Schema 4 deliberately rejects raw `Data` equality. E.4g may introduce a new,
explicitly named structural relation after all of these pass:

- definitional Lean controls for every `Data` constructor;
- map ordering and duplicate-entry controls;
- solver positive, refuted, and timeout/unknown controls;
- parent rejection of cross-type or implicit coercion; and
- canonical serialization/hash tests.

If those controls do not pass reliably, raw datum/redeemer payloads remain
opaque values supporting presence and transport only. E.4g is not permitted
to weaken equality or deserialize them as contract types to make a fixture
solve.

### 9. Preserve the existing theorem envelope and domain bridges

E.4g adds expressions inside a guarantee. It does not add a new domain or
purpose. Each property continues to have the form:

```text
reviewed solver-domain(ctx)
  && exact-UPLC-succeeds(ctx, datum/redeemer, recorded fuel)
  -> user guarantee(ctx, typed contract roots)
```

For a ledger-valid claim, the generated per-claim corollary must still compile
and connect the pinned `valid{Purpose}Context` predicate to the reviewed
solver-domain superset. If a new field or helper cannot be translated under
that domain, the result is `COULD-NOT-EVALUATE` or the capability remains
unsupported. The domain is never silently weakened to make the property pass.

### 10. Keep module boundaries unchanged

Expected production changes are limited to:

- `julc-verification` for type references, IR, generated models, validation,
  semantic dependencies, and rendering;
- `julc-cli` as the composition root and for native-image metadata;
- `verification/e4g` for reproducible evidence; and
- verification ADRs/guides.

No E.4g implementation belongs in `julc-core`, `julc-compiler`,
`julc-ledger-api`, `julc-stdlib`, `julc-blueprint`, PIR, optimizer, or ordinary
validator lowering. If repository reality requires one of those modules to
change, implementation stops and this ADR is revised before proceeding.

## Illustrative user surface

Names remain provisional, but the milestone should make a property of this
shape possible without Lean:

```java
public PropertySet properties(ReferenceGateModel contract) {
    var datum = contract.datum();
    var tx = contract.context().txInfo();

    var matchingReference = tx.referenceInputs().exactlyOne(input ->
            input.outRef().id().eq(datum.oracleTxId())
                    .and(input.outRef().index().eq(datum.oracleIndex()))
                    .and(input.resolved().address().paymentCredential()
                            .matchesKeyHash(datum.oracleKey()))
                    .and(input.resolved().datum().isInline()));

    var oneContinuation = contract.continuingOutputs().exactlyOne(output ->
            output.datum().isInline()
                    .and(output.referenceScript().isAbsent()));

    var witnessPresent = tx.datums().containsKey(datum.expectedDatumHash());

    return PropertySet.typed(
            property("reference-gate.context-shape",
                    matchingReference
                            .and(oneContinuation)
                            .and(witnessPresent)
                            .and(tx.fee().ge(integer(0)))));
}
```

The fixed theorem envelope supplies exact execution and the chosen reviewed
ledger domain. The user formula does not receive movable
`exactUplcSucceeds` or `validSpendingContext` nodes.

This example does not claim payment or value preservation. Those claims need
E.4j operations and separate properties.

## Capability inventory changes

E.4g must audit and update the machine-readable inventory rather than merely
changing status counts. At minimum it reviews:

- `field.txInfo.referenceInputs`;
- `field.txInfo.fee`;
- `field.txInfo.redeemers`;
- `field.txInfo.data`;
- `field.txInfo.id`;
- `field.txInInfo.outRef` and `field.txInInfo.resolved` generic DSL access;
- output/address/credential/staking/output-datum/reference-script fields and
  constructors, adding missing inventory entries;
- current spending-reference and own-input selection;
- `helper.findPubKeyInputs`;
- `helper.findScriptInputs`;
- `helper.findRedeemer`;
- V2 `findDatum`/`findDatumHash` used by the pinned V3 context; and
- `resolveInput`/`findOwnInput` semantics.

A capability moves to `TYPED` only after its exact canonical IR, renderer,
parent validation, and semantic controls exist. A Java getter alone is not
`TYPED` support.

## Implementation milestones

### E.4g.1 — Schema-5 compatibility and ledger type authority

- Freeze schema-1–4 canonical JSON, semantic dependencies, Lean, and retained
  re-verification fixtures.
- Add explicit schema-5 admission and strict unknown-version rejection.
- Add the closed ledger type-reference family and recursive container use.
- Expand the capability inventory with the missing fields, constructors, and
  helpers in scope.
- Parent-validate every ledger root/type/field/payload against the inventory.
- Add native-image sealed-subtype compatibility gates.
- Prove that verification-only source changes remain UPLC-neutral.

### E.4g.2 — Inputs, output metadata, and credentials

- Add ordinary/reference-input generic traversal and `TxInInfo` projections.
- Add transaction fee/ID and `TxOutRef` ID/index access.
- Add current spending reference and optional own-input resolution.
- Add output datum/reference-script access.
- Add complete address, credential, staking credential, and pointer guarded
  elimination.
- Add public-key/script input filters and explicit first/all/count semantics.
- Kernel-check encodings, constructors, wrong tags/arities, optionals, and
  malformed nested values.

### E.4g.3 — Witness maps, redeemer maps, and continuing outputs

- Add datum/redeemer ordered association-map wrappers.
- Add first/all/count/contains and ordered structural operations.
- Add supported current-purpose redeemer lookup without erasing unsupported
  purpose entries.
- Decide raw-`Data` structural equality strictly by the evidence gate above.
- Add own-input-address-based continuing-output selection.
- For helpers expressible through the admitted generic core, demonstrate
  helper/manual canonical-IR and Lean equivalence. Give pinned ledger helpers
  that deliberately have no generic formula one canonical node and
  kernel-reduced semantic controls instead.
- Test absent, duplicate, reordered, malformed, and multiple-match cases.

### E.4g.4 — Exact execution, solver evidence, and documentation

- Add a real spending validator/property using reference inputs, resolved
  outputs, complete credentials, output datum metadata, and at least one
  witness/redeemer-map operation.
- Add authorized, vulnerable/refuted, malformed-context, ambiguous-selection,
  and vacuous controls.
- Reproduce the positive property with local, Docker, and GraalVM-native CLI
  launchers using identical semantic input hashes.
- Kernel-check every required ledger-domain corollary from the executable
  verification plan.
- Profile CEK fuel and solver time; record the smallest reviewed non-vacuous
  bounds with margin rather than copying an older milestone's fuel.
- Update getting-started, DSL, capability, trust-boundary, and migration docs.
- Run affected-module tests, the full Gradle build, native compilation, and
  the reproducible E.4g evidence driver.

## Verification strategy and required tests

### Compatibility and determinism

- byte-frozen schema-1–4 canonical property fixtures;
- successful re-verification of retained E.4e/E.4f schema-4 workspaces;
- deterministic ledger type IDs, generated names, binder IDs, dependency
  plans, and generated Lean;
- repeated generation produces identical hashes;
- unknown schema-5 types, fields, helpers, constructors, and nodes fail before
  any process runs; and
- capability revision/signature drift fails the compatibility gate.

### Input and selection semantics

- empty, singleton, duplicate, reordered, and multiple-matching ordinary and
  reference inputs;
- `resolve`/own-input first-match behavior with duplicate references;
- missing own input returns none and required selection fails;
- public-key filter excludes script credentials and vice versa;
- staking credentials do not affect payment-credential input filters;
- safe negative/out-of-range list indexing; and
- input helpers equal their manually composed canonical formulas and Lean.

### Address and output semantics

- full address equality distinguishes different staking credentials;
- public-key and script credential guarded payloads;
- staking-hash and staking-pointer guarded payloads;
- every staking-pointer component and integer boundary;
- output datum none/hash/inline positive and wrong-constructor controls;
- optional reference script none/some and malformed controls;
- continuing outputs: zero, one, many, reordered, same payment credential but
  different staking credential, and unresolved own input; and
- wrong record tag/arity or nested constructor shape is rejected.

### Datum/redeemer map semantics

- empty, singleton, duplicate-key, duplicate-entry, reordered, and malformed
  map controls;
- first lookup contrasts with all lookup and count;
- datum hash miss/presence and duplicate witness behavior;
- supported current-purpose redeemer hit/miss;
- voting/proposing entries are not dropped or reinterpreted;
- structural `Data` equality, if admitted, distinguishes constructor, list
  order, map order, and duplicate entries; and
- unsupported raw-data inspection fails before Lean generation.

### Theorem and result honesty

- positive exact UPLC property under the recorded domain and fuel;
- deliberately vulnerable validator produces `REFUTED` with retained raw
  model;
- always-failing validator produces
  `COULD-NOT-EVALUATE/property-vacuous`;
- malformed exact VM contexts reject;
- solver timeout/unknown is never promoted to success;
- all generated domain corollaries are invoked by hash-bound scripts, not only
  generated on disk;
- certificates retain conservative counterexample-domain and concrete-VM
  reproduction flags; and
- local/Docker/native runs bind identical exact artifact, canonical DSL IR,
  property IR, generated Lean, model pins, and bounds.

### Module boundary and regression

- no compiler/core/ledger/stdlib/blueprint production source changes;
- annotation and DSL property source remains byte-neutral to UPLC;
- existing annotation profiles and schema-1–4 DSL tests remain green;
- `:julc-verification:test` and `:julc-cli:test` rerun from scratch;
- repository-wide `./gradlew build` passes, with skipped optional tasks listed;
- GraalVM native image builds and executes schema-5 reflection; and
- `git diff --check` passes with the milestone staged independently of
  unrelated working-tree files.

## Compatibility and migration

- Schema 5 is opt-in and experimental.
- Existing schemas do not auto-upgrade and their canonical bytes do not
  change.
- Existing high-level annotations continue lowering through their current
  reviewed schemas and have no dependency on E.4g.
- Generated schema-5 Java APIs may evolve before public stabilization, but a
  committed canonical schema-5 operation cannot silently change meaning.
- Stale schema-5 workspaces fail hash/preflight checks and require explicit
  regeneration; they are never partially migrated in place.
- Capability inventory changes may alter the inventory hash and therefore
  require evidence regeneration. That is an evidence change, not an on-chain
  script change.

## Risks and mitigations

- **A flat ledger-type list grows into a second unreviewed model.** Ledger
  references are closed IDs pinned to the capability inventory; the DSL does
  not restate arbitrary upstream definitions.
- **Association maps are mistaken for Java maps.** APIs and docs say
  `lookupFirst`, `lookupAll`, `countKey`, and `structuralEquals`; tests retain
  duplicates and order.
- **Continuing output is selected too weakly.** The canonical helper compares
  complete addresses and returns all matches; uniqueness is explicit.
- **Own-input resolution hides malformed/ambiguous input lists.** It returns
  the pinned first match, while count/uniqueness remain separate properties.
- **Guarded payload access becomes unchecked projection.** Variant/optional
  eliminators reuse schema-4 parent validation and wrong-case tests.
- **Raw datum access becomes arbitrary schema casting.** Raw values remain
  opaque `Data`; only reviewed structural operations are admitted.
- **Governance sneaks in through redeemer-map keys.** All entries remain
  representable, but governance payload access and voting/proposing artifact
  selection remain fail-closed.
- **New context terms exceed old fuel bounds.** E.4g profiles both exact CEK
  and solver time and records new bounds; under-fueling remains non-success or
  a bounded claim, never an unqualified proof.
- **Typed surface is mistaken for ledger validity.** The certificate separates
  operation admission, solver result, exact execution bound, domain predicate,
  and kernel bridge.
- **Verification work affects compiler output.** Module-boundary and
  byte-neutrality tests gate the milestone.

## Alternatives considered

- **Continue adding fixed annotations.** Rejected because input/output
  selection is foundational composition, not one security theorem.
- **Expose the whole `ScriptContext` as raw `Data`.** Rejected because users
  would manually reproduce tags, arities, map encodings, and helper semantics.
- **Treat association lists as unique Java maps.** Rejected because duplicate
  and ordering behavior is observable in the pinned model.
- **Make `findOwnInput` or one continuing output an assumption.** Rejected
  because existence and uniqueness are security-relevant conclusions.
- **Match continuing outputs by payment credential only.** Rejected because it
  ignores staking credentials and differs from the established stateful
  profile's full-address semantics.
- **Add value aggregation while traversing outputs.** Rejected because value
  duplicate/normalization semantics require their own E.4j review.
- **Add governance payload types because `ScriptPurpose` contains them.**
  Rejected because purpose selection, solver/model support, and CIP-57
  vocabulary remain unresolved.
- **Widen schema 4 in place.** Rejected because schema-4 semantic dependencies
  and retained E.4f evidence have now been reviewed; schema 5 provides a clean
  compatibility and admission boundary.

## Resolved implementation decisions

1. `LedgerTypeRef` is a closed subtype of the existing
   `VerificationTypeRef` graph. Containers, binders, canonicalization, and
   validation are therefore shared rather than duplicated.
2. Raw `Data` payload equality remains unexposed. Witness and redeemer payloads
   support opaque transport, presence, and collection traversal only. Distinct
   reviewed byte aliases such as transaction IDs, datum hashes, script hashes,
   public-key hashes, and currency symbols are explicit identity bridges and
   cannot be invented for arbitrary `Data`.
3. Pinned selection operations are canonical `LedgerHelperNode` variants.
   This keeps `resolveInput`, `findOwnInput`, payment/script input filters,
   current purpose, continuing outputs, and Lovelace projection bound to one
   reviewed meaning and one capability rule.
4. Schema 5 does not add a general filter node. Existing closed list
   quantifiers/counts plus the reviewed input/continuing-output helpers cover
   this milestone without admitting arbitrary collection callbacks.
5. All six pinned `ScriptPurpose` constructors are representable as map keys
   and have kind recognizers. Voting/proposing payloads remain opaque and no
   voting/proposing artifact or ledger-domain adapter is added.
6. The E.4g.3 helper-equivalence rule is intentionally bifurcated. A
   convenience with an admitted generic formula must produce equivalent
   canonical IR and Lean. A pinned helper whose semantics cannot be assembled
   from the public generic core receives one closed canonical node plus direct
   kernel-reduced controls. Requiring a synthetic "manual" second
   implementation for the latter would duplicate semantics rather than test
   equivalence. This is an explicit implementation decision, not a relaxation
   of the parent-validation or semantic-evidence gates.

## Implementation outcome

All four E.4g phases are implemented as opt-in inner DSL schema 5 and
certificate-facing `julc.dsl-ledger/v1` property schema 3. No production source
changed in `julc-core`, `julc-compiler`, `julc-ledger-api`, `julc-stdlib`, or
`julc-blueprint`.

The delivered surface includes:

- a parent-validated closed ledger type authority, field/constructor graph,
  helpers, byte aliases, and purpose/domain gates;
- ordered ordinary/reference inputs, resolved outputs, output references,
  transaction ID and integer fee;
- complete addresses, payment/staking credentials, output datum kinds,
  optional reference scripts, own-input resolution, and continuing outputs by
  full address equality;
- duplicate-preserving datum/redeemer association maps with first/all/count,
  list, and structural collection operations while raw payloads remain opaque;
- deterministic canonical schema-5 IR, generated Java wrappers, Lean
  rendering, native-image metadata, and capability-inventory bindings;
- kernel-reduced ledger-context controls for first-match resolution, filters,
  complete-address distinction, strict output-datum constructors, missing own
  input, continuing-output selection, complete `ScriptInfo.toScriptPurpose`
  conversion, duplicate-preserving `ScriptPurpose` lookup, and preservation of
  opaque voting-purpose keys; and
- an exact-VM fixture that accepts canonical V3 context data, rejects malformed
  observed list/constructor/option/map shapes, and retains duplicate witness
  entries.

The reproducible evidence uses fuel 5000 and recursive depth 8. The authorized
fixture establishes five independent properties for the complete current
spending purpose, reference-output shape, non-negative fee, datum-witness
presence, and redeemer-witness presence. The
vulnerable fixture is `REFUTED`; the always-failing fixture is
`COULD-NOT-EVALUATE/property-vacuous`. Local, Docker, and GraalVM-native CLI
positive runs bind identical semantic inputs:

- compiled-code SHA-256
  `dba4f675fbea805a059fe169193d86d2549659fe6672bf9e8f1617d42aece0fe`;
- Cardano script hash
  `71e5d1370ececbc071dfc5664068db8380b6d2a02d85120bca825078`;
- canonical DSL IR SHA-256
  `ed8edb0477b42fb2f76e47d1dae59fb913285c09ba675c056d486558fbb08bba`;
- property IR SHA-256
  `5a0bca10d0d74bba854268e8aa969246e43b71a7035bf7c719926c604e25b7c5`;
  and
- generated Lean SHA-256
  `142bc962f243bb49b481178bd6199b559fdfe1141f57469bf4a86ec2ad143508`.

The native certificate records the authenticated local proof backend, not a
native-launcher digest; identical hashes establish semantic-input identity,
not independent launcher provenance. The schema-4 E.4f workspaces were
regenerated because the pinned capability inventory intentionally changed;
their previously reviewed DSL, property, and Lean hashes remained unchanged,
and the retained authorized workspace re-verifies with the current runner.

Validation includes fresh affected-module suites, the reproducible E.4g local
and Docker drivers, the GraalVM 25.0.2 native build and positive run, schema-4
re-verification, exact-VM controls, and the repository-wide build recorded by
the final milestone review.

## Acceptance and permitted claim

E.4g is complete only when all four implementation milestones and the full
test matrix pass, schema-1–4 evidence remains valid, and one genuinely new
transaction-context property reproduces through local, Docker, and native CLI
launchers with identical semantic hashes.

The strongest permitted result remains:

> The named generated property of the exact recorded UPLC artifact was
> established by the pinned Blaster/Z3 path under the recorded purpose-specific
> domain, fuel, recursion depth, solver bounds, and admitted schema-5
> transaction-context semantics.

It is not a claim that all transaction-context properties are decidable, that
raw data was semantically decoded, that the pinned model is the Cardano ledger
specification, that JuLC is compiler-verified, or that the contract is safe.
