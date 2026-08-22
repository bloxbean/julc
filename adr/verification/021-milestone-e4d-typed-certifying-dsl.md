# ADR-021: Milestone E.4d Typed Certifying Verification DSL

- **Status:** Implemented experimentally
- **Date:** 2026-08-21
- **Feature branch:** `feat/typed-verification-dsl-e4d-certifying`
- **Parent:**
  [ADR-016 — Typed Verification DSL and Foundational Profile Catalog](016-typed-verification-dsl-and-profile-catalog.md)
- **Prerequisites:**
  [ADR-019 — Compositional Property Promotion Core](019-milestone-e4b-compositional-property-promotion-core.md),
  [ADR-020 — Typed Rewarding Verification DSL](020-milestone-e4c-typed-rewarding-dsl.md)

## Context and problem

JuLC and its purpose-indexed CIP-57 blueprints publish a certifying validator
as compiler purpose `CERTIFY` and blueprint purpose `publish`. The pinned
CardanoLedgerApi model represents the current purpose as
`CertifyingScript Integer TxCert`, supplies the exact `certifyingInputs`, and
checks `validCertifyingContext` using both `validScriptCertificate` and
`isKnownCertificate` against `TxInfo.txInfoTxCerts`.

The generic schema-3 DSL does not yet represent the current certificate,
certificate index, certificate list, or any of the 11 Conway `TxCert`
constructors. Consequently, a Java author cannot state even a basic property
such as “successful execution of this certificate kind requires an authority
signature” without dropping to handwritten Lean.

Deep payload projection for all certificate variants would pull several new
ledger types into the DSL at once: credentials, delegatees, optional deposits,
pool hashes, epochs, and committee credentials. That breadth is not necessary
to establish a useful, honest vertical slice.

## Goals

- Select exact certifying artifacts through CIP-57 `publish`.
- Expose typed current-certificate and current-index roots.
- Represent the raw ordered `TxCert` list and the pinned indexed-membership
  helper without normalization.
- Recognize all 11 pinned `TxCert` constructors through a closed enum.
- Compose certificate-kind, known-index, strict-redeemer, signer, Boolean, and
  existing transaction predicates through the generic schema-3 path.
- Add a reviewed certifying solver domain and a Lean-kernel inclusion bridge
  from pinned `validCertifyingContext`.
- Produce positive, refuted, malformed, index-boundary, vacuous, local,
  Docker, and native evidence.
- Keep verification declarations UPLC-neutral and keep compiler/core modules
  independent of verification.

## Non-goals

- Project arbitrary payload fields from every `TxCert` constructor.
- Add generic list indexing or optional-value elimination to the DSL.
- Claim that the transaction certificate list is unique or sorted beyond the
  pinned ledger-domain predicate.
- Add a stable certificate-policy annotation.
- Support voting or proposing in this milestone.
- Change ledger encoding, strict boundaries, compiler lowering, validator
  wrappers, or CIP-57 publication.
- Expose arbitrary Lean, arbitrary assumptions, or raw constructor tags.

## Current behavior

The capability inventory classifies `purpose.certifying`,
`field.txInfo.certificates`, `helper.isKnownCertificate`, and
`ledger.validCertifyingContext` as `UNSUPPORTED_IR`. The CLI purpose parser and
runner admit spending, minting, and rewarding only. Schema-3 metamodels have no
certifying roots, and the generic renderer cannot form `certifyingInputs`.

## Invariants

1. The selected compiler purpose, CIP-57 `publish` entry, blueprint identity,
   observational compile result, exact bytes, and Cardano script hash must
   agree before the property worker runs.
2. The current certificate and index come only from `CertifyingScript`; user
   Java cannot supply or replace them.
3. `TxInfo.txInfoTxCerts` remains an ordered list. Indexed membership is the
   pinned `isKnownCertificate` meaning: negative and out-of-range indices are
   false, and equality is checked at exactly one index.
4. Certificate kind is a closed semantic enum mapped to the 11 pinned
   constructors, not a user-supplied integer tag or Lean fragment.
5. `VALID_CERTIFYING_V3_PINNED` is a parent-owned theorem-envelope domain and
   cannot appear within a user guarantee.
6. An SMT-valid domain-qualified claim is published only after its generated
   certifying ledger corollary kernel-compiles.
7. Counterexamples remain qualified as belonging to the reviewed Blaster
   certifying superset unless a separate ledger-valid witness gate succeeds.
8. Schema-1/schema-2 behavior and existing schema-3 canonical DSL values and
   meanings remain unchanged; new nodes are admitted only for schema 3.
   Derived property metadata is separately version-sensitive as documented in
   ADR-019 and the compatibility section below.
9. Verification property source and DSL declarations have zero effect on
   validator UPLC.

## Decision

### Purpose and metamodel

Add `CERTIFYING` to verification purpose inventories, mapped to
`ContractSchema.Purpose.CERTIFY` and CIP-57 `publish`. Generate a
`CertifyingContractModel` with:

- `context()`;
- `certificate()`;
- `certificateIndex()`; and
- `redeemerStrictlyDecodes()`.

