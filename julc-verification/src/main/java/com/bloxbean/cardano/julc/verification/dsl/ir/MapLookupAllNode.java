package com.bloxbean.cardano.julc.verification.dsl.ir;

import com.bloxbean.cardano.julc.verification.dsl.type.ListTypeRef;
import com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef;
import java.util.Objects;

public record MapLookupAllNode(
        PropertyNode map, VerificationTypeRef keyType, VerificationTypeRef valueType,
        PropertyNode key) implements PropertyNode {
    public MapLookupAllNode {
        map = Objects.requireNonNull(map, "map");
        keyType = Objects.requireNonNull(keyType, "keyType");
        valueType = Objects.requireNonNull(valueType, "valueType");
        key = Objects.requireNonNull(key, "key");
    }
    public VerificationTypeRef typedResult() { return new ListTypeRef(valueType); }
    @Override public DslType resultType() { return DslType.TYPED_VALUE; }
}
