# ADR-027: Milestone E.4l — Reviewed Raw-Data Adapters

- **Status:** Proposed
- **Date:** 2026-08-22
- **Parent:**
  [ADR-016 — Typed Verification DSL and Foundational Profile Catalog](016-typed-verification-dsl-and-profile-catalog.md)
- **Prerequisites:**
  [ADR-019 — Compositional Property Promotion Core](019-milestone-e4b-compositional-property-promotion-core.md),
  [ADR-022 — Generic Contract Types and Collections](022-milestones-e4e-e4f-generic-contract-types-and-collections.md),
  [ADR-023 — Typed Non-Value Transaction Context](023-milestone-e4g-typed-non-value-transaction-context.md),
  [ADR-025 — Certificate Payloads and Value Algebra](025-milestones-e4i-e4j-certificate-payloads-and-value-algebra.md), and
  [ADR-026 — Typed Governance Transaction Data](026-milestone-e4k-typed-governance-transaction-data.md)
- **Pinned proof model:** `CardanoLedgerApiBlaster` V3 revision
  `5dab3c43f042b8735b6d067223baaa8d32ed28a1`
- **Integration branch:** `feat/typed-verification-dsl-e4`
- **Expected milestone branch:**
  `feat/typed-verification-dsl-e4l-raw-data-adapters`

## Context and problem

After E.4k, the typed verification DSL covers contract-owned types, ordinary
transaction context, authorization, certificate payloads, value algebra, and
most governance transaction data. Four fields in the pinned V3 model remain
deliberately represented as raw `Data`:

| Capability | Pinned field type | Current classification |
|---|---|---|
| transaction validity range | `txInfoValidRange : Data` | `RAW_DATA_ONLY` |
| current treasury amount | `txInfoCurrentTreasuryAmount : Data` | `RAW_DATA_ONLY` |
| treasury donation | `txInfoTreasuryDonation : Data` | `RAW_DATA_ONLY` |
| proposal governance action | `ppGovernanceAction : Data` | `RAW_DATA_ONLY` |

E.4k already adds a narrow strict decoder for the outer governance action, but
keeps its `ChangedParameters` and `Quorum` payloads opaque. The other three raw
transaction fields have no typed DSL projection.

Raw storage in the Lean model does not mean that JuLC may guess a type from a
Java class and cast the value. It means a reviewed adapter is required. That
adapter must state:

- the exact accepted `Data` shapes;
- the distinction between malformed, absent, and present values;
- whether duplicates and order remain observable;
- which validity rules are structural and which are ledger assumptions;
- whether the pinned proof model actually enforces those rules; and
- what a successful solver result is permitted to claim.

The treasury fields expose a particularly important model boundary. JuLC's V3
ledger API encodes them as the usual optional integer:

```text
Some n -> Constr 0 [I n]
None   -> Constr 1 []
```

The pinned Lean `validTreasuryAmount` and `validTreasuryDonation` helpers,
however, attempt to decode the raw field directly as an `Integer`. A failed
integer decode is treated as the amount not being specified. Consequently,
those helpers do not distinguish canonical absence from arbitrary malformed
data and do not establish JuLC's strict optional encoding. E.4l must expose
this difference; it must not hide it inside a stronger solver domain.

Validity intervals have a different situation. The pinned model keeps the
field at `Data`, but `V2.validTxRange` uses the pinned strict
`IsData POSIXTimeRange` decoder and then checks that the decoded range is not
empty. This makes a reviewed typed adapter and a kernel bridge possible, with
one subtlety: the pinned decoder accepts either Boolean closure value on an
infinite bound even though its encoder emits `true`. Decoder-valid and
canonical-encoding predicates must therefore remain distinct.

Finally, `ChangedParameters` and `Quorum` are raw aliases in the pinned proof
model. A comment describes changed parameters as a non-empty, ascending
integer-keyed map, while quorum is described only as a rational kept at the
`Data` level. E.4l may add useful typed views only for semantics supported by
pinned source and explicit codec controls. Heterogeneous protocol-parameter
values remain opaque unless a later milestone reviews each parameter's schema.

## Decision summary

E.4l will add opt-in property schema 10 containing a closed family of reviewed
raw-data adapters. It will start with:

1. pinned-decoder and canonical-encoding views of the transaction validity
   interval;
2. a strict three-state optional-integer view for current treasury amount and
   treasury donation;
3. an integer-key index view of `ChangedParameters` that preserves raw map
   order and duplicates while never exposing heterogeneous values; and
