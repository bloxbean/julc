package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.CompareNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.CompareOperator;
import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;

import java.util.Objects;

public record ByteStringExpr(PropertyNode node) implements Expr {
    public ByteStringExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr eq(ByteStringExpr other) {
        return new BoolExpr(new CompareNode(CompareOperator.EQ, node, other.node));
    }
}
