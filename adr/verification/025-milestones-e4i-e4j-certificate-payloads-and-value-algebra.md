# ADR-025: Milestones E.4i–E.4j — Certificate Payloads and Value Algebra

- **Status:** E.4i and E.4j implemented experimentally; E.4j manual review pending
- **Date:** 2026-08-22
- **Parent:**
  [ADR-016 — Typed Verification DSL and Foundational Profile Catalog](016-typed-verification-dsl-and-profile-catalog.md)
- **Prerequisites:**
  [ADR-019 — Compositional Property Promotion Core](019-milestone-e4b-compositional-property-promotion-core.md),
  [ADR-021 — Typed Certifying DSL](021-milestone-e4d-typed-certifying-dsl.md),
  [ADR-022 — Generic Contract Types and Collections](022-milestones-e4e-e4f-generic-contract-types-and-collections.md),
  [ADR-023 — Typed Non-Value Transaction Context](023-milestone-e4g-typed-non-value-transaction-context.md), and
  [ADR-024 — Compositional Authorization Algebra](024-milestone-e4h-authorization-algebra.md)
- **Pinned model:** `CardanoLedgerApiBlaster` V3 revision
  `5dab3c43f042b8735b6d067223baaa8d32ed28a1`
- **Integration branch:** `feat/typed-verification-dsl-e4`
- **Expected milestone branches:**
  `feat/typed-verification-dsl-e4i-certificate-payloads` and
  `feat/typed-verification-dsl-e4j-value-algebra`

## Context and problem

The typed verification DSL can now select exact spending, minting, rewarding,
and certifying artifacts; traverse compiler-owned datum/redeemer types and
non-value transaction context; compose closed typed formulas; and state
authorization policies. Two important gaps remain.

First, E.4d deliberately exposes only the kind and index of a transaction
certificate. A property can state that the current certificate is an update
DRep certificate, but it cannot yet inspect the credential, deposit, refund,
delegation target, pool identifier, retirement epoch, or committee
credentials carried by the certificate. These payloads are security relevant.
For example, a validator may need to establish that a refund goes with a
specific credential, that a pool retirement uses an allowed epoch, or that a
particular cold committee credential authorizes a hot credential.

Second, E.4g exposes output values only through the previously reviewed
lovelace projection, while E.4a has one deliberately narrow raw-mint predicate.
It cannot yet express general multi-asset payment, preservation, leakage,
balancing, or mint/burn properties.

This second gap is unusually easy to model incorrectly. In the pinned Lean
library, `V2.Value` and V3 `MintValue` are represented at the builtin level as
ordered association lists of raw `Data` pairs. Duplicate policy or token
entries and malformed inner entries are representable before a ledger-domain
predicate is assumed. The pinned `V2.valueOf` helper returns the first matching
policy and first matching token quantity; it does not sum duplicates. The
`merge`, `lovelaceOf`, `withoutLovelace`, `valueSpent`, and `valueProduced`
helpers assume the relevant values satisfy their pinned validity predicates.

Consequently, the words "quantity", "equals", "contains", "preserved", and
"paid" are ambiguous unless the DSL records whether it means raw structure,
first-match lookup, summed duplicates, or a normalized/extensional relation.
This ADR makes those meanings explicit rather than hiding them behind one
convenient `Value` API.

## Goals

### E.4i goals

- Expose every payload of the 11 pinned Conway-era V3 `TxCert` constructors.
- Expose the nested `Delegatee` and `DRep` constructors required to interpret
  delegation certificates.
- Preserve credential, public-key hash, deposit/refund, pool, epoch, and
  committee-credential identities and types.
- Require constructor guards before every variant payload projection.
- Reuse the generic schema-4+ typed binder/eliminator machinery rather than
  adding fixed whole-formula resolvers.
- Work for both the current certifying certificate and certificates traversed
  from `txInfoTxCerts`.

### E.4j goals

- Expose raw policy and token entry traversal without dropping order,
  duplicates, zero quantities, or malformed entries.
- Provide explicitly named first-match and strict summed quantity operations.
- Distinguish structural equality from normalized/extensional equality and
  pointwise order.
- Add reviewed value construction, addition, subtraction/negation, and
  asset-scaling operations where their domains are explicit.
- Expose the pinned `valueSpent`, `valueProduced`, and `isBalanced` meanings.
- Support properties about payments, asset preservation/leakage, and
  generalized mint/burn constraints.
- Preserve the difference between complete-address payments and the weaker
  payment-credential-only relation.
- State the duplicate and malformed-data policy for every value operation.

### Shared goals

- Keep exact UPLC success, purpose selection, ledger-domain selection,
  non-vacuity, and fuel in the generator-owned theorem envelope.
- Add only closed, parent-validated canonical IR; no raw Lean or unchecked
  Java expression may enter generated proof source.
- Keep all changes in `julc-verification`, `julc-cli`, verification evidence,
  and documentation. Validator compilation and generated UPLC must not change.
- Preserve canonical values for property schemas 1 through 6.
- Keep local, Docker, and native backends bound to identical semantic inputs.

## Non-goals

- Change the compiler, ledger API, strict on-chain boundaries, CIP-57 output,
  validator UPLC, CBOR, script hash, size, or execution cost.
- Invent a different `Value` representation from the pinned Lean model.
- Treat an association list as a unique-key map without an explicit checked
  normalization operation.
- Make malformed value entries equal to zero, absent, or ignored unless an
  operation is explicitly the pinned first-match helper with that behavior.
- Claim that a transaction-local payment property prevents multi-satisfaction
  or double satisfaction across multiple script inputs.
- Treat ledger balance, which is already included in a selected valid-context
  premise, as validator-specific evidence.
- Add arbitrary folds, general recursion, nonlinear arithmetic, division,
  modulus, exponentiation, arbitrary raw `Data` inspection, or raw Lean.
