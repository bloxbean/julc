package org.julclang.verification.dsl;

import org.julclang.verification.dsl.ir.*;
import java.util.function.Function;

public record GovernanceVoteOptionExpr(PropertyNode node) implements Expr {
    public BoolExpr exists(Function<VoteExpr, BoolExpr> predicate) {
        return BinderScope.bind(v -> new BoolExpr(new OptionExistsNode(node, v,
                LedgerTypeAuthority.VOTE, predicate.apply(new VoteExpr(
                        new TypedVariableNode(v, LedgerTypeAuthority.VOTE))).node())));
    }
}
