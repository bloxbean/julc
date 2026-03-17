package com.bloxbean.cardano.julc.vm.java.builtins;

import com.bloxbean.cardano.julc.vm.java.CekValue;

import java.math.BigInteger;
import java.util.List;

import static com.bloxbean.cardano.julc.vm.java.builtins.BuiltinHelper.*;

/**
 * Integer arithmetic and comparison builtins.
 */
public final class IntegerBuiltins {

    private IntegerBuiltins() {}

    public static CekValue addInteger(List<CekValue> args) {
        var a = asInteger(args.get(0), "AddInteger");
        var b = asInteger(args.get(1), "AddInteger");
        return mkInteger(a.add(b));
    }

    public static CekValue subtractInteger(List<CekValue> args) {
        var a = asInteger(args.get(0), "SubtractInteger");
        var b = asInteger(args.get(1), "SubtractInteger");
        return mkInteger(a.subtract(b));
    }

    public static CekValue multiplyInteger(List<CekValue> args) {
        var a = asInteger(args.get(0), "MultiplyInteger");
        var b = asInteger(args.get(1), "MultiplyInteger");
        return mkInteger(a.multiply(b));
    }

    public static CekValue divideInteger(List<CekValue> args) {
        var a = asInteger(args.get(0), "DivideInteger");
        var b = asInteger(args.get(1), "DivideInteger");
        if (b.signum() == 0) {
            throw new BuiltinException("DivideInteger: division by zero");
        }
        // Haskell div: rounds toward negative infinity
        BigInteger[] qr = a.divideAndRemainder(b);
        BigInteger q = qr[0];
        BigInteger r = qr[1];
        // Adjust for floor division: if remainder != 0 and signs differ, subtract 1
        if (r.signum() != 0 && (r.signum() ^ b.signum()) < 0) {
            q = q.subtract(BigInteger.ONE);
        }
        return mkInteger(q);
    }

    public static CekValue quotientInteger(List<CekValue> args) {
        var a = asInteger(args.get(0), "QuotientInteger");
        var b = asInteger(args.get(1), "QuotientInteger");
        if (b.signum() == 0) {
            throw new BuiltinException("QuotientInteger: division by zero");
        }
        // Java divide truncates toward zero — same as Haskell quot
        return mkInteger(a.divide(b));
    }

    public static CekValue remainderInteger(List<CekValue> args) {
        var a = asInteger(args.get(0), "RemainderInteger");
        var b = asInteger(args.get(1), "RemainderInteger");
        if (b.signum() == 0) {
            throw new BuiltinException("RemainderInteger: division by zero");
        }
        // Java remainder truncates toward zero — same as Haskell rem
        return mkInteger(a.remainder(b));
    }

    public static CekValue modInteger(List<CekValue> args) {
        var a = asInteger(args.get(0), "ModInteger");
        var b = asInteger(args.get(1), "ModInteger");
        if (b.signum() == 0) {
            throw new BuiltinException("ModInteger: division by zero");
        }
        // Haskell mod: result has the same sign as divisor
        BigInteger r = a.remainder(b);
        if (r.signum() != 0 && (r.signum() ^ b.signum()) < 0) {
            r = r.add(b);
        }
        return mkInteger(r);
    }

    public static CekValue equalsInteger(List<CekValue> args) {
        var a = asInteger(args.get(0), "EqualsInteger");
        var b = asInteger(args.get(1), "EqualsInteger");
        return mkBool(a.equals(b));
    }

    public static CekValue lessThanInteger(List<CekValue> args) {
        var a = asInteger(args.get(0), "LessThanInteger");
        var b = asInteger(args.get(1), "LessThanInteger");
        return mkBool(a.compareTo(b) < 0);
    }

    public static CekValue lessThanEqualsInteger(List<CekValue> args) {
        var a = asInteger(args.get(0), "LessThanEqualsInteger");
        var b = asInteger(args.get(1), "LessThanEqualsInteger");
        return mkBool(a.compareTo(b) <= 0);
    }

    public static CekValue expModInteger(List<CekValue> args) {
        var base = asInteger(args.get(0), "ExpModInteger");
        var exp = asInteger(args.get(1), "ExpModInteger");
        var mod = asInteger(args.get(2), "ExpModInteger");
        if (mod.signum() == 0) {
            throw new BuiltinException("ExpModInteger: modulus is zero");
        }
        if (mod.signum() < 0) {
            throw new BuiltinException("ExpModInteger: negative modulus");
        }
        if (exp.signum() < 0) {
            // Negative exponent: compute modular inverse first
            // base^(-n) mod m = (base^(-1))^n mod m
            try {
                BigInteger inverse = base.modInverse(mod);
                return mkInteger(inverse.modPow(exp.negate(), mod));
            } catch (ArithmeticException e) {
                throw new BuiltinException("ExpModInteger: base has no modular inverse");
            }
        }
        return mkInteger(base.modPow(exp, mod));
    }
}
