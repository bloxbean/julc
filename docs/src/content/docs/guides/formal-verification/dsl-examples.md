---
title: "Typed DSL Examples"
description: "Examples for every supported JuLC verification DSL capability group"
---

:::caution[Experimental verification feature]
These examples use the stable Java construction API v1 and canonical property
schema 1. Verification remains relative to the exact artifact, selected model,
fuel, solver, and assumptions recorded in the result certificate.
:::

The snippets below are property-building code, not on-chain validator code.
They execute as trusted project Java in the bounded DSL worker, and JuLC
revalidates the returned closed property IR before generating Lean.

Unless a complete class is shown, place the snippet inside
`VerificationSpecification.properties()` after generating the named model with
`julc verify dsl-init`.

## Common imports and property envelope

```java
import com.bloxbean.cardano.julc.verification.dsl.*;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslDomain;
import com.bloxbean.cardano.julc.verification.dsl.ir.DslPropertySet;
import com.bloxbean.cardano.julc.verification.dsl.ir.TxCertKind;

import static com.bloxbean.cardano.julc.verification.dsl.VerificationDsl.*;
```

The generated model owns the purpose and contract-schema hash. Return one or
more named properties through its `properties(...)` method:

```java
return contract.properties(
    property("authorization.owner", DslDomain.NONE, ownerSigned),
    property("state.increases", DslDomain.VALID_SPENDING_V3_PINNED,
             nextState.gt(currentState)));
```

Boolean properties compose with `and`, `or`, `implies`, and `not`. Integer
expressions support comparisons, addition, subtraction, negation, and scaling:

```java
var boundedIncrease = nextState.gt(currentState)
    .and(nextState.le(currentState.add(integer(10))))
    .and(nextState.ne(integer(0)));
```

## Contract records, optionals, lists, maps, and variants

Generated accessors follow the compiler-owned datum and redeemer schema. For a
contract containing nested records, an optional minimum, a list of values, a
map of balances, and a sealed redeemer:

```java
var contract = new CollectionGateModel();

var guarantee = contract.datum().exists(datum ->
    contract.context().txInfo().signatories().contains(datum.config().owner())
        .and(datum.config().minimum().isPresent()
            .or(datum.config().minimum().isEmpty()))
        .and(datum.config().values().exactlyOne(v -> v.gt(integer(0))))
        .and(contract.redeemer().exists(action ->
            action.whenUse(use -> datum.config().balances()
                .lookupFirst(use.key()).isPresent()))));

return contract.properties(property(
    "collections.authorized", DslDomain.NONE, guarantee));
```

Lists and maps retain order and duplicates. `lookupFirst` returns the first
matching raw map entry; `lookupAll` returns every match in order. Structural
equality observes the encoded ordered representation.

```java
var mapChecks = balances.containsKey(key)
    .and(balances.countKey(key).ge(integer(1)))
    .and(balances.lookupAll(key).all(v -> v.ge(integer(0))));

var listChecks = values.isNotEmpty()
    .and(values.at(integer(0)).exists(first -> first.eq(expected)))
    .and(values.none(value -> value.lt(integer(0))));
```

Variant fields are available only inside the matching `when<Constructor>`
guard. There is no unchecked cast:

```java
var authorizedAction = contract.redeemer().exists(action ->
    action.whenTransfer(transfer ->
        transfer.amount().gt(integer(0))
            .and(transfer.recipient().eq(expectedRecipient))));
```

## Spending: signer and strict continuing datum

```java
var contract = new StateModel();

var ownerSigned = contract.datum().exists(datum ->
    contract.context().txInfo().signatories().contains(datum.owner()));

var successorIsCanonical = contract.continuingOutputs()
    .whenSingleton(output -> output.datum().whenInline(raw ->
        contract.decodeDatum(raw, successor ->
            successor.owner().eq(expectedOwner)
                .and(successor.state().gt(integer(0))))));

return contract.properties(property(
    "state.authorized-successor",
    DslDomain.VALID_SPENDING_V3_PINNED,
    ownerSigned.and(successorIsCanonical)));
```

`decodeDatum` uses the compiler-projected datum type. A malformed inline datum
makes the predicate false; user code cannot supply a decoder name or arbitrary
Lean type.

## Minting: authority, anchor, and exact own-policy asset

The purpose helper supplies the exact minting roots, while the generated model
supplies the schema-bound property envelope:

