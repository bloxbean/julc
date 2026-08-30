# ADR-013: Milestone C.7 Controlled Minting Profile v1

- **Status:** Implemented, manually reviewed, and integrated
- **Date:** 2026-08-13
- **Related:**
  [ADR-007 — Java-Annotation Security Properties](007-java-annotation-security-properties-and-one-command-verification.md),
  [ADR-009 — Verification Product Roadmap](009-verification-product-roadmap.md),
  [ADR-012 — Stateful Spending Profile](012-milestone-c6-stateful-spending-profile.md)

## Context

C.5 and C.6 cover spending validators. Minting policies have a different
ledger root and a different security boundary: the currency symbol supplied by
the minting `ScriptInfo` identifies the policy being run, while `TxInfo.mint`
may contain assets for several policies. A useful profile must bind its checks
to the current policy, prohibit extra assets under that policy, and distinguish
minting from burning.

An authority selected from an untrusted redeemer is not a meaningful fixed
authority: an attacker could select their own key and sign. C.7 therefore uses
literal policy parameters in verification metadata. The exact compiled UPLC
must independently establish that it enforces the same values. The annotations
remain outside compiler lowering and have zero effect on script bytes.

## Decision

### Java interface

C.7 adds a verification-only annotation:

```java
@ControlledMint(
    authority = "4a554c435f5645524946595f415554484f524954595f303030303031",
    tokenName = "4a554c43",
    quantity = 1,
    action = MintAction.MINT)
@MintingValidator
class ControlledTokenPolicy { ... }
```

`authority` and `tokenName` are lowercase or uppercase even-length hexadecimal
byte strings. Authority must decode to exactly 28 bytes. `quantity` is a
strictly positive Java `long`; the property IR stores it as a decimal integer
string. `MINT` interprets it as positive and `BURN` as negative. Zero,
`Long.MIN_VALUE`, unsupported actions, malformed hex, and use on any purpose
other than a two-parameter minting validator fail at the annotation location.

The validator redeemer may be any C.3-supported named record schema, including
productive recursive fields. C.7
strictly decodes it but does not select authority, token, or quantity from it.
Later profiles may add typed parameter or allow-list selections. They must not
weaken the fixed-authority profile under the same template version.

### Typed property IR and module boundary

The optional `julc-verification` module owns `ControlledMintProperty`,
`MintAction`, its resolver, and the annotation processor diagnostics. The
template is `julc.controlled-mint/v1`. The IR records:

- validator, minting purpose, property identity, and source reference;
- canonical authority and token-name bytes;
- positive magnitude, action, and signed expected quantity;
- redeemer nominal type and strict-decoding requirement;
- current-policy and exact-own-policy-asset selection rules;
- zero domain assumptions; and
- `ledgerValidityModeled: false`.

The CLI remains the composition root between ordinary compilation, the
compiler-owned `ContractSchema`, and the verification module. No compiler,
core, ledger API, or stdlib lowering depends on C.7 declarations. A regression
test compares printed UPLC with and without the annotation.

### Exact profile semantics

For the configured authority `A`, token name `N`, signed quantity `Q`,
redeemer type `R`, and context `ctx`, `controlledMintV1(ctx)` is true exactly
when:

1. `ctx.scriptContextScriptInfo` is `MintingScript ownPolicy`.
2. `ctx.scriptContextRedeemer` strictly decodes as `R`.
3. `A` occurs anywhere in `ctx.scriptContextTxInfo.txInfoSignatories`.
4. Filtering the raw mint association list by currency symbol `ownPolicy`
   produces exactly one entry.
5. That entry's token map contains exactly one raw entry.
6. The entry token name is exactly `N`.
7. Its quantity is exactly `Q`.
8. `Q > 0` for `MINT`, or `Q < 0` for `BURN`.

Other currency symbols in `TxInfo.mint` are outside this policy's authority and
are permitted. Duplicate entries for the current currency symbol and unrelated
token names under it are rejected. Association-list structure is inspected
directly; the profile does not assume normalized or unique ledger maps.

The generated exact-artifact obligation is:

```text
for every V3 ScriptContext ctx,
  exact artifact execution succeeds within pinned CEK fuel
  -> controlledMintV1(ctx)
```

No datum or spending root exists in this profile. A `datum.*` path or spending
validator is rejected rather than translated to an opaque value.

### Bounds, evidence, and certificate

C.7 reuses the authenticated runner protocol, non-vacuity gate, admission scan,
generated-source and property-IR hashes, retained counterexamples, and five
result classes. A vacuous policy skips the main theorem. `SMT-VALID` covers only
executions completing within the certificate's CEK fuel.

The certificate additionally records:

- action and exact signed quantity;
- authority and token-name hex;
- own-policy linkage mode;
- whether other policies are permitted;
- exact-singleton own-policy asset shape;
- `ledgerValidityModeled: false`; and
- the explicit fuel bound.

Tracked evidence contains a conforming mint policy, missing-authority control,
wrong-token/extra-own-policy-asset control, wrong-quantity/action control, and
always-failing vacuity control. Every vulnerable control must be `REFUTED` with
a retained raw model. Solver uncertainty remains a non-success result.

## Rejected alternatives

- **Authority from the redeemer.** This proves only that a caller-selected key
  signed, not that a fixed policy authority approved the mint.
- **Require the entire mint map to be a singleton.** A policy should constrain
  its own currency symbol without forbidding unrelated policies in the same
  transaction.
- **Use value lookup alone.** Lookup can hide duplicate policy or token entries;
  v1 constrains the raw association-list shape.
- **Treat mint and burn as one nonzero action.** The quantity sign is a security
  decision and is explicit in both IR and certificate.
- **Infer constants from Java bytecode or UPLC.** The theorem states the desired
  policy, then proves the exact artifact enforces it; reverse inference could
  merely document an unintended implementation.

## Acceptance criteria

C.7 is complete when:

- annotations, resolver, and IR remain in `julc-verification`;
- malformed literals, wrong purpose, and unsupported schemas fail at source;
- annotation presence has zero UPLC effect;
- generated Lean implements all eight clauses using the pinned V3 ledger API;
- the conforming mint and burn fixtures are `SMT-VALID` and non-vacuous;
- authority, asset-shape, quantity/action, and vacuity controls classify as
  specified with retained evidence;
- tampering with literals, IR, generated Lean, or exact UPLC fails preflight;
- the certificate exposes every assumption, selection, and bound;
- C.5 and C.6 evidence and affected Java suites continue to pass; and
- no compiler/core source is changed.
