package org.julclang.verification.dsl.ir;

import org.julclang.verification.dsl.type.VerificationTypeRef;
import java.util.Objects;

public record TypedEqualityNode(
        PropertyNode left, PropertyNode right, VerificationTypeRef valueType,
        boolean negated) implements PropertyNode {
    public TypedEqualityNode {
        left = Objects.requireNonNull(left, "left");
        right = Objects.requireNonNull(right, "right");
        valueType = Objects.requireNonNull(valueType, "valueType");
    }
    @Override public DslType resultType() { return DslType.BOOL; }
}
