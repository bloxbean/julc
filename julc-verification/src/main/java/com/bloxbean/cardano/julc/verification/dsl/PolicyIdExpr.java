package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;

import java.util.Objects;

public record PolicyIdExpr(PropertyNode node) implements Expr {
    public PolicyIdExpr { node = Objects.requireNonNull(node, "node"); }
}
