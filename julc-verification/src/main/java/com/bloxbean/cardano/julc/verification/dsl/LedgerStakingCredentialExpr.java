package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;

import java.util.Objects;
import java.util.function.Function;

public record LedgerStakingCredentialExpr(PropertyNode node) implements Expr {
    public LedgerStakingCredentialExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr isHash() { return is("StakingHash"); }
    public BoolExpr isPointer() { return is("StakingPtr"); }
    public BoolExpr whenHash(Function<LedgerCredentialExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> {
            var bound = new TypedVariableNode(variable, LedgerTypeAuthority.STAKING_CREDENTIAL);
            var payload = new LedgerCredentialExpr(new LedgerVariantFieldNode(bound,
                    LedgerTypeAuthority.STAKING_CREDENTIAL, "StakingHash", "credential",
                    LedgerTypeAuthority.CREDENTIAL));
            return new BoolExpr(new LedgerVariantWhenNode(node,
                    LedgerTypeAuthority.STAKING_CREDENTIAL, "StakingHash", variable,
                    predicate.apply(payload).node()));
        });
    }
    public BoolExpr whenPointer(
            TriFunction<IntegerExpr, IntegerExpr, IntegerExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> {
            var bound = new TypedVariableNode(variable, LedgerTypeAuthority.STAKING_CREDENTIAL);
            var slot = integerField(bound, "slot");
            var transaction = integerField(bound, "transactionIndex");
            var certificate = integerField(bound, "certificateIndex");
            return new BoolExpr(new LedgerVariantWhenNode(node,
                    LedgerTypeAuthority.STAKING_CREDENTIAL, "StakingPtr", variable,
                    predicate.apply(slot, transaction, certificate).node()));
        });
    }
    private BoolExpr is(String constructor) {
        return new BoolExpr(new LedgerVariantIsNode(
                node, LedgerTypeAuthority.STAKING_CREDENTIAL, constructor));
    }
    private static IntegerExpr integerField(TypedVariableNode target, String name) {
        return new IntegerExpr(new LedgerVariantFieldNode(target,
                LedgerTypeAuthority.STAKING_CREDENTIAL, "StakingPtr", name,
                LedgerTypeAuthority.INTEGER));
    }

    @FunctionalInterface
    public interface TriFunction<A, B, C, R> {
        R apply(A first, B second, C third);
    }
}
