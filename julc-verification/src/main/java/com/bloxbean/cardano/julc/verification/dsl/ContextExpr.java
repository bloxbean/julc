package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.DslType;
import com.bloxbean.cardano.julc.verification.dsl.ir.FieldNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;

import java.util.Objects;

public record ContextExpr(PropertyNode node) implements Expr {
    public ContextExpr { node = Objects.requireNonNull(node, "node"); }
    public TxInfoExpr txInfo() {
        return new TxInfoExpr(new FieldNode(node, "txInfo", DslType.TX_INFO));
    }
}
