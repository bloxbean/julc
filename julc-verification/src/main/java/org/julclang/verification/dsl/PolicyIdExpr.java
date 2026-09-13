package org.julclang.verification.dsl;

import org.julclang.verification.dsl.ir.PropertyNode;

import java.util.Objects;

public record PolicyIdExpr(PropertyNode node) implements Expr {
    public PolicyIdExpr { node = Objects.requireNonNull(node, "node"); }
}
