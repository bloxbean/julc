package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import java.util.Objects;
import java.util.function.Function;

/** Guarded proposal-list lookup. */
public record GovernanceProposalOptionExpr(PropertyNode node) implements Expr {
    public GovernanceProposalOptionExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr exists(Function<ProposalProcedureExpr, BoolExpr> predicate) {
        return BinderScope.bind(variable -> new BoolExpr(new OptionExistsNode(node, variable,
                LedgerTypeAuthority.PROPOSAL_PROCEDURE,
                predicate.apply(new ProposalProcedureExpr(new TypedVariableNode(variable,
                        LedgerTypeAuthority.PROPOSAL_PROCEDURE))).node())));
    }
}
