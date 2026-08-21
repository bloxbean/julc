package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;
import com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef;

import java.util.Objects;

/** Low-level carrier used only by generated schema-4 wrappers. */
public record TypedValueExpr(PropertyNode node, VerificationTypeRef valueType)
        implements Expr {
    public TypedValueExpr {
        node = Objects.requireNonNull(node, "node");
        valueType = Objects.requireNonNull(valueType, "valueType");
    }

    public BoolExpr eq(TypedValueExpr other) { return equality(other, false); }
    public BoolExpr ne(TypedValueExpr other) { return equality(other, true); }
    private BoolExpr equality(TypedValueExpr other, boolean negated) {
        Objects.requireNonNull(other, "other");
        return new BoolExpr(new com.bloxbean.cardano.julc.verification.dsl.ir.TypedEqualityNode(
                node, other.node, valueType, negated));
    }
}
