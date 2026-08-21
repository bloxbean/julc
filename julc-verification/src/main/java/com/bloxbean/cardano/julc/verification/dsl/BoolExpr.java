package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.BoolBinaryNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.BoolLiteralNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.BoolNotNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.BoolOperator;
import com.bloxbean.cardano.julc.verification.dsl.ir.TypedEqualityNode;
import com.bloxbean.cardano.julc.verification.dsl.type.BuiltinTypeRef;
import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;

import java.util.Objects;

public record BoolExpr(PropertyNode node) implements Expr {
    public BoolExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr and(BoolExpr other) {
        return binary(BoolOperator.AND, other);
    }
    public BoolExpr or(BoolExpr other) {
        return binary(BoolOperator.OR, other);
    }
    public BoolExpr implies(BoolExpr other) {
        return binary(BoolOperator.IMPLIES, other);
    }
    public BoolExpr not() { return new BoolExpr(new BoolNotNode(node)); }
    public BoolExpr eq(BoolExpr other) {
        return equality(other, false);
    }
    public BoolExpr ne(BoolExpr other) {
        return equality(other, true);
    }
    private BoolExpr equality(BoolExpr other, boolean negated) {
        return new BoolExpr(new TypedEqualityNode(node, other.node,
                new BuiltinTypeRef(BuiltinTypeRef.BuiltinKind.BOOLEAN), negated));
    }
    private BoolExpr binary(BoolOperator operator, BoolExpr other) {
        return new BoolExpr(new BoolBinaryNode(operator, node, other.node));
    }
}
