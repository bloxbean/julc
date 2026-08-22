package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.ExactOwnPolicyAssetNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;

import java.util.Objects;

public record MintValueExpr(PropertyNode node) implements Expr {
    public MintValueExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr exactOwnPolicyAsset(
            PolicyIdExpr policy, ByteStringExpr tokenName, IntegerExpr quantity) {
        return new BoolExpr(new ExactOwnPolicyAssetNode(
                node, policy.node(), tokenName.node(), quantity.node()));
    }
}
