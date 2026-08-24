package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;

import java.util.Objects;
import java.util.function.Function;

public record LedgerTxOutOptionExpr(PropertyNode node) implements Expr {
    public LedgerTxOutOptionExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr isPresent() { return state(OptionState.PRESENT); }
    public BoolExpr isEmpty() { return state(OptionState.EMPTY); }
    public BoolExpr exists(Function<LedgerTxOutExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> new BoolExpr(new OptionExistsNode(node,
                variable, LedgerTypeAuthority.TX_OUT,
                predicate.apply(new LedgerTxOutExpr(new TypedVariableNode(
                        variable, LedgerTypeAuthority.TX_OUT))).node())));
    }
    private BoolExpr state(OptionState state) {
        return new BoolExpr(new OptionStateNode(node, LedgerTypeAuthority.TX_OUT, state));
    }
}
