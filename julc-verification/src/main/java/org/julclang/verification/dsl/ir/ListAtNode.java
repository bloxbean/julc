package org.julclang.verification.dsl.ir;

import org.julclang.verification.dsl.type.OptionalTypeRef;
import org.julclang.verification.dsl.type.VerificationTypeRef;
import java.util.Objects;

public record ListAtNode(
        PropertyNode list, VerificationTypeRef elementType, PropertyNode index)
        implements PropertyNode {
    public ListAtNode {
        list = Objects.requireNonNull(list, "list");
        elementType = Objects.requireNonNull(elementType, "elementType");
        index = Objects.requireNonNull(index, "index");
    }
    public VerificationTypeRef valueType() { return new OptionalTypeRef(elementType); }
    @Override public DslType resultType() { return DslType.TYPED_VALUE; }
}
