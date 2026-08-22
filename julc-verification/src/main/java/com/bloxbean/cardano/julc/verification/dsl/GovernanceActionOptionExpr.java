package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import java.util.Objects;
import java.util.function.Function;

/** Guarded result of strict decoding of a proposal's raw governance action. */
public record GovernanceActionOptionExpr(PropertyNode node) implements Expr {
    public GovernanceActionOptionExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr exists(Function<GovernanceActionExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> new BoolExpr(new OptionExistsNode(node, variable,
                LedgerTypeAuthority.GOVERNANCE_ACTION,
                predicate.apply(new GovernanceActionExpr(new TypedVariableNode(
                        variable, LedgerTypeAuthority.GOVERNANCE_ACTION))).node())));
    }
    public BoolExpr isPresent() { return state(OptionState.PRESENT); }
    public BoolExpr isEmpty() { return state(OptionState.EMPTY); }
    private BoolExpr state(OptionState state) {
        return new BoolExpr(new OptionStateNode(node, LedgerTypeAuthority.GOVERNANCE_ACTION, state));
    }
}
