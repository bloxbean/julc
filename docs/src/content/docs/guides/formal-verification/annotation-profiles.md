---
title: "Annotation Profiles"
description: "Verify reviewed JuLC signer, state-transition, and controlled-mint properties"
---

:::caution[Experimental verification feature]
Annotations state properties for the experimental verifier. They do not add
validator checks, change generated UPLC, or certify a contract as generally
safe.
:::

JuLC annotations are concise frontends over the same canonical typed DSL used
by explicit specifications. Start with the [formal-verification overview](../)
for backend setup, trust boundaries, outcomes, fuel, and CI guidance.

## Required signer

The shortest example is a spending validator whose datum owner must sign:

```java
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.verification.annotation.RequiresSigner;

@RequiresSigner("datum.owner")
@SpendingValidator
class AuthorizedStateValidator {
    record Datum(byte[] owner) {}
    record Redeemer() {}

    @Entrypoint
    static boolean validate(Datum datum, Redeemer redeemer, ScriptContext ctx) {
        return ContextsLib.signedBy(ctx.txInfo(), datum.owner());
    }
}
```

From the project containing `julc.toml` and `src/`:

```bash
julc verify . \
  --validator AuthorizedStateValidator \
  --backend docker
```

`julc verify` performs an exact build, resolves the annotation against the
compiler-owned contract schema, generates a managed workspace, validates its
hashes, checks property non-vacuity, runs the proof, and writes
`verification-result.json`.

The annotation states a property; it does not enforce it. If the validator
returns `true` without checking the signer, the expected result is `REFUTED`
with a retained symbolic Blaster countermodel.

## Stateful spending profile

Use all three annotations together. JuLC rejects a partial combination rather
than silently proving a weaker theorem. This declaration excerpt omits the
entrypoint body because that body must independently implement every clause:

```java
@RequiresSigner("datum.owner")
@Monotonic(
    current = "datum.state",
    next = "redeemer.nextState",
    relation = Relation.GREATER_THAN)
@PreservesValue(output = OutputSelection.SINGLE_CONTINUING_OUTPUT)
@SpendingValidator
class StateMachine {
    record Datum(byte[] owner, BigInteger state) {}
    record Redeemer(BigInteger nextState) {}

    // @Entrypoint implementation omitted: it must enforce authorization,
    // continuing-output/value rules, and the declared transition.
}
```

The generated theorem strictly decodes the current datum, redeemer, and inline
successor datum. It also requires the continuing output to share the complete
address of the resolved own input; matching only the payment credential is not
equivalent.

## Controlled mint profile

The same rule applies to controlled minting; this is a property declaration
excerpt, not the validator implementation:

```java
@ControlledMint(
    authority = "4a554c435f5645524946595f415554484f524954595f303030303031",
    tokenName = "4a554c43",
    quantity = 1,
    action = MintAction.MINT)
@MintingValidator
class TokenPolicy {
    // The validator must independently check the authority and exact
    // current-policy token shape described by the annotation.
}
```

The annotation's `quantity` is always a strictly positive magnitude. `MINT`
uses that magnitude and `BURN` lowers it to the corresponding negative on-chain
quantity. Authority hashes are 28 bytes and token names are at most 32 bytes.
Invalid or ambiguous literals fail before Lean generation.

## Supported profiles

| Annotation/profile | Purpose | Reviewed property |
|---|---|---|
| `@RequiresSigner("datum.owner")` | spending | successful validation implies that the strictly decoded datum owner occurs in the complete transaction signatory list |
| `@ControlledMint(...)` | minting | the fixed authority signed and the current policy contains the configured token, quantity, and direction with no additional asset under that policy |
| `@RequiresSigner` + `@PreservesValue` + `@Monotonic` | spending | authorization, exactly one full-address continuing output, structural value and authority preservation, redeemer-committed successor state, and strict state increase |

For properties outside these reviewed profiles, use the
[typed Java DSL](../typed-dsl/).
