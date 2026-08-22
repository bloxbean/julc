package com.bloxbean.cardano.julc.verification.dsl.ir;

import com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef;
import java.util.Objects;

public record MapQuantifierNode(
        PropertyNode map, VerificationTypeRef keyType, VerificationTypeRef valueType,
        QuantifierKind quantifier, String keyVariable, String valueVariable,
        PropertyNode predicate) implements PropertyNode {
    public MapQuantifierNode {
        map = Objects.requireNonNull(map, "map");
        keyType = Objects.requireNonNull(keyType, "keyType");
        valueType = Objects.requireNonNull(valueType, "valueType");
        quantifier = Objects.requireNonNull(quantifier, "quantifier");
        keyVariable = Objects.requireNonNull(keyVariable, "keyVariable");
        valueVariable = Objects.requireNonNull(valueVariable, "valueVariable");
        predicate = Objects.requireNonNull(predicate, "predicate");
    }
    @Override public DslType resultType() { return DslType.BOOL; }
}
