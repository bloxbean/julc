package com.bloxbean.cardano.julc.verification.dsl.ir;

import com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef;
import java.util.Objects;

public record ListStateNode(
        PropertyNode list, VerificationTypeRef elementType, ListState state)
        implements PropertyNode {
    public ListStateNode {
        list = Objects.requireNonNull(list, "list");
        elementType = Objects.requireNonNull(elementType, "elementType");
        state = Objects.requireNonNull(state, "state");
    }
    @Override public DslType resultType() { return DslType.BOOL; }
}
