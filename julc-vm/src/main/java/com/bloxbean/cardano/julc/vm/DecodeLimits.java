package com.bloxbean.cardano.julc.vm;

/** Protocol-selected UPLC deserialization limits. */
public record DecodeLimits(int constantTypeHeader, int constructorFields) {

    public static final DecodeLimits UNBOUNDED =
            new DecodeLimits(Integer.MAX_VALUE, Integer.MAX_VALUE);
    public static final DecodeLimits PV11 = new DecodeLimits(32, 1024);

    public DecodeLimits {
        if (constantTypeHeader <= 0 || constructorFields <= 0) {
            throw new IllegalArgumentException("Decode limits must be positive");
        }
    }
}
