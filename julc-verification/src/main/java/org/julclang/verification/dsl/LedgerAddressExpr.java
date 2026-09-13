package org.julclang.verification.dsl;

import org.julclang.verification.dsl.ir.LedgerFieldNode;
import org.julclang.verification.dsl.ir.PropertyNode;
import org.julclang.verification.dsl.type.OptionalTypeRef;

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
