# ADR-024: Milestone E.4h — Compositional Authorization Algebra

- **Status:** Implemented experimentally; manual review pending
- **Date:** 2026-08-21
- **Parent:**
  [ADR-016 — Typed Verification DSL and Foundational Profile Catalog](016-typed-verification-dsl-and-profile-catalog.md)
- **Prerequisites:**
  [ADR-019 — Compositional Property Promotion Core](019-milestone-e4b-compositional-property-promotion-core.md),
  [ADR-022 — Generic Contract Types and Collections](022-milestones-e4e-e4f-generic-contract-types-and-collections.md), and
  [ADR-023 — Typed Non-Value Transaction Context](023-milestone-e4g-typed-non-value-transaction-context.md)
- **Pinned model:** `CardanoLedgerApiBlaster` V3 revision
  `5dab3c43f042b8735b6d067223baaa8d32ed28a1`
- **Expected milestone branch:**
  `feat/typed-verification-dsl-e4h-authorization-algebra`
- **Integration branch:** `feat/typed-verification-dsl-e4`

## Context and problem

JuLC can currently express that one public-key hash occurs in the complete
transaction signatory list. The same membership meaning is used by
`@RequiresSigner`, the stateful-spending profile, controlled minting, and the
typed DSL.

Real authorization policies are often broader:

- the owner **or** recovery key may authorize an action;
- the owner **and** auditor must both sign;
- at least two of three committee members must sign;
- exactly two approved members may sign;
- every signer must belong to an allow-list;
- all expected signers must be present and no other signer may be present;
- authorities may be fixed in the property, selected from typed datum or
  redeemer fields, or applied as deployment parameters; and
- different actions in one redeemer sum may have different authorization
  policies.

These are foundational relations over the signatory list and typed contract
data. Adding a fixed annotation/template pair for every combination would
conflict with ADR-019's compositional design. Asking users to manually expand
thresholds into large Boolean formulas would make duplicate handling,
canonicalization, diagnostics, and certificates inconsistent.

The pinned V3 model represents signatories as an ordered
`List PubKeyHash`. Its `txSignedBy` helper is ordinary membership. The model
also contains an upstream helper named `onlySingedBy`; despite the spelling,
its meaning is exact structural equality with the singleton list `[pk]`. It
is not a general allow-list relation and is not suitable as the semantics for
JuLC's authorization algebra.

Lists in the model can contain repeated values unless a separately selected
ledger-domain predicate establishes otherwise. An M-of-N policy must therefore
not be satisfied multiple times by repeated authority entries or repeated
signatory entries. The algebra needs explicit set-like identity semantics on
top of the raw ordered list while preserving the raw list elsewhere in the
DSL.

## Goals

- Add compositional any, all, none, at-least, and exactly-N authorization
  predicates.
- Add an explicit no-unexpected-signers/allow-list predicate.
- Add an exact-authorized-set predicate as the conjunction of all-required
  and no-unexpected-signers semantics.
- Count distinct authorized public-key hashes, independent of duplicate or
  order artifacts in either source authorities or transaction signatories.
- Accept fixed authorities and authorities obtained from compiler-owned typed
  datum/redeemer fields and supported contract collections.
- Support deployer-applied parameter authorities only when their values are
  demonstrably bound to the exact verified UPLC artifact.
- Permit the authorization expressions to compose freely with all other
  purpose-compatible DSL predicates.
- Lower the existing singleton `@RequiresSigner` meaning and the equivalent
  DSL expression to one canonical semantic path.
- Keep ledger-domain assumptions, exact UPLC execution, and non-vacuity in the
  mechanically controlled theorem envelope.
- Preserve schema-1 through schema-5 canonical values and evidence.
- Keep the feature in optional verification/CLI modules with zero effect on
  validator UPLC, CBOR, or script hash.

## Non-goals

- Verify cryptographic signatures. The ledger supplies public-key hashes in
  `txInfoSignatories`; the property reasons about that modeled field.
- Prove that a public-key hash belongs to a real-world person or organization.
- Treat list occurrences as independent signers.
- Treat `exactly N approved signers signed` as equivalent to `there were no
  other signers`.
