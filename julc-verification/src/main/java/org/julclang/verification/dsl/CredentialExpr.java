package org.julclang.verification.dsl;

import org.julclang.verification.dsl.ir.CompareNode;
import org.julclang.verification.dsl.ir.CompareOperator;
import org.julclang.verification.dsl.ir.CredentialKeyHashNode;
import org.julclang.verification.dsl.ir.PropertyNode;

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
