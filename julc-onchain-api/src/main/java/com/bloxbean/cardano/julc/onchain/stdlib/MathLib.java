package com.bloxbean.cardano.julc.onchain.stdlib;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.math.BigInteger;

/**
 * On-chain mathematical operations.
 * <p>
 * These methods are executable both on-chain (compiled to UPLC via StdlibRegistry)
 * and off-chain (as plain Java for debugging and testing).
 */
public final class MathLib {

    private MathLib() {}

    /**
     * Returns the absolute value of an integer.
     *
     * @param x the input integer
     * @return the absolute value of x
     */
    public static BigInteger abs(BigInteger x) {
        return x.abs();
    }

    /**
     * Returns the maximum of two integers.
     *
     * @param a first integer
     * @param b second integer
     * @return the maximum value
     */
    public static BigInteger max(BigInteger a, BigInteger b) {
        return a.max(b);
    }

    /**
     * Returns the minimum of two integers.
     *
     * @param a first integer
     * @param b second integer
     * @return the minimum value
     */
    public static BigInteger min(BigInteger a, BigInteger b) {
        return a.min(b);
    }

    /**
     * Returns division and modulo as a pair: (div, mod).
     * <p>
     * Result is encoded as ConstrData(0, [IData(div), IData(mod)]).
     *
     * @param a the dividend
     * @param b the divisor
     * @return PlutusData.Constr representing (div, mod)
     */
    public static PlutusData divMod(BigInteger a, BigInteger b) {
        var div = a.divide(b);
        var mod = a.mod(b);
        return new PlutusData.Constr(0, java.util.List.of(
                new PlutusData.IntData(div),
                new PlutusData.IntData(mod)));
    }

    /**
     * Returns quotient and remainder as a pair: (quot, rem).
     * <p>
     * Result is encoded as ConstrData(0, [IData(quot), IData(rem)]).
     *
     * @param a the dividend
     * @param b the divisor
     * @return PlutusData.Constr representing (quot, rem)
     */
    public static PlutusData quotRem(BigInteger a, BigInteger b) {
        var quot = a.divide(b);
        var rem = a.remainder(b);
        return new PlutusData.Constr(0, java.util.List.of(
                new PlutusData.IntData(quot),
                new PlutusData.IntData(rem)));
    }

    /**
     * Returns base raised to the power of exp.
     *
     * @param base the base value
     * @param exp  the exponent (must be non-negative)
     * @return base^exp
     */
    public static BigInteger pow(BigInteger base, BigInteger exp) {
        if (exp.signum() < 0) {
            throw new IllegalArgumentException("Exponent must be non-negative");
        }
        return base.pow(exp.intValueExact());
    }

    /**
     * Returns the sign of an integer: -1 if negative, 0 if zero, 1 if positive.
     *
     * @param x the input integer
     * @return -1, 0, or 1
     */
    public static BigInteger sign(BigInteger x) {
        return BigInteger.valueOf(x.signum());
    }

    /**
     * Returns (base^exp) mod modulus using modular exponentiation.
     *
     * @param base the base value
     * @param exp  the exponent
     * @param mod  the modulus
     * @return (base^exp) mod modulus
     */
    public static BigInteger expMod(BigInteger base, BigInteger exp, BigInteger mod) {
        return base.modPow(exp, mod);
    }
}
