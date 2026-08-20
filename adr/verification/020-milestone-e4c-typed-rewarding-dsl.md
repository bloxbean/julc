# ADR-020: Milestone E.4c Typed Rewarding Verification DSL

- **Status:** Implemented experimentally
- **Date:** 2026-08-21
- **Feature branch:** `feat/typed-verification-dsl-e4c-rewarding`
- **Parent:**
  [ADR-016 — Typed Verification DSL and Foundational Profile Catalog](016-typed-verification-dsl-and-profile-catalog.md)
- **Prerequisite:**
  [ADR-019 — Compositional Property Promotion Core](019-milestone-e4b-compositional-property-promotion-core.md)

## Context and problem

JuLC and its purpose-indexed CIP-57 blueprints already support rewarding
validators as compiler purpose `WITHDRAW` and blueprint purpose `withdraw`.
The pinned CardanoLedgerApi model exposes `RewardingScript Credential`, raw
`Withdrawals`, `rewardingInputs`, `credentialInWithdrawals`, and
`validRewardingContext`. The verification DSL, runner, and certificate
vocabulary currently admit only spending and minting.

Merely proving that the selected credential occurs in withdrawals is not a
useful security theorem under `validRewardingContext`: that membership is
already a ledger-domain condition. A useful rewarding property adds a
contract policy such as an authority signature or a bound on the selected
withdrawal amount.

## Goals

- Select exact rewarding artifacts through CIP-57 `withdraw`.
- Add a typed rewarding credential root and raw withdrawal traversal.
- Preserve association-list order and duplicate entries.
- Admit freely composed schema-3 guarantees using existing Boolean,
  comparison, credential, signer, and quantified-list nodes.
- Add a reviewed solver-compatible rewarding domain and kernel inclusion
  bridge from pinned `validRewardingContext`.
- Produce positive, refuted, malformed, duplicate, vacuous, local, Docker,
  and native evidence.
- Keep emitted validator UPLC byte-identical and keep compiler/core modules
  independent of verification.

## Non-goals

- Claim withdrawal-map uniqueness or extensional equality.
- Prove total rewards withdrawn across duplicate entries.
- Add a stable `@AuthorizedWithdrawal` annotation.
- Change ledger decoding, validator wrappers, compiler lowering, or CIP-57.
- Expose arbitrary Lean or arbitrary assumptions.

## Invariants

1. The selected artifact, Cardano script hash, and `withdraw` interface must
   match the observational compiler result before the property worker runs.
2. `RewardingScript` supplies the authoritative current credential.
3. Withdrawals remain the pinned raw `List (Credential × Integer)`; no Java
   map normalization may collapse or reorder duplicates.
4. `VALID_REWARDING_V3_PINNED` is a parent-owned theorem-envelope domain and
   cannot occur inside a user guarantee.
5. An SMT-valid domain-qualified claim is published only after its generated
   rewarding ledger corollary kernel-compiles.
6. Counterexamples remain qualified as belonging to the reviewed Blaster
   rewarding superset unless a separate ledger-valid witness gate exists.
7. Verification declarations have zero effect on validator UPLC.

## Decision

### Purpose and model

Add `REWARDING` to the schema-3 and CLI purpose inventories, mapped to
`ContractSchema.Purpose.WITHDRAW` and CIP-57 `withdraw`. Generate a
`RewardingContractModel` with:

- `context()`;
- `rewardingCredential()`; and
- `redeemerStrictlyDecodes()`.

### Withdrawal vocabulary

Add closed types `WITHDRAWALS` and `WITHDRAWAL_ENTRY`. `TxInfoExpr` exposes
`withdrawals()`. A withdrawal entry exposes typed `credential()` and
`amount()`. Existing bounded `ExistsNode` is generalized only to explicitly
reviewed list types.

The meaning of:

```java
withdrawals.exists(w -> w.credential().eq(current)
    .and(w.amount().ge(minimum)))
```

is raw association-list existence. With duplicate entries, one satisfying
entry is sufficient. It is not a statement about a unique or summed map value.

### Reviewed domain

Use a solver-facing predicate:

```text
RewardingScript purpose
&& validScriptInfo ctx
&& blasterValidTxInfo ctx
```

where `blasterValidTxInfo` is the already reviewed solver-compatible
transaction subset. Kernel-prove that pinned `validRewardingContext` implies
this predicate. The generated per-claim ledger corollary composes the SMT
result with that inclusion theorem.

### Example useful property

The vertical slice requires both a minimum matching raw withdrawal entry and
the configured authority signer. Credential membership alone is retained as
domain/codec evidence, not advertised as a security result.

## Affected modules

- `julc-verification`: purpose/domain enums, typed expressions, validation,
  capability/dependency planning, metamodel generation, and tests.
- `julc-cli`: purpose selection, generic Lean generation, rewarding input,
  bridge/certificate support, native metadata, and tests.
