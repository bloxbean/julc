package com.bloxbean.cardano.julc.verification.dsl.ir;

import java.util.Objects;

/** Linear integer arithmetic; SCALE requires a bounded canonical constant. */
public record IntegerArithmeticNode(
        IntegerArithmeticOperator operator,
        PropertyNode left,
        PropertyNode right,
        String constant) implements PropertyNode {
    public IntegerArithmeticNode {
        operator = Objects.requireNonNull(operator, "operator");
        left = Objects.requireNonNull(left, "left");
        if ((operator == IntegerArithmeticOperator.ADD
                || operator == IntegerArithmeticOperator.SUBTRACT) != (right != null)) {
            throw new IllegalArgumentException("Binary integer operation requires right operand");
        }
        if ((operator == IntegerArithmeticOperator.SCALE) != (constant != null)) {
            throw new IllegalArgumentException("SCALE requires exactly one constant");
        }
    }
    @Override public DslType resultType() { return DslType.INTEGER; }
}