- Silently assume an authority list is non-empty, unique, correctly sized, or
  well governed.
- Add arbitrary raw Lean, unchecked assumptions, user-defined folds, or
  unbounded recursion.
- Add value, validity-interval, certificate-payload, or governance algebra.
- Add voting/proposing exact-artifact selection while CIP-57 lacks truthful
  purpose vocabulary for those interfaces.
- Stabilize the experimental Java DSL or approve new public annotations.
- Claim whole-contract safety from one authorization property.

## Terminology

For one property evaluation, let:

- `S` be the raw ordered `txInfoSignatories` list;
- `A` be the ordered list of authority expressions supplied to one
  authorization expression;
- `set(S)` and `set(A)` mean equality-deduplicated public-key-hash identities;
  and
- `signed(A, S)` be `set(A) ∩ set(S)`.

Deduplication here defines only the authorization relation. It does not change
the representation or structural equality of either underlying list.

## Required semantics

### Membership and threshold relations

The canonical relations are:

| DSL meaning | Required semantics |
|---|---|
| `anySigned(A)` | `|signed(A, S)| >= 1` |
| `allSigned(A)` | `set(A) ⊆ set(S)` |
| `noneSigned(A)` | `|signed(A, S)| = 0` |
| `atLeastSigned(k, A)` | `|signed(A, S)| >= k` |
| `exactlySigned(k, A)` | `|signed(A, S)| = k` |
| `noUnexpectedSigners(A)` | `set(S) ⊆ set(A)` |
| `exactSignerSet(A)` | `set(S) = set(A)` |

`exactlySigned(k, A)` counts only approved authorities that signed. It does
not reject a signer outside `A`. A policy requiring both exactly `k` approved
signers and no others must say so explicitly:

```java
authorities.exactlySigned(2)
        .and(authorities.noUnexpectedSigners())
```

This separation prevents a threshold API from silently becoming an
allow-list API.

### Duplicate and ordering behavior

- Repeating one authority in `A` never increases the threshold count.
- Repeating one signer in `S` never increases the threshold count.
- Reordering either list does not change an authorization result.
- Raw structural list equality elsewhere in the DSL remains ordered and
  duplicate-sensitive.
- Fixed authority literals supplied as one static builder set are rejected if
  byte-identical duplicates are visible during construction. Dynamic
  duplicates remain representable and are handled by the distinct semantics.

The implementation may use a reviewed distinct-count helper, a bounded
combination expansion, or another kernel-tested definition. It may not lower
thresholds to raw list occurrence counts.

### Empty authority sets and threshold bounds

The API must avoid accidental vacuous authorization:

- a statically constructed authority set rejects zero members;
- `anySigned` and `atLeastSigned(k)` for positive `k` are false when fewer than
  `k` distinct authorities are available; `atLeastSigned(0)` is explicitly
  true;
- `allSigned` over a dynamically empty authority collection follows the
  mathematical subset meaning and is therefore true;
- `noneSigned` is the explicit way to require that none of the selected
  authorities signed;
- `noSigners()` is the explicit way to require an empty transaction
  signatory set; and
- thresholds are non-negative canonical integers with an implementation
  bound reviewed against AST size and solver behavior. Negative, overflowing,
  or unsupported thresholds fail before Lean generation.

Generated documentation must warn when a dynamic `allSigned` source can be
empty. Profiles intended to prove positive authorization should normally
compose it with a non-empty source predicate or use `atLeastSigned(1)`.

### Authority identity

An authorization member has the pinned `PubKeyHash` representation. Sources
are admitted as follows:

1. **Fixed literal:** exactly 28 bytes of canonical hexadecimal property data,
   subject to the pinned solver-translation restriction below.
2. **Typed contract value:** a compiler-owned `byte[]` field or collection
   value deliberately bridged to `PubKeyHash` by an authorization-source
   operation.
3. **Applied parameter value:** a supported `@Param` value only after exact
   parameter application is independently reconstructed and hash-checked.

