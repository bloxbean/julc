package com.bloxbean.cardano.julc.verification.dsl;

import com.bloxbean.cardano.julc.verification.dsl.ir.PropertyNode;
import com.bloxbean.cardano.julc.verification.dsl.ir.TxCertKind;
import com.bloxbean.cardano.julc.verification.dsl.ir.TxCertKindNode;

import java.util.Objects;

/** Symbolic pinned V3 transaction certificate. */
public record TxCertExpr(PropertyNode node) implements Expr {
    public TxCertExpr { node = Objects.requireNonNull(node, "node"); }

    public BoolExpr isKind(TxCertKind kind) {
        return new BoolExpr(new TxCertKindNode(node, kind));
    }
}
