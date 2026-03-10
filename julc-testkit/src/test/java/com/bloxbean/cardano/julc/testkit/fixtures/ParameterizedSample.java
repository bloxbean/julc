package com.bloxbean.cardano.julc.testkit.fixtures;

import com.bloxbean.cardano.julc.stdlib.annotation.Param;

/**
 * Test fixture for parameterized class testing.
 * Has @Param fields that must be supplied via JulcEval.forClass(class, params).
 *
 * Note: Uses {@code long} (not BigInteger) and avoids operators on BigInteger
 * so this file is valid Java AND valid JuLC source.
 * @Param fields are {@code public static} to be valid Java (JuLC also allows instance fields).
 */
public class ParameterizedSample {

    @Param public static long threshold;
    @Param public static byte[] owner;

    static long getThreshold() {
        return threshold;
    }

    static byte[] getOwner() {
        return owner;
    }

    static boolean isAboveThreshold(long value) {
        return value > threshold;
    }

    static long addToThreshold(long x) {
        return threshold + x;
    }

    static long doubleIt(long x) {
        return x * 2;
    }
}