- Add voting/proposing artifact verification or governance action payloads;
  those remain E.4k.
- Stabilize the experimental Java DSL or create public annotations for every
  new relation.
- Claim whole-contract safety from any one proved formula.

## Pinned source semantics

### V3 certificate constructors

The capability inventory and compatibility gate must pin the following exact
constructor order, payload order, and types from `V3/TxCert.lean`:

| Tag | Constructor | Ordered payloads |
|---:|---|---|
| 0 | `TxCertRegStaking` | `Credential`, `Option Integer` deposit |
| 1 | `TxCertUnRegStaking` | `Credential`, `Option Integer` refund |
| 2 | `TxCertDelegStaking` | `Credential`, `Delegatee` |
| 3 | `TxCertRegDeleg` | `Credential`, `Delegatee`, `Integer` deposit |
| 4 | `TxCertRegDRep` | `DRepCredential`, `Integer` deposit |
| 5 | `TxCertUpdateDRep` | `DRepCredential` |
| 6 | `TxCertUnRegDRep` | `DRepCredential`, `Integer` refund |
| 7 | `TxCertPoolRegister` | pool `PubKeyHash`, VRF `PubKeyHash` |
| 8 | `TxCertPoolRetire` | pool `PubKeyHash`, `Integer` epoch |
| 9 | `TxCertAuthHotCommittee` | cold `Credential`, hot `Credential` |
| 10 | `TxCertResignColdCommittee` | cold `Credential` |

The model aliases `DRepCredential`, `ColdCommitteeCredential`, and
`HotCommitteeCredential` to `V2.Credential`. The DSL keeps their semantic role
as provenance in wrapper/API names even when their underlying symbolic
representation is the same. Parent validation rejects a forged payload role or
position. An explicit conversion to the common credential view may still be
used for comparisons that are valid for the underlying pinned alias.

`Delegatee` has these constructors:

- `DelegStake(PubKeyHash)`;
- `DelegVote(DRep)`; and
- `DelegStakeVote(PubKeyHash, DRep)`.

`DRep` has these constructors:

- `DRep(DRepCredential)`;
- `DRepAlwaysAbstain`; and
- `DRepAlwaysNoConfidence`.

All three sums have strict `IsData` decoders in the pinned model. Wrong tags,
wrong arities, wrong payload kinds, and trailing fields decode to `none`.

### V3 value representation and helpers

The pinned V3 transaction uses:

- `TxOut.txOutValue : V2.Value`;
- `TxInfo.txInfoMint : MintValue`, with the same builtin association-list
  representation but a different ledger validity rule;
- `TxInfo.txInfoFee : Integer`;
- `Contexts.valueSpent`, which left-folds the values of resolved inputs using
  `V2.merge`; and
- `Contexts.valueProduced`, which left-folds output values using `V2.merge`.

At representation level, a value is:

```text
List (Data × Data)
  where a well-formed entry is
  (Data.B policyId, Data.Map [(Data.B tokenName, Data.I quantity), ...])
```

The outer and inner lists are observable. The strict V3 validity predicates
add stronger conditions, including sorted unique keys and non-zero quantities;
those conditions are premises only when the selected theorem domain includes
them. The representation alone does not establish them.

The upstream helper meanings are pinned as follows:

- `valueOf` is first-match lookup and returns `0` for absence and some
  malformed matched cases;
- `lovelaceOf` recognizes only the leading canonical Ada entry;
- `add` and `merge` assume sorted, well-formed valid values and combine equal
  entries while removing a resulting zero quantity;
- `valueSpent`/`valueProduced` inherit the assumptions of `merge`; and
- V3 `isBalanced` compares lovelace separately and compares non-Ada values
  after `merge`/`withoutLovelace`.

JuLC must not silently strengthen or simplify any of these meanings.

## Invariants

1. **One type authority.** Compiler boundary types still come only from
   `ContractSchema`/`PirType`; ledger types still come only from the pinned
   closed `LedgerTypeAuthority` and capability inventory.
2. **Guarded payload access.** A `TxCert`, `Delegatee`, or `DRep` payload is
   accessible only inside the matching constructor eliminator. No `asX`, cast,
   default payload, or unchecked field node is admitted.
3. **Strict shape.** Wrong certificate/value constructor, arity, or payload
   shape makes the affected predicate false or an explicit option absent. It
   never supplies a default security-relevant value.
4. **No implicit normalization.** Raw order, duplicates, zero quantities, and
   malformed entries remain observable until a named checked operation states
   otherwise.
5. **Meaning in the node.** First-match, strict-summed, structural, and
   extensional operations use different canonical node kinds and dependency
   rules. A renderer cannot choose the meaning from context.
6. **Strict operations fail closed.** A strict-summed or normalized operation
   encountering malformed value structure returns `none`/false; it never
   treats malformed structure as absence or zero.
7. **Finite extensionality.** Extensional equality/order ranges over the
   finite union of asset keys present in the compared values. It does not
   quantify over the universe of byte strings.
8. **Type distinction.** Output `Value`, mint `MintValue`, policy ID, token
   name, quantity, and an arithmetic `ValueDelta` remain distinct symbolic
   roles even where the pinned representation aliases bytes or association
   lists.
9. **Explicit payment scope.** Complete address, payment credential, selected
   input/output set, and whole transaction are different operations. No helper
   silently weakens one scope to another.
10. **Explicit theorem domain.** Operations relying on valid/sorted values may
    be admitted only under a purpose-compatible pinned valid-context domain or
    must carry an independently proved well-formedness premise.
11. **Domain-implied results are labeled.** `isBalanced` under a pinned valid
    V3 context is ledger-domain evidence, not a validator-specific theorem.
12. **Closed capability plan.** Every root, field, constructor, helper, and
    JuLC-defined semantic helper appears in the derived dependency plan and is
    `TYPED` before promotion.
