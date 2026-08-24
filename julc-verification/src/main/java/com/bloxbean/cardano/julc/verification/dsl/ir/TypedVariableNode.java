package com.bloxbean.cardano.julc.verification.dsl.ir;

import com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef;

import java.util.Objects;

public record TypedVariableNode(String variable, VerificationTypeRef valueType)
        implements PropertyNode {
    public TypedVariableNode {
        variable = Objects.requireNonNull(variable, "variable");
        valueType = Objects.requireNonNull(valueType, "valueType");
    }

    @Override
    public DslType resultType() {
        return DslType.TYPED_VALUE;
    }
}
