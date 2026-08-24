package com.bloxbean.cardano.julc.verification.dsl.ir;

import com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef;

import java.util.Objects;

/** Raw, ordered, duplicate-preserving policy entries of a Value-like expression. */
public record ValueEntriesNode(
        PropertyNode value,
        VerificationTypeRef valueType,
        VerificationTypeRef entryType) implements PropertyNode {
    public ValueEntriesNode {
        value = Objects.requireNonNull(value, "value");
        valueType = Objects.requireNonNull(valueType, "valueType");
        entryType = Objects.requireNonNull(entryType, "entryType");
    }
    @Override public DslType resultType() { return DslType.TYPED_VALUE; }
}