4. a strict structural numerator/denominator view of `Quorum`, only if the
   implementation audit confirms the encoding against pinned primary sources.

Every adapter is selected by a closed enum/type and is parent-validated against
an exact permitted raw field. No node accepts an arbitrary type name, Lean
definition, decoder name, field path, or raw expression supplied by user code.

The base raw-data fields do not automatically become fully typed merely
because one adapter exists. The capability inventory will distinguish the raw
storage field from each reviewed adapter. A field moves from `RAW_DATA_ONLY`
to `TYPED` only when the supported DSL view accounts for all distinctions
required by that field. Otherwise the field remains `RAW_DATA_ONLY` and a
separate `adapter.*` capability becomes `TYPED`.

No new theorem assumption is silently added. In particular, strict optional
treasury well-formedness is a property or an exact-validator consequence, not
an implication of the current pinned `validTreasuryAmount` helper.

## Goals

- Add a closed schema-10 raw-adapter IR and generated Java API.
- Make validity-interval properties such as contains, entirely-before,
  entirely-after, and interval inclusion expressible without raw `Data` or
  Lean.
- Preserve the pinned distinction between decoder validity and canonical
  interval encoding.
- Represent treasury optional integers with three observable states:
  malformed, canonical absence, and canonical presence.
- Make malformed treasury data fail a strict property rather than count as
  absence or zero.
- Expose changed-parameter IDs, raw order, duplicate counts, and an explicit
  canonical-key-order predicate without exposing heterogeneous parameter
  values.
- Expose quorum numerator and denominator only after its exact structural
  encoding is independently pinned; do not infer normalization or unit-range
  validity from the Java type name.
- Compose every adapter with the existing Boolean, arithmetic, option,
  collection, contract-type, authorization, value, certificate, and
  governance DSL operations.
- Bind adapter versions, model discrepancies, exact artifact, canonical IR,
  generated Lean, domain, fuel, and dependency revisions into the result.
- Preserve schema-1-through-9 canonical bytes and behavior.
- Keep the verification implementation outside compiler/runtime modules and
  leave emitted UPLC byte-identical.

## Non-goals

- Expose general `Data` constructor inspection, arbitrary casts, or raw Lean.
- Claim every raw V3 field is now semantically typed.
- Interpret heterogeneous changed-parameter values.
- Assign meanings to protocol-parameter IDs without a separately pinned CDDL
  and per-parameter schema table.
- Normalize, reorder, or deduplicate a changed-parameter map.
- Claim that a structurally decoded quorum is normalized, positive, reduced,
  or in the unit interval unless the property checks an explicitly reviewed
  predicate.
- Treat malformed treasury data as `None`, zero, or a successful decode.
- Quietly strengthen a Blaster domain with canonical raw-data assumptions.
- Change pinned `CardanoLedgerApiBlaster` definitions to make a proof easier.
- Add voting/proposing artifact selection or non-standard CIP-57 purposes.
- Add a generic user-extensible decoder registry.
- Change `julc-core`, `julc-compiler`, `julc-ledger-api`, `julc-stdlib`, the
  blueprint generator, validator CBOR, script hashes, sizes, or execution
  costs.
- Stabilize the experimental DSL as a public compatibility promise.

## Current behavior

The canonical capability inventory exposes the four raw fields above only as
`RAW_DATA_ONLY`. Generic schema-4 equality deliberately rejects raw `Data`, so
a property cannot compare or destructure them by escaping through ordinary
typed nodes.

E.4k's `actionStrict()` is a reviewed exception for the outer proposal action:
it strictly decodes one raw field into a guarded `GovernanceAction` view. The
`ParameterChange` and `UpdateCommittee` guards deliberately provide no Java
projection for changed parameters or quorum. That boundary remains in force
until the relevant E.4l adapter completes its evidence gate.

The ordinary JuLC ledger API has typed Java `Interval`,
`Optional<BigInteger>`, and `Rational` classes. Those classes define JuLC's
on-chain/off-chain encoding and are valuable conformance inputs, but they are
not by themselves authority for a Lean theorem. Verification support is
authorized by the pinned proof model, reviewed ledger encoding sources, and
executable semantic controls together.

## Authoritative sources and conflict policy

E.4l must pin and compare three source layers:

1. the exact `CardanoLedgerApiBlaster` Lean revision used by the generated
   workspace;
2. JuLC's ledger API encoding and strict decoder tests; and
3. the exact Plutus V3/Conway encoding source or CDDL revision used to justify
   any claim described as ledger encoding rather than JuLC encoding.