```java
var generated = new TokenPolicyModel();
var minting = new MintingContractModel();
var quantity = integer(1);

var guarantee = minting.redeemerStrictlyDecodes()
    .and(minting.context().txInfo().inputs()
        .consumes(txOutRef(ANCHOR_TRANSACTION_ID, 0)))
    .and(minting.context().txInfo().signatories()
        .contains(keyHash(AUTHORITY_KEY_HASH)))
    .and(minting.context().txInfo().mint().exactOwnPolicyAsset(
        minting.ownPolicy(), tokenName("4a554c43"), quantity))
    .and(quantity.gt(integer(0)));

return generated.properties(property(
    "policy.one-shot", DslDomain.VALID_MINTING_V3_PINNED, guarantee));
```

`exactOwnPolicyAsset` rejects additional assets under the current policy and
does not normalize away duplicate or malformed entries.

## Rewarding: own withdrawal and signer

```java
var contract = new RewardingModel();

var ownMinimum = contract.context().txInfo().withdrawals().existsEntry(
    (credential, amount) ->
        credential.eq(contract.rewardingCredential().typed())
            .and(new IntegerExpr(amount.node()).ge(integer(1_000_000))));

var signed = contract.context().txInfo().signatories()
    .contains(keyHash(AUTHORITY_KEY_HASH));

return contract.properties(property(
    "reward.authorized-minimum",
    DslDomain.VALID_REWARDING_V3_PINNED,
    contract.redeemer().isPresent().and(signed).and(ownMinimum)));
```

Withdrawals are an ordered, duplicate-preserving association list. Existence
does not sum duplicate entries.

## Certifying: current certificate and guarded payload

```java
var contract = new CertifyingModel();

var current = contract.context().txInfo().certificates().containsAt(
    contract.certificateIndex(), contract.certificate());

var authorizedUpdate = contract.certificate()
    .whenUpdateDRep(drep -> drep.isPubKey())
    .and(contract.context().txInfo().signatories()
        .contains(keyHash(AUTHORITY_KEY_HASH)));

return contract.properties(property(
    "certificate.authorized-update",
    DslDomain.VALID_CERTIFYING_V3_PINNED,
    current.and(authorizedUpdate)));
```

All eleven Conway certificate constructors have guarded eliminators. For
example, a pool-retirement bound is:

```java
var expectedPool = LedgerExpressions.publicKeyHash(bytes(POOL_KEY_HASH));
var boundedRetirement = contract.certificate().whenPoolRetire(
    (pool, epoch) -> pool.eq(expectedPool).and(epoch.le(integer(100))));
```

## Transaction inputs, outputs, credentials, and datum witnesses

```java
var tx = contract.context().txInfo();

var firstReferenceInput = tx.referenceInputs().at(integer(0)).exists(input ->
    input.resolved().address().paymentCredential().isScript()
        .and(input.resolved().datum().isInline())
        .and(input.resolved().referenceScript().isEmpty()));

var resolvedAnchor = tx.inputs().resolve(contract.currentOutputRef())
    .isPresent();

var datumWitnessExists = tx.datums().existsEntry((hash, rawData) -> bool(true));

var onlyOneContinuingOutput = contract.continuingOutputs()
    .whenSingleton(output -> output.address().paymentCredential().isScript());
```

`inputs`, `referenceInputs`, `outputs`, `datums`, `redeemers`, certificates,
and withdrawals remain ordered and duplicate-preserving. Address filters can
use the complete address or only the payment credential; those are different
security meanings.

## Authorization algebra

```java
var auth = contract.authorization();
var committee = auth.authorities(
    auth.fixed("41".repeat(28)),
    auth.fixed("42".repeat(28)),
    auth.fixed("43".repeat(28)));

var twoApprovedAndNoOutsider = committee.exactlySigned(2)
    .and(committee.noUnexpectedSigners());
```

Authorization counts distinct identities. `exactlySigned(2)` does not forbid
an unrelated signer; `noUnexpectedSigners()` is the separate allow-list
constraint. Other relations are `anySigned`, `allSigned`, `noneSigned`,
`atLeastSigned`, and `exactSignerSet`. Use `auth.noSigners()` for an empty
transaction signatory list.

A generated `byte[]` field can be admitted through
`auth.fromContractBytes(field)`. A generated `List<byte[]>` wrapper exposes
`asAuthorities()`.

