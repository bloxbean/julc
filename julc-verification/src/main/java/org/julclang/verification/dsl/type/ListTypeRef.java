package org.julclang.verification.dsl.type;

import java.util.Objects;

public record ListTypeRef(VerificationTypeRef elementType) implements VerificationTypeRef {
    public ListTypeRef {
        elementType = Objects.requireNonNull(elementType, "elementType");
    }
}
