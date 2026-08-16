package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.CompareNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.CompareOperator;
import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;

import java.util.Objects;

public record IntegerExpr(PropertyNode node) implements Expr {
    public IntegerExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr eq(IntegerExpr other) { return compare(CompareOperator.EQ, other); }
    public BoolExpr ge(IntegerExpr other) { return compare(CompareOperator.GE, other); }
    public BoolExpr gt(IntegerExpr other) { return compare(CompareOperator.GT, other); }
    public BoolExpr le(IntegerExpr other) { return compare(CompareOperator.LE, other); }
    public BoolExpr lt(IntegerExpr other) { return compare(CompareOperator.LT, other); }
    private BoolExpr compare(CompareOperator operator, IntegerExpr other) {
        return new BoolExpr(new CompareNode(operator, node, other.node));
    }
}
