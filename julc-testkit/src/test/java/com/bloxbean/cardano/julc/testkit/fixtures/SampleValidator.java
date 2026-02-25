package com.bloxbean.cardano.julc.testkit.fixtures;

import com.bloxbean.cardano.julc.stdlib.Builtins;

/**
 * Test fixture for file-based MethodEvaluator tests.
 * Contains helper methods that can be compiled and evaluated individually.
 *
 * Note: Uses {@code long} (not BigInteger) so this file is valid Java AND valid JuLC source.
 * JuLC maps both {@code long} and {@code BigInteger} to IntegerType on-chain.
 */
public class SampleValidator {

    static long doubleIt(long x) {
        return x * 2;
    }

    static byte[] concatBytes(byte[] a, byte[] b) {
        return Builtins.appendByteString(a, b);
    }

    static boolean isPositive(long x) {
        return x > 0;
    }

    static long sumUpTo(long n) {
        long result = 0;
        long i = 1;
        while (i <= n) {
            result = result + i;
            i = i + 1;
        }
        return result;
    }
}
