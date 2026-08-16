package com.bloxbean.cardano.julc.verification.dsl.ir;

import java.util.Objects;

public record RootNode(String name, DslType resultType) implements PropertyNode {
    public RootNode {
        name = Objects.requireNonNull(name, "name");
        resultType = Objects.requireNonNull(resultType, "resultType");
    }
}