The implementation report records file names, definitions, and revision IDs.
If these sources disagree, JuLC does not choose the most convenient meaning.
It must do one of the following:

- retain the capability as `RAW_DATA_ONLY`;
- expose a narrowly named model-specific or JuLC-encoding-specific view and
  record the mismatch in the certificate; or
- update the pinned proof model in a separate dependency-review change with
  regenerated evidence.

An implementation may not silently patch generated Lean to disagree with the
pinned dependency while still naming that dependency as the theorem model.

## Pinned validity-interval semantics

The candidate interval adapter mirrors the pinned `V1.Time` decoder. The raw
shape is:

```text
POSIXTimeRange := Constr 0 [lowerBound, upperBound]
bound          := Constr 0 [extendedBound, closure]
extendedBound := Constr 0 []       -- negative infinity
               | Constr 1 [I time] -- finite
               | Constr 2 []       -- positive infinity
closure        := pinned IsData Bool
```

Both lower and upper bounds use the same raw bound representation. Their Lean
types are distinct because ordering at equal finite times interprets closure
direction differently.

The pinned decoder:

- rejects a wrong outer kind, tag, or arity;
- rejects malformed finite times or closure Booleans;
- accepts negative or positive infinity in either lower or upper position;
- accepts either decoded closure Boolean for an infinite bound; and
- discards that closure value when constructing the typed infinite bound.

The pinned encoder always writes `true` for an infinite-bound closure.
Therefore schema 10 defines two distinct predicates:

- **decoder-valid** — the exact pinned `fromData` accepts the value; and
- **canonical encoding** — re-encoding the decoded value is byte-for-byte
  structurally equal to the original raw `Data`.

`validTxRange` establishes decoder validity and non-emptiness. It does not, by
itself, establish canonical encoding. Certificate language and helper names
must preserve this distinction.

The typed view exposes at least:

- lower and upper bound variants;
- finite time and inclusive/exclusive closure under a finite guard;
- pinned `isEmpty`;
- pinned `contains(time)`;
- pinned `includes(other)`;
- pinned entirely-before and entirely-after relations; and
- explicit decoder-valid/canonical predicates.

Convenience names must say whether a threshold itself is included. Ambiguous
names such as `after` may exist only if their exact inclusive semantics are
documented and kernel-controlled.

## Strict treasury optional semantics

Schema 10 represents each treasury raw field as a closed three-state value:

```text
Malformed
Absent
Present(Integer)
```

The strict candidate decoder is:

```text
Constr 0 [I n] -> Present(n)
Constr 1 []    -> Absent
anything else -> Malformed
```

This is intentionally not a flattened `Option<Integer>` because flattening
would make malformed and absent indistinguishable. The Java API must support
guarded elimination and predicates equivalent to:

```java
var donation = context.treasuryDonationStrict();

property("donation-is-well-formed",
        donation.isWellFormed());

property("present-donation-is-positive",
        donation.whenPresent(amount -> amount.gt(integer(0))));

property("donation-is-canonically-absent",
        donation.isAbsent());
```

The exact method names remain experimental. Their semantics do not:

- `isAbsent()` is true only for exact canonical `None`;
- `isMalformed()` is true only when strict decoding fails;
- `isWellFormed()` is true for canonical `Some` or canonical `None`;
- a guarded present-value predicate is false when absent or malformed unless
  its explicitly named combinator states another behavior; and
- no operation supplies a hidden zero/default value.

The pinned `validTreasuryAmount` and `validTreasuryDonation` definitions do
not prove this three-state decoder is well formed. E.4l must include a kernel
control demonstrating the difference and must not add strict treasury
well-formedness to the solver domain unless a separate kernel theorem derives
it from an explicitly pinned ledger premise.

## Reviewed governance payload adapters

### Changed-parameter IDs

The pinned model aliases `ChangedParameters` to `Data` and documents a map
whose keys are parameter IDs and whose values are heterogeneous parameter
values. Schema 10 may expose a reviewed index view with these rules:

- the outer value must be `Data.Map`;
- every key must be `Data.I`;
- raw entry order and duplicate keys are preserved;
- the adapter exposes IDs, count, first/all ID occurrence, non-emptiness, and
  an explicit strictly-ascending-unique predicate;
- the heterogeneous values remain opaque and cannot be compared, cast,
  serialized into Lean text, or returned as a general `DataExpr`; and
- the view does not call the map valid merely because its keys decode.

The canonical map predicate is separate from structural decoding. This lets a
property state the exact requirement it needs and prevents the adapter from
silently importing the comment's invariant as a theorem assumption.

