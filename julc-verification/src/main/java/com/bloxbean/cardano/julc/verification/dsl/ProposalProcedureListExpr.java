package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import java.util.Objects;
import java.util.List;
import java.util.function.Function;

/** Ordered duplicate-preserving proposal list. */
public record ProposalProcedureListExpr(PropertyNode node) implements Expr {
    public ProposalProcedureListExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr exists(Function<ProposalProcedureExpr, BoolExpr> predicate) {
        return quantify(QuantifierKind.EXISTS, predicate);
    }
    public BoolExpr all(Function<ProposalProcedureExpr, BoolExpr> predicate) {
        return quantify(QuantifierKind.ALL, predicate);
    }
    public BoolExpr none(Function<ProposalProcedureExpr, BoolExpr> predicate) {
        return quantify(QuantifierKind.NONE, predicate);
    }
    public IntegerExpr count(Function<ProposalProcedureExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> new IntegerExpr(new ListCountNode(node,
                LedgerTypeAuthority.PROPOSAL_PROCEDURE, variable,
                predicate.apply(proposal(variable)).node())));
    }
    public GovernanceProposalOptionExpr at(IntegerExpr index) {
        Objects.requireNonNull(index, "index");
        return new GovernanceProposalOptionExpr(new ListAtNode(node,
                LedgerTypeAuthority.PROPOSAL_PROCEDURE, index.node()));
    }
    public TypedListExpr typed() { return new TypedListExpr(node, LedgerTypeAuthority.PROPOSAL_PROCEDURE); }
    public BoolExpr isKnown(ProposalProcedureExpr proposal, IntegerExpr index) {
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(index, "index");
        return new BoolExpr(new LedgerHelperNode(
                LedgerHelperNode.LedgerHelperKind.IS_KNOWN_PROPOSAL,
                List.of(proposal.node(), index.node(), node), LedgerTypeAuthority.BOOL));
    }
    private BoolExpr quantify(QuantifierKind kind,
            Function<ProposalProcedureExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(variable -> new BoolExpr(new ListQuantifierNode(node,
                LedgerTypeAuthority.PROPOSAL_PROCEDURE, kind, variable,
                predicate.apply(proposal(variable)).node())));
    }
    private static ProposalProcedureExpr proposal(String variable) {
        return new ProposalProcedureExpr(new TypedVariableNode(
                variable, LedgerTypeAuthority.PROPOSAL_PROCEDURE));
    }
}
