package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.DslType;
import com.bloxbean.cardano.julc.verification.dsl.ir.FieldNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;

import java.util.Objects;

public record TxInfoExpr(PropertyNode node) implements Expr {
    public TxInfoExpr { node = Objects.requireNonNull(node, "node"); }
    public ByteStringListExpr signatories() {
        return new ByteStringListExpr(new FieldNode(node, "signatories", DslType.LIST_BYTE_STRING));
    }
    public TxOutListExpr outputs() {
        return new TxOutListExpr(new FieldNode(node, "outputs", DslType.LIST_TX_OUT));
    }
    public TxInInfoListExpr inputs() {
        return new TxInInfoListExpr(new FieldNode(node, "inputs", DslType.LIST_TX_IN_INFO));
    }
    public MintValueExpr mint() {
        return new MintValueExpr(new FieldNode(node, "mint", DslType.MINT_VALUE));
    }
    public WithdrawalsExpr withdrawals() {
        return new WithdrawalsExpr(new FieldNode(node, "withdrawals", DslType.WITHDRAWALS));
    }
    public TxCertListExpr certificates() {
        return new TxCertListExpr(new FieldNode(node, "certificates", DslType.LIST_TX_CERT));
    }
}