### Quorum pair

The pinned Lean model aliases `Quorum` to raw `Data`; it does not define a
typed decoder or rational validity predicate. JuLC's ledger API currently
encodes `Rational` structurally as `Constr 0 [I numerator, I denominator]`.
E.4l will promote a quorum view only after the primary-source audit confirms
that shape for the selected ledger/model contract.

If promoted, the first view is structural:

- exact constructor tag 0 and arity 2;
- two integer payloads in numerator/denominator order;
- malformed data produces decode failure;
- structural equality remains distinct from mathematical rational equality;
- no automatic reduction, sign normalization, or denominator default; and
- any positivity or unit-interval rule is an explicit named predicate with
  its own source and kernel controls.

If exact rational constraints cannot be grounded, the quorum payload remains
opaque in E.4l. Partial success for interval and treasury adapters does not
authorize calling quorum typed.

### Base governance-action classification

Even if the changed-parameter index and quorum pair adapters are promoted,
`ppGovernanceAction : Data` remains visibly raw at the pinned model boundary.
The inventory records the outer strict action adapter and each payload adapter
separately. Heterogeneous parameter values are still raw, so E.4l does not
claim complete typed governance-action coverage.

## Explicit invariants

1. **No general raw-data escape hatch.** A raw field can be consumed only by a
   closed reviewed adapter registered for that exact parent field.
2. **Three-state optionals.** Malformed treasury data is never collapsed into
   absent, present zero, or solver success.
3. **Pinned-decoder honesty.** Mirroring a pinned decoder includes its accepted
   non-canonical cases; stricter canonicality is a separately named predicate.
4. **No hidden domain strengthening.** Adapter well-formedness is never added
   to a theorem premise merely to make a property provable.
5. **No invented ledger semantics.** Java wrapper names do not establish proof
   meaning when the pinned model keeps a value raw.
6. **Guarded payload access.** Finite bounds, present treasury amounts,
   changed-parameter indexes, and quorum fields are available only after the
   corresponding strict guard/decoder.
7. **Order and duplicates remain observable.** Raw maps are never normalized
   into Java map semantics.
8. **Opaque values stay opaque.** Changed-parameter values cannot re-enter the
   generic expression graph as raw `Data`.
9. **Purpose compatibility remains closed.** These transaction fields compose
   only under the four exactly selectable purposes; schema 10 does not enable
   voting/proposing validator selection.
10. **One canonical semantics.** Java wrappers, canonical IR, Lean definitions,
    semantic controls, and certificate metadata are derived from the same
    adapter identifier and version.
11. **Parent validation is authoritative.** Worker output is revalidated
    against exact field identity, type, purpose, schema version, and capability
    inventory before Lean generation.
12. **Tampering fails closed.** Runner preflight binds adapter inventory,
    canonical DSL IR, derived property IR, generated Lean, and exact artifact.
13. **Old schemas stay frozen.** Schema-1-through-9 canonical values and
    generated meanings do not change.
14. **No on-chain effect.** Schema-10 support changes neither validator
    compilation nor emitted UPLC.
15. **Evidence precedes promotion.** A raw capability moves to `TYPED` only
    after codec, malformed-data, kernel, parent-validation, and exact-artifact
    evidence required by this ADR exists.

## Java DSL shape

The following is illustrative, not a frozen API:

```java
public PropertySet properties(GeneratedContract contract) {
    var ctx = contract.context();
    var deadline = contract.datum().deadline();

    var time = property("deadline-covered",
            ctx.validityRangeStrict().exists(range ->
                    range.contains(deadline)
                         .and(range.decoderValid())));

    var treasury = property("donation-present-and-positive",
            ctx.treasuryDonationStrict().isWellFormed()
                    .and(ctx.treasuryDonationStrict()
                            .whenPresent(amount -> amount.gt(integer(0)))));

    var parameters = property("only-reviewed-parameter-ids",
            ctx.proposals().all(proposal ->
                    proposal.actionStrict().exists(action ->
                            action.whenParameterChange(change ->
                                    change.changedParameterIdsStrict()
                                            .exists(ids -> ids.isStrictlyAscending())))));

    return PropertySet.schema10(time, treasury, parameters);
}
```

The generated API should avoid forcing users to understand raw constructor
tags. It must still make malformed and non-canonical outcomes expressible
where they affect the theorem.

## Canonical IR and schema evolution

