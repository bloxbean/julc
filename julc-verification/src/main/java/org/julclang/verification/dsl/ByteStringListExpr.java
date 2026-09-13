package org.julclang.verification.dsl;

import org.julclang.verification.dsl.ir.ContainsNode;
import org.julclang.verification.dsl.ir.PropertyNode;

import java.util.Objects;

public record ByteStringListExpr(PropertyNode node) implements Expr {
    public ByteStringListExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr contains(ByteStringExpr value) {
        return new BoolExpr(new ContainsNode(node, value.node()));
    }
}
