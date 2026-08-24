package com.bloxbean.cardano.julc.verification.dsl.type;

import java.util.Objects;

/** Raw ordered duplicate-preserving association-map type. */
public record AssocMapTypeRef(VerificationTypeRef keyType, VerificationTypeRef valueType)
        implements VerificationTypeRef {
    public AssocMapTypeRef {
        keyType = Objects.requireNonNull(keyType, "keyType");
        valueType = Objects.requireNonNull(valueType, "valueType");
    }
}
