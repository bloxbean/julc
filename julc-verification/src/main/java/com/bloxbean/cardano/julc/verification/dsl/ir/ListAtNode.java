package com.bloxbean.cardano.julc.verification.dsl.ir;

import com.bloxbean.cardano.julc.verification.dsl.type.OptionalTypeRef;
import com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef;
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
