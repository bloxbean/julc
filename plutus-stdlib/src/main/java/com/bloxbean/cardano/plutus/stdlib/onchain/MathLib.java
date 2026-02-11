package com.bloxbean.cardano.plutus.stdlib.onchain;

import com.bloxbean.cardano.plutus.core.PlutusData;
import com.bloxbean.cardano.plutus.onchain.annotation.OnchainLibrary;
import com.bloxbean.cardano.plutus.onchain.stdlib.Builtins;

/**
 * Mathematical operations compiled from Java source to UPLC.
 */
@OnchainLibrary
public class MathLib {

    /** Returns the absolute value of an integer. */
    public static long abs(long x) {
        if (x < 0) {
            return 0 - x;
        } else {
            return x;
        }
    }

    /** Returns the maximum of two integers. */
    public static long max(long a, long b) {
        if (a < b) {
            return b;
        } else {
            return a;
        }
    }

    /** Returns the minimum of two integers. */
    public static long min(long a, long b) {
        if (a <= b) {
            return a;
        } else {
            return b;
        }
    }

    /** Returns division and modulo as a pair: ConstrData(0, [IData(div), IData(mod)]). */
    public static PlutusData divMod(long a, long b) {
        var div = a / b;
        var mod = a % b;
        var fields = Builtins.mkCons(Builtins.iData(div), Builtins.mkCons(Builtins.iData(mod), Builtins.mkNilData()));
        return Builtins.constrData(0, fields);
    }

    /** Returns quotient and remainder as a pair: ConstrData(0, [IData(quot), IData(rem)]). */
    public static PlutusData quotRem(long a, long b) {
        var quot = a / b;
        var rem = a % b;
        var fields = Builtins.mkCons(Builtins.iData(quot), Builtins.mkCons(Builtins.iData(rem), Builtins.mkNilData()));
        return Builtins.constrData(0, fields);
    }

    /** Returns base raised to the power of exp. */
    public static long pow(long base, long exp) {
        var result = 1L;
        var e = exp;
        while (e > 0) {
            result = result * base;
            e = e - 1;
        }
        return result;
    }

    /** Returns (base^exp) mod modulus using the builtin ExpModInteger operation. */
    public static long expMod(long base, long exp, long mod) {
        return Builtins.expModInteger(base, exp, mod);
    }

    /** Returns -1 if negative, 0 if zero, 1 if positive. */
    public static long sign(long x) {
        if (x < 0) {
            return 0 - 1;
        } else {
            if (x == 0) {
                return 0;
            } else {
                return 1;
            }
        }
    }
}
