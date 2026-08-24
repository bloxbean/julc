package com.bloxbean.cardano.julc.verification.dsl.ir;

import java.util.Objects;

/** Closed certificate-constructor recognition; no raw tag enters the IR. */
public record TxCertKindNode(PropertyNode certificate, TxCertKind kind)
        implements PropertyNode {
    public TxCertKindNode {
        certificate = Objects.requireNonNull(certificate, "certificate");
        kind = Objects.requireNonNull(kind, "kind");
    }

    @Override
    public DslType resultType() {
        return DslType.BOOL;
    }
}
