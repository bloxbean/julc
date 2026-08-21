package com.bloxbean.cardano.julc.verification.dsl.ir;

import java.util.Objects;

public record BoolNotNode(PropertyNode value) implements PropertyNode {
    public BoolNotNode { value = Objects.requireNonNull(value, "value"); }
    @Override public DslType resultType() { return DslType.BOOL; }
}
