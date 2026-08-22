package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.TypedEqualityNode;
import com.bloxbean.cardano.julc.verification.dsl.type.LedgerTypeRef;

import java.util.Objects;

/** Explicitly typed ledger hash/identifier whose representation is a byte string. */
public record LedgerByteAliasExpr(PropertyNode node, LedgerTypeRef aliasType)
        implements Expr {
    public LedgerByteAliasExpr {
        node = Objects.requireNonNull(node, "node");
        aliasType = Objects.requireNonNull(aliasType, "aliasType");
    }
    public TypedValueExpr typed() { return new TypedValueExpr(node, aliasType); }
    public BoolExpr eq(LedgerByteAliasExpr other) { return equality(other, false); }
    public BoolExpr ne(LedgerByteAliasExpr other) { return equality(other, true); }
    private BoolExpr equality(LedgerByteAliasExpr other, boolean negated) {
        Objects.requireNonNull(other, "other");
        if (!aliasType.equals(other.aliasType)) {
            throw new IllegalArgumentException("Ledger byte aliases do not match");
        }
        return new BoolExpr(new TypedEqualityNode(
                node, other.node, aliasType, negated));
    }
}
