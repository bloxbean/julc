package org.julclang.verification.dsl.ir;

import org.julclang.verification.dsl.type.NominalTypeRef;

import java.util.Objects;

/** Guarded constructor elimination; all other constructors evaluate to false. */
public record VariantWhenNode(
        PropertyNode value,
        NominalTypeRef sumType,
        String constructor,
        String variable,
        PropertyNode predicate) implements PropertyNode {
    public VariantWhenNode {
        value = Objects.requireNonNull(value, "value");
        sumType = Objects.requireNonNull(sumType, "sumType");
        constructor = Objects.requireNonNull(constructor, "constructor");
        variable = Objects.requireNonNull(variable, "variable");
        predicate = Objects.requireNonNull(predicate, "predicate");
    }

    @Override
    public DslType resultType() {
        return DslType.BOOL;
    }
}
