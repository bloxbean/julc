package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.*;
import java.util.Objects;

/** Symbolic pinned V3 governance-action identifier. */
public record GovernanceActionIdExpr(PropertyNode node) implements Expr {
    public GovernanceActionIdExpr { node = Objects.requireNonNull(node, "node"); }
    public LedgerTxIdExpr txId() { return new LedgerTxIdExpr(field("txId", LedgerTypeAuthority.TX_ID)); }
    public IntegerExpr index() { return new IntegerExpr(field("index", LedgerTypeAuthority.INTEGER)); }
    public TypedValueExpr typed() { return new TypedValueExpr(node, LedgerTypeAuthority.GOVERNANCE_ACTION_ID); }
    public BoolExpr eq(GovernanceActionIdExpr other) { return equality(other, false); }
    public BoolExpr ne(GovernanceActionIdExpr other) { return equality(other, true); }
    private PropertyNode field(String name, com.bloxbean.cardano.julc.verification.dsl.type.VerificationTypeRef type) {
        return new LedgerFieldNode(node, LedgerTypeAuthority.GOVERNANCE_ACTION_ID, name, type);
    }
    private BoolExpr equality(GovernanceActionIdExpr other, boolean negated) {
        Objects.requireNonNull(other, "other");
        return new BoolExpr(new TypedEqualityNode(node, other.node,
                LedgerTypeAuthority.GOVERNANCE_ACTION_ID, negated));
    }
}
