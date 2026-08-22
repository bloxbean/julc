package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.ReviewedAdapterPredicateNode;

import java.util.List;
import java.util.Objects;

/** Duplicate- and order-preserving integer-key view of ChangedParameters. */
public record ChangedParametersExpr(PropertyNode guardedAction) {
    public ChangedParametersExpr {
        guardedAction = Objects.requireNonNull(guardedAction, "guardedAction");
    }

    public BoolExpr isWellFormed() { return predicate(
            ReviewedAdapterPredicateNode.ReviewedAdapterPredicate
                    .CHANGED_PARAMETERS_WELL_FORMED); }
    public BoolExpr isNonEmpty() { return predicate(
            ReviewedAdapterPredicateNode.ReviewedAdapterPredicate
                    .CHANGED_PARAMETERS_NON_EMPTY); }
    public BoolExpr isStrictlyAscendingUnique() { return predicate(
            ReviewedAdapterPredicateNode.ReviewedAdapterPredicate
                    .CHANGED_PARAMETERS_STRICTLY_ASCENDING_UNIQUE); }
    public BoolExpr containsId(IntegerExpr id) {
        Objects.requireNonNull(id, "id");
        return predicate(ReviewedAdapterPredicateNode.ReviewedAdapterPredicate
                .CHANGED_PARAMETERS_CONTAINS_ID, id.node());
    }
    public BoolExpr countIdEquals(IntegerExpr id, IntegerExpr count) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(count, "count");
        return predicate(ReviewedAdapterPredicateNode.ReviewedAdapterPredicate
                .CHANGED_PARAMETERS_COUNT_ID_EQUALS, id.node(), count.node());
    }

    private BoolExpr predicate(
            ReviewedAdapterPredicateNode.ReviewedAdapterPredicate kind,
            PropertyNode... trailing) {
        var arguments = new java.util.ArrayList<PropertyNode>();
        arguments.add(guardedAction);
        arguments.addAll(List.of(trailing));
        return new BoolExpr(new ReviewedAdapterPredicateNode(kind, arguments));
    }
}
