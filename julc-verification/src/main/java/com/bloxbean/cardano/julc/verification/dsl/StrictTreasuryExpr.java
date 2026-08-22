package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Three-state reviewed optional-integer adapter for one pinned treasury field. */
public record StrictTreasuryExpr(PropertyNode txInfo, TreasuryField field) {
    public StrictTreasuryExpr {
        txInfo = Objects.requireNonNull(txInfo, "txInfo");
        field = Objects.requireNonNull(field, "field");
    }

    public BoolExpr isWellFormed() { return predicate(switch (field) {
        case CURRENT_AMOUNT -> ReviewedAdapterPredicateNode.ReviewedAdapterPredicate
                .CURRENT_TREASURY_WELL_FORMED;
        case DONATION -> ReviewedAdapterPredicateNode.ReviewedAdapterPredicate
                .TREASURY_DONATION_WELL_FORMED;
    }); }

    public BoolExpr isAbsent() { return predicate(switch (field) {
        case CURRENT_AMOUNT -> ReviewedAdapterPredicateNode.ReviewedAdapterPredicate
                .CURRENT_TREASURY_ABSENT;
        case DONATION -> ReviewedAdapterPredicateNode.ReviewedAdapterPredicate
                .TREASURY_DONATION_ABSENT;
    }); }

    public BoolExpr isMalformed() { return predicate(switch (field) {
        case CURRENT_AMOUNT -> ReviewedAdapterPredicateNode.ReviewedAdapterPredicate
                .CURRENT_TREASURY_MALFORMED;
        case DONATION -> ReviewedAdapterPredicateNode.ReviewedAdapterPredicate
                .TREASURY_DONATION_MALFORMED;
    }); }

    public BoolExpr whenPresent(Function<IntegerExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> new BoolExpr(new ReviewedAdapterWhenNode(
                field == TreasuryField.CURRENT_AMOUNT
                        ? ReviewedAdapterWhenNode.ReviewedAdapterEliminator
                                .CURRENT_TREASURY_PRESENT
                        : ReviewedAdapterWhenNode.ReviewedAdapterEliminator
                                .TREASURY_DONATION_PRESENT,
                txInfo, List.of(variable),
                predicate.apply(new IntegerExpr(new TypedVariableNode(
                        variable, LedgerTypeAuthority.INTEGER))).node())));
    }

    private BoolExpr predicate(
            ReviewedAdapterPredicateNode.ReviewedAdapterPredicate kind) {
        return new BoolExpr(new ReviewedAdapterPredicateNode(kind, List.of(txInfo)));
    }

    public enum TreasuryField { CURRENT_AMOUNT, DONATION }
}
