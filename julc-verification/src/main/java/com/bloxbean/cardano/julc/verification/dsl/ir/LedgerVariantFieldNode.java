package com.bloxbean.cardano.julc.verification.dsl.ir;

import com.bloxbean.cardano.julc.verification.dsl.type.LedgerTypeRef;
import com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef;

import java.util.Objects;

public record LedgerVariantFieldNode(
        PropertyNode target,
        LedgerTypeRef sumType,
        String constructor,
        String name,
        VerificationTypeRef valueType) implements PropertyNode {
    public LedgerVariantFieldNode {
        target = Objects.requireNonNull(target, "target");
        sumType = Objects.requireNonNull(sumType, "sumType");
        constructor = Objects.requireNonNull(constructor, "constructor");
        name = Objects.requireNonNull(name, "name");
        valueType = Objects.requireNonNull(valueType, "valueType");
    }
    @Override public DslType resultType() { return DslType.TYPED_VALUE; }
}
