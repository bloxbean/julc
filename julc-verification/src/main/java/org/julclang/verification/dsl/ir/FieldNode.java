package org.julclang.verification.dsl.ir;

import java.util.Objects;

public record FieldNode(PropertyNode target, String name, DslType resultType)
        implements PropertyNode {
    public FieldNode {
        target = Objects.requireNonNull(target, "target");
        name = Objects.requireNonNull(name, "name");
        resultType = Objects.requireNonNull(resultType, "resultType");
    }
}
