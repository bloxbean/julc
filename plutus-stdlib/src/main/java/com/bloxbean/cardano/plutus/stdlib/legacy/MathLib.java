package com.bloxbean.cardano.plutus.stdlib.legacy;

import com.bloxbean.cardano.plutus.compiler.pir.PirTerm;
import com.bloxbean.cardano.plutus.compiler.pir.PirType;
import com.bloxbean.cardano.plutus.core.Constant;
import com.bloxbean.cardano.plutus.core.DefaultFun;

import java.math.BigInteger;
import java.util.List;

/**
 * Mathematical operations built as PIR term builders.
 * <p>
 * Each method returns a {@link PirTerm} that can be plugged into the compiler output.
 * Provides common mathematical operations like abs, max, min, divMod, quotRem, pow, sign,
 * and expMod.
 */
public final class MathLib {

    private MathLib() {}

    /**
     * Returns the absolute value of an integer.
     * <p>
     * Implemented as: IfThenElse(x < 0, -x, x)
     *
     * @param x PIR term representing an Integer
     * @return PIR term that evaluates to the absolute value
     */
    public static PirTerm abs(PirTerm x) {
        // IfThenElse(LessThanInteger(x, 0), SubtractInteger(0, x), x)
        var lessThanZero = builtinApp2(
                DefaultFun.LessThanInteger,
                x,
                new PirTerm.Const(Constant.integer(BigInteger.ZERO)));
        var negateX = builtinApp2(
                DefaultFun.SubtractInteger,
                new PirTerm.Const(Constant.integer(BigInteger.ZERO)),
                x);
        return new PirTerm.IfThenElse(lessThanZero, negateX, x);
    }

    /**
     * Returns the maximum of two integers.
     * <p>
     * Implemented as: IfThenElse(a < b, b, a)
     *
     * @param a PIR term representing an Integer
     * @param b PIR term representing an Integer
     * @return PIR term that evaluates to the maximum value
     */
    public static PirTerm max(PirTerm a, PirTerm b) {
        // IfThenElse(LessThanInteger(a, b), b, a)
        var lessThan = builtinApp2(DefaultFun.LessThanInteger, a, b);
        return new PirTerm.IfThenElse(lessThan, b, a);
    }

    /**
     * Returns the minimum of two integers.
     * <p>
     * Implemented as: IfThenElse(a <= b, a, b)
     *
     * @param a PIR term representing an Integer
     * @param b PIR term representing an Integer
     * @return PIR term that evaluates to the minimum value
     */
    public static PirTerm min(PirTerm a, PirTerm b) {
        // IfThenElse(LessThanEqualsInteger(a, b), a, b)
        var lessThanOrEquals = builtinApp2(DefaultFun.LessThanEqualsInteger, a, b);
        return new PirTerm.IfThenElse(lessThanOrEquals, a, b);
    }

    /**
     * Returns division and modulo as a pair: (div, mod).
     * <p>
     * Returns ConstrData(0, [IData(div), IData(mod)])
     *
     * @param a PIR term representing the dividend (Integer)
     * @param b PIR term representing the divisor (Integer)
     * @return PIR term that evaluates to ConstrData(0, [quotient, remainder])
     */
    public static PirTerm divMod(PirTerm a, PirTerm b) {
        // ConstrData(0, [IData(DivideInteger(a, b)), IData(ModInteger(a, b))])
        var div = builtinApp2(DefaultFun.DivideInteger, a, b);
        var mod = builtinApp2(DefaultFun.ModInteger, a, b);
        return makePairData(div, mod);
    }

    /**
     * Returns quotient and remainder as a pair: (quot, rem).
     * <p>
     * Returns ConstrData(0, [IData(quot), IData(rem)])
     *
     * @param a PIR term representing the dividend (Integer)
     * @param b PIR term representing the divisor (Integer)
     * @return PIR term that evaluates to ConstrData(0, [quotient, remainder])
     */
    public static PirTerm quotRem(PirTerm a, PirTerm b) {
        // ConstrData(0, [IData(QuotientInteger(a, b)), IData(RemainderInteger(a, b))])
        var quot = builtinApp2(DefaultFun.QuotientInteger, a, b);
        var rem = builtinApp2(DefaultFun.RemainderInteger, a, b);
        return makePairData(quot, rem);
    }