The pinned Lean model represents `PubKeyHash` with bytes. Bridging a runtime
contract `byte[]` does not itself prove a 28-byte length invariant. If the
datum supplies a malformed-length value, membership will normally be false;
the theorem must not silently assume canonical length. A future bytes-length
primitive may express that invariant explicitly.

During implementation, an exact-UPLC/DSL conformance control found that a
fixed key hash containing byte `00` is not represented consistently by the
pinned Blaster UPLC-constant translation and the Lean property-literal path.
The Lean kernel and JuLC VM both preserve the byte, but Blaster produced a
countermodel in which the UPLC-side 28 zero bytes became an empty byte string.
Until the pinned dependency fixes that discrepancy and a negative control
locks the fix, schema 6 rejects fixed authority hashes containing any `00`
byte before Lean generation. Compiler-owned dynamic contract byte sources do
not embed a second fixed property literal and remain admitted. This is a
solver-coverage limitation, not a claim that Cardano key hashes exclude zero
bytes.

## Proposed Java surface

Names remain experimental, but the semantic distinction must survive any API
renaming. A representative schema-6 property is:

```java
public DslPropertySet properties() {
    var contract = new TreasuryModel();
    var auth = contract.authorization();

    var authorized = contract.datum().exists(datum -> {
        var authorities = auth.authorities(
                auth.fromContractBytes(datum.owner()),
                auth.fromContractBytes(datum.recovery()),
                auth.fixed("41".repeat(28)));

        var approved = authorities.atLeastSigned(2);
        var allowListed = authorities.noUnexpectedSigners();
        return approved.and(allowListed);
    });

    return contract.properties(property(
            "treasury.two-of-three-approved-only",
            DslDomain.VALID_SPENDING_V3_PINNED,
            authorized));
}
```

The example is representative rather than a frozen Java API; its important
property is that strict datum decoding is part of the guarantee and the
authorization expression remains an ordinary composable `BoolExpr`.

Action-dependent authorization remains ordinary composition:

```java
contract.redeemer().exists(action ->
    action.whenSpend(spend -> ownerOnly.exactSignerSet())
          .or(action.whenRecover(recover -> ownerOrRecovery.anySigned())))
```

Common helpers such as `ownerOrRecovery(...)`, `twoOfThree(...)`, or a later
annotation are AST builders only. They receive no privileged promotion or
proof path.

## Decision

### 1. Introduce opt-in property schema 6

E.4h adds an explicit inner DSL schema 6. It includes schema-5 contract and
ledger vocabulary unchanged and adds only the reviewed authorization nodes
and sources in this ADR.

Users select it explicitly, for example:

```text
julc verify dsl-init . --validator Treasury --schema-version 6
```

Schema-1 through schema-5 sources do not auto-upgrade. Old readers reject
schema 6, and current readers reject unknown source/relation variants. The
certificate-facing composed property protocol may remain schema 3 and
`julc.dsl-ledger/v1` if the authenticated dependency plan distinguishes the
new operations unambiguously; otherwise it receives a reviewed protocol or
template revision. Existing template meanings cannot be widened silently.

### 2. Add a closed authorization IR family

The IR will represent:

- fixed public-key-hash sources;
- explicitly bridged typed contract byte-string sources;
- bounded authority collections;
- any/all/none/at-least/exactly relations;
- no-unexpected-signers;
- exact signer-set equality; and
- explicit empty-signatory testing.

Worker JSON cannot invent a source, threshold mode, public-key-hash coercion,
or collection type. Parent validation re-derives every contract path from the
compiler-owned projection and every ledger operation from the pinned
capability inventory.

The IR remains a closed, typed formula language. It contains no generated Lean
identifier, raw Lean fragment, Java callback body, or arbitrary helper name.

### 3. Use semantic authorization nodes, not fixed full-formula templates

Threshold and allow-list relations are foundational operations. They may have
dedicated nodes and reviewed Lean definitions so their distinct-count meaning
is auditable and solver behavior can be profiled. A node takes typed authority
expressions and produces a Boolean expression; it does not own the complete
property.

