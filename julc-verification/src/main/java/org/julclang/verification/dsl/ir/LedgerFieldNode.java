package org.julclang.verification.dsl.ir;

import org.julclang.verification.dsl.type.LedgerTypeRef;
import org.julclang.verification.dsl.type.VerificationTypeRef;

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
