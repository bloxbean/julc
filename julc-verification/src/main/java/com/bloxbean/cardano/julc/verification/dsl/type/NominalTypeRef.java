package com.bloxbean.cardano.julc.verification.dsl.type;

import java.util.Objects;

/** Stable compiler identity; never inferred from a Java simple name. */
public record NominalTypeRef(String stableId, NominalKind nominalKind)
        implements VerificationTypeRef {
    public NominalTypeRef {
        stableId = Objects.requireNonNull(stableId, "stableId");
        nominalKind = Objects.requireNonNull(nominalKind, "nominalKind");
        if (stableId.isBlank() || stableId.length() > 1024
                || stableId.indexOf('\n') >= 0 || stableId.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid nominal stable ID");
        }
    }

    public enum NominalKind {
        RECORD,
        SUM,
        /** Reserved until ContractSchema retains compiler-owned newtype identity. */
        NEWTYPE
    }
}
