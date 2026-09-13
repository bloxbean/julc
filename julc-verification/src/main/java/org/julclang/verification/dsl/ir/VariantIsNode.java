package org.julclang.verification.dsl.ir;

import org.julclang.verification.dsl.type.NominalTypeRef;

import java.util.Objects;

public record VariantIsNode(
        PropertyNode value, NominalTypeRef sumType, String constructor)
        implements PropertyNode {
    public VariantIsNode {
        value = Objects.requireNonNull(value, "value");
        sumType = Objects.requireNonNull(sumType, "sumType");
        constructor = Objects.requireNonNull(constructor, "constructor");
    }

    @Override
    public DslType resultType() {
        return DslType.BOOL;
    }
}
