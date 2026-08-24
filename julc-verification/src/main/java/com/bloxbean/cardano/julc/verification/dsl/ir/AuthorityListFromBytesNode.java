package com.bloxbean.cardano.julc.verification.dsl.ir;

import java.util.Objects;

/** Dynamic contract byte-string list bridged element-wise to public-key hashes. */
public record AuthorityListFromBytesNode(PropertyNode bytesList) implements PropertyNode {
    public AuthorityListFromBytesNode {
        bytesList = Objects.requireNonNull(bytesList, "bytesList");
    }

    @Override
    public DslType resultType() {
        return DslType.TYPED_VALUE;
    }
}
