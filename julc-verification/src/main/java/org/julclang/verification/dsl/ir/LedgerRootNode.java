package org.julclang.verification.dsl.ir;

import org.julclang.verification.dsl.type.LedgerTypeRef;

import java.util.Objects;

public record LedgerRootNode(String name, LedgerTypeRef valueType) implements PropertyNode {
    public LedgerRootNode {
        name = Objects.requireNonNull(name, "name");
        valueType = Objects.requireNonNull(valueType, "valueType");
    }
    @Override public DslType resultType() { return DslType.TYPED_VALUE; }
}
