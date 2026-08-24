package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import java.util.Objects;

/** Strict symbolic pinned V3 governance vote. */
public record VoteExpr(PropertyNode node) implements Expr {
    public VoteExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr isNo() { return is("VoteNo"); }
    public BoolExpr isYes() { return is("VoteYes"); }
    public BoolExpr isAbstain() { return is("Abstain"); }
    public TypedValueExpr typed() { return new TypedValueExpr(node, LedgerTypeAuthority.VOTE); }
    private BoolExpr is(String constructor) {
        return new BoolExpr(new LedgerVariantIsNode(node, LedgerTypeAuthority.VOTE, constructor));
    }
}
