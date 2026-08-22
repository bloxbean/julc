package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/** Reviewed Plutus-rational view of the raw UpdateCommittee quorum payload. */
public record QuorumExpr(PropertyNode guardedAction) {
    public QuorumExpr {
        guardedAction = Objects.requireNonNull(guardedAction, "guardedAction");
    }

    public BoolExpr decoderValid() { return predicate(
            ReviewedAdapterPredicateNode.ReviewedAdapterPredicate.QUORUM_DECODER_VALID); }
    public BoolExpr canonicalEncoding() { return predicate(
            ReviewedAdapterPredicateNode.ReviewedAdapterPredicate
                    .QUORUM_CANONICAL_ENCODING); }
    public BoolExpr isUnitInterval() { return predicate(
            ReviewedAdapterPredicateNode.ReviewedAdapterPredicate.QUORUM_UNIT_INTERVAL); }

    /** Supplies the normalized numerator and positive denominator. */
    public BoolExpr whenDecoded(
            BiFunction<IntegerExpr, IntegerExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(numerator -> BinderScope.bind(denominator ->
                new BoolExpr(new ReviewedAdapterWhenNode(
                        ReviewedAdapterWhenNode.ReviewedAdapterEliminator.QUORUM_DECODED,
                        guardedAction, List.of(numerator, denominator),
                        predicate.apply(
                                new IntegerExpr(new TypedVariableNode(
                                        numerator, LedgerTypeAuthority.INTEGER)),
                                new IntegerExpr(new TypedVariableNode(
                                        denominator, LedgerTypeAuthority.INTEGER))).node()))));
    }

    private BoolExpr predicate(
            ReviewedAdapterPredicateNode.ReviewedAdapterPredicate kind) {
        return new BoolExpr(new ReviewedAdapterPredicateNode(
                kind, List.of(guardedAction)));
    }
}
