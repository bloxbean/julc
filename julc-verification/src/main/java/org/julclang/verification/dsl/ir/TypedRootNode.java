package org.julclang.verification.dsl.ir;

import org.julclang.verification.dsl.type.VerificationTypeRef;

import java.util.Objects;

/** Strictly decoded contract boundary root. Datum and redeemer roots are optional. */
public record TypedRootNode(String name, VerificationTypeRef valueType)
        implements PropertyNode {
    public TypedRootNode {
        name = Objects.requireNonNull(name, "name");
        valueType = Objects.requireNonNull(valueType, "valueType");
    }

    @Override
    public DslType resultType() {
        return DslType.TYPED_VALUE;
    }
}
