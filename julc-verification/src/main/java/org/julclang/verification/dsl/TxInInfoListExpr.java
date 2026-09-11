package org.julclang.verification.dsl;

import org.julclang.verification.dsl.ir.ConsumesNode;
import org.julclang.verification.dsl.ir.PropertyNode;

import java.util.Objects;

public record TxInInfoListExpr(PropertyNode node) implements Expr {
    public TxInInfoListExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr consumes(TxOutRefExpr outputReference) {
        return new BoolExpr(new ConsumesNode(node, outputReference.node()));
    }
}