13. **Canonical compatibility.** Schemas 1–6 remain byte-identical. E.4i uses
    property schema 7; E.4j uses property schema 8.
14. **Post-promotion integrity.** Generation and runner preflight revalidate
    structural IR, binder rules, capability dependencies, schema version,
    property hashes, generated Lean hashes, exact artifact bytes, and script
    hash before executing tools.
15. **Zero on-chain effect.** The implementation cannot be referenced from
    compiler lowering and cannot alter generated validator artifacts.

## Decision

### 1. Introduce property schemas 7 and 8

E.4i adds an explicit opt-in schema-7 factory. E.4j adds an explicit opt-in
schema-8 factory. Existing schema factories retain their current versions and
canonical bytes. A newer schema accepts all still-supported earlier nodes but
does not change their serialization or meaning.

Schema version is checked at all three boundaries:

1. worker output deserialization;
2. parent-process promotion against fresh compiler and ledger authorities; and
3. workspace/run preflight before Lean or Blaster execution.

Unknown versions and nodes introduced after the selected schema fail before
Lean generation.

### 2. E.4i uses generated guarded eliminators

Representative experimental Java surface:

```java
var cert = contract.certificate();

var registration = cert.whenRegStaking((credential, deposit) ->
        credential.whenPubKey(key -> key.eq(expectedKey))
                .and(deposit.noneOr(amount -> amount.ge(minimumDeposit))));

var delegation = cert.whenDelegStaking((credential, delegatee) ->
        delegatee.whenStakeVote((pool, drep) ->
                pool.eq(expectedPool)
                        .and(drep.whenCredential(c -> c.eq(expectedDRep)))));

var retirement = contract.txInfo().certificates().exists(candidate ->
        candidate.whenPoolRetire((pool, epoch) ->
                pool.eq(expectedPool).and(epoch.le(lastAllowedEpoch))));
```

Names are provisional. The required architecture is not:

- an eliminator emits a constructor guard and scoped canonical binders;
- payload binders carry parent-derived types and declaration-order positions;
- payload nodes are invalid outside that guard scope;
- aliases such as cold/hot/DRep credential retain role-specific wrappers;
- nested `Delegatee`/`DRep` elimination follows the same rule; and
- helper and manually composed equivalents lower to identical canonical IR.

Constructor-kind predicates remain available when no payload is needed.

### 3. E.4j exposes four deliberately different value views

The public DSL must make the selected semantics visible in method names and
canonical IR.

#### Raw structural view

The raw view preserves the ordered outer policy entries and ordered inner
token entries. It supports bounded/generic collection traversal and structural
equality. Payload decoding is guarded:

```java
value.rawPolicies().exists(policyEntry ->
        policyEntry.whenWellFormed((policy, tokens) ->
                policy.eq(wantedPolicy)
                        .and(tokens.exists(tokenEntry ->
                                tokenEntry.whenWellFormed((name, quantity) ->
                                        name.eq(wantedName)
                                                .and(quantity.gt(0)))))));
```

Malformed entries remain representable to semantic controls. A convenience
that exposes only well-formed entries must be named as filtering and may not
support a claim that no malformed entries existed.

#### Upstream first-match view

An operation such as `quantityFirst(policy, token)` reproduces pinned
`V2.valueOf` exactly, including its absence/malformed fallback behavior. It is
provided for compatibility and explicit reasoning about upstream helpers. It
must not be named simply `quantity` and must not back preservation or
extensional-equality helpers.

#### Strict summed view

`quantitySumStrict(policy, token)` traverses every raw matching policy and
token entry and sums all matching integer quantities. Its result is optional:
malformed outer or inner structure yields `none`, not zero. Convenience
comparisons such as `hasStrictQuantity(...)` lower through the option and are
false when strict decoding fails.

#### Normalized/extensional view

Two strictly well-formed values are extensionally equal when every asset key
present in either raw value has the same strict summed quantity. Pointwise
order is defined over the same finite support union. Zero-sum assets therefore
do not distinguish two values extensionally, while they remain different in
raw structural equality.

This definition avoids a hidden sort implementation and avoids quantifying
over all possible policy IDs and token names. An implementation may construct
a canonical normalized list for performance, but it must prove equivalence to
the support-union definition in Lean and retain malformed failure.

### 4. Value arithmetic is checked and domain-aware

The first implementation should reuse pinned `V2.add`/`V2.merge` only where
their sorted/well-formed assumptions are established by the selected ledger
domain. JuLC-defined strict arithmetic over arbitrary raw values returns an
option and does not reuse the upstream helpers outside their preconditions.

The admitted algebra is initially:

- singleton asset value;
- add/merge values;
- negate or subtract quantities/values;
- scale by a literal or linear symbolic integer when the resulting formula
  remains within the reviewed linear fragment;
- structural equality;
- extensional equality and inequality;
- pointwise extensional `<=`, `<`, `>=`, and `>` where explicitly defined; and
- per-asset strict-summed comparisons.

Arithmetic that can create negative quantities or omit the mandatory positive
Ada entry produces a symbolic `ValueDelta`, not a valid output `Value`.
`ValueDelta` supports comparison and further checked arithmetic but cannot be
reinserted as a transaction output or mint value without an explicit validity
operation. This prevents Java wrapper types from claiming ledger validity that
the arithmetic did not establish.

Nonlinear multiplication of two symbolic quantities, division, modulus,
exponentiation, and user-defined folds remain rejected.

### 5. Transaction aggregates and balance retain pinned meanings

The DSL adds typed helpers for:

- `context.valueSpent()` — exact pinned fold over resolved input values;
- `context.valueProduced()` — exact pinned fold over output values;
- `context.mint()` — raw V3 mint value;
- `context.fee()` — V3 integer lovelace fee; and
- `context.isBalanced()` — exact pinned V3 `isBalanced` predicate.

