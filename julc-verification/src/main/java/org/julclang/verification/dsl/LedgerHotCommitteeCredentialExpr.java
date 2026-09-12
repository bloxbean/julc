package org.julclang.verification.dsl;

import org.julclang.verification.dsl.ir.PropertyNode;

import java.util.Objects;

/** Hot-committee-credential role over the pinned credential representation. */
public final class LedgerHotCommitteeCredentialExpr implements LedgerRoleCredentialExpr {
    private final PropertyNode node;

    LedgerHotCommitteeCredentialExpr(PropertyNode node) {
        this.node = Objects.requireNonNull(node, "node");
    }

    @Override
    public PropertyNode node() {
        return node;
    }
}
