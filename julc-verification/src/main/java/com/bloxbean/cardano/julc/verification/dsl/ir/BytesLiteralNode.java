package com.bloxbean.cardano.julc.verification.dsl.ir;

import java.util.Objects;

public record BytesLiteralNode(
        DslType resultType, BytesLiteralKind kind, String hex) implements PropertyNode {
    public BytesLiteralNode {
        resultType = Objects.requireNonNull(resultType, "resultType");
        kind = Objects.requireNonNull(kind, "kind");
        hex = Objects.requireNonNull(hex, "hex");
    }
}
