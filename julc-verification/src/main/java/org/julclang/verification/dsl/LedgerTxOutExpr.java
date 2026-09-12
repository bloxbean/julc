package org.julclang.verification.dsl;

import org.julclang.verification.dsl.ir.LedgerFieldNode;
import org.julclang.verification.dsl.ir.PropertyNode;
import org.julclang.verification.dsl.type.OptionalTypeRef;

import java.util.Objects;

public record LedgerTxOutExpr(PropertyNode node) implements Expr {
    public LedgerTxOutExpr { node = Objects.requireNonNull(node, "node"); }
    public LedgerAddressExpr address() {
        return new LedgerAddressExpr(new LedgerFieldNode(node, LedgerTypeAuthority.TX_OUT,
                "address", LedgerTypeAuthority.ADDRESS));
    }
    public LedgerValueExpr value() {
        return new LedgerValueExpr(new LedgerFieldNode(node, LedgerTypeAuthority.TX_OUT,
                "value", LedgerTypeAuthority.VALUE));
    }
    public LedgerOutputDatumExpr datum() {
        return new LedgerOutputDatumExpr(new LedgerFieldNode(node,
                LedgerTypeAuthority.TX_OUT, "datum", LedgerTypeAuthority.OUTPUT_DATUM));
    }
    public TypedOptionExpr referenceScript() {
        return new TypedOptionExpr(new LedgerFieldNode(node, LedgerTypeAuthority.TX_OUT,
                "referenceScript", new OptionalTypeRef(LedgerTypeAuthority.SCRIPT_HASH)),
                LedgerTypeAuthority.SCRIPT_HASH);
    }
}