The interface has no datum.

### Closed certificate vocabulary

Add DSL types `TX_CERT` and `LIST_TX_CERT` and a public `TxCertKind` enum with
these exact values and pinned meanings:

1. `REG_STAKING` — `TxCertRegStaking` tag 0;
2. `UNREG_STAKING` — `TxCertUnRegStaking` tag 1;
3. `DELEG_STAKING` — `TxCertDelegStaking` tag 2;
4. `REG_DELEG` — `TxCertRegDeleg` tag 3;
5. `REG_DREP` — `TxCertRegDRep` tag 4;
6. `UPDATE_DREP` — `TxCertUpdateDRep` tag 5;
7. `UNREG_DREP` — `TxCertUnRegDRep` tag 6;
8. `POOL_REGISTER` — `TxCertPoolRegister` tag 7;
9. `POOL_RETIRE` — `TxCertPoolRetire` tag 8;
10. `AUTH_HOT_COMMITTEE` — `TxCertAuthHotCommittee` tag 9; and
11. `RESIGN_COLD_COMMITTEE` — `TxCertResignColdCommittee` tag 10.

`TxCertExpr.isKind(TxCertKind)` lowers to a generated total constructor match.
No raw tag comparison is present in canonical IR.

### Indexed certificate relation

`TxInfoExpr.certificates()` returns `TxCertListExpr`.
`TxCertListExpr.containsAt(index, certificate)` creates a closed
`KnownCertificateNode` and lowers to the pinned `isKnownCertificate` helper.
It does not expose a partial list lookup or invent an exception/default value.

This predicate is redundant under `VALID_CERTIFYING_V3_PINNED`, because
`validScriptInfo` already establishes it. It remains foundational for
domain-free properties, explicit diagnostics, and executable semantic
controls. The milestone’s advertised security property adds certificate-kind
and signer restrictions rather than presenting known membership as a security
result.

### Reviewed solver domain

Use:

```text
CertifyingScript index certificate
&& validScriptInfo ctx
&& blasterValidTxInfo ctx
```

as the solver-facing predicate. Kernel-prove that pinned
`validCertifyingContext` implies this reviewed predicate, and compile each
domain-qualified property’s generated ledger corollary after SMT success.

If pinned Blaster cannot translate the certificate branch of
`validScriptInfo`, the implementation must stop fail-closed and revise this
ADR. It must not silently remove certificate validity or indexed membership
from the domain.

### Vertical-slice property

The positive fixture permits only the `UPDATE_DREP` certificate kind and
requires a fixed authority signer plus strict redeemer decoding. Controls
remove the signature, accept an additional certificate kind, or reject every
input. This makes each result materially different from the ledger-domain
facts.

## Affected stages and modules

- `julc-verification`: closed IR nodes/enums, canonicalization, validation,
  dependency/capability planning, typed wrappers, metamodel generation, and
  tests.
- `julc-cli`: purpose selection, exact certifying inputs, Lean rendering,
  reviewed domain/bridge, runner preflight, evidence generation, and tests.
- `verification/e4d`: exact fixtures, Java specification, semantic controls,
  reproducible evidence, and documentation.
- verification ADRs and getting-started documentation.

No compiler, core, ledger API, stdlib, PIR, optimizer, wrapper, or blueprint
source change is expected. Discovery of such a requirement stops that part
until the ADR is revised.

## Compatibility

The canonical schema remains version 3. Existing node subtype names, enum
values, and schema-1/schema-2 bytes do not change. Schema 3 gains two closed
node variants, two types, one purpose/domain pair, and a closed certificate
kind enum. Old readers fail closed on the new subtype or enum value. Existing
validator UPLC and script hashes must remain unchanged.

Canonical DSL compatibility is distinct from generated-workspace
compatibility. E.4c changed the authenticated derived existential rule from
`exists-output` to `exists:LIST_TX_OUT`; current preflight therefore rejects
an older E.4b workspace until it is regenerated. This fail-closed migration is
documented in ADR-019 and ADR-020 and does not reinterpret historical result
certificates.

## Implementation milestones

### E.4d.1 — Purpose, roots, and closed IR

- Add certifying CLI/DSL purpose and domain routing.
- Add current certificate/index roots and certificate-list field access.
- Add closed kind and indexed-membership nodes with strict JSON decoding.
- Extend canonicalization and node budgets recursively.
- Reject wrong purpose, wrong domain, wrong type, unknown kind, and use under
  schema 1/2 before Lean generation.

### E.4d.2 — Lean semantics and domain bridge

- Render all 11 constructor-kind matches.
- Render `txInfoTxCerts` and pinned `isKnownCertificate`.
- Use `certifyingInputs` for the exact UPLC obligation.
- Add `blasterValidCertifyingContext`, inclusion theorem, and per-claim ledger
  corollary.
- Add executable controls for all kinds, negative/out-of-range indices,
  reordered/mismatched lists, and malformed `TxCert` Data.

### E.4d.3 — Evidence and product integration

