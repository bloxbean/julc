package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.CompareNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.CompareOperator;
import com.bloxbean.cardano.julc.verification.dsl.ir.IntegerArithmeticNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.IntegerArithmeticOperator;
import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;

import java.util.Objects;

public record IntegerExpr(PropertyNode node) implements Expr {
    public IntegerExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr eq(IntegerExpr other) { return compare(CompareOperator.EQ, other); }
    public BoolExpr ne(IntegerExpr other) { return compare(CompareOperator.NE, other); }
    public BoolExpr ge(IntegerExpr other) { return compare(CompareOperator.GE, other); }
    public BoolExpr gt(IntegerExpr other) { return compare(CompareOperator.GT, other); }
    public BoolExpr le(IntegerExpr other) { return compare(CompareOperator.LE, other); }
    public BoolExpr lt(IntegerExpr other) { return compare(CompareOperator.LT, other); }
    public IntegerExpr negate() {
        return new IntegerExpr(new IntegerArithmeticNode(
                IntegerArithmeticOperator.NEGATE, node, null, null));
    }
    public IntegerExpr add(IntegerExpr other) {
        return arithmetic(IntegerArithmeticOperator.ADD, other);
    }
    public IntegerExpr subtract(IntegerExpr other) {
        return arithmetic(IntegerArithmeticOperator.SUBTRACT, other);
    }
    public IntegerExpr times(long constant) {
        return new IntegerExpr(new IntegerArithmeticNode(
                IntegerArithmeticOperator.SCALE, node, null, Long.toString(constant)));
    }
    private IntegerExpr arithmetic(IntegerArithmeticOperator operator, IntegerExpr other) {
        Objects.requireNonNull(other, "other");
        return new IntegerExpr(new IntegerArithmeticNode(operator, node, other.node, null));
    }
    private BoolExpr compare(CompareOperator operator, IntegerExpr other) {
        return new BoolExpr(new CompareNode(operator, node, other.node));
    }
}
