package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;

import java.util.Objects;
import java.util.function.Function;

public record LedgerStakingCredentialOptionExpr(PropertyNode node) implements Expr {
    public LedgerStakingCredentialOptionExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr isPresent() { return state(OptionState.PRESENT); }
    public BoolExpr isEmpty() { return state(OptionState.EMPTY); }
    public BoolExpr exists(Function<LedgerStakingCredentialExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> new BoolExpr(new OptionExistsNode(node,
                variable, LedgerTypeAuthority.STAKING_CREDENTIAL,
                predicate.apply(new LedgerStakingCredentialExpr(new TypedVariableNode(
                        variable, LedgerTypeAuthority.STAKING_CREDENTIAL))).node())));
    }
    private BoolExpr state(OptionState state) {
        return new BoolExpr(new OptionStateNode(
                node, LedgerTypeAuthority.STAKING_CREDENTIAL, state));
    }
}
