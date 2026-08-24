package com.bloxbean.cardano.julc.verification.dsl.ir;

import com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef;

import java.util.Objects;

/** Quantity lookup with its duplicate/malformed semantics explicit in the node. */
public record ValueQuantityNode(
        ValueQuantityKind quantityKind,
        PropertyNode value,
        VerificationTypeRef valueType,
        PropertyNode policy,
        PropertyNode token) implements PropertyNode {
    public ValueQuantityNode {
        quantityKind = Objects.requireNonNull(quantityKind, "quantityKind");
        value = Objects.requireNonNull(value, "value");
        valueType = Objects.requireNonNull(valueType, "valueType");
        policy = Objects.requireNonNull(policy, "policy");
        token = Objects.requireNonNull(token, "token");
    }
    @Override public DslType resultType() {
        return quantityKind == ValueQuantityKind.FIRST_MATCH
                ? DslType.INTEGER : DslType.TYPED_VALUE;
    }
    public enum ValueQuantityKind { FIRST_MATCH, STRICT_SUMMED }
}
