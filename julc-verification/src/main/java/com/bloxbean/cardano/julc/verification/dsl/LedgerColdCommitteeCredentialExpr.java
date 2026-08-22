package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;

import java.util.Objects;

/** Cold-committee-credential role over the pinned credential representation. */
public final class LedgerColdCommitteeCredentialExpr implements LedgerRoleCredentialExpr {
    private final PropertyNode node;

    LedgerColdCommitteeCredentialExpr(PropertyNode node) {
        this.node = Objects.requireNonNull(node, "node");
    }

    @Override
    public PropertyNode node() {
        return node;
    }
}
