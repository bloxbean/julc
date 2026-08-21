package com.bloxbean.cardano.julc.verification.dsl.ir;

import com.bloxbean.cardano.julc.verification.dsl.type.LedgerTypeRef;

import java.util.Objects;

public record LedgerRootNode(String name, LedgerTypeRef valueType) implements PropertyNode {
    public LedgerRootNode {
        name = Objects.requireNonNull(name, "name");
        valueType = Objects.requireNonNull(valueType, "valueType");
    }
    @Override public DslType resultType() { return DslType.TYPED_VALUE; }
}