`valueSpent` and `valueProduced` may only be used under a domain that
establishes valid input/output values, unless a future explicit well-formedness
premise is added. The dependency plan records this requirement.

Because every current pinned valid purpose context already implies
`isBalanced`, a certificate for `context.isBalanced()` must label the claim as
`domain-implied`. It is useful for domain diagnostics and composition, but it
is not evidence that validator logic enforced balance.

### 6. Payments and preservation have explicit scopes

Representative operations include:

```java
var paid = contract.txInfo().outputs()
        .toAddress(sellerAddress)
        .valueProduced()
        .quantitySumStrict(policy, token)
        .exists(q -> q.ge(price));

var preserved = contract.context().valueSpent()
        .extensionallyEquals(
                contract.context().valueProduced()
                        .plusFee(contract.txInfo().fee())
                        .minus(contract.txInfo().mint()));

var noTokenLeak = selectedInputs.valueSpent()
        .quantitySumStrict(policy, token)
        .eq(selectedOutputs.valueProduced()
                .quantitySumStrict(policy, token));
```

The exact surface may differ, but each operation must record:

- which inputs/outputs are aggregated;
- whether matching is by complete address, payment credential, script hash,
  or an explicit predicate;
- whether quantities use first-match or strict-summed meaning;
- how duplicate entries are treated;
- whether lovelace fees and mint/burn are included; and
- whether the claim is transaction-local or links one specific script input
  to specific outputs.

`paidToPaymentCredential` is weaker than `paidToAddress` because it ignores
staking credentials. Their IR node/dependency rule and certificate narrative
must remain different.

A local output-payment theorem does not by itself prove a sale safe against
multi-satisfaction. The generated README and certificate retain
`globalMultiInputLinkageModeled: false` unless the property explicitly models
the required input/output linkage.

### 7. Existing annotations remain profiles over the shared core

E.4j does not add a second proof path for `@PreservesValue` or
`@ControlledMint`. After the value core is stable:

- existing annotations lower to canonical DSL formulas where their current
  semantics are exactly representable;
- helper/manual/annotation equivalence tests compare canonical IR and rendered
  Lean; and
- any semantic expansion requires its own annotation/profile ADR rather than
  silently strengthening an existing annotation.

Annotations remain convenience profiles. The generic DSL remains the
foundational property language.

### 8. Capability and solver admission remain evidence-driven

Every new source constructor/helper receives an inventory entry or an updated
status. JuLC-defined helpers receive explicit `dsl.*` capability IDs and
semantic controls even when no upstream helper exists.

A capability becomes `TYPED` only after all of these exist:

1. closed canonical IR;
2. parent validation and schema gating;
3. renderer support;
4. semantic dependency mapping;
5. Lean kernel controls for normal, duplicate, reordered, zero, absent, and
   malformed cases as applicable;
6. runner integrity/tamper tests; and
7. at least one exact-artifact proof or an honest `UNSUPPORTED_SOLVER`
   classification.

Being typed does not promise that every composition terminates within useful
solver bounds. Expensive formulas retain explicit fuel/time bounds and may
produce `COULD-NOT-EVALUATE`.

## Canonical IR additions

Names are provisional, but separate node identities are required for:

### Schema 7

- guarded ledger-constructor elimination;
- guarded ledger-constructor payload projection; and
- role-preserving credential aliases where needed for validation and
  diagnostics.

The node carries the pinned sum type, constructor ID, payload position/name,
payload type, scoped binder, and capability ID. Parent validation re-derives
all of them.

### Schema 8

- raw policy-entry and token-entry traversal;
- strict well-formed entry elimination;
- upstream first-match quantity;
- strict summed quantity;
- structural value equality;
- extensional value equality/order;
- checked value arithmetic;
- value aggregation over a typed input/output collection; and
- pinned spent/produced/balance helpers.

No node contains raw Lean, an arbitrary helper name, an arbitrary type name,
or user-selected theorem-envelope text.

Canonicalization may reorder operands only for operations proved commutative.
Raw structural value operations are never reordered. Scoped binders are
alpha-normalized before hashing, including binders nested inside commutative
operands.

## Theorem and trust boundary

For each claim, the generated obligation retains the ADR-019 shape:

```text
selected purpose
∧ selected pinned ledger domain (or reviewed solver-compatible superset)
∧ exact hash-bound UPLC execution succeeds within recorded fuel
→ user guarantee
```

The user guarantee cannot introduce, remove, duplicate, or weaken those
premises. If Blaster cannot translate the complete pinned ledger domain, JuLC
may prove over a reviewed superset only when a separate generated Lean theorem
kernel-checks inclusion from the pinned ledger domain to that solver domain.
The per-claim ledger corollary must be compiled by the hash-bound runner plan.

Certificates record at least:

- property schema and canonical DSL IR hash;
- exact artifact bytes and script hash;
- generated Lean tree hash;
- pinned model and capability-inventory hashes;
- purpose and selected domain;
- fuel and timeout bounds;
- per-claim non-vacuity result;
- value semantics used by each claim (`STRUCTURAL`, `FIRST_MATCH`,
  `STRICT_SUMMED`, or `EXTENSIONAL`);
- payment aggregation scope;
- whether a result is domain-implied;
- whether global multi-input linkage was modeled; and
- conservative counterexample-domain qualifications.

No result is described as "the contract is safe" or "fully verified".

## Implementation milestones

E.4i and E.4j are separate review/merge units even though this ADR governs
both. E.4j starts only after E.4i has been reviewed and merged to the E.4
integration branch.

### E.4i.1 — Inventory and schema-7 authority

- Pin all `TxCert`, `Delegatee`, and `DRep` constructors and payloads.
- Add ledger symbolic types and role-specific aliases.
- Add schema-7 codec/admission and freeze schemas 1–6.
- Reject forged constructors, fields, payload order, types, binders, and
  capability IDs before Lean generation.

### E.4i.2 — Guarded Java API and Lean lowering

