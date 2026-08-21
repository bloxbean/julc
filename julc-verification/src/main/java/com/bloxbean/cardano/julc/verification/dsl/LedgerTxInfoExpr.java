package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.LedgerFieldNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;
import com.bloxbean.cardano.julc.verification.dsl.type.AssocMapTypeRef;
import com.bloxbean.cardano.julc.verification.dsl.type.ListTypeRef;
import com.bloxbean.cardano.julc.verification.dsl.type.OptionalTypeRef;

import java.util.Objects;

public record LedgerTxInfoExpr(PropertyNode node) implements Expr {
    public LedgerTxInfoExpr { node = Objects.requireNonNull(node, "node"); }

    public LedgerTxInInfoListExpr inputs() {
        return inputsField("inputs");
    }
    public LedgerTxInInfoListExpr referenceInputs() {
        return inputsField("referenceInputs");
    }
    public LedgerTxOutListExpr outputs() {
        return new LedgerTxOutListExpr(new LedgerFieldNode(node, LedgerTypeAuthority.TX_INFO,
                "outputs", new ListTypeRef(LedgerTypeAuthority.TX_OUT)));
    }
    public IntegerExpr fee() {
        return new IntegerExpr(new LedgerFieldNode(node, LedgerTypeAuthority.TX_INFO,
                "fee", LedgerTypeAuthority.INTEGER));
    }
    public LedgerTxIdExpr id() {
        return new LedgerTxIdExpr(new LedgerFieldNode(node, LedgerTypeAuthority.TX_INFO,
                "id", LedgerTypeAuthority.TX_ID));
    }
    public TypedAssocMapExpr datums() {
        return new TypedAssocMapExpr(new LedgerFieldNode(node, LedgerTypeAuthority.TX_INFO,
                "datums", new AssocMapTypeRef(
                        LedgerTypeAuthority.DATUM_HASH, LedgerTypeAuthority.DATA)),
                LedgerTypeAuthority.DATUM_HASH, LedgerTypeAuthority.DATA);
    }
    public TypedAssocMapExpr redeemers() {
        return new TypedAssocMapExpr(new LedgerFieldNode(node, LedgerTypeAuthority.TX_INFO,
                "redeemers", new AssocMapTypeRef(
                        LedgerTypeAuthority.SCRIPT_PURPOSE, LedgerTypeAuthority.DATA)),
                LedgerTypeAuthority.SCRIPT_PURPOSE, LedgerTypeAuthority.DATA);
    }

    private LedgerTxInInfoListExpr inputsField(String name) {
        return new LedgerTxInInfoListExpr(new LedgerFieldNode(node,
                LedgerTypeAuthority.TX_INFO, name,
                new ListTypeRef(LedgerTypeAuthority.TX_IN_INFO)));
    }
}
