# ADR-026: Milestone E.4k — Typed Governance Transaction Data

- **Status:** Implemented experimentally; E.4k complete
- **Date:** 2026-08-22
- **Parent:**
  [ADR-016 — Typed Verification DSL and Foundational Profile Catalog](016-typed-verification-dsl-and-profile-catalog.md)
- **Prerequisites:**
  [ADR-017 — Purpose-Indexed CIP-57 Blueprints](017-purpose-indexed-multivalidator-blueprints.md),
  [ADR-019 — Compositional Property Promotion Core](019-milestone-e4b-compositional-property-promotion-core.md),
  [ADR-022 — Generic Contract Types and Collections](022-milestones-e4e-e4f-generic-contract-types-and-collections.md),
  [ADR-023 — Typed Non-Value Transaction Context](023-milestone-e4g-typed-non-value-transaction-context.md),
  [ADR-024 — Authorization Algebra](024-milestone-e4h-authorization-algebra.md), and
  [ADR-025 — Certificate Payloads and Value Algebra](025-milestones-e4i-e4j-certificate-payloads-and-value-algebra.md)
- **Pinned model:** `CardanoLedgerApiBlaster` V3 revision
  `5dab3c43f042b8735b6d067223baaa8d32ed28a1`
- **Integration branch:** `feat/typed-verification-dsl-e4`
- **Expected milestone branch:**
  `feat/typed-verification-dsl-e4k-governance-data`

## Context and problem

The schema-8 verification DSL can traverse compiler-owned contract data,
ordinary V3 transaction context, certificate payloads, authorization rules,
and multi-asset values. The remaining large typed V3 transaction surface is
governance data:

- `TxInfo.txInfoVotes : VoterMap`;
- `TxInfo.txInfoProposalProcedures : List ProposalProcedure`;
- the three `Voter` constructors;
- the three `Vote` constructors;
- `GovernanceActionId`;
- `ProtocolVersion`;
- `ProposalProcedure`; and
- the seven `GovernanceAction` constructors.

This data is useful even when the current script is spending, minting,
rewarding, or certifying. For example, a spending validator may require a
specific DRep vote to be present, a minting policy may inspect a governance
action identifier, or a certifying validator may constrain proposals in the
same transaction.

Two boundaries make this milestone more subtle than adding ordinary record
fields.

First, the pinned Lean model deliberately stores
`ProposalProcedure.ppGovernanceAction` as raw `Data`, even though the same
model defines a strict `IsData GovernanceAction` decoder. `ChangedParameters`
and `Quorum` also remain raw `Data`. JuLC must not pretend these values are
fully typed merely because the Java ledger API has richer off-chain wrappers.

Second, V3 models voting and proposing script purposes, but the pinned CIP-57
vocabulary has standard purpose names only for `spend`, `mint`, `withdraw`,
and `publish`. ADR-017 therefore correctly keeps voting/proposing blueprints
fail-closed. Adding transaction governance data must not silently create an
untruthful exact-artifact convention or relabel voting/proposing validators as
another purpose.

This ADR separates those concerns. E.4k adds typed governance **transaction
data** to properties over the four purposes JuLC can already select exactly.
Exact voting/proposing validator verification remains deferred until a
separate ADR defines a truthful artifact-selection convention.

## Goals

- Add opt-in property schema 9 for typed V3 governance transaction data.
- Expose ordered, duplicate-preserving voter maps and nested governance-vote
  maps without silently applying Java `Map` uniqueness.
- Expose proposal lists without silently deduplicating or reordering them.
- Add guarded eliminators for every `Voter`, `Vote`, and `GovernanceAction`
  constructor supported by the pinned model.
- Add typed fields for `GovernanceActionId`, `ProtocolVersion`, and the typed
  portions of `ProposalProcedure`.
- Add a narrow, strict decoder from a proposal's raw governance-action field
  to `Option GovernanceAction`.
- Reuse the schema-4+ collection, option, binder, equality, authorization, and
  arithmetic core instead of adding fixed whole-formula resolvers.
- Expose exact `isKnownVoter` and `isKnownProposal` helper semantics where
  useful, including their duplicate/index behavior.
- Preserve purpose-aware composition for spending, minting, rewarding, and
  certifying properties.
- Bind every result to the exact script artifact, property IR, capability
  inventory, generated Lean, selected domain, fuel, and dependency revisions.
- Keep compiler output and validator UPLC byte-identical.

## Non-goals

