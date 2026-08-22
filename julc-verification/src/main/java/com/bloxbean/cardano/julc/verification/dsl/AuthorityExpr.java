package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;

import java.util.Objects;

/** One explicitly sourced modeled public-key-hash identity. */
public record AuthorityExpr(PropertyNode node) implements Expr {
    public AuthorityExpr {
        node = Objects.requireNonNull(node, "node");
    }
}
