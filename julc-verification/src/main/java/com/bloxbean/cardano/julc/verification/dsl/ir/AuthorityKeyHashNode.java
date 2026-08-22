package com.bloxbean.cardano.julc.verification.dsl.ir;

import java.util.Objects;

/** Explicit fixed or compiler-owned byte-string to public-key-hash bridge. */
public record AuthorityKeyHashNode(
        AuthoritySourceKind sourceKind, PropertyNode bytes) implements PropertyNode {
    public AuthorityKeyHashNode {
        sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
        bytes = Objects.requireNonNull(bytes, "bytes");
    }

    @Override
    public DslType resultType() {
        return DslType.TYPED_VALUE;
    }
}