- Add `DslPurpose.VOTING` or `DslPurpose.PROPOSING` in this milestone.
- Publish non-standard or misleading CIP-57 purpose strings.
- Verify a voting or proposing validator through a spending/minting/
  rewarding/certifying entry.
- Mark `validVotingContext` or `validProposingContext` as supported before
  exact artifact selection exists.
- Invent typed meanings for `ChangedParameters`, `Quorum`, current treasury,
  treasury donation, anchors omitted by the pinned model, or arbitrary raw
  `Data`.
- Expose arbitrary governance-action raw payload inspection or raw Lean.
- Treat a voter map as a unique-key map outside an explicit ledger-domain
  premise.
- Claim that a vote observed in transaction data was cast by an authorized
  off-chain identity; this model sees ledger data, not witness provenance
  beyond the modeled rules.
- Claim complete Conway governance-model coverage. The pinned model omits
  anchors and keeps several payloads raw.
- Add a public annotation for governance policies before the DSL semantics and
  solver behavior are stable.
- Change the compiler, ledger API, strict boundary semantics, blueprint
  generation, CBOR, script hash, size, or execution cost.

## Current behavior

The capability inventory currently classifies the transaction vote/proposal
fields, typed voter/vote/action structures, and helper functions as
`UNSUPPORTED_IR` or `RAW_DATA_ONLY`. E.4g preserves voting and proposing
`ScriptPurpose` keys only as opaque values so redeemer-map structure is not
lost. It does not authorize payload projection.

The compiler and Java ledger API already represent governance structures for
on-chain programs. That does not by itself authorize verification support.
The DSL follows the pinned Lean proof model, and every promoted capability
requires reviewed canonical IR, parent validation, Lean lowering, semantic
controls, and evidence.

ADR-017 publishes truthful CIP-57 interfaces for spending, minting,
rewarding/withdraw, and certifying/publish. It intentionally rejects voting
and proposing blueprint interfaces because the pinned standard has no matching
purpose vocabulary.

## Pinned source semantics

### Voter and vote

The pinned `Governance.lean` defines:

| Data tag | `Voter` constructor | Payload |
|---:|---|---|
| 0 | `CommitteeVoter` | `HotCommitteeCredential` |
| 1 | `DRepVoter` | `DRepCredential` |
| 2 | `StakePoolVoter` | `V2.PubKeyHash` |

Each voter constructor has exactly one payload. Role-specific credential
wrappers remain distinct in Java even though the pinned aliases share the
underlying `Credential` representation.

`Vote` is encoded as an empty constructor:

| Data tag | Constructor |
|---:|---|
| 0 | `VoteNo` |
| 1 | `VoteYes` |
| 2 | `Abstain` |

Wrong tags, wrong arities, trailing fields, and malformed voter payloads fail
the strict pinned decoders.

### Governance action identifiers and protocol versions

`GovernanceActionId` is `Constr 0 [TxId, Integer]`, preserving transaction ID
and action index. `ProtocolVersion` is `Constr 0 [Integer, Integer]`,
preserving major and minor components. Both have pinned equality and
lexicographic ordering definitions.

The DSL may expose equality and integer component comparisons. It must not
invent nonnegative bounds for indexes or protocol versions unless the selected
property states them or a pinned domain predicate provides them.

### Vote maps

The pinned representation is:

```text
VoterMap = List (Voter × GovernanceVoteMap), encoded as Data.Map
GovernanceVoteMap = List (GovernanceActionId × Vote), encoded as Data.Map
```

At raw representation level, order and duplicates are observable. Strict
decoding checks every key and value but does not itself prove uniqueness or
sorting. `validVoterMap`, which is included in pinned `validTxInfo`, requires:

- outer voter keys to be sorted and unique;
- every inner governance-vote map to be non-empty; and
- every inner action-ID key set to be sorted and unique.

The DSL must retain both facts: raw maps preserve duplicates; selected valid
V3 domains imply the stronger sorted/unique constraints. No API may silently
switch between them.

The pinned `isKnownVoter` checks whether any outer map entry has the voter key.
It does not inspect a particular action ID or vote. A lookup of a particular
vote is therefore expressed through the reviewed nested map operations, with
first/all/count meaning visible in canonical IR.

### Proposal procedures and governance actions

`ProposalProcedure` is strictly encoded as:

```text
Constr 0 [deposit : Integer, returnAddress : Credential,
          governanceAction : Data]
```

