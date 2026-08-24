package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import com.bloxbean.cardano.julc.verification.dsl.type.ListTypeRef;

import java.util.Objects;
import java.util.function.Function;
import java.util.List;

/** Ordered V2 output list as embedded in the pinned V3 transaction model. */
public record LedgerTxOutListExpr(PropertyNode node) implements Expr {
    public LedgerTxOutListExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr isEmpty() { return state(ListState.EMPTY); }
    public BoolExpr isNotEmpty() { return state(ListState.NON_EMPTY); }
    public BoolExpr exists(Function<LedgerTxOutExpr, BoolExpr> predicate) {
        return quantify(QuantifierKind.EXISTS, predicate);
    }
    public BoolExpr all(Function<LedgerTxOutExpr, BoolExpr> predicate) {
        return quantify(QuantifierKind.ALL, predicate);
    }
    public BoolExpr none(Function<LedgerTxOutExpr, BoolExpr> predicate) {
        return quantify(QuantifierKind.NONE, predicate);
    }
    public BoolExpr whenSingleton(Function<LedgerTxOutExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> new BoolExpr(new ListSingletonWhenNode(
                node, LedgerTypeAuthority.TX_OUT, variable,
                predicate.apply(new LedgerTxOutExpr(new TypedVariableNode(
                        variable, LedgerTypeAuthority.TX_OUT))).node())));
    }
    public IntegerExpr count(Function<LedgerTxOutExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> new IntegerExpr(new ListCountNode(node,
                LedgerTypeAuthority.TX_OUT, variable, predicate.apply(new LedgerTxOutExpr(
                new TypedVariableNode(variable, LedgerTypeAuthority.TX_OUT))).node())));
    }
    public BoolExpr exactlyOne(Function<LedgerTxOutExpr, BoolExpr> predicate) {
        return count(predicate).eq(VerificationDsl.integer(1));
    }
    public LedgerTxOutOptionExpr at(IntegerExpr index) {
        Objects.requireNonNull(index, "index");
        return new LedgerTxOutOptionExpr(new ListAtNode(
                node, LedgerTypeAuthority.TX_OUT, index.node()));
    }
    public LedgerValueExpr valueProduced() {
        return new LedgerValueExpr(new LedgerHelperNode(
                LedgerHelperNode.LedgerHelperKind.AGGREGATE_OUTPUT_VALUES,
                List.of(node), LedgerTypeAuthority.VALUE));
    }
    public LedgerTxOutListExpr toAddress(TypedValueExpr address) {
        return filter(address, LedgerTypeAuthority.ADDRESS,
                LedgerHelperNode.LedgerHelperKind.FILTER_ADDRESS_OUTPUTS);
    }
    public LedgerTxOutListExpr toPaymentCredential(TypedValueExpr credential) {
        return filter(credential, LedgerTypeAuthority.CREDENTIAL,
                LedgerHelperNode.LedgerHelperKind.FILTER_PAYMENT_CREDENTIAL_OUTPUTS);
    }
    public BoolExpr structurallyEquals(LedgerTxOutListExpr other) {
        return structural(other, false);
    }
    public BoolExpr structurallyNotEquals(LedgerTxOutListExpr other) {
        return structural(other, true);
    }
    private BoolExpr state(ListState state) {
        return new BoolExpr(new ListStateNode(node, LedgerTypeAuthority.TX_OUT, state));
    }
    private BoolExpr quantify(QuantifierKind kind,
            Function<LedgerTxOutExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> new BoolExpr(new ListQuantifierNode(node,
                LedgerTypeAuthority.TX_OUT, kind, variable,
                predicate.apply(new LedgerTxOutExpr(new TypedVariableNode(
                        variable, LedgerTypeAuthority.TX_OUT))).node())));
    }
    private BoolExpr structural(LedgerTxOutListExpr other, boolean negated) {
        Objects.requireNonNull(other, "other");
        return new BoolExpr(new StructuralEqualsNode(node, other.node,
                new ListTypeRef(LedgerTypeAuthority.TX_OUT), negated));
    }
    private LedgerTxOutListExpr filter(
            TypedValueExpr value,
            com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef expected,
            LedgerHelperNode.LedgerHelperKind helper) {
        Objects.requireNonNull(value, "value");
        if (!expected.equals(value.valueType())) {
            throw new IllegalArgumentException("Output filter type does not match");
        }
        return new LedgerTxOutListExpr(new LedgerHelperNode(helper,
                List.of(node, value.node()), new ListTypeRef(LedgerTypeAuthority.TX_OUT)));
    }
}
