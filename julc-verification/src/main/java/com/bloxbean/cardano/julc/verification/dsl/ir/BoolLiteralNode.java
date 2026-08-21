package com.bloxbean.cardano.julc.verification.dsl.ir;

public record BoolLiteralNode(boolean value) implements PropertyNode {
    @Override public DslType resultType() { return DslType.BOOL; }
}
