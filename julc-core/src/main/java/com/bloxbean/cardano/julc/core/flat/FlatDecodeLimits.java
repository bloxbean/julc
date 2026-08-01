package com.bloxbean.cardano.julc.core.flat;

/** Limits applied while decoding a ledger UPLC program from FLAT. */
public record FlatDecodeLimits(
        int maxConstantTypeNodes,
        int maxConstructorFields) {

    public static final FlatDecodeLimits UNBOUNDED =
            new FlatDecodeLimits(Integer.MAX_VALUE, Integer.MAX_VALUE);

    public FlatDecodeLimits {
        if (maxConstantTypeNodes <= 0 || maxConstructorFields <= 0) {
            throw new IllegalArgumentException("FLAT decode limits must be positive");
        }
    }
}