The proposal decoder validates the outer tag/arity, deposit, and return
credential, but retains the third field as raw `Data`. E.4k introduces only a
narrow adapter:

```text
ProposalProcedure.actionStrict : Option GovernanceAction
```

Its meaning is exactly the pinned
`(IsData.fromData ppGovernanceAction : Option GovernanceAction)`. An absent
option means the raw field is not a valid pinned governance action. It does not
mean `InfoAction`, an empty action, or a default value.

The seven governance-action constructors are:

| Tag | Constructor | Ordered payloads |
|---:|---|---|
| 0 | `ParameterChange` | prior action ID, raw changed parameters, optional constitution script hash |
| 1 | `HardForkInitiation` | prior action ID, protocol version |
| 2 | `TreasuryWithdrawals` | withdrawals, optional constitution script hash |
| 3 | `NoConfidence` | prior action ID |
| 4 | `UpdateCommittee` | prior action ID, old members, new members, raw quorum |
| 5 | `NewConstitution` | prior action ID, optional constitution script hash |
| 6 | `InfoAction` | no payloads |

The strict decoder validates tags, arities, optionals, identifiers, protocol
versions, withdrawals, old/new committee collections, and script hashes.
`ChangedParameters` and `Quorum` accept raw `Data` under the pinned aliases.
Their presence and position are validated, but their contents remain
unavailable to the typed DSL until a later reviewed adapter milestone.

`Constitution` is only `Option ScriptHash` in this pinned model; anchors are
omitted. The DSL must name it accordingly and must not imply that it models a
complete on-ledger constitution.

The pinned `isKnownProposal` is exact indexed equality over the full proposal,
including raw governance-action `Data`. Negative indexes and out-of-range
indexes return false. This dedicated helper may be exposed without admitting
general raw-`Data` equality.

### Current-purpose rules

The pinned model defines `VotingScript Voter` and
`ProposingScript Integer ProposalProcedure`, plus `validVotingContext` and
`validProposingContext`. Those definitions are source truth for a future
purpose milestone. They are not enough to select an exact JuLC artifact:
CIP-57 still lacks truthful voting/proposing interface purpose names.

Schema 9 therefore exposes governance data only through `TxInfo` for the four
already selectable purposes. Current-voter/current-proposal roots remain
unavailable, and attempts to request voting/proposing domains fail before Lean
generation.

## Invariants

1. **Pinned type authority.** Every governance type, field, constructor,
   helper, tag, arity, role, and payload order comes from the pinned Lean model
   and reviewed capability inventory.
2. **Guarded sum access.** Voter, vote, and governance-action payloads are
   accessible only under the matching constructor guard. No cast, `asX`, or
   default payload node is admitted.
3. **Strict raw-action bridge.** A proposal's raw governance-action field is
   converted only through the pinned strict `IsData` decoder and produces an
   option. Malformed data never selects a constructor.
4. **Raw fields remain raw.** Changed parameters and quorum are not surfaced as
   typed values, equality operands, or Lean fragments in E.4k.
5. **Duplicate preservation.** Voter maps, nested vote maps, and proposal lists
   preserve raw order and duplicate occurrences. Unique/sorted behavior may be
   assumed only from a recorded valid-domain premise.
6. **Role preservation.** Hot-committee, DRep, stake-pool, cold-committee, and
   ordinary credential identities cannot be interchanged accidentally. Any
   permitted common-credential conversion is explicit and parent-validated.
7. **Purpose honesty.** Schema 9 cannot select, generate, or certify voting or
   proposing artifacts. Governance transaction-data support is not purpose
   support.
8. **One theorem envelope.** Exact UPLC success, purpose selection, ledger
   domain, fuel, and non-vacuity remain generator-owned. User formulas cannot
   move or duplicate them.
9. **Closed IR.** Only sealed schema-9 nodes and types are deserialized.
   Unknown kinds, fields, capabilities, binders, roles, or type identities fail
   before Lean generation.
10. **No UPLC effect.** Verification specifications and schema-9 support do
    not change emitted validator UPLC, CBOR, or script hashes.
11. **Honest results.** A solver result records its governance-data scope,
    domain, fuel, model pins, and counterexample qualification. It never claims
    governance authorization or whole-contract safety not stated by the
    formula.

## Decision

### 1. Add opt-in property schema 9

Schema 9 extends schema 8. Schemas 1 through 8 remain accepted with frozen
canonical bytes and unchanged meaning. Governance nodes, fields, and types are
rejected under earlier schemas.

