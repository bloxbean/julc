package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;

import java.util.Objects;
import java.util.function.Function;

public record LedgerTxInInfoOptionExpr(PropertyNode node) implements Expr {
    public LedgerTxInInfoOptionExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr isPresent() { return state(OptionState.PRESENT); }
    public BoolExpr isEmpty() { return state(OptionState.EMPTY); }
    public BoolExpr exists(Function<LedgerTxInInfoExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> new BoolExpr(new OptionExistsNode(node,
                variable, LedgerTypeAuthority.TX_IN_INFO,
                predicate.apply(new LedgerTxInInfoExpr(new TypedVariableNode(
                        variable, LedgerTypeAuthority.TX_IN_INFO))).node())));
    }
    private BoolExpr state(OptionState state) {
        return new BoolExpr(new OptionStateNode(node, LedgerTypeAuthority.TX_IN_INFO, state));
    }
}
