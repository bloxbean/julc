package com.bloxbean.cardano.julc.verification.dsl.ir;

import com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef;
import java.util.Objects;

public record OptionStateNode(
        PropertyNode optional, VerificationTypeRef elementType, OptionState state)
        implements PropertyNode {
    public OptionStateNode {
        optional = Objects.requireNonNull(optional, "optional");
        elementType = Objects.requireNonNull(elementType, "elementType");
        state = Objects.requireNonNull(state, "state");
    }
    @Override public DslType resultType() { return DslType.BOOL; }
}