The generated metamodel uses an explicit schema-9 factory. No existing project
silently opts into governance support.

### 2. Replace opaque transaction governance values with closed types

Schema 9 adds closed symbolic identities for:

- `Voter` and role-specific voter payloads;
- `Vote`;
- `GovernanceActionId`;
- `ProtocolVersion`;
- `GovernanceVoteMap` and `VoterMap`;
- `ProposalProcedure` and proposal lists;
- `GovernanceAction`;
- old and new committee member collections; and
- the typed optional/list/map payloads needed by governance actions.

The schema-9 `ScriptPurpose` map-key representation may replace E.4g's
`OPAQUE_VOTER`/`OPAQUE_PROPOSAL` placeholders with these exact closed types,
but only within schema 9. Schema-5-through-8 canonical IR stays frozen.

### 3. Reuse generic collection semantics

Voter and governance-vote maps use the existing ordered,
duplicate-preserving map core:

- `entries()` observes raw order and duplicates;
- `lookupFirst(key)` returns the first matching value;
- `lookupAll(key)` preserves every matching value;
- `containsKey(key)` and `countKey(key)` retain their reviewed meanings; and
- structural equality remains ordered pair-list equality.

Proposal procedures use the generic list core (`at`, `exists`, `all`, `none`,
and count). No general filter, fold, sorting, deduplication, or normalization
node is introduced.

Convenience methods such as `hasVote(voter, actionId, vote)` must lower to the
same generic canonical IR as their manual composition or receive a dedicated
node only when matching a pinned helper with materially different semantics.

### 4. Add guarded governance eliminators

Conceptually, the Java surface supports formulas such as:

```java
var drep = contract.context().txInfo().votes().entries().exists(entry ->
    entry.whenWellFormed((voter, actions) ->
        voter.whenDRep(drepCredential ->
            drepCredential.eq(expectedDRep)
                .and(actions.lookupFirst(actionId)
                    .exists(vote -> vote.isYes())))));
```

and:

```java
var hardFork = contract.context().txInfo().proposals().at(integer(0))
    .exists(proposal ->
        proposal.deposit().ge(minDeposit)
            .and(proposal.returnAddress().eq(expectedCredential))
            .and(proposal.actionStrict().exists(action ->
                action.whenHardFork((previous, version) ->
                    version.major().eq(integer(11))))));
```

Names are provisional. The semantic constraints are not: each payload is
available only inside its matching guard, and raw-action decoding is explicit
and optional.

For constructors containing deferred raw payloads, the guard still validates
the complete constructor shape and payload position. The callback exposes only
the reviewed typed payloads. It cannot access changed-parameter or quorum raw
data.

### 5. Admit narrow pinned helpers

E.4k may add:

- `isKnownVoter(voter, votes)` with exact upstream any-key semantics; and
- `isKnownProposal(proposal, index, proposals)` with exact upstream indexed
  full-equality semantics.

The latter is a dedicated reviewed operation because it compares the raw
governance-action field as part of pinned proposal equality. It does not make
general raw-`Data` equality admissible.

Current-purpose helper nodes for voting/proposing are not added.

### 6. Preserve the existing ledger-domain bridge direction

The selected spending/minting/rewarding/certifying valid-context premise
implies the Blaster-compatible solver domain, not the reverse. If the solver
domain omits `validVoterMap` because of translation limits, a theorem proved
over that larger set is stronger, while a counterexample is not automatically
ledger-valid. Certificates retain the conservative counterexample-domain
qualification.

JuLC must not silently add `validVoterMap`, proposal validity, or a
voting/proposing domain premise to make a desired theorem true. Any additional
domain restriction must be an allow-listed reviewed domain with a kernel
inclusion theorem.

Notably, pinned `validTxInfo` validates the voter map but does not globally
validate every proposal procedure in non-proposing contexts. Schema 9 must not
claim otherwise.

### 7. Record governance scope in results

For schema-9 properties, derived result metadata records at least:

- whether the formula observes transaction votes, transaction proposals, or
  both;
- whether raw proposal action data is strictly decoded;
- whether full proposal equality through `isKnownProposal` is used;
- `currentVotingPurposeModeled: false`; and
- `currentProposingPurposeModeled: false`.

Metadata is re-derived from canonical IR during runner preflight rather than
trusted from worker JSON. Existing result schemas retain compatibility
constructors and omit the new fields for schemas 1 through 8.