Users may place that expression under supported Boolean composition, guarded
datum/redeemer variants, optional elimination, and other schema-6 predicates.
Promotion remains generic and does not recognize a complete two-of-three or
owner-or-recovery formula.

### 4. Keep exact execution and ledger domains outside the algebra

The authorization expression is only the guarantee. The generated theorem
retains the ADR-019 envelope:

```text
selected purpose
  ∧ optional reviewed purpose-specific ledger domain
  ∧ successful exact UPLC execution within recorded CEK fuel
  ⇒ authorization guarantee
```

Per-claim non-vacuity remains separate. The algebra cannot introduce,
duplicate, move, or weaken exact execution or domain premises.

### 5. Parameter sources require exact applied-artifact binding

`@Param` declarations describe unapplied script lambdas. A declaration name
and type in `ContractSchema` do not prove which deployment value was applied.
Therefore E.4h must not expose `contract.parameter("owner")` merely because a
blueprint publishes such a parameter.

Parameter-derived authority is admitted only if all of the following are
implemented:

1. JuLC obtains canonical parameter `Data` values from an explicit,
   hash-bound verification input.
2. JuLC applies them in declared order to the exact unapplied compiled code.
3. The resulting program bytes and Cardano script hash equal the exact
   artifact selected for verification.
4. The parameter value is strictly decoded according to the compiler-owned
   parameter type before it becomes a typed symbolic root.
5. The manifest and certificate bind the unapplied artifact, ordered applied
   values, applied artifact, parameter schema, and both hashes.
6. Runner preflight reconstructs the application and equality checks before
   starting Lean, Blaster, or Z3.

If that chain is not completed within E.4h, fixed and datum/redeemer authority
sources may ship while parameter sources remain fail-closed and are recorded
as deferred rather than simulated with a free property literal.

### 6. One singleton authorization semantics

The existing `@RequiresSigner("datum.owner")` semantics must be equivalent to
a singleton `anySigned`, singleton `allSigned`, and at-least-one expression.
Where schema versions differ, tests compare the normalized semantic meaning
and generated Lean rather than requiring historical canonical JSON bytes to
change.

No existing annotation is reinterpreted as no-unexpected-signers. A required
signer property permits additional signers exactly as it does today.

### 7. Certificate vocabulary stays precise

Each claim records at least:

- relation kind;
- authority-source kinds;
- fixed literals or authenticated contract/parameter paths;
- threshold and implementation bound, where applicable;
- duplicate policy: distinct public-key-hash identities;
- whether unexpected signers are constrained;
- purpose and ledger-domain selection;
- exact UPLC/artifact hashes and applied-parameter binding, if any;
- generated Lean and canonical IR hashes;
- fuel, recursion depth, solver timeout, tool pins, and observed result; and
- conservative counterexample qualifications inherited from ADR-019.

`SMT-VALID` means the recorded implication was established within these
bounds. It is not rendered as “multisig is secure” or “the transaction was
signed by real owners.”

## Invariants

1. Authorization counts distinct public-key-hash identities, never list
   occurrences.
2. Threshold and no-unexpected-signers semantics are separate and visibly
   composable.
3. Every authority source is parent-authorized from a fixed literal,
   compiler-owned contract path, or exactly applied parameter binding.
4. A worker cannot invent a key-hash coercion, source path, threshold mode, or
   helper identifier.
5. The raw signatory list remains available with its pinned order and
   duplicate behavior; authorization does not normalize the ledger model.
6. The exact UPLC premise and ledger-domain premise remain fixed envelope
   components rather than user-authored guarantee nodes.
7. Datum/redeemer absence or strict-decode failure makes an authority property
   requiring that value false; it never becomes an assumption.
8. Parameter declarations alone are insufficient authority for a symbolic
   parameter root.
9. Canonicalization is deterministic, alpha-stable, idempotent, and does not
   reorder semantically ordered contract paths.
10. Derived capability and rule identifiers are authenticated; a spelling or
    semantic change either preserves compatibility or requires explicit
    workspace regeneration/protocol versioning.