- `verification/e4c`: exact validators, Java specifications, VM/Lean/solver
  controls, reproducible evidence, and documentation.

No compiler, core, ledger API, stdlib, PIR, optimizer, wrapper, or blueprint
source change is expected. Discovery of such a requirement stops that part
until this ADR is revised.

## Milestones

### E.4c.1 — Purpose, model, and admission

- Add rewarding purpose/domain routing and metamodel generation.
- Add current credential and raw withdrawal entry types.
- Generalize bounded existential validation and rendering for withdrawals.
- Reject rewarding roots, fields, and domain under every other purpose.

### E.4c.2 — Semantics and domain bridge

- Emit rewarding credential and withdrawal Lean expressions.
- Use `rewardingInputs` for exact UPLC application.
- Add solver-domain definition, kernel inclusion theorem, and per-claim
  ledger corollary.
- Add duplicate-order and malformed-shape semantic controls.

### E.4c.3 — Runner, evidence, and product integration

- Bind rewarding domain/capabilities into canonical IR, manifest, plan, and
  certificate.
- Add correct, missing-authority, insufficient-amount, and vacuous fixtures.
- Add exact VM context execution and local proof evidence.
- Reproduce through Docker and a GraalVM native CLI with the JVM worker.
- Update getting-started, capability inventory, ADR-016, and branch ledger.

## Required tests

- single and multi-validator `withdraw` selection;
- metamodel and strict worker round trip;
- wrong purpose/domain/root/type rejection before Lean generation;
- empty, singleton, duplicate, reordered, and multiple-matching withdrawals;
- exact credential and integer encoding controls;
- rewarding-domain inclusion theorem and deliberately strengthened negative
  control;
- generated proof script invokes the ledger corollary;
- positive, refuted, and vacuous classification;
- tampered IR, plan, generated source, and capability inventory rejection;
- exact VM execution on a real rewarding `ScriptContext`;
- local, Docker, and native semantic-input hash agreement; and
- byte-identical UPLC when only the verification source changes.

## Compatibility

Schema-1 and schema-2 meanings are unchanged. Existing schema-3 spending and
minting values remain valid; schema 3 gains an experimental, closed rewarding
purpose and node surface. Existing validator scripts and hashes must not
change.

## Risks and mitigations

- **Duplicate maps are misread as unique.** Preserve list structure and state
  exact existential semantics in API docs and certificates.
- **Ledger-domain tautology is presented as security.** Use membership only
  for domain evidence and require an additional policy constraint in the
  vertical slice.
- **Solver cannot translate rewarding validity.** Prove over a reviewed
  solver-compatible superset and kernel-check inclusion; otherwise stop
  fail-closed.
- **Purpose confusion.** Cross-check compiler purpose, CIP-57 purpose,
  selected ScriptInfo, canonical IR, manifest, and runner plan.

## Alternatives

- Treat withdrawals as a Java map: rejected because it collapses raw duplicate
  behavior admitted by the model.
- Add a fixed authorized-withdrawal resolver: rejected because ADR-019 makes
  generic schema-3 composition authoritative.
- Prove only `credentialInWithdrawals`: rejected because it is already a
  ledger-domain condition.

## Acceptance and result claim

E.4c is complete only when all milestones and required controls pass without a
compiler/core change. The strongest permitted result remains a named property
of the exact recorded UPLC under the pinned rewarding model, reviewed domain,
and execution bounds. It is not a claim that the validator or contract is safe.

## Implementation outcome

E.4c is implemented on `feat/typed-verification-dsl-e4c-rewarding` without a
compiler, core, ledger API, stdlib, wrapper, PIR, optimizer, or blueprint
source change. The schema-3 generic promotion path now selects CIP-57
`withdraw`, exposes the authoritative rewarding credential and raw withdrawal
association list, validates purpose/domain use before Lean generation, and
emits the rewarding solver domain plus a per-claim kernel-checked ledger
corollary.

The evidence under `verification/e4c` binds the exact 644-byte
`AuthorizedRewards` artifact and classifies the four controls as specified:
authorized `SMT-VALID`, missing authority and missing amount bound `REFUTED`,
and always-failing `COULD-NOT-EVALUATE/property-vacuous`. Exact VM tests cover
the real rewarding wrapper, strict malformed redeemers, missing constraints,
and duplicate first-match behavior. Generated Lean controls preserve empty,
ordered, reordered, and duplicate withdrawal association lists.

The positive property succeeds through the local backend, the pinned Docker
image, and a real GraalVM 25.0.2 native CLI whose trusted property builder runs
in the documented child JVM. Those workspaces bind identical exact UPLC,
Cardano script hash, canonical DSL IR, generated Lean, dependency pins, and
execution bounds. Refutations remain explicitly qualified to the reviewed
Blaster rewarding superset; no ledger-valid or concrete-VM counterexample is
claimed.
