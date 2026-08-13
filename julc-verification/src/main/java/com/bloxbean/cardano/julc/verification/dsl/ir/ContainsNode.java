package com.bloxbean.cardano.julc.verification.dsl.ir;

import java.util.Objects;

public record ContainsNode(PropertyNode collection, PropertyNode value) implements PropertyNode {
    public ContainsNode {
        collection = Objects.requireNonNull(collection, "collection");
        value = Objects.requireNonNull(value, "value");
    }

    @Override
    public DslType resultType() {
        return DslType.BOOL;
    }
}
