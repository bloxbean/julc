package com.bloxbean.cardano.julc.verification.dsl.ir;

import java.util.Objects;

public record CompareNode(
        CompareOperator operator, PropertyNode left, PropertyNode right) implements PropertyNode {
    public CompareNode {
        operator = Objects.requireNonNull(operator, "operator");
        left = Objects.requireNonNull(left, "left");
        right = Objects.requireNonNull(right, "right");
    }

    @Override
    public DslType resultType() {
        return DslType.BOOL;
    }
}
