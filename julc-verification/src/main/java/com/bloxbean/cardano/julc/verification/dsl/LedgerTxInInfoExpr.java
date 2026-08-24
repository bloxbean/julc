package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.LedgerFieldNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;

import java.util.Objects;

public record LedgerTxInInfoExpr(PropertyNode node) implements Expr {
    public LedgerTxInInfoExpr { node = Objects.requireNonNull(node, "node"); }
    public LedgerTxOutRefExpr outRef() {
        return new LedgerTxOutRefExpr(new LedgerFieldNode(node,
                LedgerTypeAuthority.TX_IN_INFO, "outRef", LedgerTypeAuthority.TX_OUT_REF));
    }
    public LedgerTxOutExpr resolved() {
        return new LedgerTxOutExpr(new LedgerFieldNode(node,
                LedgerTypeAuthority.TX_IN_INFO, "resolved", LedgerTypeAuthority.TX_OUT));
    }
}
