package com.bloxbean.cardano.julc.verification.dsl.ir;

import com.bloxbean.cardano.julc.verification.dsl.type.LedgerTypeRef;

import java.util.Objects;

/** Explicit representation-preserving byte-string to ledger-hash alias bridge. */
public record LedgerByteAliasNode(
        PropertyNode bytes, LedgerTypeRef aliasType) implements PropertyNode {
    public LedgerByteAliasNode {
        bytes = Objects.requireNonNull(bytes, "bytes");
        aliasType = Objects.requireNonNull(aliasType, "aliasType");
    }
    @Override public DslType resultType() { return DslType.TYPED_VALUE; }
}