- Add authorized, missing-authority, overly-permissive-kind, and vacuous
  validators plus a composed Java property.
- Add exact VM execution using real certifying `ScriptContext` Data.
- Update the pinned capability inventory and compatibility gate.
- Reproduce local, Docker, and GraalVM-native positive evidence and the local
  refuted/vacuous matrix.
- Update ADR-016, branch ledger, verification index, and getting-started guide.

## Verification strategy and required tests

- single and purpose-indexed multi-validator `publish` selection;
- metamodel generation and strict worker/canonical round trip;
- schema-1/schema-2 canonical-byte freeze;
- purpose/domain/root/type/subtype rejection before Lean generation;
- all 11 kind mappings against the pinned constructor names and Data tags;
- indexed membership at zero, positive, negative, out-of-range, mismatched,
  reordered, and duplicate-certificate positions;
- malformed constructor tag, arity, and payload rejection by pinned `IsData`;
- generated obligation uses `certifyingInputs`;
- domain inclusion theorem and a strengthened-domain negative control;
- generated proof scripts invoke each selected-domain ledger corollary;
- positive, refuted, and vacuous result classification;
- tampered canonical IR, plan, generated Lean, artifact, and inventory
  rejection before processes execute;
- exact VM execution through a real certifying context;
- local/Docker/native equality of exact artifact, property IR, canonical DSL
  IR, generated Lean, pins, and bounds; and
- zero compiler/core source changes and byte-identical UPLC when only property
  source changes.

## Risks and mitigations

- **Constructor drift:** the capability revision gate and 11-kind executable
  controls fail when the pinned model changes.
- **Index semantics are accidentally normalized:** use only the pinned helper
  over the original ordered list and test negative/out-of-range/reordering.
- **A ledger-domain tautology is marketed as security:** advertise signer and
  allowed-kind restrictions; document known membership as a domain fact.
- **Variant payload breadth becomes an unsafe escape hatch:** expose only
  reviewed kind recognition in E.4d; add payload projections later through a
  separate inventory/ADR extension.
- **Solver translation fails on certifying validity:** stop fail-closed; do
  not weaken the domain silently.
- **Purpose confusion:** cross-check compiler purpose, CIP-57 purpose,
  ScriptInfo constructor, canonical IR, manifest, and runner plan.

## Alternatives considered

- Compare raw integer constructor tags: rejected because it exposes encoding
  details as an untyped user input and weakens drift detection.
- Expose every certificate payload now: rejected because it adds many types
  without a second demonstrated property need.
- Add a fixed `@AllowedCertificate` annotation/resolver: rejected because the
  schema-3 compositional path is authoritative after ADR-019.
- Prove only `isKnownCertificate`: rejected because it is already part of the
  selected ledger-valid domain.
- Treat certificates as an unordered set: rejected because the current index
  and pinned helper are order-sensitive.

## Open questions

- Which certificate payload projections have enough recurring use to justify
  a later closed extension?
- Should an annotation profile graduate only after multiple real contracts use
  the kind/signer composition?

Neither question blocks the bounded E.4d vertical slice.

## Implementation outcome

E.4d.1–E.4d.3 are implemented on
`feat/typed-verification-dsl-e4d-certifying`. The schema-3 DSL now selects the
exact CIP-57 `publish` interface, exposes authoritative certificate/index
roots, recognizes all 11 pinned `TxCert` constructors, and applies the pinned
ordered `isKnownCertificate` relation without list normalization. The
capability inventory and native-image reachability metadata cover the new
closed surface.

The generated solver obligation uses exact `certifyingInputs` under the
reviewed certifying superset. Both the domain-inclusion theorem and the
per-claim ledger corollary kernel-compile. A deliberately strengthened-domain
control fails to elaborate, demonstrating that an extra unproved assumption
cannot silently enter the bridge.

The committed driver reproduces these local classifications:

- `AuthorizedCertificates`: `SMT-VALID`;
- `MissingAuthorityCertificates`: `REFUTED`;
- `AnyCertificate`: `REFUTED`; and
- `VacuousCertificates`: `COULD-NOT-EVALUATE/property-vacuous`.

The positive proof also passes through Docker and the GraalVM native CLI. All
three positive certificates bind identical compiled-code SHA-256, Cardano
script hash, canonical DSL IR, property IR, and generated Lean tree. Exact VM
tests independently exercise strict redeemer decoding, authority rejection,
and certificate-kind rejection. No compiler, core, ledger API, stdlib,
blueprint, PIR, optimizer, or validator-wrapper source changed.

The `authorized-native` directory name records how the evidence driver was
invoked; the current certificate records the authenticated `local` proof
backend but does not attest the CLI launcher flavor or native executable
digest. Equality of the bound hashes establishes semantic-input identity
across the runs, not independent proof that a particular launcher binary was
used.

## Acceptance and result claim

E.4d is complete only when all three milestones and required controls pass
without a compiler/core change. The strongest permitted result remains a named
property of one exact recorded UPLC artifact within recorded fuel, pinned
model, and reviewed certifying domain. It is not a claim that the validator,
transaction, or contract is generally safe.
