package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.TypedEqualityNode;
import com.bloxbean.cardano.julc.verification.dsl.type.BuiltinTypeRef;

import java.util.Objects;

/** Source String remains distinct from byte[] even though both encode as bytes. */
public record StringExpr(PropertyNode node) implements Expr {
    public StringExpr {
        node = Objects.requireNonNull(node, "node");
    }

    public BoolExpr eq(StringExpr other) { return equality(other, false); }
    public BoolExpr ne(StringExpr other) { return equality(other, true); }
    private BoolExpr equality(StringExpr other, boolean negated) {
        Objects.requireNonNull(other, "other");
        return new BoolExpr(new TypedEqualityNode(node, other.node,
                new BuiltinTypeRef(BuiltinTypeRef.BuiltinKind.STRING), negated));
    }
}
