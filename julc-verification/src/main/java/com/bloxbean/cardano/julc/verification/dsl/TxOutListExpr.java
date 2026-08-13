package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.DslType;
import com.bloxbean.cardano.julc.verification.dsl.ir.ExistsNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.RootNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;

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