- Generate or provide guarded eliminators for all 11 certificate constructors.
- Add nested guarded eliminators for all `Delegatee` and `DRep` constructors.
- Support current-certificate and transaction-list traversal.
- Add canonicalization, rendering, dependency planning, native metadata, and
  helper/manual equivalence tests.

### E.4i.3 — Semantic and exact-artifact evidence

- Kernel-check every tag, arity, payload order, alias, nested constructor, and
  malformed rejection.
- Add VM controls for representative certificate payload validator behavior.
- Prove at least one non-tautological exact certifying property, retain a
  vulnerable refutation, and retain a vacuous control.
- Reproduce local, Docker, and native runs with identical semantic hashes.
- Update the capability inventory, getting-started guide, and limitations.

### E.4j.1 — Raw value inventory and schema-8 views

- Pin `Value`, `MintValue`, `valueOf`, `lovelaceOf`, `add`, `merge`,
  `valueSpent`, `valueProduced`, `validTxOutValue`, `validMintValue`, and
  `isBalanced` signatures/semantics.
- Add distinct output-value and mint-value symbolic types.
- Add raw outer/inner entry traversal and strict entry guards.
- Add schema-8 admission and freeze schemas 1–7.

### E.4j.2 — Quantity and extensional core

- Add exact upstream first-match lookup.
- Add optional strict-summed lookup.
- Add structural equality and support-union extensional equality/order.
- Add checked linear value arithmetic.
- Kernel-check duplicates, order, zero sums, absence, Ada placement, malformed
  policy entries, malformed token maps, malformed names, and malformed
  quantities.

### E.4j.3 — Transaction aggregates and property vocabulary

- Add pinned spent/produced/balance helpers.
- Add explicit complete-address and payment-credential aggregations.
- Add scoped preservation/leakage and generalized mint/burn formulas.
- Mark ledger-domain-tautological properties and multi-satisfaction limits in
  certificates.
- Establish annotation/helper/manual canonical equivalence only where current
  meanings are identical.

### E.4j.4 — Evidence, performance, and documentation

- Add non-tautological positive, vulnerable, malformed, duplicate, and vacuous
  exact-artifact fixtures.
- Exercise spending and minting at minimum; add rewarding/certifying
  composition tests where purpose-compatible value roots are used.
- Run local, Docker, and native backends and compare all semantic hashes.
- Measure solver time/fuel for first-match, strict-summed, and extensional
  formulas separately.
- Document unsupported or impractical formulas as `UNSUPPORTED_SOLVER` or
  bounded limitations rather than weakening their meanings.
- Update the getting-started guide with concrete multi-asset examples and an
  explanation of all four value meanings.

## Required tests

### Shared admission and compatibility

- schema-1 through schema-6 canonical byte fixtures remain byte-identical;
- schema-7 nodes fail under schemas 1–6;
- schema-8 nodes fail under schemas 1–7;
- unknown JSON fields, node kinds, types, constructors, and helpers fail;
- canonicalization is deterministic and idempotent;
- commuted formulas with scoped binders canonicalize consistently;
- generated-name and binder collisions fail deterministically;
- capability inventory revision/signature drift fails closed;
- consistently re-hashed tampered type graphs or payload positions fail before
  Lean generation; and
- no compiler/core/ledger/stdlib source or emitted artifact changes.

### E.4i semantics

- all 11 certificate constructors accept exact tags/arities and reject wrong
  tags, short/trailing fields, and wrong payload kinds;
- optional deposit/refund distinguishes `some 0`, `some n`, and `none`;
- mandatory deposit/refund never becomes optional;
- all `Delegatee` and `DRep` nested cases work and reject cross-constructor
  projections;
- forged cold/hot/DRep credential role metadata or payload positions are
  rejected by parent validation, while explicit common-credential comparison
  follows the pinned aliases;
- pool ID and VRF key field order is pinned;
- pool retirement epoch is not confused with a deposit/refund;
- current-certificate payload and the indexed transaction-list certificate
  agree under `isKnownCertificate`;
- guarded payload binders cannot escape their constructor branch; and
- a deliberately vulnerable certificate validator yields a useful
  counterexample without claiming ledger validity of that model.

### E.4j semantics

- raw structural equality observes order, duplicates, zero entries, and exact
  nested structure;
- first-match lookup contrasts with strict-summed lookup on duplicate policy
  and token entries;
- absent asset and explicit zero are distinguishable in strict optional APIs;
- malformed entries yield `none`/false for strict operations;
- extensional equality ignores order and zero-sum duplicate decomposition but
  rejects unequal summed quantities;
- extensional order checks the union of both supports, including negative
  mint/burn quantities;
- Ada empty policy/token handling matches the pinned model;
- output-value and mint-value validity assumptions are not interchanged;
- `merge`/arithmetic controls cover cancellation to zero and sorted insertion;
- `valueSpent`/`valueProduced` include every selected input/output exactly once;
- duplicate transaction inputs/outputs remain visible to the exact pinned
  folds rather than silently deduplicated;
- complete-address and payment-credential aggregations differ on staking
  credentials;
- local payment evidence is labeled as not modeling global multi-input
  linkage;
- balance-only claims are labeled domain-implied;
- VM controls reproduce representative positive and negative validator cases;
- local, Docker, and native evidence binds identical artifact, DSL IR,
  property IR, capability inventory, and Lean hashes; and
- timeouts, fuel exhaustion, untranslated operations, or missing bridge
  compilation produce `COULD-NOT-EVALUATE`, never `SMT-VALID`.

## Affected modules

### `julc-verification`

- property schemas 7 and 8;
- certificate/delegatee/DRep ledger type authority;
- value types, nodes, wrappers, validation, canonicalization, dependencies,
  and rendering;
- capability inventory updates; and
- admission, semantic, equivalence, and compatibility tests.

### `julc-cli`