11. Unknown modes, malformed hashes, unsupported collections, resource-limit
    overflow, solver uncertainty, and stale capability inventories fail
    closed.
12. Verification declarations remain observational and have zero effect on
    compiler output.

## Module and ownership boundaries

Expected production changes are limited to:

- `julc-verification`: sealed IR nodes, typed DSL builders, parent validation,
  canonicalization, semantic dependencies, capability inventory, generated
  metamodel conveniences, and deterministic Lean rendering;
- `julc-cli`: schema selection, exact applied-parameter binding if delivered,
  workspace/runner preflight, native-image metadata, result classification,
  and documentation; and
- `verification/e4h`: fixtures, Lean controls, exact-VM tests, scripts, and
  retained certificate evidence.

No E.4h implementation belongs in `julc-core`, `julc-compiler`,
`julc-ledger-api`, `julc-stdlib`, optimizer, ordinary PIR lowering, strict
boundary generation, or blueprint generation. If exact applied-parameter
verification reveals a missing compiler-owned observation, that requires a
separate narrowly reviewed interface rather than making compiler output depend
on the verification DSL.

## Implementation milestones

### E.4h.1 — Semantics, schema-6 IR, and compatibility freeze

- Pin authorization semantics to the complete V3 signatory list.
- Add closed authority-source and authorization-relation nodes.
- Add parent validation, canonicalization, resource bounds, semantic
  dependencies, serialization, and strict unknown-field rejection.
- Define distinct counting, empty-source behavior, and threshold limits.
- Freeze schema-1 through schema-5 canonical fixtures and retained evidence.
- Update the capability inventory for newly reviewed authorization relations;
  do not describe the upstream singleton structural helper as an allow-list.

### E.4h.2 — Fixed and typed contract authority sources

- Add fixed 28-byte key-hash authorities.
- Add explicit byte-string-to-public-key-hash bridges for typed datum,
  redeemer, nested record/variant, optional, and collection values.
- Add generated Java conveniences without granting generated names semantic
  authority.
- Render any/all/none/threshold/allow-list/exact-set predicates
  deterministically in Lean.
- Kernel-check duplicate, ordering, dynamic-equality, empty-source, and
  threshold-bound controls.
- Prove singleton equivalence with the existing required-signer meaning.

### E.4h.3 — Applied parameters and purpose-wide composition

- Implement the exact applied-parameter binding chain from Decision 5, or
  leave parameter authority roots explicitly unsupported with tests and
  diagnostics.
- Exercise authorization composition under spending, minting, rewarding, and
  certifying exact-artifact selection.
- Verify action-dependent policies over typed redeemer variants.
- Confirm a property can combine authorization with E.4g reference/input
  predicates and earlier minting/rewarding/certifying primitives without a
  formula-specific resolver.
- Extend GraalVM reachability metadata for every new sealed subtype and
  generated worker path.

### E.4h.4 — Evidence, negative controls, and documentation

- Add an authorized two-of-three fixture. Kernel- and VM-check the independent
  no-unexpected-signers meaning; attempt the combined SMT theorem and record a
  fail-closed solver limitation if the pinned stack cannot discharge it within
  the documented calibration window.
- Add an owner-or-recovery action-dependent fixture.
- Add vulnerable controls for one-of-three, duplicate-authority counting,
  duplicate-signatory counting, and unexpected signers.
- Add malformed fixed-hash, invalid threshold, missing datum/redeemer,
  malformed strict decode, vacuous-validator, and solver-undetermined paths.
- Add an exact-VM execution test for at least one actual threshold validator;
  do not rely only on a hand-written property model.
- Reproduce a positive property through local, Docker, and GraalVM-native CLI
  launchers with identical semantic hashes.
- Record performance deltas for one, three, and the maximum supported number
  of authorities, including Lean generation, SMT solve time, and CEK fuel.
- Document examples, trust boundaries, bounded claims, parameter support or
  deferral, and certificate interpretation.

## Verification strategy

### Semantic controls

Kernel-reduced or kernel-proved controls cover:

