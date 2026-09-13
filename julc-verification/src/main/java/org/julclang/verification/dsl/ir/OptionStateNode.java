package org.julclang.verification.dsl.ir;

import org.julclang.verification.dsl.type.VerificationTypeRef;
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
