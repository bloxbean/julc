package com.bloxbean.cardano.julc.verification.dsl.ir;

import java.util.Objects;

public record DslProperty(String id, PropertyNode expression) {
    public DslProperty {
        if (id == null || !id.matches("[A-Za-z][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Invalid property ID: " + id);
        }
        expression = Objects.requireNonNull(expression, "expression");
    }
}