- schema-7/8 metamodel generation;
- hash-bound workspace sources and semantic controls;
- runner preflight/certificate metadata;
- native reachability metadata; and
- local/Docker/native exact-artifact tests.

### `verification/`

- E.4i and E.4j reproducible fixtures, scripts, controls, certificates, and
  getting-started examples.

### Explicitly unaffected

- `julc-core`;
- `julc-compiler`;
- `julc-ledger-api`;
- `julc-stdlib`;
- `julc-blueprint`; and
- validator UPLC/CBOR/script hashes.

If implementation appears to require modifying one of these unaffected
modules, that part stops for ADR review rather than silently expanding scope.

## Compatibility and migration

- Existing schemas 1–6, annotations, property sources, certificates, and
  generated workspaces retain their meanings.
- E.4i/E.4j are opt-in property-schema changes; no existing source is silently
  upgraded.
- Capability-rule spelling becomes certificate-visible and must follow an
  explicit stability policy. If a rule must change before stabilization,
  retained workspaces are regenerated and the migration is documented.
- An upstream pinned-model revision requires a reviewed inventory update,
  regenerated semantic controls, and fresh exact-artifact evidence.
- Existing `lovelace()` and E.4a controlled-mint helpers retain their current
  canonical semantics. They may delegate internally to shared helpers only
  after byte/IR/Lean equivalence is locked by tests.

## Risks and mitigations

### Variant payload confusion

**Risk:** a property reads a field from the wrong certificate constructor or
uses the right type at the wrong payload position.

**Mitigation:** guarded eliminators, parent re-derivation, exact constructor
tables, strict IsData controls, and binder-scope validation.

### Value semantic ambiguity

**Risk:** a proof uses first-match lookup while its certificate/documentation
suggests duplicate-summed or normalized behavior.

**Mitigation:** distinct nodes, method names, capability rules, result metadata,
and contrast tests with duplicates.

### Hidden malformed-data defaults

**Risk:** malformed entries become zero or absent and accidentally satisfy a
security property.

**Mitigation:** strict operations return options and predicates fail on
`none`; only the explicitly named upstream first-match helper reproduces its
pinned fallback.

### Solver explosion

**Risk:** nested policy/token traversal, support-union extensionality, and
value folds exceed practical Blaster/Z3 bounds.

**Mitigation:** stage vertical slices, measure each operation, retain explicit
fuel/time bounds, prefer per-asset strict sums when whole-value extensionality
is unnecessary, and classify unsupported compositions honestly.

### Tautological ledger claims

**Risk:** proving balance under `valid*Context` is presented as validator
assurance even though balance is already a domain premise.

**Mitigation:** mark domain-implied claims in admission, generated README, and
certificate; require non-tautological evidence fixtures for milestone
acceptance.

### Multi-satisfaction overclaim

**Risk:** summing all outputs paid to one address proves a transaction-local
payment but is described as one payment per consumed contract input.

**Mitigation:** explicit aggregation/linkage scope and conservative
`globalMultiInputLinkageModeled` metadata.

### Upstream helper preconditions

**Risk:** `merge` or spent/produced folds are applied to arbitrary malformed or
unsorted values outside their pinned assumptions.

**Mitigation:** domain-aware admission or optional strict JuLC helpers; no
unchecked reuse of an upstream helper.

## Rejected alternatives

### Expose certificate payload fields directly

Rejected because payload position is meaningful only after constructor
recognition. Direct access recreates the unchecked sum-projection problem
already rejected for contract variants.

### Treat `Value` as `Map<Policy, Map<Token, Integer>>`

Rejected because it erases order, duplicates, malformed entries, and the
actual pinned builtin representation.

### Define one generic `quantity()` operation

Rejected because first-match and summed meanings disagree on representable
data. The selected meaning must be visible in IR and certificates.

### Normalize every value on ingestion

Rejected because normalization would hide malformed or duplicate inputs and
would make structural properties impossible to express.

### Use upstream `merge` for all raw values

Rejected because the helper assumes well-formed sorted values. Its unreachable
branches are not a safe malformed-data semantics.

### Prove payment by lovelace or first-match alone

Rejected as the general design because it cannot establish multi-asset
payments and can misread duplicates. Narrow compatibility helpers remain
available with explicit names.

### Add fixed annotations before the generic algebra

Rejected because fixed templates would duplicate semantics and would not
support user composition. Annotations may graduate later as profiles over the
shared canonical IR.

### Expose arbitrary Lean to cover missing operations

Rejected because it bypasses the closed type/capability inventory, canonical
hashing, trust boundary, and certificate interpretation.

## Open questions

These questions may refine API names or solver admission during implementation,
but they do not permit weakening the invariants above:

1. Can support-union extensional equality be translated and solved within a
   useful bound for realistic multi-asset values, or should the first promoted
   solver surface remain per-asset strict sums while whole-value extensionality
   is kernel-tested and marked `UNSUPPORTED_SOLVER`?
2. Should checked arithmetic be represented by primitive value nodes or by a
   smaller normalized core plus generated Java conveniences? Either choice must
   yield one canonical IR for equivalent formulas.
3. Which payment aggregations produce useful counterexamples without causing
   prohibitive nested-list formulas? Solver convenience cannot change complete
   address or duplicate semantics.
4. Should any E.4j formula graduate to a new annotation/profile after the DSL
   evidence is stable? Such graduation requires a separate ADR and canonical
   equivalence to the shared DSL path.
5. Is the pinned upstream first-match `valueOf` sufficiently useful to expose
   publicly, or should it remain an explicitly named low-level compatibility
   operation? It must exist in semantic controls either way.

### Resolved E.4j implementation decisions

1. Whole-value extensional equality and order remain admitted typed formulas
   with kernel-checked semantics. The retained calibration did not discharge
   them within the documented bound, so E.4j presents no extensional
   `SMT-VALID` result. Per-asset first-match is the retained positive solver
   surface; strict-summed lookup has the same explicit bounded limitation.
