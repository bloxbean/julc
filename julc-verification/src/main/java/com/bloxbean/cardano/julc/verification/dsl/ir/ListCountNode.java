package com.bloxbean.cardano.julc.verification.dsl.ir;

import com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef;
import java.util.Objects;

public record ListCountNode(
        PropertyNode list, VerificationTypeRef elementType,
        String variable, PropertyNode predicate) implements PropertyNode {
    public ListCountNode {
        list = Objects.requireNonNull(list, "list");
        elementType = Objects.requireNonNull(elementType, "elementType");
        variable = Objects.requireNonNull(variable, "variable");
        predicate = Objects.requireNonNull(predicate, "predicate");
    }
    @Override public DslType resultType() { return DslType.INTEGER; }
}
