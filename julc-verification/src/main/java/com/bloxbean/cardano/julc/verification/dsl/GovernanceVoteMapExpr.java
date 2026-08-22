package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import java.util.Objects;
import java.util.function.BiFunction;

/** Ordered duplicate-preserving action-ID to vote map. */
public record GovernanceVoteMapExpr(PropertyNode node) implements Expr {
    public GovernanceVoteMapExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr existsEntry(BiFunction<GovernanceActionIdExpr, VoteExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(k -> BinderScope.bind(v -> new BoolExpr(new MapQuantifierNode(
                node, LedgerTypeAuthority.GOVERNANCE_ACTION_ID, LedgerTypeAuthority.VOTE,
                QuantifierKind.EXISTS, k, v, predicate.apply(actionId(k), vote(v)).node()))));
    }
    public GovernanceVoteOptionExpr lookupFirst(GovernanceActionIdExpr id) {
        return new GovernanceVoteOptionExpr(new MapLookupFirstNode(node,
                LedgerTypeAuthority.GOVERNANCE_ACTION_ID, LedgerTypeAuthority.VOTE, id.node()));
    }
    public IntegerExpr countKey(GovernanceActionIdExpr id) {
        return new IntegerExpr(new MapCountKeyNode(node, LedgerTypeAuthority.GOVERNANCE_ACTION_ID,
                LedgerTypeAuthority.VOTE, id.node()));
    }
    public BoolExpr containsKey(GovernanceActionIdExpr id) {
        return new BoolExpr(new MapContainsKeyNode(node,
                LedgerTypeAuthority.GOVERNANCE_ACTION_ID,
                LedgerTypeAuthority.VOTE, id.node()));
    }
    public TypedListExpr lookupAll(GovernanceActionIdExpr id) {
        return new TypedListExpr(new MapLookupAllNode(node,
                LedgerTypeAuthority.GOVERNANCE_ACTION_ID,
                LedgerTypeAuthority.VOTE, id.node()), LedgerTypeAuthority.VOTE);
    }
    public TypedAssocMapExpr typed() { return new TypedAssocMapExpr(node,
            LedgerTypeAuthority.GOVERNANCE_ACTION_ID, LedgerTypeAuthority.VOTE); }
    private static GovernanceActionIdExpr actionId(String name) { return new GovernanceActionIdExpr(
            new TypedVariableNode(name, LedgerTypeAuthority.GOVERNANCE_ACTION_ID)); }
    private static VoteExpr vote(String name) { return new VoteExpr(
            new TypedVariableNode(name, LedgerTypeAuthority.VOTE)); }
}
