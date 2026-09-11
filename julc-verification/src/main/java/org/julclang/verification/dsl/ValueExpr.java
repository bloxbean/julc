package org.julclang.verification.dsl;

import org.julclang.verification.dsl.ir.DslType;
import org.julclang.verification.dsl.ir.FieldNode;
import org.julclang.verification.dsl.ir.PropertyNode;

import java.util.Objects;

public record ValueExpr(PropertyNode node) implements Expr {
    public ValueExpr { node = Objects.requireNonNull(node, "node"); }
    public IntegerExpr lovelace() {
        return new IntegerExpr(new FieldNode(node, "lovelace", DslType.INTEGER));
    }
}
