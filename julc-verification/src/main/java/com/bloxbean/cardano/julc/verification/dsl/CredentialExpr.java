package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.CompareNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.CompareOperator;
import com.bloxbean.cardano.julc.verification.dsl.ir.CredentialKeyHashNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;

import java.util.Objects;

public record CredentialExpr(PropertyNode node) implements Expr {
    public CredentialExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr eq(CredentialExpr other) {
        return new BoolExpr(new CompareNode(CompareOperator.EQ, node, other.node));
    }
    public BoolExpr matchesKeyHash(ByteStringExpr keyHash) {
        return new BoolExpr(new CredentialKeyHashNode(node, keyHash.node()));
    }
}