Schema 10 adds only sealed, explicitly registered nodes and type identities.
A likely shape is a small `ReviewedDataAdapterNode` keyed by a closed enum,
plus guarded field/eliminator nodes for the adapter result types. A family of
separate nodes is also acceptable if it is clearer. In either design:

- an adapter kind cannot be supplied as an arbitrary string;
- parent raw-field identity and result type are fixed by the validator;
- no raw payload is rendered directly;
- every node participates in canonicalization, dependency planning, node
  budgeting, strict JSON decoding, native-image metadata, and integrity
  re-derivation; and
- schema 9 and earlier reject the new node/type identities before Lean
  generation.

Provisional result identities are:

- `POSIX_TIME_RANGE`;
- `LOWER_BOUND` and `UPPER_BOUND`;
- `STRICT_OPTIONAL_INTEGER_STATE`;
- `CHANGED_PARAMETER_ID_INDEX`; and
- `QUORUM_INTEGER_PAIR`, if promoted.

Names may change during implementation review, but the representation
distinctions may not be merged for convenience.

The property certificate records at least:

- `propertySchemaVersion: 10`;
- every adapter identifier and semantic version used by each claim;
- decoder mode (`PINNED_DECODER`, `CANONICAL_ENCODING`, or an explicitly named
  JuLC encoding where necessary);
- raw-field capability inventory hash;
- known pinned-model discrepancies relevant to the claim;
- exact artifact, canonical DSL IR, derived property IR, and generated Lean
  hashes; and
- domain, fuel, recursion depth, dependency revisions, and backend.

## Lean lowering and domain rules

Generated Lean definitions must be total and structurally recursive. Decode
failure is represented as data (`Option` or a closed decode-state type), not as
an axiom, `panic`, fabricated default, or proof assumption.

Validity helpers should reuse the pinned `V1.Time` definitions where their
semantics are exactly the desired operation. JuLC-owned wrappers may make
decode/canonical state explicit, but semantic duplication requires equality
controls against the pinned helper.

The interval domain bridge must kernel-check at least:

```text
V2.validTxRange raw = true
  -> exists range, IsData.fromData raw = some range
                   and Time.isEmpty range = false
```

It must not conclude canonical re-encoding unless separately proved.

There is no corresponding treasury bridge from the current pinned helpers to
the strict optional decoder. The generated theorem envelope must therefore do
one of the following honestly:

- prove strict treasury shape from exact validator acceptance;
- require it as the user-visible guarantee being checked;
- use a separately reviewed, kernel-bridged ledger premise; or
- return/refute/leave undetermined without weakening the property.

Changed-parameter key order and quorum validity follow the same rule: they are
properties, not invisible assumptions, unless a pinned premise and kernel
bridge establish them.

## Milestones

### E.4l.1 — Encoding audit and closed schema 10

- Pin the exact Lean, JuLC ledger API, and Plutus/Conway source definitions for
  every candidate adapter.
- Add an executable audit table containing accepted shapes, rejected shapes,
  canonical forms, and semantic constraints.
- Record the treasury helper/strict-optional mismatch explicitly.
- Add schema-10 codec/admission, node/type inventory, native metadata, and
  frozen schema-1-through-9 fixtures.
- Add exact parent-field/purpose validation and reject adapter use on any other
  raw `Data` source.
- Do not change capability status in this phase.

**Exit gate:** the audit identifies an authoritative encoding and honest claim
vocabulary for each promoted adapter. Unresolved candidates remain
`RAW_DATA_ONLY` without blocking independently complete adapters.

### E.4l.2 — Validity-interval adapter

- Add strict pinned decoding of range, lower/upper bounds, extended bounds,
  finite time, and closure.
- Add decoder-valid and canonical-encoding predicates as different nodes.
- Add contains, includes, empty, entirely-before, and entirely-after semantics.
- Add kernel equality controls against pinned `V1.Time` helpers.
- Add the `validTxRange` kernel bridge without overclaiming canonicality.
- Add Java admission, invalid-parent, malformed, composition, and exact-VM
  tests.
- Promote the interval adapter capability only after all controls pass.

**Exit gate:** a meaningful exact-artifact time property is established or
refuted without raw data, and malformed/non-canonical cases remain visible.

### E.4l.3 — Treasury strict optional adapters

- Add the three-state strict optional integer result and guarded elimination.
- Add separate current-treasury and donation adapter identities even if their
  codec implementation is shared.
- Add exhaustive Some/None/malformed tag/arity/kind controls.
- Add a kernel negative control demonstrating that the current pinned treasury
  validity helper is not a strict optional decoder.
- Add a real exact-VM fixture that reads these fields with JuLC's ledger API
  encoding.
