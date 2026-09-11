package org.julclang.verification.dsl;

import org.julclang.verification.dsl.ir.CompareNode;
import org.julclang.verification.dsl.ir.CompareOperator;
import org.julclang.verification.dsl.ir.PropertyNode;

import java.util.Objects;

public record ByteStringExpr(PropertyNode node) implements Expr {
    public ByteStringExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr eq(ByteStringExpr other) {
        return new BoolExpr(new CompareNode(CompareOperator.EQ, node, other.node));
    }
    public BoolExpr ne(ByteStringExpr other) {
        return new BoolExpr(new CompareNode(CompareOperator.NE, node, other.node));
    }
}