### 8. Keep voting/proposing artifact support as a separate decision

A later ADR may add exact voting/proposing verification only after choosing a
truthful, deterministic artifact identity. Acceptable directions include a
future standard CIP-57 vocabulary or a clearly labeled JuLC-specific manifest
that cannot be confused with standard CIP-57. That decision must cover CLI,
Gradle, annotation processing, playground, native resources, migration, and
consumer compatibility.

E.4k neither chooses nor prototypes that convention.

## Capability-inventory policy

A capability moves to `TYPED` only after its exact canonical IR, renderer,
parent validation, native metadata where necessary, semantic controls, and
compatibility gate exist.

Expected E.4k promotions include:

- transaction vote and proposal fields;
- voter/vote constructors;
- governance action ID and protocol-version fields;
- proposal deposit and return-address fields;
- strict proposal governance-action decoding;
- governance-action constructors and reviewed typed payloads;
- voter/governance-vote map types; and
- `isKnownVoter`/`isKnownProposal` if their controls and lowering are complete.

The following remain visibly deferred:

- `purpose.voting` and `purpose.proposing`;
- `ledger.validVotingContext` and `ledger.validProposingContext`;
- changed-parameter contents;
- quorum contents;
- current treasury and donation fields; and
- any omitted anchor data.

An inventory revision without matching source signatures and evidence fails
closed. Additive inventory drift invalidates workspace re-execution under the
new CLI but does not retroactively change hash-bound historical certificates;
the implementation outcome must document that distinction.

## Milestones

### E.4k.1 — Inventory, schema 9, and governance type authority

- Audit every pinned governance type, field, constructor, helper, alias, and
  raw-data boundary.
- Add schema-9 codec/admission and freeze schemas 1 through 8.
- Add voter, vote, action-ID, protocol-version, proposal, vote-map, and action
  symbolic types.
- Replace opaque script-purpose governance payloads only for schema 9.
- Reject forged types, aliases, roles, payload order, binders, and capabilities
  before Lean generation.

### E.4k.2 — Collections, guarded payloads, and strict action adapter

- Expose duplicate-preserving nested vote maps and proposal lists through the
  existing generic collection core.
- Add guarded voter and vote eliminators.
- Add proposal typed fields and `actionStrict()`.
- Add guarded eliminators for all seven governance-action constructors while
  withholding changed-parameter and quorum contents.
- Add exact `isKnownVoter` and `isKnownProposal` operations if their pinned
  semantics are fully controlled.
- Add canonicalization, dependency planning, rendering, parent revalidation,
  native reachability, and helper/manual equivalence tests.

### E.4k.3 — Lean semantics and domain controls

- Kernel-check every voter, vote, ID, protocol-version, proposal, and action
  tag/arity/payload order.
- Systematically reject wrong tags, short/trailing fields, wrong payload
  kinds, malformed nested maps, and malformed raw actions.
- Pin duplicate/order/lookup semantics for both map levels.
- Pin `isKnownVoter` and `isKnownProposal`, including negative and
  out-of-range indexes and duplicate proposals.
- Kernel-check that raw changed parameters/quorum cannot be projected by the
  generated surface.
- Reuse and recheck the existing valid-context-to-Blaster-domain bridge without
  silently strengthening it.

### E.4k.4 — Exact artifact evidence, performance, and documentation

- Add a real validator fixture that inspects at least one non-tautological
  proposal/vote property through ordinary transaction context.
- Add exact-VM positive, negative, duplicate, wrong-constructor, and malformed
  controls.
- Retain positive, vulnerable, and vacuous verification outcomes.
- Calibrate voter-map lookup and strict governance-action decoding separately;
  report timeouts or translation gaps instead of weakening formulas.
- Run local, Docker, and GraalVM-native positive workflows and compare all
  semantic hashes.
- Update the capability inventory, getting-started guide, roadmap, limitations,
  and implementation outcome.
- State prominently that exact voting/proposing validator verification remains
  unsupported.

## Required tests

### Admission and compatibility

- schemas 1 through 8 retain byte-identical canonical fixtures;
- governance nodes fail under schemas 1 through 8;
- schema 9 accepts governance data under spending, minting, rewarding, and
  certifying purposes;
- schema 9 rejects voting/proposing purposes and domains;
- unknown JSON fields, node kinds, constructors, roles, helpers, and types fail;
- consistently re-hashed forged payload roles/order/type graphs fail before
  Lean generation;
