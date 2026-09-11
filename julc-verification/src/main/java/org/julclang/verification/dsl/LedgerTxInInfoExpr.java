package org.julclang.verification.dsl;

import org.julclang.verification.dsl.ir.LedgerFieldNode;
import org.julclang.verification.dsl.ir.PropertyNode;

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
