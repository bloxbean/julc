package com.bloxbean.cardano.julc.verification.dsl.ir;

import java.util.Objects;

/** Tests specifically for a public-key credential carrying the selected key hash. */
public record CredentialKeyHashNode(PropertyNode credential, PropertyNode keyHash)
        implements PropertyNode {
    public CredentialKeyHashNode {
        credential = Objects.requireNonNull(credential, "credential");
        keyHash = Objects.requireNonNull(keyHash, "keyHash");
    }

    @Override
    public DslType resultType() { return DslType.BOOL; }
}
