package com.bloxbean.cardano.julc.verification.dsl.type;

import java.util.Objects;

public record BuiltinTypeRef(BuiltinKind builtin) implements VerificationTypeRef {
    public BuiltinTypeRef {
        builtin = Objects.requireNonNull(builtin, "builtin");
    }

    public enum BuiltinKind {
        BOOLEAN,
        INTEGER,
        BYTE_STRING,
        STRING,
        UNIT,
        DATA
    }
}
