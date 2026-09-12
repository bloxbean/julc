package org.julclang.verification.dsl.ir;

import org.julclang.verification.dsl.type.OptionalTypeRef;
import org.julclang.verification.dsl.type.VerificationTypeRef;
import java.util.Objects;

public record MapLookupFirstNode(
        PropertyNode map, VerificationTypeRef keyType, VerificationTypeRef valueType,
        PropertyNode key) implements PropertyNode {
    public MapLookupFirstNode {
        map = Objects.requireNonNull(map, "map");
        keyType = Objects.requireNonNull(keyType, "keyType");
        valueType = Objects.requireNonNull(valueType, "valueType");
        key = Objects.requireNonNull(key, "key");
    }
    public VerificationTypeRef typedResult() { return new OptionalTypeRef(valueType); }
    @Override public DslType resultType() { return DslType.TYPED_VALUE; }
}
