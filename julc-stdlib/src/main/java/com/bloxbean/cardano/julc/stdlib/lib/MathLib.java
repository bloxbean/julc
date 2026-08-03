package com.bloxbean.cardano.julc.stdlib.lib;

import com.bloxbean.cardano.julc.core.types.Tuple2;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.stdlib.Builtins;

import java.math.BigInteger;

/**
 * Mathematical operations compiled from Java source to UPLC.
 */
@OnchainLibrary
public class MathLib {

    /** Returns the absolute value of an integer. */
    public static BigInteger abs(BigInteger x) {
        if (x.compareTo(BigInteger.ZERO) < 0) {
            return x.negate();
        } else {
            return x;
        }
    }

    /** Returns the maximum of two integers. */
    public static BigInteger max(BigInteger a, BigInteger b) {
        if (a.compareTo(b) < 0) {
            return b;
        } else {
            return a;
        }
    }

    /** Returns the minimum of two integers. */
    public static BigInteger min(BigInteger a, BigInteger b) {
        if (a.compareTo(b) <= 0) {
            return a;
        } else {
            return b;
        }
    }

    /**
     * Returns division and modulo as a Tuple2.
     * <p>
     * Division by zero causes script failure on-chain (UPLC error) and ArithmeticException off-chain.
     */
    public static Tuple2<BigInteger, BigInteger> divMod(BigInteger a, BigInteger b) {
        var div = MathLib.floorDiv(a, b);
        var mod = MathLib.floorMod(a, b);
        return new Tuple2(Builtins.iData(div), Builtins.iData(mod));
    }

    /**
     * Returns quotient and remainder as a Tuple2.
     * <p>
     * Division by zero causes script failure on-chain (UPLC error) and ArithmeticException off-chain.
     */
    public static Tuple2<BigInteger, BigInteger> quotRem(BigInteger a, BigInteger b) {
        var quot = a.divide(b);
        var rem = a.remainder(b);
        return new Tuple2(Builtins.iData(quot), Builtins.iData(rem));
    }

    /**
     * Returns floor division, rounding toward negative infinity.
     * <p>
     * On-chain this is compiled directly to UPLC DivideInteger.
     */
    public static BigInteger floorDiv(BigInteger a, BigInteger b) {
        BigInteger q = a.divide(b);
        BigInteger r = a.remainder(b);
        if (!r.equals(BigInteger.ZERO)
                && ((r.compareTo(BigInteger.ZERO) < 0 && b.compareTo(BigInteger.ZERO) > 0)
                || (r.compareTo(BigInteger.ZERO) > 0 && b.compareTo(BigInteger.ZERO) < 0))) {
            return q.subtract(BigInteger.ONE);
        } else {
            return q;
        }
    }

    /**
     * Returns floor modulo, with the same sign as the divisor.
     * <p>
     * On-chain this is compiled directly to UPLC ModInteger.
     */
    public static BigInteger floorMod(BigInteger a, BigInteger b) {
        BigInteger r = a.remainder(b);
        if (!r.equals(BigInteger.ZERO)
                && ((r.compareTo(BigInteger.ZERO) < 0 && b.compareTo(BigInteger.ZERO) > 0)
                || (r.compareTo(BigInteger.ZERO) > 0 && b.compareTo(BigInteger.ZERO) < 0))) {
            return r.add(b);
        } else {
            return r;
        }
    }

    /**
     * Returns base raised to the power of exp.
     * <p>
     * For negative exponents, returns 1 (the loop condition {@code e > 0} is never satisfied,
     * so the result stays at the initial value of {@code BigInteger.ONE}).
     * This matches integer exponentiation semantics (no fractional results).
     */
    public static BigInteger pow(BigInteger base, BigInteger exp) {
        BigInteger result = BigInteger.ONE;
        var e = exp;
        while (e.compareTo(BigInteger.ZERO) > 0) {
            result = result.multiply(base);
            e = e.subtract(BigInteger.ONE);
        }
        return result;
    }

    /** Returns (base^exp) mod modulus using the PV11 Batch 6 ExpModInteger operation (CIP-109). */
    public static BigInteger expMod(BigInteger base, BigInteger exp, BigInteger mod) {
        return Builtins.expModInteger(base, exp, mod);
    }

    /** Returns -1 if negative, 0 if zero, 1 if positive. */
    public static BigInteger sign(BigInteger x) {
        if (x.compareTo(BigInteger.ZERO) < 0) {
            return BigInteger.valueOf(-1);
        } else {
            if (x.equals(BigInteger.ZERO)) {
                return BigInteger.ZERO;
            } else {
                return BigInteger.ONE;
            }
        }
    }
}
