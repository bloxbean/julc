package org.julclang.verification.dsl.ir;

import org.julclang.verification.dsl.type.VerificationTypeRef;
import java.util.Objects;

public record ListContainsNode(
        PropertyNode list, PropertyNode value, VerificationTypeRef elementType)
        implements PropertyNode {
    public ListContainsNode {
        list = Objects.requireNonNull(list, "list");
        value = Objects.requireNonNull(value, "value");
        elementType = Objects.requireNonNull(elementType, "elementType");
    }
    @Override public DslType resultType() { return DslType.BOOL; }
}
