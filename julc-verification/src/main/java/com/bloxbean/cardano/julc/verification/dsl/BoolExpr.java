package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.BoolBinaryNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.BoolOperator;
import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;

import java.util.Objects;

public record BoolExpr(PropertyNode node) implements Expr {
    public BoolExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr and(BoolExpr other) {
        return binary(BoolOperator.AND, other);
    }
    public BoolExpr or(BoolExpr other) {
        return binary(BoolOperator.OR, other);
    }
    public BoolExpr implies(BoolExpr other) {
        return binary(BoolOperator.IMPLIES, other);
    }
    private BoolExpr binary(BoolOperator operator, BoolExpr other) {
        return new BoolExpr(new BoolBinaryNode(operator, node, other.node));
    }
}
