package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;

import java.util.Objects;

public record TxOutRefExpr(PropertyNode node) implements Expr {
    public TxOutRefExpr { node = Objects.requireNonNull(node, "node"); }
}