2. Checked arithmetic uses closed primitive value nodes. Java conveniences
   lower directly to those nodes, and parent validation rechecks operation,
   arity, argument roles, and result option type. Scaling is literal-only in
   schema 8 to keep the admitted arithmetic linear.
3. The positive exact-artifact evidence uses a direct output scope because it
   yields a useful counterexample and tractable obligation. Complete-address,
   payment-credential, selected-input/output, and whole-transaction aggregates
   are separately represented and kernel/admission tested; scope is never
   changed merely to make a solver query finish.
4. No formula graduates to an annotation in this milestone. Any future
   profile must be a separate ADR and must demonstrate canonical equivalence
   to these shared nodes.
5. The pinned first-match operation is exposed under the explicit name
   `quantityFirst`. It is not the default meaning of a generic `quantity`
   method, and its use is recorded as `FIRST_MATCH` in the result.

## Acceptance criteria

E.4i is complete only when:

- every pinned certificate/delegatee/DRep constructor and payload is covered
  by guarded parent-validated IR and kernel controls;
- exact-artifact positive, refuted, and vacuous evidence exists;
- all schemas 1–6 remain frozen;
- local, Docker, and native semantic hashes agree; and
- no compiler or validator artifact changes.

E.4j is complete only when:

- raw, first-match, strict-summed, structural, and extensional meanings are
  separately represented, tested, and documented;
- malformed and duplicate controls demonstrate the distinctions;
- spent/produced/payment/preservation operations record exact scope and domain;
- positive evidence is non-tautological and refutations remain honestly
  domain-qualified;
- impractical solver surfaces are explicitly classified rather than weakened;
- all schemas 1–7 remain frozen;
- local, Docker, and native semantic hashes agree; and
- the affected-module and repository-wide builds pass with no UPLC regression.

## Permitted claims after completion

JuLC may claim that, for a named exact script artifact, selected purpose,
recorded ledger-domain model, property schema, fuel bound, and pinned
dependencies:

- a stated guarded certificate-payload property was established, refuted, or
  could not be evaluated; or
- a stated value/payment/preservation property with an explicit duplicate and
  aggregation meaning was established, refuted, or could not be evaluated.

JuLC may not claim from these milestones alone that:

- every certificate or value property is supported;
- the solver result is an independently reconstructed Lean proof term;
- a transaction-local payment property prevents global multi-satisfaction;
- a domain-implied balance property validates the contract's own logic; or
- the contract is formally verified and safe in all respects.

## E.4i implementation outcome

The schema-7 certificate-payload slice is implemented on
`feat/typed-verification-dsl-e4i-certificate-payloads` without changes to
compiler lowering, the ledger API, blueprint generation, or validator UPLC.
E.4j remains a separate branch and review unit.

Implemented behavior includes:

- closed authority and capability entries for all 11 pinned `TxCert`
  constructors and every nested `Delegatee`/`DRep` constructor;
- current-certificate and duplicate-preserving transaction-certificate-list
  traversal through the shared schema-4+ typed collection core;
- guarded payload eliminators with parent-revalidated constructor, field,
  order, type, binder, and capability identities;
- role-preserving Java wrappers for DRep, cold-committee, and hot-committee
  credentials while retaining the pinned credential representation;
- schema-7 metamodel generation, canonicalization, worker admission,
  promotion, workspace generation, runner preflight, and native execution;
- kernel-reduced controls for tags 0–10, payload arities/order, strict malformed
  rejection, all nested delegation/DRep constructors, and known-certificate
  index behavior; and
- exact-VM controls for registration deposit, update-DRep nested credential,
  and pool-retirement pool/epoch behavior, including malformed inputs.

The retained positive exact-artifact theorem is non-tautological: successful
execution of the exact `AuthorizedDRepRegistration` validator implies that the
selected registration certificate carries deposit `1`. The vulnerable
constructor-only validator is `REFUTED`; the always-failing validator is
`COULD-NOT-EVALUATE/property-vacuous`. Local, Docker, and GraalVM-native runs
at CEK fuel 5000 and recursive depth 4 bind identical semantic inputs:

- compiled-code SHA-256
  `c10a6871a2eac7bad5b709207b81535127ccf2079c814177eb8065a76d438bef`;
- Cardano script hash
  `dd7450212f98c9320341cdc5e612dcc0e3986c22ddad0be798ff89a1`;
- canonical DSL IR SHA-256
  `95a50eea3dc29f1e2970699bd4d6c3603cc2dff9314f6d996ffe64f1e8ca9223`;
- property IR SHA-256
  `5b5ae7c96f74fa8115edd2beb03b41dbca9be16135d619c8263fb5e0028ca462`;
  and
- generated Lean SHA-256
  `5aa0e53a893778366023311b7a3538c4b7a02814fcb702b2185d9ddc3ed3d18b`.

Calibration also found an honest solver boundary. Concrete VM executions and
Lean controls establish nested update-DRep credentials and pool-retirement
payload behavior, but positive exact-artifact non-vacuity for validators that
combine those payloads with byte-string equality or the epoch bound was not
established at fuel 5000 or 8000. Those broader examples are not presented as
SMT-VALID. This is a retained tractability limitation, not a weakened payload
semantics or a claim that the validators have no concrete successful input.

Final validation used fresh forced runs of all 83 `julc-verification` tests and
all 425 `julc-cli` tests with zero failures, followed by the repository-wide
Gradle build. The local evidence driver, Docker backend, GraalVM 25.0.2 native
build, and native positive verification all completed successfully.

