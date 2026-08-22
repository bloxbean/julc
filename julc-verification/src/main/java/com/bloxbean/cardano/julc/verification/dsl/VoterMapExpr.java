package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import com.bloxbean.cardano.julc.verification.dsl.type.AssocMapTypeRef;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/** Ordered duplicate-preserving voter map. */
public record VoterMapExpr(PropertyNode node) implements Expr {
    private static final AssocMapTypeRef INNER = new AssocMapTypeRef(
            LedgerTypeAuthority.GOVERNANCE_ACTION_ID, LedgerTypeAuthority.VOTE);
    public VoterMapExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr existsEntry(BiFunction<VoterExpr, GovernanceVoteMapExpr, BoolExpr> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        return BinderScope.bind(k -> BinderScope.bind(v -> new BoolExpr(new MapQuantifierNode(
                node, LedgerTypeAuthority.VOTER, INNER, QuantifierKind.EXISTS, k, v,
                predicate.apply(new VoterExpr(new TypedVariableNode(k, LedgerTypeAuthority.VOTER)),
                        new GovernanceVoteMapExpr(new TypedVariableNode(v, INNER))).node()))));
    }
    public BoolExpr isKnown(VoterExpr voter) {
        return new BoolExpr(new LedgerHelperNode(LedgerHelperNode.LedgerHelperKind.IS_KNOWN_VOTER,
                List.of(voter.node(), node), LedgerTypeAuthority.BOOL));
    }
    public GovernanceVoteMapOptionExpr lookupFirst(VoterExpr voter) {
        Objects.requireNonNull(voter, "voter");
        return new GovernanceVoteMapOptionExpr(new MapLookupFirstNode(node,
                LedgerTypeAuthority.VOTER, INNER, voter.node()));
    }
    public IntegerExpr countKey(VoterExpr voter) {
        Objects.requireNonNull(voter, "voter");
        return new IntegerExpr(new MapCountKeyNode(node, LedgerTypeAuthority.VOTER,
                INNER, voter.node()));
    }
    public TypedAssocMapExpr typed() { return new TypedAssocMapExpr(node,
            LedgerTypeAuthority.VOTER, INNER); }
}
