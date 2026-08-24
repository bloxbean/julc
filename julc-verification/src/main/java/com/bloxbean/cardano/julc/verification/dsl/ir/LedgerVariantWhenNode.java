package com.bloxbean.cardano.julc.verification.dsl.ir;

import com.bloxbean.cardano.julc.verification.dsl.type.LedgerTypeRef;

import java.util.Objects;

public record LedgerVariantWhenNode(
        PropertyNode value,
        LedgerTypeRef sumType,
        String constructor,
        String variable,
        PropertyNode predicate) implements PropertyNode {
    public LedgerVariantWhenNode {
        value = Objects.requireNonNull(value, "value");
        sumType = Objects.requireNonNull(sumType, "sumType");
        constructor = Objects.requireNonNull(constructor, "constructor");
        variable = Objects.requireNonNull(variable, "variable");
        predicate = Objects.requireNonNull(predicate, "predicate");
    }
    @Override public DslType resultType() { return DslType.BOOL; }
}
