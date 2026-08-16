package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.ContainsNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;

import java.util.Objects;

public record ByteStringListExpr(PropertyNode node) implements Expr {
    public ByteStringListExpr { node = Objects.requireNonNull(node, "node"); }
    public BoolExpr contains(ByteStringExpr value) {
        return new BoolExpr(new ContainsNode(node, value.node()));
    }
}
