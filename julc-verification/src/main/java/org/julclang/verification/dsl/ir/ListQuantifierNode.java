package org.julclang.verification.dsl.ir;

import org.julclang.verification.dsl.type.VerificationTypeRef;
import java.util.Objects;

public record ListQuantifierNode(
        PropertyNode list, VerificationTypeRef elementType, QuantifierKind quantifier,
        String variable, PropertyNode predicate) implements PropertyNode {
    public ListQuantifierNode {
        list = Objects.requireNonNull(list, "list");
        elementType = Objects.requireNonNull(elementType, "elementType");
        quantifier = Objects.requireNonNull(quantifier, "quantifier");
        variable = Objects.requireNonNull(variable, "variable");
        predicate = Objects.requireNonNull(predicate, "predicate");
    }
    @Override public DslType resultType() { return DslType.BOOL; }
}
