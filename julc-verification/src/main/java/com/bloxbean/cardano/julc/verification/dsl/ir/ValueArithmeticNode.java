package com.bloxbean.cardano.julc.verification.dsl.ir;

import com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef;

import java.util.List;
import java.util.Objects;

/** Checked linear arithmetic yielding an optional ValueDelta. */
public record ValueArithmeticNode(
        ValueArithmeticKind arithmetic,
        List<PropertyNode> arguments,
        List<VerificationTypeRef> argumentTypes,
        VerificationTypeRef resultTypeRef) implements PropertyNode {
    public ValueArithmeticNode {
        arithmetic = Objects.requireNonNull(arithmetic, "arithmetic");
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        argumentTypes = List.copyOf(Objects.requireNonNull(argumentTypes, "argumentTypes"));
        resultTypeRef = Objects.requireNonNull(resultTypeRef, "resultTypeRef");
    }
    @Override public DslType resultType() { return DslType.TYPED_VALUE; }
    public enum ValueArithmeticKind { VALIDATE, SINGLETON, ADD, NEGATE, SCALE }
}
