package org.julclang.verification.dsl.ir;

import org.julclang.verification.dsl.type.LedgerTypeRef;

import java.util.Objects;

public record LedgerVariantIsNode(
        PropertyNode value, LedgerTypeRef sumType, String constructor)
        implements PropertyNode {
    public LedgerVariantIsNode {
        value = Objects.requireNonNull(value, "value");
        sumType = Objects.requireNonNull(sumType, "sumType");
        constructor = Objects.requireNonNull(constructor, "constructor");
    }
    @Override public DslType resultType() { return DslType.BOOL; }
}