- binder escape, shadowing, and generated-name collisions fail deterministically;
- inventory revision/signature drift fails closed; and
- annotations and verification specifications have zero effect on UPLC.

### Voter and vote semantics

- voter tags 0–2 accept exact arity and reject wrong tags, arity, payload kind,
  and trailing fields;
- committee and DRep credentials retain their semantic role;
- stake-pool voter payload is not accepted as a credential voter;
- vote tags 0–2 accept only empty constructor payloads;
- vote equality distinguishes no, yes, and abstain; and
- guarded voter payload binders cannot escape their branch.

### Vote-map semantics

- outer and inner maps require `Data.Map` for strict decoding;
- raw entry order and duplicate voter/action keys are preserved;
- first/all/count lookups differ on duplicate keys;
- malformed outer voter, inner map, action ID, or vote rejects strict decoding;
- `isKnownVoter` checks only outer voter presence, exactly as pinned;
- a valid-domain control pins sorted/unique and non-empty-inner-map behavior;
  and
- no Java `Map` uniqueness assumption appears in generated semantics.

### Proposal and action semantics

- proposal tag 0 and arity 3 are strict;
- deposit and return credential wrong kinds reject;
- raw governance action remains observable only through `actionStrict()` or
  dedicated full proposal equality;
- malformed action tag/arity/payload yields `none`;
- all action tags 0–6 and payload orders are pinned;
- optional prior IDs and script hashes distinguish `some` from `none`;
- governance action ID and protocol version field order is pinned;
- treasury withdrawals preserve duplicate/order semantics;
- old/new committee collections preserve their list/map representation;
- changed parameters and quorum have no typed projection path;
- `isKnownProposal` rejects negative/out-of-range indexes and checks exactly one
  indexed full proposal, including raw action equality; and
- proposal-list duplicates remain visible.

### Evidence and certificate honesty

- exact VM execution covers representative positive and negative governance
  inputs;
- the positive property is not implied solely by the selected ledger domain;
- the vulnerable validator yields a useful counterexample;
- the vacuous validator stops proof promotion;
- result metadata is re-derived and rejects tampering before any process runs;
- counterexamples are not labeled ledger-valid without a separate witness;
- local, Docker, and native positive runs bind identical artifact, DSL IR,
  property IR, capability inventory, and generated Lean hashes;
- timeout/fuel/translation failure produces `COULD-NOT-EVALUATE`; and
- no certificate claims current voting/proposing purpose support.

## Affected modules

### `julc-verification`

- property schema 9;
- governance symbolic types and role wrappers;
- sealed nodes, validation, canonicalization, semantic dependencies, and Lean
  rendering;
- capability-inventory updates; and
- admission, semantic, equivalence, compatibility, and tamper tests.

### `julc-cli`

- schema-9 metamodel generation;
- generated Lean governance definitions and controls;
- result metadata and runner preflight;
- native reachability metadata; and
- exact-VM and workspace-generation tests.

### `verification/`

- E.4k fixtures, trusted specifications, scripts, workspaces, certificates,
  counterexamples, and documentation.

### Explicitly unaffected

- `julc-core`;
- `julc-compiler`;
- `julc-ledger-api`;
- `julc-stdlib`;
- `julc-blueprint`;
- CIP-57 serialization; and
- emitted validator UPLC/CBOR/script hashes.

If implementation discovers that an unaffected module must change, that part
stops and this ADR is revised before proceeding.

## Compatibility

- Schema 9 is explicit opt-in; schemas 1 through 8 retain their canonical
  values and meaning.
- No existing annotation changes semantics.
- Existing exact artifacts remain byte-identical.
- The capability-inventory hash changes additively; older certificates remain
  valid historical records but older generated workspaces require regeneration
  under the current CLI.
- E.4g's opaque voting/proposing script-purpose keys remain valid for schemas
  5 through 8. Schema 9 may use richer exact types without rewriting old IR.
- Voting/proposing blueprint failure behavior remains unchanged and actionable.

## Risks and mitigations

### Mistaking governance data support for purpose support

Mitigation: do not add voting/proposing enum values, roots, domains, or
blueprint aliases. Record both current-purpose modeled flags as false.

### Raw governance action creates a type-confusion path

Mitigation: one dedicated strict optional decoder; no generic cast, raw
inspection, default action, or raw Lean.

### Java ledger types appear richer than the pinned Lean model

