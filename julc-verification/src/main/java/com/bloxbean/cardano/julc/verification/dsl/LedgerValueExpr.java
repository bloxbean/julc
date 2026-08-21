package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.LedgerHelperNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;

import java.util.List;
import java.util.Objects;

/** Bridge to the already-reviewed lovelace-only value surface. */
public record LedgerValueExpr(PropertyNode node) implements Expr {
    public LedgerValueExpr { node = Objects.requireNonNull(node, "node"); }
    public IntegerExpr lovelace() {
        return new IntegerExpr(new LedgerHelperNode(
                LedgerHelperNode.LedgerHelperKind.LOVELACE_OF,
                List.of(node), LedgerTypeAuthority.INTEGER));
    }
}