- one required signer present/absent;
- owner-or-recovery with each, both, and neither present;
- all-required with missing members;
- at-least and exactly thresholds at zero/below/at/above the boundary;
- repeated authority values;
- repeated signatory values;
- reordered authorities and signatories;
- unexpected signers inside and outside the allow-list;
- exact signer-set equality;
- dynamic empty authority-source semantics and static empty-set admission
  rejection; and
- singleton equivalence with `txSignedBy`/`@RequiresSigner` meaning.

### Admission and canonicalization

Tests reject before Lean generation:

- malformed or non-28-byte fixed key hashes;
- fixed key hashes containing `00` while the pinned UPLC/property literal
  translations disagree;
- unknown source/relation subtypes and unknown JSON fields;
- a contract path with the wrong compiler-owned type;
- an implicit raw-`Data`-to-key-hash cast;
- an unbound or mismatched parameter value;
- negative, noncanonical, overflowing, or above-bound thresholds;
- duplicate visible fixed literals in one static set;
- envelope roots inside a user guarantee;
- purpose/domain mismatches;
- binder escapes, node-budget exhaustion, and generated-name collisions; and
- tampered canonical IR, type/projection graphs, capability plans, parameter
  applications, generated Lean, or runner plans.

Equivalent fixed-set orderings, Boolean associations, and alpha-renamed
binders canonicalize deterministically. Dynamic source order remains
semantically irrelevant to authorization but its contract paths remain
authenticated and reviewable in the canonical IR.

### Exact-artifact and runtime evidence

- The actual JuLC validator succeeds and rejects expected controls through
  the supported VM/testkit.
- Every theorem imports the exact hash-bound UPLC artifact.
- Positive evidence runs only after a non-vacuity control succeeds.
- Selected ledger-domain bridges and per-claim corollaries kernel-compile.
- Refutations retain raw Blaster models and do not claim ledger-valid or
  concrete-VM witnesses unless independently established.
- Local, Docker, and native launchers bind identical artifact, canonical DSL,
  property IR, Lean, pins, and bounds.

### Regression and module boundary

- Existing annotation, schema-1–5, verification, and CLI suites rerun fresh.
- Retained E.3–E.4g evidence either re-verifies unchanged or receives an
  explicit fail-closed regeneration note for authenticated inventory changes.
- Affected Gradle modules and repository-wide `./gradlew build` pass, with
  optional skipped tasks listed.
- No core compiler or ordinary on-chain lowering source changes.
- Byte-identity tests confirm verification source and schema version do not
  alter UPLC, CBOR, or script hash.
- `git diff --check` passes and milestone files are staged independently of
  unrelated working-tree content.

## Compatibility and migration

- Schema 6 is opt-in and experimental.
- Schemas 1–5 do not auto-upgrade or gain new admitted node meanings.
- Existing `@RequiresSigner` continues permitting additional signers.
- Existing controlled-mint/stateful profiles retain their recorded semantics;
  they are not silently changed into allow-list or threshold properties.
- Adding schema-6 verification changes no deployed script.
- Capability inventory changes may require explicit workspace regeneration
  while leaving historical certificates as hash-bound records of their runs.
- Applied parameter support, if delivered, produces a distinct exact script
  hash for each parameter set. A certificate for one applied artifact says
  nothing about another parameterization.

## Risks and mitigations

- **Duplicate entries satisfy a threshold twice.** Count distinct authority
  identities and pin duplicate controls in Lean and Java tests.
- **Exactly-N is misunderstood as no extra signers.** Keep threshold and
  allow-list relations separate in APIs, IR, docs, and certificates.
- **`allSigned` succeeds for an empty dynamic set.** Document the mathematical
  meaning and provide explicit non-empty/at-least-one patterns.
- **A blueprint parameter declaration is mistaken for an applied value.**
  Require byte-for-byte exact parameter application reconstruction.
- **Contract bytes are assumed to be a canonical key hash.** The identity
  bridge makes no length theorem; malformed runtime values remain visible to
  the property.
