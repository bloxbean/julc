package com.bloxbean.cardano.julc.verification.dsl.ir;

import java.util.Objects;

public record LiteralNode(DslType resultType, String value) implements PropertyNode {
    public LiteralNode {
        resultType = Objects.requireNonNull(resultType, "resultType");
        value = Objects.requireNonNull(value, "value");
    }
}
