package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;

import java.util.Objects;
import java.util.function.Function;

public record LedgerTxCertOptionExpr(PropertyNode node) implements Expr {
    public LedgerTxCertOptionExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr isPresent() { return state(OptionState.PRESENT); }
    public BoolExpr isEmpty() { return state(OptionState.EMPTY); }
    public BoolExpr exists(Function<TxCertExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> new BoolExpr(new OptionExistsNode(
                node, variable, LedgerTypeAuthority.TX_CERT,
                predicate.apply(new TxCertExpr(new TypedVariableNode(
                        variable, LedgerTypeAuthority.TX_CERT))).node())));
    }
    private BoolExpr state(OptionState state) {
        return new BoolExpr(new OptionStateNode(
                node, LedgerTypeAuthority.TX_CERT, state));
    }
}