- Attempt a non-tautological exact-artifact theorem without adding a hidden
  well-formedness domain. Record `REFUTED`, `UNDETERMINED`, or solver limits
  honestly if that is what the pinned model supports.

**Exit gate:** properties can distinguish present, absent, and malformed
treasury fields, and the certificate states the model discrepancy.

### E.4l.4 — Narrow governance raw-payload adapters

- Add the changed-parameter ID index view and canonical-key predicate.
- Keep heterogeneous values inaccessible and duplicate/order behavior explicit.
- Add the quorum structural pair only if E.4l.1 pins the encoding; otherwise
  retain it as a documented fail-closed deferral.
- Wire guarded access only under E.4k's matching `ParameterChange` and
  `UpdateCommittee` action guards.
- Add kernel controls for correct, malformed, empty, reordered, duplicate,
  and non-integer-key parameter maps.
- If quorum is promoted, cover wrong tag/arity/kind, zero/negative denominator,
  unreduced pairs, and structural-versus-mathematical equality distinctions.
- Keep `ppGovernanceAction` base storage and untyped parameter values visibly
  raw in the inventory.

**Exit gate:** every newly `TYPED` adapter has exact semantic controls; any
unresolved raw payload remains inaccessible and accurately classified.

### E.4l.5 — Evidence, compatibility, and documentation

- Add positive, vulnerable, malformed, and vacuous exact-artifact fixtures.
- Include at least one non-tautological validity-range theorem and one strict
  treasury calibration.
- Exercise novel composition with existing contract fields and at least one
  authorization, value, or governance operation.
- Run JVM, Docker, and native CLI evidence and compare all semantic hashes.
- Run affected-module tests, repository-wide build, and exact VM controls.
- Regenerate retained workspaces whose capability-inventory hashes changed, or
  document additive historical-certificate drift under the established policy.
- Update `verification/GETTING_STARTED.md`, capability coverage, limitations,
  and certificate interpretation.

**Exit gate:** local, Docker, and native evidence agree on semantic inputs;
the docs explain exactly which raw fields are typed, partially adapted, or
still opaque.

## Required test matrix

### Validity interval

- finite inclusive and exclusive lower/upper bounds;
- negative and positive infinity in both positions;
- canonical infinite closure and decoder-valid non-canonical closure;
- wrong outer kind, tag, arity, finite payload, and closure encoding;
- empty, singleton-point, bounded, half-infinite, universal, and reversed
  ranges;
- contains at the point immediately before, at, and immediately after each
  boundary;
- includes equality, strict containment, overlap, and disjointness;
- pinned helper/manual equivalence; and
- a negative bridge control that canonicality does not follow merely from
  decoder validity.

### Treasury fields

- exact `Some(negative)`, `Some(0)`, `Some(positive)`, and `None`;
- wrong tag, wrong arity, trailing fields, wrong outer kind, and non-integer
  Some payload;
- malformed is not absent and is not present zero;
- current amount and donation cannot be accidentally interchanged;
- exact JuLC ledger API encoding round trips through the semantic controls;
- the pinned `validTreasury*` mismatch is executable and documented; and
- solver/domain behavior never promotes malformed-as-absence.

### Changed parameters

- empty map;
- one and multiple integer IDs;
- ascending, descending, and reordered entries;
- duplicate IDs with equal and unequal opaque values;
- non-integer keys and wrong outer kind;
- IDs and counts remain available while values cannot be projected; and
- use outside a guarded `ParameterChange` fails admission.

### Quorum, if promoted

- structurally valid numerator/denominator pairs;
- zero and negative denominator;
- negative numerator, numerator greater than denominator, and unreduced pair;
- wrong tag, arity, and payload kind;
- structural equality versus any reviewed mathematical equality; and
- use outside a guarded `UpdateCommittee` fails admission.

### Cross-cutting

- schema-1-through-9 canonical bytes remain frozen;
- strict JSON rejects unknown nodes/fields and version mixing;
- arbitrary raw `Data`, decoder names, Lean identifiers, and helper names are
  rejected before Lean generation;
- parent/type/purpose/capability tampering fails before any process runs;
- node count, binder depth, timeout, process-output, and admission scans still
  apply;
- JVM, Docker, and native semantic hashes match;
- exact validator UPLC is byte-identical with and without DSL sources; and
- compiler, ledger, stdlib, blueprint, and ordinary build behavior do not
  regress.

## Evidence plan

The retained E.4l evidence should contain at least:

