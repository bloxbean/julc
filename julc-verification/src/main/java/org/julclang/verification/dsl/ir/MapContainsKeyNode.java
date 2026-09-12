package org.julclang.verification.dsl.ir;

import org.julclang.verification.dsl.type.VerificationTypeRef;
import java.util.Objects;

public record MapContainsKeyNode(
        PropertyNode map, VerificationTypeRef keyType, VerificationTypeRef valueType,
        PropertyNode key) implements PropertyNode {
    public MapContainsKeyNode {
        map = Objects.requireNonNull(map, "map");
        keyType = Objects.requireNonNull(keyType, "keyType");
        valueType = Objects.requireNonNull(valueType, "valueType");
        key = Objects.requireNonNull(key, "key");
    }
    @Override public DslType resultType() { return DslType.BOOL; }
}