- **Combinatorial threshold lowering overwhelms Blaster.** Use a reviewed
  bounded semantic helper, profile scaling, cap the admitted authority count,
  and classify undetermined outcomes honestly.
- **Allow-list semantics depend on signatory ordering.** Define them through
  membership/subset relations, not structural list equality.
- **A convenience annotation creates a second semantics.** Require canonical
  equivalence with the generic IR and renderer.
- **Authorization success is presented as contract safety.** Certificates and
  CLI output name the exact property, domain, artifact, and bounds only.
- **Verification expansion changes on-chain code.** Module-boundary and
  byte-neutrality gates remain mandatory.

## Alternatives considered

- **Add `@RequiresAnySigner`, `@RequiresAllSigners`, and one annotation per
  threshold.** Rejected as the primary design because it creates a profile
  catalog rather than a compositional foundation. Selected helpers may be
  added later over the same IR.
- **Ask users to write AND/OR expansions manually.** Rejected because
  thresholds, duplicate identities, bounds, and certificates would be
  inconsistent and error-prone.
- **Count occurrences in `txInfoSignatories`.** Rejected because repeated
  entries are not independent authorities.
- **Reuse upstream `onlySingedBy`.** Rejected because its singleton structural
  list equality is not a general allow-list or distinct-set relation.
- **Normalize the ledger signatory list globally.** Rejected because it would
  change raw modeled list semantics outside authorization.
- **Treat exactly-N as exactly-N-and-no-others.** Rejected because it hides a
  materially stronger policy inside an ambiguous name.
- **Expose blueprint parameters directly as symbolic values.** Rejected
  because declarations do not authenticate applied values or exact artifact
  identity.
- **Widen schema 5 in place.** Rejected because E.4g canonical operations and
  evidence are already reviewable as a frozen opt-in surface.

## Open implementation questions

1. Which distinct-count Lean definition gives the best combination of clear
   semantics, kernel controls, Blaster translation, and solver performance?
2. What maximum number of authority expressions and threshold value should be
   admitted initially?
3. Should a dynamic contract list of authority bytes be accepted in E.4h, or
   should the first slice accept only a bounded vararg set of typed
   expressions while retaining a clear later extension path?
4. Can the current artifact workflow represent an applied parameterized
   blueprint without ambiguity, or does exact parameter binding require a
   separately versioned artifact manifest?
5. Should schema 6 keep certificate template `julc.dsl-ledger/v1`, or should
   its new authenticated semantic inventory use `julc.dsl-ledger/v2`?
6. Which common authorization formulas merit later annotations after generic
   DSL evidence demonstrates stable semantics and useful diagnostics?

These questions affect implementation shape or resource bounds, not the
semantic invariants above. Any answer that weakens distinct identity counting,
exact applied-parameter binding, or the fixed theorem envelope requires an ADR
revision before implementation.

## Resolved implementation decisions

- Distinct identities are defined by a reviewed recursive Lean helper and
  kernel controls. Static authority sets are canonicalized by member bytes;
  dynamic collection order remains authenticated but authorization results are
  set-like.
- Static authority sets admit at most 16 expressions, and threshold literals
  are canonical nonnegative integers bounded at 16. Dynamic `List<byte[]>`
  sources are admitted with their mathematical empty-list behavior.
- Schema 6 retains outer certificate template `julc.dsl-ledger/v1`; the inner
  schema number, canonical IR hash, source/relation rules, and capability plan
  authenticate the new meanings without widening schema 5.
- Applied parameter authority roots are deferred fail-closed. Current
  `ContractSchema` parameter declarations do not authenticate the ordered
  applied values or resulting exact UPLC artifact.
- Fixed authority literals containing byte `00` are deferred fail-closed due
  to the observed pinned Blaster translation mismatch described above.
- No new public annotations are introduced. Existing singleton
  `@RequiresSigner` semantics remain unchanged and are kernel-related to
  singleton membership.

## Implementation outcome

The experimental schema-6 slice is implemented on
`feat/typed-verification-dsl-e4h-authorization-algebra` with no changes to
`julc-core`, `julc-compiler`, `julc-ledger-api`, `julc-stdlib`, blueprint
generation, or ordinary validator lowering.

