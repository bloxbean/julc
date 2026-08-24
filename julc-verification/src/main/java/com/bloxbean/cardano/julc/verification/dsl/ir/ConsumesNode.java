package com.bloxbean.cardano.julc.verification.dsl.ir;

import java.util.Objects;

public record ConsumesNode(PropertyNode inputs, PropertyNode outputReference)
        implements PropertyNode {
    public ConsumesNode {
        inputs = Objects.requireNonNull(inputs, "inputs");
        outputReference = Objects.requireNonNull(outputReference, "outputReference");
    }

    @Override
    public DslType resultType() {
        return DslType.BOOL;
    }
}