1. **Authorized time validator — `SMT-VALID`.** Exact validator acceptance
   implies a non-tautological property involving a strictly decoded validity
   range and a contract-owned deadline.
2. **Vulnerable time validator — `REFUTED`.** The same property is checked
   against an artifact that omits or weakens the time condition.
3. **Treasury calibration.** A validator/property pair that exercises strict
   `Present`/`Absent` behavior without assuming it from the pinned treasury
   helper. The recorded result may be valid, refuted, or undetermined, but its
   interpretation must be explicit.
4. **Malformed controls.** Exact VM and Lean controls for every adapter, with
   malformed data rejected by the adapter rather than mapped to a default.
5. **Vacuous validator — `COULD-NOT-EVALUATE/property-vacuous`.** No theorem is
   promoted from an artifact with no successful execution under the recorded
   bound.
6. **Governance calibration.** Changed-parameter/quorum adapter semantics are
   kernel-controlled; solver evidence is required only for capabilities moved
   to automated `TYPED` support rather than `UNSUPPORTED_SOLVER`.

Counterexamples remain conservatively qualified to the recorded Blaster
domain unless separate kernel/VM evidence establishes ledger validity.

## Affected modules

Expected changes are limited to:

- `julc-verification` — schema-10 AST/types, wrappers, validator,
  canonicalization, dependency inventory, and adapter semantics;
- `julc-cli` — schema-10 metamodel generation, Lean rendering, workspace,
  runner/certificate metadata, native-image reachability, and tests;
- `verification/e4l` — fixtures, exact-VM/kernel/solver controls, evidence, and
  scripts;
- `verification/GETTING_STARTED.md`; and
- verification ADRs/capability documentation.

The following must not depend on or change for E.4l:

- `julc-core`;
- `julc-compiler`;
- `julc-ledger-api`;
- `julc-stdlib`;
- `julc-blueprint`;
- validator compilation/lowering; and
- emitted UPLC and script hashes.

If the encoding audit discovers a real defect in `julc-ledger-api` or the
pinned proof model, fix it in a separate ADR/PR. E.4l must not bundle an
on-chain semantic change into verification-only work.

## Compatibility

Schema 10 is opt-in. Existing annotation, schema-1-through-9 DSL, build,
blueprint, and verification workflows keep their canonical meaning.

The capability inventory will change as adapters are promoted. Historical
certificates remain hash-bound records under their old inventory revision.
Generated workspaces are not forward-compatible caches: a current CLI may
require regeneration after additive inventory or rule changes. Every such
drift must be documented; it must not be presented as evidence invalidation
when the recorded certificate and its bound inputs remain unchanged.

No compatibility promise is made yet for Java schema-10 method names. The
representation distinctions and fail-closed behavior in this ADR are the
compatibility-critical semantics.

## Risks and mitigations

### A typed wrapper is mistaken for ledger validity

Mitigation: separate structural decode, canonical encoding, semantic validity,
and theorem-domain predicates; record each in canonical IR and certificate
metadata.

### Treasury malformed data becomes absence

Mitigation: use a closed three-state result and include executable mismatch
controls against the pinned helper.

### Canonical interval encoding is inferred from decoder acceptance

Mitigation: expose separate predicates and a negative kernel control for
infinite-bound closure.

### Heterogeneous changed parameters reopen raw-data access

Mitigation: expose only decoded integer IDs and structural metadata. Values
never become generic property nodes.

### Rational semantics are invented

Mitigation: require a pinned primary-source audit; begin with structural pair
semantics; defer normalization/unit-range claims independently.

### Stronger domains make proofs vacuous or ledger-relative without disclosure

Mitigation: keep adapter well-formedness out of the fixed theorem envelope;
require non-vacuity and record every reviewed domain premise.

### Solver cost grows on nested decoders

Mitigation: kernel-control codec semantics separately, calibrate one adapter at
a time, record fuel/time, and classify bounded solver failure honestly.

### Capability status overstates partial support

Mitigation: inventory raw fields and reviewed adapters separately; a partial
payload index does not turn heterogeneous raw values into typed values.

### Generated Java, IR, Lean, and certificate meanings drift

Mitigation: derive them from one closed adapter inventory, hash all semantic
artifacts, and re-derive integrity at runner preflight.

## Rejected alternatives

### Expose a generic `DataExpr`

Rejected because it permits tag/arity logic, ambiguous casts, raw equality,
and a path toward arbitrary Lean outside the reviewed IR.

### Reuse Java ledger types as proof authority

