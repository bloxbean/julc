package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.LedgerFieldNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.TypedEqualityNode;

import java.util.Objects;

public record LedgerTxOutRefExpr(PropertyNode node) implements Expr {
    public LedgerTxOutRefExpr { node = Objects.requireNonNull(node, "node"); }
    public LedgerTxIdExpr id() {
        return new LedgerTxIdExpr(new LedgerFieldNode(node, LedgerTypeAuthority.TX_OUT_REF,
                "id", LedgerTypeAuthority.TX_ID));
    }
    public IntegerExpr index() {
        return new IntegerExpr(new LedgerFieldNode(node, LedgerTypeAuthority.TX_OUT_REF,
                "index", LedgerTypeAuthority.INTEGER));
    }
    public BoolExpr eq(LedgerTxOutRefExpr other) { return equality(other, false); }
    public BoolExpr ne(LedgerTxOutRefExpr other) { return equality(other, true); }
    private BoolExpr equality(LedgerTxOutRefExpr other, boolean negated) {
        Objects.requireNonNull(other, "other");
        return new BoolExpr(new TypedEqualityNode(node, other.node,
                LedgerTypeAuthority.TX_OUT_REF, negated));
    }
}
