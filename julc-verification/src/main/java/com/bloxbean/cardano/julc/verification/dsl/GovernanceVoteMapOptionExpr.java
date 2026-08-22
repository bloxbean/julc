package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import com.bloxbean.cardano.julc.verification.dsl.type.AssocMapTypeRef;
import java.util.function.Function;

/** Guarded outer voter-map lookup result. */
public record GovernanceVoteMapOptionExpr(PropertyNode node) implements Expr {
    public BoolExpr exists(Function<GovernanceVoteMapExpr, BoolExpr> predicate) {
        var inner = new AssocMapTypeRef(LedgerTypeAuthority.GOVERNANCE_ACTION_ID,
                LedgerTypeAuthority.VOTE);
        return BinderScope.bind(v -> new BoolExpr(new OptionExistsNode(node, v, inner,
                predicate.apply(new GovernanceVoteMapExpr(
                        new TypedVariableNode(v, inner))).node())));
    }
}
