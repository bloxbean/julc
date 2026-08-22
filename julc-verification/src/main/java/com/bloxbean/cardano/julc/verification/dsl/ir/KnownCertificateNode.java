package com.bloxbean.cardano.julc.verification.dsl.ir;

import java.util.Objects;

/** Pinned isKnownCertificate relation over one ordered certificate list. */
public record KnownCertificateNode(
        PropertyNode certificate,
        PropertyNode index,
        PropertyNode certificates) implements PropertyNode {
    public KnownCertificateNode {
        certificate = Objects.requireNonNull(certificate, "certificate");
        index = Objects.requireNonNull(index, "index");
        certificates = Objects.requireNonNull(certificates, "certificates");
    }

    @Override
    public DslType resultType() {
        return DslType.BOOL;
    }
}
