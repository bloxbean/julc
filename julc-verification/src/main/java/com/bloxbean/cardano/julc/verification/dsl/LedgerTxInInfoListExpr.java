package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import com.bloxbean.cardano.julc.verification.dsl.type.ListTypeRef;

import java.util.Objects;
import java.util.List;
import java.util.function.Function;

/** Ordered, duplicate-preserving V3 input list. */
public record LedgerTxInInfoListExpr(PropertyNode node) implements Expr {
    public LedgerTxInInfoListExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr isEmpty() { return state(ListState.EMPTY); }
    public BoolExpr isNotEmpty() { return state(ListState.NON_EMPTY); }
    public BoolExpr exists(Function<LedgerTxInInfoExpr, BoolExpr> predicate) {
        return quantify(QuantifierKind.EXISTS, predicate);
    }
    public BoolExpr all(Function<LedgerTxInInfoExpr, BoolExpr> predicate) {
        return quantify(QuantifierKind.ALL, predicate);
    }
    public BoolExpr none(Function<LedgerTxInInfoExpr, BoolExpr> predicate) {
        return quantify(QuantifierKind.NONE, predicate);
    }
    public IntegerExpr count(Function<LedgerTxInInfoExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> new IntegerExpr(new ListCountNode(node,
                LedgerTypeAuthority.TX_IN_INFO, variable, predicate.apply(new LedgerTxInInfoExpr(
                new TypedVariableNode(variable, LedgerTypeAuthority.TX_IN_INFO))).node())));
    }
    public BoolExpr exactlyOne(Function<LedgerTxInInfoExpr, BoolExpr> predicate) {
        return count(predicate).eq(VerificationDsl.integer(1));
    }
    public LedgerTxInInfoOptionExpr at(IntegerExpr index) {
        Objects.requireNonNull(index, "index");
        return new LedgerTxInInfoOptionExpr(new ListAtNode(
                node, LedgerTypeAuthority.TX_IN_INFO, index.node()));
    }
    public LedgerTxInInfoOptionExpr resolve(LedgerTxOutRefExpr reference) {
        Objects.requireNonNull(reference, "reference");
        return new LedgerTxInInfoOptionExpr(new LedgerHelperNode(
                LedgerHelperNode.LedgerHelperKind.RESOLVE_INPUT,
                List.of(node, reference.node()),
                new com.bloxbean.cardano.julc.verification.dsl.type.OptionalTypeRef(
                        LedgerTypeAuthority.TX_IN_INFO)));
    }
    public LedgerValueExpr valueSpent() {
        return new LedgerValueExpr(new LedgerHelperNode(
                LedgerHelperNode.LedgerHelperKind.AGGREGATE_INPUT_VALUES,
                List.of(node), LedgerTypeAuthority.VALUE));
    }
    public LedgerTxInInfoListExpr forPaymentKey(TypedValueExpr keyHash) {
        return filter(keyHash, LedgerTypeAuthority.PUB_KEY_HASH,
                LedgerHelperNode.LedgerHelperKind.FILTER_PAYMENT_KEY_INPUTS);
    }
    public LedgerTxInInfoListExpr forScript(TypedValueExpr scriptHash) {
        return filter(scriptHash, LedgerTypeAuthority.SCRIPT_HASH,
                LedgerHelperNode.LedgerHelperKind.FILTER_SCRIPT_INPUTS);
    }
    public BoolExpr structurallyEquals(LedgerTxInInfoListExpr other) {
        return structural(other, false);
    }
    public BoolExpr structurallyNotEquals(LedgerTxInInfoListExpr other) {
        return structural(other, true);
    }
    private BoolExpr state(ListState state) {
        return new BoolExpr(new ListStateNode(node, LedgerTypeAuthority.TX_IN_INFO, state));
    }
    private BoolExpr quantify(QuantifierKind kind,
            Function<LedgerTxInInfoExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> new BoolExpr(new ListQuantifierNode(node,
                LedgerTypeAuthority.TX_IN_INFO, kind, variable,
                predicate.apply(new LedgerTxInInfoExpr(new TypedVariableNode(
                        variable, LedgerTypeAuthority.TX_IN_INFO))).node())));
    }
    private BoolExpr structural(LedgerTxInInfoListExpr other, boolean negated) {
        Objects.requireNonNull(other, "other");
        return new BoolExpr(new StructuralEqualsNode(node, other.node,
                new ListTypeRef(LedgerTypeAuthority.TX_IN_INFO), negated));
    }
    private LedgerTxInInfoListExpr filter(
            TypedValueExpr hash,
            com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef expected,
            LedgerHelperNode.LedgerHelperKind helper) {
        Objects.requireNonNull(hash, "hash");
        if (!expected.equals(hash.valueType())) {
            throw new IllegalArgumentException("Input filter hash type does not match");
        }
        return new LedgerTxInInfoListExpr(new LedgerHelperNode(helper,
                List.of(node, hash.node()),
                new ListTypeRef(LedgerTypeAuthority.TX_IN_INFO)));
    }
}
