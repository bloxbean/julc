package com.bloxbean.cardano.julc.verification.dsl.ir;

import com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef;

import java.util.Objects;

/** Strictly decodes compiler-projected {@code Data}; malformed data makes the predicate false. */
public record StrictDecodeNode(
        PropertyNode data,
        VerificationTypeRef decodedType,
        String variable,
        PropertyNode predicate) implements PropertyNode {
    public StrictDecodeNode {
        data = Objects.requireNonNull(data, "data");
        decodedType = Objects.requireNonNull(decodedType, "decodedType");
        variable = Objects.requireNonNull(variable, "variable");
        predicate = Objects.requireNonNull(predicate, "predicate");
    }

    @Override
    public DslType resultType() {
        return DslType.BOOL;
    }
}
