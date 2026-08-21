package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.TypedEqualityNode;

import java.util.Objects;

public record LedgerTxIdExpr(PropertyNode node) implements Expr {
    public LedgerTxIdExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr eq(LedgerTxIdExpr other) { return equality(other, false); }
    public BoolExpr ne(LedgerTxIdExpr other) { return equality(other, true); }
    public BoolExpr eq(LedgerByteAliasExpr other) { return aliasEquality(other, false); }
    public BoolExpr ne(LedgerByteAliasExpr other) { return aliasEquality(other, true); }
    private BoolExpr equality(LedgerTxIdExpr other, boolean negated) {
        Objects.requireNonNull(other, "other");
        return new BoolExpr(new TypedEqualityNode(node, other.node,
                LedgerTypeAuthority.TX_ID, negated));
    }
    private BoolExpr aliasEquality(LedgerByteAliasExpr other, boolean negated) {
        Objects.requireNonNull(other, "other");
        if (!LedgerTypeAuthority.TX_ID.equals(other.aliasType())) {
            throw new IllegalArgumentException("Expected a transaction-ID alias");
        }
        return new BoolExpr(new TypedEqualityNode(node, other.node(),
                LedgerTypeAuthority.TX_ID, negated));
    }
}
