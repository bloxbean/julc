package com.bloxbean.cardano.julc.verification.dsl.ir;

import com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef;
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
