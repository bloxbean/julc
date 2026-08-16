package com.bloxbean.cardano.julc.verification.dsl.ir;

import java.util.Objects;

public record ExistsNode(
        PropertyNode collection, String variable, PropertyNode predicate) implements PropertyNode {
    public ExistsNode {
        collection = Objects.requireNonNull(collection, "collection");
        variable = Objects.requireNonNull(variable, "variable");
        predicate = Objects.requireNonNull(predicate, "predicate");
    }

    @Override
    public DslType resultType() {
        return DslType.BOOL;
    }
}
