package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;
import com.bloxbean.cardano.julc.verification.dsl.type.BuiltinTypeRef;
import com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef;

import java.util.Objects;

/** Low-level carrier used only by generated schema-1 wrappers. */
public record TypedValueExpr(PropertyNode node, VerificationTypeRef valueType)
        implements Expr {
    public TypedValueExpr {
        node = Objects.requireNonNull(node, "node");
        valueType = Objects.requireNonNull(valueType, "valueType");
    }

    public BoolExpr eq(TypedValueExpr other) { return equality(other, false); }
    public BoolExpr ne(TypedValueExpr other) { return equality(other, true); }

    /**
     * Narrows an integer-typed generic binder to the public integer expression API.
     *
     * <p>Generic list/map/option callbacks use {@code TypedValueExpr} because their
     * element type is supplied by the compiler-owned type graph. This conversion
     * keeps callers out of the raw IR while rejecting every non-integer type.</p>
     */
    public IntegerExpr asInteger() {
        var integerType = new BuiltinTypeRef(BuiltinTypeRef.BuiltinKind.INTEGER);
        if (!integerType.equals(valueType)) {
            throw new IllegalArgumentException(
                    "Typed value is not an integer: " + valueType);
        }
        return new IntegerExpr(node);
    }

    private BoolExpr equality(TypedValueExpr other, boolean negated) {
        Objects.requireNonNull(other, "other");
        return new BoolExpr(new com.bloxbean.cardano.julc.verification.dsl.ir.TypedEqualityNode(
                node, other.node, valueType, negated));
    }
}
