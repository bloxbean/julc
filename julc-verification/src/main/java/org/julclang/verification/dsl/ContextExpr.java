package org.julclang.verification.dsl;

import org.julclang.verification.dsl.ir.DslType;
import org.julclang.verification.dsl.ir.FieldNode;
import org.julclang.verification.dsl.ir.PropertyNode;

import java.util.Objects;

public record ContextExpr(PropertyNode node) implements Expr {
    public ContextExpr { node = Objects.requireNonNull(node, "node"); }
    public TxInfoExpr txInfo() {
        return new TxInfoExpr(new FieldNode(node, "txInfo", DslType.TX_INFO));
    }
}
