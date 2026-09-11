package org.julclang.verification.dsl;

import org.julclang.verification.dsl.ir.DslType;
import org.julclang.verification.dsl.ir.ExistsNode;
import org.julclang.verification.dsl.ir.RootNode;
import org.julclang.verification.dsl.ir.PropertyNode;

import java.util.Objects;
import java.util.function.Function;

public record TxOutListExpr(PropertyNode node) implements Expr {
    public TxOutListExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr exists(Function<TxOutExpr, BoolExpr> predicate) {
        String variable = "output";
        var value = new TxOutExpr(new RootNode(variable, DslType.TX_OUT));
        return new BoolExpr(new ExistsNode(node, variable, predicate.apply(value).node()));
    }
}