E.4i changes the bundled capability-inventory SHA-256 from
`4230b38874c05a1538f9b79285f7a0314de43806d543b1136d690a1db4e88bcb`
to `f55d08b47d71768e4ca67bcb417e6c556fdf55cfc6647c8ff45e73efe65675c8`.
The change is additive: the 17 certificate, `Delegatee`, and `DRep`
constructor entries move from `UNSUPPORTED_IR` to `TYPED`; no previously
admitted capability, signature, or schema-1-through-6 meaning changes.
Retained E.4f-through-E.4h results therefore remain valid hash-bound historical
records of their original runs, but their workspaces require regeneration
before the current CLI will re-run them. Most bind inventory hash `4230b388…`;
the older E.4f Docker/native fuel-sweep variants bind `a17c44ab…`. They were not
regenerated in E.4i because none could have referenced the newly admitted
schema-7 constructors.

## E.4j implementation outcome

The schema-8 multi-asset value slice is implemented on
`feat/typed-verification-dsl-e4j-value-algebra` without changes to compiler
lowering, the ledger API, blueprint generation, or validator UPLC. It keeps
four meanings deliberately separate in canonical IR, generated Lean, result
metadata, and documentation:

- `STRUCTURAL` compares the exact ordered nested association-list shape;
- `FIRST_MATCH` uses the pinned upstream `valueOf` behavior;
- `STRICT_SUMMED` validates the complete raw value and returns an option that
  distinguishes an absent asset from an explicitly present zero sum; and
- `EXTENSIONAL` compares summed quantities over the finite union of raw
  supports after strict validation.

Implemented behavior includes:

- duplicate- and order-preserving raw policy/token traversal for output value
  and mint value, with guarded policy/token entry binders;
- first-match, strict-summed, structural, extensional equality, and
  extensional pointwise-order operations;
- checked singleton, addition, negation, literal scaling, and value-delta
  operations that reject malformed inputs rather than normalizing them
  silently;
- pinned `valueSpent`, `valueProduced`, V3 `isBalanced`, selected
  input/output aggregation, complete-address filtering, and the deliberately
  weaker payment-credential filtering;
- schema-8 metamodel generation, canonicalization, worker admission,
  promotion, workspace generation, runner preflight, native reachability, and
  certificate metadata for value meaning, aggregation scope, domain-implied
  claims, and global multi-input linkage; and
- kernel controls for duplicate policies/tokens, first-match versus summed
  quantities, absence versus explicit zero, malformed entries, ordering,
  zero-sum decompositions, Ada placement, checked arithmetic, aggregate folds,
  address scopes, value validity, and balance.

Purpose-aware admission tests exercise output/mint value composition for all
four currently verifiable purposes, including mint-value strict lookup. Exact
VM evidence exercises the real spending fixture with positive, insufficient,
duplicate-first-match, and malformed-map inputs. The retained solver evidence
is deliberately one spending theorem; E.4j does not claim a separate minting
theorem was established merely because the same algebra is purpose-compatible.

The retained non-tautological exact-artifact theorem states that successful
execution of `AuthorizedValue` implies a first-match quantity of at least 10
for the named asset in the first output. The always-succeeding control is
`REFUTED`; the always-failing control is
`COULD-NOT-EVALUATE/property-vacuous`. The result records
`valueSemantics: [FIRST_MATCH]`, `paymentAggregationScopes: [DIRECT_OUTPUT]`,
`domainImplied: false`, and `globalMultiInputLinkageModeled: false` rather than
inflating this transaction-local result into a global payment claim.

Local and Docker runs at CEK fuel 5000 and recursive depth 4 bind identical
semantic inputs:

- compiled-code SHA-256
  `554f6e4660b045310c6f6b8e80701b7618ac5f306b94c28fa5291283e0e0b522`;
- Cardano script hash
  `4f2c2b849b1e676ecd1c39505f02a2f18a78182ad4c8037b4ab82e00`;
- canonical DSL IR SHA-256
  `26d424bf28050741f723664d3a7f43c39d9a9e5d278ac648fe708e5f4df87d04`;
- property IR SHA-256
  `448c2414e4d85d5b23b149e5a1775521560a771a9be0a8a937ec9c6a13b1cd3c`;
  and
- generated Lean SHA-256
  `f30709b363ffd107a110bc6297d0d5a88d42be0caf75122310f4e134ca519adb`.

Solver calibration preserves the distinction between semantic support and
automated discharge. The strict-summed calibration established non-vacuity in
approximately 2m28s, but its proof did not finish within a twelve-minute
ceiling. The whole-value extensional calibration established non-vacuity in
approximately 2m42s, but its proof did not finish within a ten-minute ceiling.
Neither formula is weakened or presented as `SMT-VALID`; both remain supported
typed properties with a documented bounded-solver limitation. The
first-match theorem is the retained positive solver evidence.

E.4j changes the bundled capability-inventory SHA-256 from
`f55d08b47d71768e4ca67bcb417e6c556fdf55cfc6647c8ff45e73efe65675c8`
to `7f1d5e03608d104228fa90acf0403ed04f3147cbe84b93800ccd0d0731676788`.
This is an additive admission change for the reviewed value types, fields,
validity predicates, and helpers. It does not alter any previously admitted
schema-1-through-7 formula. Older certificates remain hash-bound historical
records, while their generated workspaces require regeneration before the
current inventory gate will re-run them.

The GraalVM 25.0.2 native image built successfully and executed the complete
positive verification workflow. Its compiled-code, script, canonical DSL IR,
property IR, and generated Lean hashes are byte-identical to the local and
Docker values above. The native run established non-vacuity in approximately
2m29s and returned `SMT-VALID` for the property in approximately 6s. The
native executable still launches a bounded Java worker for the trusted Java
property builder; the proof runner itself executes from the native CLI.

Final validation used fresh forced runs of all 88 `julc-verification` tests
and all 428 `julc-cli` tests with zero failures, followed by a successful
repository-wide `./gradlew build`. The local evidence driver, Docker backend,
GraalVM 25.0.2 native build, and native positive verification all completed
successfully.
