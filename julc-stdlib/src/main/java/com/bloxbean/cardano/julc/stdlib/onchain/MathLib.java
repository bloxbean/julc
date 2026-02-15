package com.bloxbean.cardano.julc.stdlib.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.onchain.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.onchain.stdlib.Builtins;

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

    /** Returns division and modulo as a pair: ConstrData(0, [IData(div), IData(mod)]). */
    public static PlutusData.ConstrData divMod(BigInteger a, BigInteger b) {
        var div = a.divide(b);
        var mod = a.remainder(b);
        var fields = Builtins.mkCons(Builtins.iData(div), Builtins.mkCons(Builtins.iData(mod), Builtins.mkNilData()));
        return Builtins.constrData(0, fields);
    }

    /** Returns quotient and remainder as a pair: ConstrData(0, [IData(quot), IData(rem)]). */
    public static PlutusData.ConstrData quotRem(BigInteger a, BigInteger b) {
        var quot = a.divide(b);
        var rem = a.remainder(b);
        var fields = Builtins.mkCons(Builtins.iData(quot), Builtins.mkCons(Builtins.iData(rem), Builtins.mkNilData()));
        return Builtins.constrData(0, fields);
    }

    /** Returns base raised to the power of exp. */
    public static BigInteger pow(BigInteger base, BigInteger exp) {
        BigInteger result = BigInteger.ONE;
        var e = exp;
        while (e.compareTo(BigInteger.ZERO) > 0) {
            result = result.multiply(base);
            e = e.subtract(BigInteger.ONE);
        }
        return result;
    }

    /** Returns (base^exp) mod modulus using the builtin ExpModInteger operation. */
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
