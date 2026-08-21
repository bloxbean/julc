package com.bloxbean.cardano.julc.verification.dsl.ir;

import com.bloxbean.cardano.julc.verification.dsl.type.LedgerTypeRef;
import com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef;

import java.util.Objects;

public record LedgerFieldNode(
        PropertyNode target,
        LedgerTypeRef ownerType,
        String name,
        VerificationTypeRef valueType) implements PropertyNode {
    public LedgerFieldNode {
        target = Objects.requireNonNull(target, "target");
        ownerType = Objects.requireNonNull(ownerType, "ownerType");
        name = Objects.requireNonNull(name, "name");
        valueType = Objects.requireNonNull(valueType, "valueType");
    }
    @Override public DslType resultType() { return DslType.TYPED_VALUE; }
}
