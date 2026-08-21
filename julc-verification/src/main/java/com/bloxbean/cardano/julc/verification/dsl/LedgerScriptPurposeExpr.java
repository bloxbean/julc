package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.TypedEqualityNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.LedgerVariantIsNode;

import java.util.Objects;

public record LedgerScriptPurposeExpr(PropertyNode node) implements Expr {
    public LedgerScriptPurposeExpr { node = Objects.requireNonNull(node, "node"); }
    public TypedValueExpr typed() {
        return new TypedValueExpr(node, LedgerTypeAuthority.SCRIPT_PURPOSE);
    }
    public BoolExpr isMinting() { return is("Minting"); }
    public BoolExpr isSpending() { return is("Spending"); }
    public BoolExpr isRewarding() { return is("Rewarding"); }
    public BoolExpr isCertifying() { return is("Certifying"); }
    public BoolExpr isVoting() { return is("Voting"); }
    public BoolExpr isProposing() { return is("Proposing"); }
    public BoolExpr eq(LedgerScriptPurposeExpr other) { return equality(other, false); }
    public BoolExpr ne(LedgerScriptPurposeExpr other) { return equality(other, true); }
    private BoolExpr equality(LedgerScriptPurposeExpr other, boolean negated) {
        Objects.requireNonNull(other, "other");
        return new BoolExpr(new TypedEqualityNode(node, other.node,
                LedgerTypeAuthority.SCRIPT_PURPOSE, negated));
    }
    private BoolExpr is(String constructor) {
        return new BoolExpr(new LedgerVariantIsNode(
                node, LedgerTypeAuthority.SCRIPT_PURPOSE, constructor));
    }
}
