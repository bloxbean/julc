package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Closed pinned V3 delegation target. */
public record LedgerDelegateeExpr(PropertyNode node) implements Expr {
    public LedgerDelegateeExpr { node = Objects.requireNonNull(node, "node"); }

    public BoolExpr isStake() { return is("DelegStake"); }
    public BoolExpr isVote() { return is("DelegVote"); }
    public BoolExpr isStakeVote() { return is("DelegStakeVote"); }

    public BoolExpr whenStake(Function<LedgerByteAliasExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return when("DelegStake", bound -> predicate.apply(
                pool(bound, "DelegStake")).node());
    }

    public BoolExpr whenVote(Function<LedgerDRepExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return when("DelegVote", bound -> predicate.apply(drep(bound, "DelegVote")).node());
    }

    public BoolExpr whenStakeVote(
            BiFunction<LedgerByteAliasExpr, LedgerDRepExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return when("DelegStakeVote", bound -> predicate.apply(
                pool(bound, "DelegStakeVote"),
                drep(bound, "DelegStakeVote")).node());
    }

    private BoolExpr is(String constructor) {
        return new BoolExpr(new LedgerVariantIsNode(
                node, LedgerTypeAuthority.DELEGATEE, constructor));
    }

    private BoolExpr when(String constructor, Function<TypedVariableNode, PropertyNode> body) {
        return BinderScope.bind(variable -> {
            var bound = new TypedVariableNode(variable, LedgerTypeAuthority.DELEGATEE);
            return new BoolExpr(new LedgerVariantWhenNode(node,
                    LedgerTypeAuthority.DELEGATEE, constructor, variable,
                    body.apply(bound)));
        });
    }

    private static LedgerByteAliasExpr pool(
            TypedVariableNode bound, String constructor) {
        return new LedgerByteAliasExpr(new LedgerVariantFieldNode(bound,
                LedgerTypeAuthority.DELEGATEE,
                constructor, "pool", LedgerTypeAuthority.PUB_KEY_HASH),
                LedgerTypeAuthority.PUB_KEY_HASH);
    }

    private static LedgerDRepExpr drep(TypedVariableNode bound, String constructor) {
        return new LedgerDRepExpr(new LedgerVariantFieldNode(bound,
                LedgerTypeAuthority.DELEGATEE, constructor, "drep",
                LedgerTypeAuthority.DREP));
    }
}
