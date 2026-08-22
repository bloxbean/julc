package com.bloxbean.cardano.julc.verification.dsl.ir;

import com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef;

import java.util.Objects;

/** Structural or strict finite-support extensional relation between Value-like values. */
public record ValueRelationNode(
        ValueRelationKind relation,
        PropertyNode left,
        VerificationTypeRef leftType,
        PropertyNode right,
        VerificationTypeRef rightType) implements PropertyNode {
    public ValueRelationNode {
        relation = Objects.requireNonNull(relation, "relation");
        left = Objects.requireNonNull(left, "left");
        leftType = Objects.requireNonNull(leftType, "leftType");
        right = Objects.requireNonNull(right, "right");
        rightType = Objects.requireNonNull(rightType, "rightType");
    }
    @Override public DslType resultType() { return DslType.BOOL; }
    public enum ValueRelationKind { STRUCTURAL_EQ, EXTENSIONAL_EQ, LE, LT, GE, GT }
}