Implemented behavior includes:

- closed fixed and compiler-owned contract-byte authority sources, including
  generated `List<byte[]>` authority adapters;
- distinct-identity any/all/none/at-least/exactly, no-unexpected-signers,
  exact-set, and no-signers relations;
- deterministic schema-6 normalization, threshold/source limits, strict JSON
  admission, parent revalidation, semantic dependency plans, native-image
  metadata, and runner integrity checks;
- purpose-wide promotion tests for spending, minting, rewarding, and
  certifying, plus a guarded redeemer-variant authorization policy;
- kernel-reduced duplicate, ordering, empty-set, threshold, outsider, exact
  set, and singleton-membership controls; and
- an exact-VM threshold validator control covering two valid signer pairs and
  rejecting one signer, duplicate identities, outsiders, and three signers.

The retained local evidence classifies the exact 422-byte authorized artifact
as `SMT-VALID` for `exactlySigned(2)`, the vulnerable control as `REFUTED`, and
the always-failing control as `COULD-NOT-EVALUATE/property-vacuous`. The
positive JVM, Docker, and GraalVM-native runs bind identical values:

- compiled-code SHA-256
  `d8694fb859125c5e6e04fcce9a80547e0692faffa069c746ccae83cdba066f26`;
- Cardano script hash
  `afdd5d79fc207637bb3a02e4b853df318a92b83a51c30eb3b3629018`;
- canonical DSL IR SHA-256
  `0463422ea35b16cc37eb0f77152226b51f88ed932e1dec95eb6ce8af054b3761`;
- property IR SHA-256
  `f54a1d2cb203827ca74121babfdafc1402152342af8422bdaddd61a0351a3db2`;
  and
- generated Lean SHA-256
  `da9e2e0b9b1b3ab849e03d9ba1c9b14ba88c88a3ed0794d27ee3c64218772338`.

The recorded profile uses CEK fuel 1700 and recursive depth 4. On a warm local
run, non-vacuity completed in about 14 seconds and the positive theorem in
about 5 seconds. Fresh module runs passed 78 `julc-verification` tests and 421
`julc-cli` tests with zero failures. GraalVM 25.0.2 built and executed the
native CLI successfully.

The owner-or-recovery action-dependent formula is covered by the
`authorizationComposesUnderStrictActionVariantGuards` parent
promotion/admission test, but E.4h retains no separate solver-evidence fixture
for that formula and makes no SMT claim about it. Performance evidence records
the three-authority exact-artifact run only; separate one-authority and
maximum-size measurements are deferred, so no scaling conclusion is inferred
from that run.

Three limitations remain explicit and fail closed:

1. applied parameter authorities are deferred until exact ordered parameter
   application can be reconstructed and matched to the verified artifact;
2. fixed hashes containing byte `00` are rejected because a calibration found
   disagreement between the pinned UPLC-constant and Lean-literal translation
   paths; and
3. the combined
   `exactlySigned(2).and(noUnexpectedSigners())` exact-artifact proof did not
   finish within a ten-minute calibration window. The allow-list relation is
   still closed, rendered, kernel-tested, and composable, but this run is not
   presented as successful SMT evidence. Maximum-size solver scaling is
   therefore deferred rather than inferred from the three-authority result.

## Acceptance criteria and permitted claim

E.4h is complete only when all four phases are implemented or explicitly
documented as fail-closed deferrals, the complete test/evidence matrix passes,
schema-1–5 compatibility remains intact, and the result receives manual review
before commit.

The strongest permitted successful statement is:

> The pinned JuLC verification stack established the named authorization
> relation over distinct modeled public-key-hash identities for the exact
> recorded UPLC artifact, under the recorded purpose-specific domain, applied
> parameters if any, fuel, recursion depth, solver bounds, and tool revisions.

E.4h does not permit:

> This multisig contract is formally verified and safe.

It also does not prove key ownership, signature cryptography, compiler
correctness, model completeness, parameter governance, or any security
property not present in the named guarantee.
