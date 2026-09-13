package org.julclang.verification.dsl.ir;

import org.julclang.verification.dsl.type.VerificationTypeRef;

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