Mitigation: the proof model is authoritative for verification. Changed
parameters and quorum stay deferred even if Java has convenience wrappers.

### Map uniqueness is assumed accidentally

Mitigation: reuse duplicate-preserving list-backed map semantics and add
first/all/count contrast controls. Treat uniqueness only as a domain premise.

### Proposal equality smuggles in general raw-data equality

Mitigation: admit only the dedicated pinned `isKnownProposal` helper and record
its full-equality semantics. Keep generic raw-data equality rejected.

### Governance formulas overwhelm the solver

Mitigation: separate kernel semantic support from automated discharge,
calibrate map lookup and action decoding independently, retain time/fuel in the
certificate, and report `COULD-NOT-EVALUATE` rather than weakening formulas.

### Model incompleteness is mistaken for ledger completeness

Mitigation: certificate and docs pin the model revision and state omitted
anchors/raw payloads. Claims are relative to that model.

### Generated Lean or certificate metadata diverges

Mitigation: derive both from canonical IR, hash the full generated tree, and
re-derive metadata during runner preflight.

## Rejected alternatives

### Add voting/proposing purposes using invented CIP-57 strings

Rejected because it would publish a non-standard or misleading interface and
break exact-artifact honesty.

### Treat governance transaction data as unavailable until CIP-57 changes

Rejected because ordinary selectable validators can already reason about
votes and proposals in `TxInfo`; that useful typed surface is independent of
current voting/proposing artifact selection.

### Follow the Java ledger model instead of the pinned Lean model

Rejected because proof generation must use the actual theorem model. Richer
Java wrappers cannot create semantics missing from the pinned Lean source.

### Expose proposal action `Data` directly

Rejected because it permits raw-shape formulas, ambiguous defaults, and a path
to raw Lean. The strict option adapter is narrower and reviewable.

### Decode malformed action data as `InfoAction`

Rejected because it conflates malformed data with the valid tag-6 empty
constructor and could establish a false security property.

### Normalize vote maps to Java maps

Rejected because it erases duplicates and order representable at the raw
boundary and changes lookup semantics outside a valid-domain premise.

### Add one fixed governance annotation

Rejected because voter, vote, proposal, and action policies require composition
with contract fields, signers, values, and transaction context. A fixed
resolver would duplicate the shared IR and cover only one formula shape.

### Expose arbitrary Lean for unsupported governance payloads

Rejected because it bypasses the closed IR, type authority, admission checks,
hash interpretation, and certificate semantics.

## Open questions

These questions may refine API shape or evidence selection. They do not permit
weakening the invariants:

1. Should `isKnownProposal` be exposed publicly despite comparing the raw
   action field, or remain an internal semantic/domain helper until a concrete
   user property needs it?
2. Can nested voter/action lookup produce useful Blaster counterexamples at a
   practical fuel/time bound, or should the first positive solver slice focus
   on proposal deposit and strictly decoded hard-fork version?
3. Should role-specific credential wrappers offer an explicit conversion to a
   common credential view everywhere, or only for comparisons already admitted
   by E.4i?
4. Is a JuLC-specific artifact manifest for voting/proposing desirable if
   CIP-57 remains unchanged, or should JuLC wait for a standard revision? This
   requires a separate ADR either way.
5. Which changed-parameter and quorum encodings should E.4l review first? E.4k
   must not infer them from Java implementation details.

## Acceptance criteria

E.4k is complete only when:

- schema 9 represents the reviewed voter, vote, action-ID, protocol-version,
  proposal, vote-map, and governance-action surface with closed typed IR;
- all raw-data boundaries and deferred payloads remain explicit and
  inaccessible through typed projection;
- every promoted capability has parent validation and kernel semantic controls;
- duplicate/order/malformed behavior is pinned for both map levels and proposal
  lists;
- current voting/proposing purpose selection remains fail-closed;
- exact-artifact positive, vulnerable, and vacuous evidence exists without a
  domain-tautological positive claim;
- impractical solver operations are documented rather than weakened;
- schemas 1 through 8 remain frozen;
- local, Docker, and native positive semantic hashes agree;
- affected-module and repository-wide builds pass; and
- compiler/runtime modules and emitted UPLC remain unchanged.

## Permitted claims after completion

JuLC may claim that, for a named exact spending, minting, rewarding, or
certifying artifact and recorded model/domain/fuel/dependencies, a stated
property over transaction governance data was established, refuted, or could
not be evaluated.