    /**
     * Returns base raised to the power of exp.
     * <p>
     * Implemented using LetRec recursion:
     * letrec go = \b -> \e ->
     *   IfThenElse(EqualsInteger(e, 0),
     *     1,
     *     MultiplyInteger(b, go(b)(SubtractInteger(e, 1))))
     * in go(base)(exp)
     *
     * @param base PIR term representing the base (Integer)
     * @param exp  PIR term representing the exponent (Integer)
     * @return PIR term that evaluates to base^exp
     */
    public static PirTerm pow(PirTerm base, PirTerm exp) {
        // letrec go = \b -> \e ->
        //   IfThenElse(EqualsInteger(e, 0),
        //     1,
        //     MultiplyInteger(b, go(b)(SubtractInteger(e, 1))))
        // in go(base)(exp)

        var bVar = new PirTerm.Var("b_pow", new PirType.IntegerType());
        var eVar = new PirTerm.Var("e_pow", new PirType.IntegerType());
        var goVar = new PirTerm.Var("go_pow", new PirType.FunType(
                new PirType.IntegerType(),
                new PirType.FunType(new PirType.IntegerType(), new PirType.IntegerType())));

        var isZero = builtinApp2(
                DefaultFun.EqualsInteger,
                eVar,
                new PirTerm.Const(Constant.integer(BigInteger.ZERO)));
        var one = new PirTerm.Const(Constant.integer(BigInteger.ONE));

        var decExp = builtinApp2(
                DefaultFun.SubtractInteger,
                eVar,
                new PirTerm.Const(Constant.integer(BigInteger.ONE)));
        var recurse = new PirTerm.App(new PirTerm.App(goVar, bVar), decExp);
        var multiply = builtinApp2(DefaultFun.MultiplyInteger, bVar, recurse);

        var body = new PirTerm.IfThenElse(isZero, one, multiply);
        var goBody = new PirTerm.Lam("b_pow", new PirType.IntegerType(),
                new PirTerm.Lam("e_pow", new PirType.IntegerType(), body));
        var binding = new PirTerm.Binding("go_pow", goBody);

        return new PirTerm.LetRec(
                List.of(binding),
                new PirTerm.App(new PirTerm.App(goVar, base), exp));
    }

    /**
     * Returns the sign of an integer: -1 if negative, 0 if zero, 1 if positive.
     * <p>
     * Implemented as nested IfThenElse:
     * IfThenElse(x < 0, -1, IfThenElse(x == 0, 0, 1))
     *
     * @param x PIR term representing an Integer
     * @return PIR term that evaluates to -1, 0, or 1
     */
    public static PirTerm sign(PirTerm x) {
        // IfThenElse(LessThanInteger(x, 0),
        //   -1,
        //   IfThenElse(EqualsInteger(x, 0), 0, 1))
        var lessThanZero = builtinApp2(
                DefaultFun.LessThanInteger,
                x,
                new PirTerm.Const(Constant.integer(BigInteger.ZERO)));
        var equalsZero = builtinApp2(
                DefaultFun.EqualsInteger,
                x,
                new PirTerm.Const(Constant.integer(BigInteger.ZERO)));

        var minusOne = new PirTerm.Const(Constant.integer(BigInteger.valueOf(-1)));
        var zero = new PirTerm.Const(Constant.integer(BigInteger.ZERO));
        var one = new PirTerm.Const(Constant.integer(BigInteger.ONE));

        var innerIf = new PirTerm.IfThenElse(equalsZero, zero, one);
        return new PirTerm.IfThenElse(lessThanZero, minusOne, innerIf);
    }

    /**
     * Returns (base^exp) mod modulus using the builtin ExpModInteger operation.
     * <p>
     * This is a direct wrapper around the UPLC ExpModInteger builtin (function code 87).
     * It computes modular exponentiation efficiently.
     *
     * @param base PIR term representing the base (Integer)
     * @param exp  PIR term representing the exponent (Integer)
     * @param mod  PIR term representing the modulus (Integer)
     * @return PIR term that evaluates to (base^exp) mod modulus
     */
    public static PirTerm expMod(PirTerm base, PirTerm exp, PirTerm mod) {
        // Direct wrapper: ExpModInteger(base)(exp)(mod)
        return new PirTerm.App(
                new PirTerm.App(
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.ExpModInteger), base),
                        exp),
                mod);
    }

    // =========================================================================
    // Helper methods
    // =========================================================================

    /**
     * Helper to apply a two-argument builtin function.
     */
    private static PirTerm builtinApp2(DefaultFun fun, PirTerm a, PirTerm b) {
        return new PirTerm.App(new PirTerm.App(new PirTerm.Builtin(fun), a), b);
    }

    /**
     * Helper to create ConstrData(0, [IData(x), IData(y)]) — pair encoding.
     */
    private static PirTerm makePairData(PirTerm x, PirTerm y) {
        // ConstrData(0, [IData(x), IData(y)])
        var nilData = new PirTerm.App(
                new PirTerm.Builtin(DefaultFun.MkNilData),
                new PirTerm.Const(Constant.unit()));
        var fields = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons),
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.IData), y)),
                nilData);
        fields = new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.MkCons),
                        new PirTerm.App(new PirTerm.Builtin(DefaultFun.IData), x)),
                fields);
        return new PirTerm.App(
                new PirTerm.App(new PirTerm.Builtin(DefaultFun.ConstrData),
                        new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                fields);
    }
}
