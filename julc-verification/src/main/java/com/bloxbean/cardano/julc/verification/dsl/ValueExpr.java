package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.DslType;
import com.bloxbean.cardano.julc.verification.dsl.ir.FieldNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;

import java.util.Objects;

public record ValueExpr(PropertyNode node) implements Expr {
    public ValueExpr { node = Objects.requireNonNull(node, "node"); }
    public IntegerExpr lovelace() {
        return new IntegerExpr(new FieldNode(node, "lovelace", DslType.INTEGER));
    }
}