JuLC may not claim from E.4k alone that:

- voting or proposing validators can be verified;
- the pinned governance model is complete;
- raw changed parameters or quorum are typed;
- a counterexample is ledger-valid without separate evidence;
- a transaction vote proves off-chain identity authorization beyond the
  modeled premises; or
- the contract is formally verified and safe in all respects.

## Review and merge sequence

1. Review this ADR against the pinned `Governance.lean`, `Contexts.lean`,
   capability inventory, ADR-017 purpose boundary, and current generated
   domain bridge.
2. Finish and merge E.4j to the E.4 integration branch.
3. Create `feat/typed-verification-dsl-e4k-governance-data` from the updated
   integration branch.
4. Implement E.4k.1 through E.4k.4 with review/test/learn/iterate gates.
5. Obtain manual review before committing the milestone.
6. Merge only to `feat/typed-verification-dsl-e4`; merge to `main` remains
   deferred until the intended E.* integration series is complete.

## Implementation outcome (2026-08-22)

Schema 9 is implemented on the dedicated E.4k branch without changes to the
compiler, core, ledger API, stdlib, blueprint generator, or emitted UPLC. It
adds closed voter/vote/action-ID/protocol-version/proposal/action identities,
duplicate-preserving nested vote maps, guarded eliminators, a strict optional
proposal-action adapter, parent revalidation, canonical dependency planning,
Lean lowering, runner metadata, and kernel semantic controls.

Raw `ChangedParameters` and `Quorum` payloads are present in guarded Lean
patterns but have no Java DSL projection path. Whole proposal and governance-
action equality are rejected; the exact upstream full-proposal comparison is
available only through `isKnownProposal`. Opaque voting/proposing
`ScriptPurpose` payloads remain frozen rather than being rewritten in schema 9,
because E.4k adds transaction-data support, not current-purpose support.

The local exact-artifact evidence produced:

- authorized minimum-deposit property: `SMT-VALID` for compiled-code SHA-256
  `33b4ec76520f3c430a0ffb67b2eb6dc33704240826fd16177c1282e0282e5da3`
  and script hash `5d417c251cd6cec84510215b7c8aeee4654e0540dbc14e0781c14fe7`;
- deliberately vulnerable strict hard-fork property: `REFUTED`; and
- never-successful validator: `COULD-NOT-EVALUATE/property-vacuous`.

The positive DSL IR, property IR, and generated Lean hashes are respectively
`6840db5313b68617e05cbf4d670f4b1fc52bd93aabfd674d488743a048e18bff`,
`1ff2dff25967a9550f6c40da8b9088de06208a889d51d06ad64d270697b29ed0`,
and `171b076d0c9ea37fbc1969f39a1f04b69d7ffcae3008073f10691138d5fe3e02`.
The capability inventory is now
`aef541d492decb2fd5d1868b22490fa275d829acfbee83f995e81db2dc6b39c1`.
This additive drift makes older generated workspaces require regeneration but
does not alter their hash-bound historical certificates.

The first attempted positive theorem combined the deposit rule with strict
hard-fork decoding. Blaster correctly refuted it with a malformed proposal
action: the pinned valid spending domain validates voter maps but does not
globally validate proposal actions, and the exact Java artifact projects that
nested ledger value permissively. The implementation retains strict decoding
and documents the counterexample instead of weakening it. The positive theorem
therefore states the independently non-tautological minimum-deposit condition;
strict action semantics remain kernel-controlled and conservatively refutable
under this domain.

The final review round expanded the hash-bound kernel controls so every
promoted voter and governance-action constructor is covered, including exact
tags and arities, round trips, systematic short/trailing/wrong-kind failures,
both levels of duplicate-preserving map lookup, malformed proposals,
`isKnownVoter`, and prior-action-ID presence. A real compiled-artifact VM test
covers the accepted case plus wrong deposit, version, action constructor,
proposal arity, return-credential kind, and duplicate/order behavior. Result
metadata is re-derived from canonical capabilities and records governance
scope, strict action decoding, full-proposal equality use, and the deliberately
unsupported current voting/proposing purposes.

Local JVM, Docker, and GraalVM-native positive workflows now bind identical
artifact, DSL IR, property IR, and generated Lean hashes. The local retained
matrix also records the expected `REFUTED` and
`COULD-NOT-EVALUATE/property-vacuous` controls. Affected-module tests, direct
Lean elaboration, and the repository-wide Gradle build pass.
