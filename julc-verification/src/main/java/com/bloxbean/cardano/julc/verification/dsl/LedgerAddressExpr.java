package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.LedgerFieldNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;
import com.bloxbean.cardano.julc.verification.dsl.type.OptionalTypeRef;

import java.util.Objects;

public record LedgerAddressExpr(PropertyNode node) implements Expr {
    public LedgerAddressExpr { node = Objects.requireNonNull(node, "node"); }
    public LedgerCredentialExpr paymentCredential() {
        return new LedgerCredentialExpr(new LedgerFieldNode(node,
                LedgerTypeAuthority.ADDRESS, "paymentCredential",
                LedgerTypeAuthority.CREDENTIAL));
    }
    public LedgerStakingCredentialOptionExpr stakingCredential() {
        return new LedgerStakingCredentialOptionExpr(new LedgerFieldNode(node,
                LedgerTypeAuthority.ADDRESS,
                "stakingCredential", new OptionalTypeRef(
                        LedgerTypeAuthority.STAKING_CREDENTIAL)));
    }
}
