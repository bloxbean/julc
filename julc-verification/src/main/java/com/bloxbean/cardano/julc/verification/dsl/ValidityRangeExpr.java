package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.ReviewedAdapterPredicateNode;

import java.util.List;
import java.util.Objects;

/** Reviewed pinned-decoder view of {@code TxInfo.txInfoValidRange}. */
public record ValidityRangeExpr(PropertyNode txInfo) {
    public ValidityRangeExpr {
        txInfo = Objects.requireNonNull(txInfo, "txInfo");
    }

    public BoolExpr decoderValid() {
        return predicate(ReviewedAdapterPredicateNode.ReviewedAdapterPredicate
                .VALIDITY_DECODER_VALID, txInfo);
    }

    public BoolExpr canonicalEncoding() {
        return predicate(ReviewedAdapterPredicateNode.ReviewedAdapterPredicate
                .VALIDITY_CANONICAL_ENCODING, txInfo);
    }

    public BoolExpr isEmpty() {
        return predicate(ReviewedAdapterPredicateNode.ReviewedAdapterPredicate
                .VALIDITY_EMPTY, txInfo);
    }

    public BoolExpr contains(IntegerExpr time) {
        Objects.requireNonNull(time, "time");
        return predicate(ReviewedAdapterPredicateNode.ReviewedAdapterPredicate
                .VALIDITY_CONTAINS, txInfo, time.node());
    }

    public BoolExpr includes(ValidityRangeExpr other) {
        Objects.requireNonNull(other, "other");
        return predicate(ReviewedAdapterPredicateNode.ReviewedAdapterPredicate
                .VALIDITY_INCLUDES, txInfo, other.txInfo());
    }

    public BoolExpr isEntirelyBefore(IntegerExpr time) {
        Objects.requireNonNull(time, "time");
        return predicate(ReviewedAdapterPredicateNode.ReviewedAdapterPredicate
                .VALIDITY_ENTIRELY_BEFORE, txInfo, time.node());
    }

    public BoolExpr isEntirelyAfter(IntegerExpr time) {
        Objects.requireNonNull(time, "time");
        return predicate(ReviewedAdapterPredicateNode.ReviewedAdapterPredicate
                .VALIDITY_ENTIRELY_AFTER, txInfo, time.node());
    }

    private static BoolExpr predicate(
            ReviewedAdapterPredicateNode.ReviewedAdapterPredicate kind,
            PropertyNode... arguments) {
        return new BoolExpr(new ReviewedAdapterPredicateNode(kind, List.of(arguments)));
    }
}
