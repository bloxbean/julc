package org.julclang.verification.dsl;

import org.julclang.verification.dsl.ir.KnownCertificateNode;
import org.julclang.verification.dsl.ir.PropertyNode;

import java.util.Objects;

/** Ordered V3 certificate list; indexing follows pinned isKnownCertificate. */
public record TxCertListExpr(PropertyNode node) implements Expr {
    public TxCertListExpr { node = Objects.requireNonNull(node, "node"); }

    public BoolExpr containsAt(IntegerExpr index, TxCertExpr certificate) {
        return new BoolExpr(new KnownCertificateNode(
                certificate.node(), index.node(), node));
    }
}
