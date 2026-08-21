package com.bloxbean.cardano.julc.verification.dsl.type;

import java.util.Objects;

public record OptionalTypeRef(VerificationTypeRef elementType)
        implements VerificationTypeRef {
    public OptionalTypeRef {
        elementType = Objects.requireNonNull(elementType, "elementType");
    }
}
