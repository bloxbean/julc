package org.julclang.verification.dsl;

import org.julclang.verification.dsl.ir.PropertyNode;

import java.util.Objects;

public record TxOutRefExpr(PropertyNode node) implements Expr {
    public TxOutRefExpr { node = Objects.requireNonNull(node, "node"); }
}