## Multi-asset value meanings

The value DSL deliberately exposes different meanings instead of silently
choosing one:

```java
var policy = LedgerExpressions.currencySymbol(bytes(POLICY_ID));
var token = LedgerExpressions.tokenName(bytes(TOKEN_NAME));

var valueChecks = output.value().quantityFirst(policy, token).ge(integer(10))
    .and(output.value().quantitySumStrict(policy, token)
        .exists(quantity -> new IntegerExpr(quantity.node()).ge(integer(10))))
    .and(output.value().extensionallyEquals(expectedValue))
    .and(output.value().structurallyNotEquals(reorderedEncoding));
```

- `quantityFirst` matches the pinned first-match ledger helper.
- `quantitySumStrict` sums duplicates and returns an empty option for absence
  or malformed data.
- structural equality observes ordering and duplicate decomposition.
- extensional equality compares strict summed quantities over finite support.
- `pointwiseLe/Lt/Ge/Gt` use the same strict extensional support.

Checked arithmetic never claims that an arbitrary result is a valid output
value:

```java
var checkedArithmetic = output.value().checkedDelta().exists(delta ->
    delta.negate().isPresent()
        .and(delta.scale(integer(2)).exists(doubled ->
            doubled.pointwiseGe(delta))));
```

For payment aggregation, choose the scope explicitly:

```java
var fullAddressValue = tx.outputs().toAddress(expectedAddress).valueProduced();
var credentialValue = tx.outputs()
    .toPaymentCredential(expectedPaymentCredential).valueProduced();
```

The second ignores the staking credential and is therefore weaker.

## Governance transaction data

Existing spending, minting, rewarding, and certifying properties can inspect
governance data even though voting/proposing validator selection is not yet
supported:

```java
var proposals = contract.context().txInfo().proposals();

var firstDeposit = proposals.at(integer(0))
    .exists(proposal -> proposal.deposit().ge(integer(10)));

var hardForkBound = proposals.exists(proposal ->
    proposal.actionStrict().exists(action ->
        action.whenHardFork((previous, version) ->
            version.major().ge(integer(9)))));
```

Votes retain both map levels and duplicates:

```java
var approvedVote = contract.context().txInfo().votes().existsEntry(
    (voter, actionVotes) -> voter.whenDRep(drep ->
        actionVotes.existsEntry((actionId, vote) -> vote.isYes())));
```

Use `actionStrict()` before inspecting an action. Wrong tags, arities, or
payload kinds make the option empty.

## Reviewed raw-data adapters

Some pinned ledger fields remain raw `Data` upstream. JuLC exposes only
reviewed, versioned operations for them:

```java
var tx = contract.context().txInfo();

var timeAndTreasury = tx.validityRangeReviewed().contains(deadline)
    .and(tx.validityRangeReviewed().canonicalEncoding())
    .and(tx.currentTreasuryStrict()
        .whenPresent(amount -> amount.ge(integer(0))))
    .and(tx.treasuryDonationStrict().isAbsent());
```

Changed parameters and quorum are available only inside the matching guarded
governance constructor:

```java
var reviewedAction = tx.proposals().exists(proposal ->
    proposal.actionStrict().exists(action ->
        action.whenParameterChange((previous, changed, script) ->
            changed.isWellFormed()
                .and(changed.isStrictlyAscendingUnique())
                .and(changed.containsId(integer(0))))
        .or(action.whenUpdateCommittee((previous, removed, added, quorum) ->
            quorum.decoderValid()
                .and(quorum.canonicalEncoding())
                .and(quorum.isUnitInterval())))));
```

The adapters do not expose arbitrary raw values or user-defined decoders.

## Current fail-closed boundaries

The public DSL intentionally does not provide:

- voting or proposing validator-interface selection;
- arbitrary Lean, shell, or user-defined AST nodes;
- unchecked access to optional or variant payloads;
- arbitrary equality over opaque raw `Data`;
- parameter-derived authority roots without exact applied-script binding;
- fixed authority hashes containing byte `00` under the current pinned symbolic
  translation; or
- the rejected E.5 temporal/state-machine prototype.

Unsupported expressions fail during construction, parent-process admission,
workspace generation, or Lean elaboration; they are never promoted to a
successful certificate by fallback.

See the [API and DSL reference](../api-reference/) for the complete operation
catalog and [Troubleshooting](../troubleshooting/) for solver and admission
failures.
