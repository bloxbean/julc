package org.julclang.verification.dsl;

import org.julclang.verification.dsl.ir.PropertyNode;

import java.util.Objects;

/** DRep-credential role over the pinned credential representation. */
public final class LedgerDRepCredentialExpr implements LedgerRoleCredentialExpr {
    private final PropertyNode node;

    LedgerDRepCredentialExpr(PropertyNode node) {
        this.node = Objects.requireNonNull(node, "node");
    }

    @Override
    public PropertyNode node() {
        return node;
    }
}
