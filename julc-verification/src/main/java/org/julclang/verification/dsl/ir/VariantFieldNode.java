package org.julclang.verification.dsl.ir;

import org.julclang.verification.dsl.type.NominalTypeRef;
import org.julclang.verification.dsl.type.VerificationTypeRef;

import java.util.Objects;

/** Field usable only inside the matching VariantWhenNode binder. */
public record VariantFieldNode(
        PropertyNode target,
        NominalTypeRef sumType,
        String constructor,
        String name,
        VerificationTypeRef valueType) implements PropertyNode {
    public VariantFieldNode {
        target = Objects.requireNonNull(target, "target");
        sumType = Objects.requireNonNull(sumType, "sumType");
        constructor = Objects.requireNonNull(constructor, "constructor");
        name = Objects.requireNonNull(name, "name");
        valueType = Objects.requireNonNull(valueType, "valueType");
    }

    @Override
    public DslType resultType() {
        return DslType.TYPED_VALUE;
    }
}
