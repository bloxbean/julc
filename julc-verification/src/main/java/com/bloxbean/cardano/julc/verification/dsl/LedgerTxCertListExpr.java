package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import com.bloxbean.cardano.julc.verification.dsl.type.ListTypeRef;

import java.util.Objects;
import java.util.function.Function;

/** Original ordered V3 transaction-certificate list. */
public record LedgerTxCertListExpr(PropertyNode node) implements Expr {
    public LedgerTxCertListExpr { node = Objects.requireNonNull(node, "node"); }

    public BoolExpr isEmpty() { return state(ListState.EMPTY); }
    public BoolExpr isNotEmpty() { return state(ListState.NON_EMPTY); }
    public BoolExpr exists(Function<TxCertExpr, BoolExpr> predicate) {
        return quantify(QuantifierKind.EXISTS, predicate);
    }
    public BoolExpr all(Function<TxCertExpr, BoolExpr> predicate) {
        return quantify(QuantifierKind.ALL, predicate);
    }
    public BoolExpr none(Function<TxCertExpr, BoolExpr> predicate) {
        return quantify(QuantifierKind.NONE, predicate);
    }
    public IntegerExpr count(Function<TxCertExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> new IntegerExpr(new ListCountNode(
                node, LedgerTypeAuthority.TX_CERT, variable,
                predicate.apply(new TxCertExpr(new TypedVariableNode(
                        variable, LedgerTypeAuthority.TX_CERT))).node())));
    }
    public LedgerTxCertOptionExpr at(IntegerExpr index) {
        Objects.requireNonNull(index, "index");
        return new LedgerTxCertOptionExpr(new ListAtNode(
                node, LedgerTypeAuthority.TX_CERT, index.node()));
    }
    public BoolExpr containsAt(IntegerExpr index, TxCertExpr certificate) {
        Objects.requireNonNull(certificate, "certificate");
        return at(index).exists(found -> found.eq(certificate));
    }
    public BoolExpr structurallyEquals(LedgerTxCertListExpr other) {
        return structural(other, false);
    }
    public BoolExpr structurallyNotEquals(LedgerTxCertListExpr other) {
        return structural(other, true);
    }

    private BoolExpr state(ListState state) {
        return new BoolExpr(new ListStateNode(node, LedgerTypeAuthority.TX_CERT, state));
    }

    private BoolExpr quantify(
            QuantifierKind kind, Function<TxCertExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> new BoolExpr(new ListQuantifierNode(
                node, LedgerTypeAuthority.TX_CERT, kind, variable,
                predicate.apply(new TxCertExpr(new TypedVariableNode(
                        variable, LedgerTypeAuthority.TX_CERT))).node())));
    }

    private BoolExpr structural(LedgerTxCertListExpr other, boolean negated) {
        Objects.requireNonNull(other, "other");
        return new BoolExpr(new StructuralEqualsNode(node, other.node,
                new ListTypeRef(LedgerTypeAuthority.TX_CERT), negated));
    }
}
