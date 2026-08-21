package com.bloxbean.cardano.julc.verification.dsl.ir;

import com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef;

import java.util.Objects;

public record OptionExistsNode(
        PropertyNode optional,
        String variable,
        VerificationTypeRef elementType,
        PropertyNode predicate) implements PropertyNode {
    public OptionExistsNode {
        optional = Objects.requireNonNull(optional, "optional");
        variable = Objects.requireNonNull(variable, "variable");
        elementType = Objects.requireNonNull(elementType, "elementType");
        predicate = Objects.requireNonNull(predicate, "predicate");
    }

    @Override
    public DslType resultType() {
        return DslType.BOOL;
    }
}