Rejected because Java encoding classes are not the pinned Lean theorem model
and can disagree with it, as the treasury helper boundary demonstrates.

### Treat any failed treasury integer decode as `None`

Rejected because malformed and canonical absence have different security
meaning and actual wire shapes.

### Make strict raw-field shape a hidden domain assumption

Rejected because a stronger premise can turn a false property into a valid
one while changing the certificate's ledger interpretation.

### Canonicalize changed-parameter maps before properties see them

Rejected because order and duplicates exist at the raw boundary and the pinned
invariant must be checked, not manufactured.

### Expose changed-parameter values as untyped objects

Rejected because heterogeneous values require a parameter-ID-indexed schema;
an object wrapper would merely rename raw `Data`.

### Model quorum as a Java floating-point number

Rejected because floating point is not the ledger representation, loses exact
rational meaning, and has no faithful Lean/UPLC semantics here.

### Promote every adapter together or none at all

Rejected because interval, treasury, changed-parameter, and quorum semantics
have independent authority and evidence. Unresolved quorum semantics must not
block an honest interval adapter, and a finished interval adapter must not
launder quorum into `TYPED`.

## Open questions

These may refine API shape or leave an individual adapter deferred. They may
not weaken the invariants:

1. Which exact Plutus/Conway source revision should be the third authority for
   treasury optional and quorum encodings?
2. Should the interval Java API expose lower/upper bound variants directly, or
   prefer reviewed relation helpers and reserve variants for advanced use?
3. Should canonical interval equality compare original raw `Data` with
   re-encoding, or use an equivalent explicit infinite-closure predicate for
   better solver performance?
4. Can strict treasury optional properties be usefully discharged under the
   current pinned model without explicit validator checks, or should automated
   support be classified `UNSUPPORTED_SOLVER` while kernel/VM support remains?
5. Is integer-key structural decoding enough for the first changed-parameter
   adapter, or must non-empty ascending uniqueness be required before any view
   is surfaced? This ADR prefers separate decode and canonical predicates.
6. Does the pinned ledger encoding establish quorum denominator positivity,
   normalization, and unit-range constraints, or only a two-integer shape?
7. Should a future per-parameter schema milestone build on the ID index, or
   stay outside E.4 until the pinned proof model types those values?

## Acceptance criteria

E.4l is complete only when:

- the encoding audit pins every promoted adapter to exact sources and records
  all discovered conflicts;
- schema 10 is closed, parent-validated, deterministic, bounded, and
  fail-closed;
- interval decoder-valid and canonical meanings remain distinct;
- treasury present, absent, and malformed states remain distinct;
- changed-parameter order/duplicates remain observable and values remain
  inaccessible;
- quorum remains deferred unless its exact shape and permitted claims are
  independently established;
- every `TYPED` adapter has codec, malformed-data, kernel, admission, and
  exact-artifact controls;
- no adapter well-formedness condition is silently added to the theorem
  domain;
- positive, vulnerable, malformed, and vacuous outcomes are retained and
  honestly classified;
- schemas 1 through 9 remain frozen;
- local, Docker, and native positive semantic hashes agree;
- affected-module and repository-wide builds pass;
- documentation explains the proof-model/ledger-encoding boundary; and
- compiler/runtime modules and emitted UPLC remain unchanged.

## Permitted claims after completion

For an adapter that passes its evidence gate, JuLC may claim that a named
property over the adapter's exact reviewed semantics was established,
refuted, or could not be evaluated for an exact artifact under the recorded
model, domain, dependencies, fuel, and depth.

JuLC may not claim from E.4l alone that:

- arbitrary raw `Data` is safely typed;
- the pinned treasury helper enforces canonical optional encoding;
- decoder-valid intervals are canonically encoded;
- changed-parameter values are understood;
- a structural quorum pair is a valid normalized unit interval;
- every counterexample is ledger-valid;
- voting/proposing validators can be selected or verified; or
- a contract is formally verified and safe in all respects.

## Review and merge sequence

1. Complete and manually review E.4k, then merge its scoped commit only into
   `feat/typed-verification-dsl-e4`.
2. Create `feat/typed-verification-dsl-e4l-raw-data-adapters` from the updated
   integration branch.
3. Implement E.4l.1 through E.4l.5 in order, applying the independent evidence
   gate to each adapter.
4. Review every source conflict and capability-state change before marking an
   adapter `TYPED`.
5. Obtain manual review before committing E.4l.
6. Merge the reviewed milestone only into the E.4 integration branch. Merge to
   `main` remains deferred until the intended E.* integration series is
   complete.
