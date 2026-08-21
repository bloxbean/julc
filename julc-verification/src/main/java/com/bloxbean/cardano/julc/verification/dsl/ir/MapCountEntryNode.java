package com.bloxbean.cardano.julc.verification.dsl.ir;

import com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef;

import java.util.Objects;

/** Counts raw association-list entries satisfying a predicate; duplicates remain visible. */
public record MapCountEntryNode(
        PropertyNode map,
        VerificationTypeRef keyType,
        VerificationTypeRef valueType,
        String keyVariable,
        String valueVariable,
        PropertyNode predicate) implements PropertyNode {
    public MapCountEntryNode {
        map = Objects.requireNonNull(map, "map");
        keyType = Objects.requireNonNull(keyType, "keyType");
        valueType = Objects.requireNonNull(valueType, "valueType");
        keyVariable = Objects.requireNonNull(keyVariable, "keyVariable");
        valueVariable = Objects.requireNonNull(valueVariable, "valueVariable");
        predicate = Objects.requireNonNull(predicate, "predicate");
    }

    @Override
    public DslType resultType() {
        return DslType.INTEGER;
    }
}
