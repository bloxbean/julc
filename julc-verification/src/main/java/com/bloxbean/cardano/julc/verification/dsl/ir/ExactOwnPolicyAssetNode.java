package com.bloxbean.cardano.julc.verification.dsl.ir;

import java.util.Objects;

/** Raw structural current-policy mint predicate; it is not map lookup. */
public record ExactOwnPolicyAssetNode(
        PropertyNode mint,
        PropertyNode policy,
        PropertyNode tokenName,
        PropertyNode quantity) implements PropertyNode {
    public ExactOwnPolicyAssetNode {
        mint = Objects.requireNonNull(mint, "mint");
        policy = Objects.requireNonNull(policy, "policy");
        tokenName = Objects.requireNonNull(tokenName, "tokenName");
        quantity = Objects.requireNonNull(quantity, "quantity");
    }

    @Override
    public DslType resultType() {
        return DslType.BOOL;
    }
}
