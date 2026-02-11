package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.pir.PirTerm;
import com.bloxbean.cardano.julc.compiler.pir.PirType;
import com.bloxbean.cardano.julc.compiler.uplc.UplcGenerator;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Task 4.6: Recursion Support (Z-combinator)
 */
class RecursionTest {

    static JulcVm vm;

    @BeforeAll
    static void setUp() {
        vm = JulcVm.create();
    }

    private BigInteger evalInteger(Term term) {
        var result = vm.evaluate(Program.plutusV3(term));
        assertTrue(result.isSuccess(), "Expected success but got: " + result);
        var val = ((Term.Const) ((EvalResult.Success) result).resultTerm()).value();
        return ((Constant.IntegerConst) val).value();
    }

    private boolean evalBool(Term term) {
        var result = vm.evaluate(Program.plutusV3(term));
        assertTrue(result.isSuccess(), "Expected success but got: " + result);
        var val = ((Term.Const) ((EvalResult.Success) result).resultTerm()).value();
        return ((Constant.BoolConst) val).value();
    }

    @Nested
    class ZCombinatorTests {
        @Test
        void factorial5() {
            // fact = \self \n -> if n == 0 then 1 else n * self(n - 1)
            // LetRec([fact = \n -> if n==0 then 1 else n*fact(n-1)], fact(5))
            var intType = new PirType.IntegerType();
            var factBody = new PirTerm.Lam("n", intType,
                    new PirTerm.IfThenElse(
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger),
                                            new PirTerm.Var("n", intType)),
                                    new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                            new PirTerm.Const(Constant.integer(BigInteger.ONE)),
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MultiplyInteger),
                                            new PirTerm.Var("n", intType)),
                                    new PirTerm.App(
                                            new PirTerm.Var("fact", new PirType.FunType(intType, intType)),
                                            new PirTerm.App(
                                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.SubtractInteger),
                                                            new PirTerm.Var("n", intType)),
                                                    new PirTerm.Const(Constant.integer(BigInteger.ONE)))))));

            var letRec = new PirTerm.LetRec(
                    List.of(new PirTerm.Binding("fact", factBody)),
                    new PirTerm.App(
                            new PirTerm.Var("fact", new PirType.FunType(intType, intType)),
                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(5)))));

            var uplc = new UplcGenerator().generate(letRec);
            assertEquals(BigInteger.valueOf(120), evalInteger(uplc));
        }

        @Test
        void factorial0() {
            var intType = new PirType.IntegerType();
            var factBody = new PirTerm.Lam("n", intType,
                    new PirTerm.IfThenElse(
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger),
                                            new PirTerm.Var("n", intType)),
                                    new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                            new PirTerm.Const(Constant.integer(BigInteger.ONE)),
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MultiplyInteger),
                                            new PirTerm.Var("n", intType)),
                                    new PirTerm.App(
                                            new PirTerm.Var("fact", new PirType.FunType(intType, intType)),
                                            new PirTerm.App(
                                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.SubtractInteger),
                                                            new PirTerm.Var("n", intType)),
                                                    new PirTerm.Const(Constant.integer(BigInteger.ONE)))))));

            var letRec = new PirTerm.LetRec(
                    List.of(new PirTerm.Binding("fact", factBody)),
                    new PirTerm.App(
                            new PirTerm.Var("fact", new PirType.FunType(intType, intType)),
                            new PirTerm.Const(Constant.integer(BigInteger.ZERO))));

            var uplc = new UplcGenerator().generate(letRec);
            assertEquals(BigInteger.ONE, evalInteger(uplc));
        }

        @Test
        void factorial1() {
            var intType = new PirType.IntegerType();
            var factBody = new PirTerm.Lam("n", intType,
                    new PirTerm.IfThenElse(
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger),
                                            new PirTerm.Var("n", intType)),
                                    new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                            new PirTerm.Const(Constant.integer(BigInteger.ONE)),
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MultiplyInteger),
                                            new PirTerm.Var("n", intType)),
                                    new PirTerm.App(
                                            new PirTerm.Var("fact", new PirType.FunType(intType, intType)),
                                            new PirTerm.App(
                                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.SubtractInteger),
                                                            new PirTerm.Var("n", intType)),
                                                    new PirTerm.Const(Constant.integer(BigInteger.ONE)))))));

            var letRec = new PirTerm.LetRec(
                    List.of(new PirTerm.Binding("fact", factBody)),
                    new PirTerm.App(
                            new PirTerm.Var("fact", new PirType.FunType(intType, intType)),
                            new PirTerm.Const(Constant.integer(BigInteger.ONE))));

            var uplc = new UplcGenerator().generate(letRec);
            assertEquals(BigInteger.ONE, evalInteger(uplc));
        }

        @Test
        void sumTo10() {
            // sum = \n -> if n == 0 then 0 else n + sum(n-1)
            var intType = new PirType.IntegerType();
            var sumBody = new PirTerm.Lam("n", intType,
                    new PirTerm.IfThenElse(
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger),
                                            new PirTerm.Var("n", intType)),
                                    new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                            new PirTerm.Const(Constant.integer(BigInteger.ZERO)),
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger),
                                            new PirTerm.Var("n", intType)),
                                    new PirTerm.App(
                                            new PirTerm.Var("sum", new PirType.FunType(intType, intType)),
                                            new PirTerm.App(
                                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.SubtractInteger),
                                                            new PirTerm.Var("n", intType)),
                                                    new PirTerm.Const(Constant.integer(BigInteger.ONE)))))));

            var letRec = new PirTerm.LetRec(
                    List.of(new PirTerm.Binding("sum", sumBody)),
                    new PirTerm.App(
                            new PirTerm.Var("sum", new PirType.FunType(intType, intType)),
                            new PirTerm.Const(Constant.integer(BigInteger.TEN))));

            var uplc = new UplcGenerator().generate(letRec);
            assertEquals(BigInteger.valueOf(55), evalInteger(uplc));
        }

        @Test
        void countdownIsZero() {
            // countdown = \n -> if n == 0 then true else countdown(n-1)
            var intType = new PirType.IntegerType();
            var boolType = new PirType.BoolType();
            var body = new PirTerm.Lam("n", intType,
                    new PirTerm.IfThenElse(
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger),
                                            new PirTerm.Var("n", intType)),
                                    new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                            new PirTerm.Const(Constant.bool(true)),
                            new PirTerm.App(
                                    new PirTerm.Var("countdown", new PirType.FunType(intType, boolType)),
                                    new PirTerm.App(
                                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.SubtractInteger),
                                                    new PirTerm.Var("n", intType)),
                                            new PirTerm.Const(Constant.integer(BigInteger.ONE))))));

            var letRec = new PirTerm.LetRec(
                    List.of(new PirTerm.Binding("countdown", body)),
                    new PirTerm.App(
                            new PirTerm.Var("countdown", new PirType.FunType(intType, boolType)),
                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(5)))));

            var uplc = new UplcGenerator().generate(letRec);
            assertTrue(evalBool(uplc));
        }

        @Test
        void twoParamRecursion() {
            // gcd = \a \b -> if b == 0 then a else gcd(b, a % b)
            var intType = new PirType.IntegerType();
            var gcdBody = new PirTerm.Lam("a", intType,
                    new PirTerm.Lam("b", intType,
                            new PirTerm.IfThenElse(
                                    new PirTerm.App(
                                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger),
                                                    new PirTerm.Var("b", intType)),
                                            new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                                    new PirTerm.Var("a", intType),
                                    new PirTerm.App(
                                            new PirTerm.App(
                                                    new PirTerm.Var("gcd", new PirType.FunType(intType, new PirType.FunType(intType, intType))),
                                                    new PirTerm.Var("b", intType)),
                                            new PirTerm.App(
                                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.ModInteger),
                                                            new PirTerm.Var("a", intType)),
                                                    new PirTerm.Var("b", intType))))));

            var letRec = new PirTerm.LetRec(
                    List.of(new PirTerm.Binding("gcd", gcdBody)),
                    new PirTerm.App(
                            new PirTerm.App(
                                    new PirTerm.Var("gcd", new PirType.FunType(intType, new PirType.FunType(intType, intType))),
                                    new PirTerm.Const(Constant.integer(BigInteger.valueOf(12)))),
                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(8)))));

            var uplc = new UplcGenerator().generate(letRec);
            assertEquals(BigInteger.valueOf(4), evalInteger(uplc));
        }

        @Test
        void letRecUsedInLargerExpression() {
            // LetRec([fact = ...], fact(5) + 1) = 121
            var intType = new PirType.IntegerType();
            var factBody = new PirTerm.Lam("n", intType,
                    new PirTerm.IfThenElse(
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger),
                                            new PirTerm.Var("n", intType)),
                                    new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                            new PirTerm.Const(Constant.integer(BigInteger.ONE)),
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MultiplyInteger),
                                            new PirTerm.Var("n", intType)),
                                    new PirTerm.App(
                                            new PirTerm.Var("fact", new PirType.FunType(intType, intType)),
                                            new PirTerm.App(
                                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.SubtractInteger),
                                                            new PirTerm.Var("n", intType)),
                                                    new PirTerm.Const(Constant.integer(BigInteger.ONE)))))));

            var letRec = new PirTerm.LetRec(
                    List.of(new PirTerm.Binding("fact", factBody)),
                    new PirTerm.App(
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger),
                                    new PirTerm.App(
                                            new PirTerm.Var("fact", new PirType.FunType(intType, intType)),
                                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(5))))),
                            new PirTerm.Const(Constant.integer(BigInteger.ONE))));

            var uplc = new UplcGenerator().generate(letRec);
            assertEquals(BigInteger.valueOf(121), evalInteger(uplc));
        }

        @Test
        void letRecTermStructure() {
            // Verify the generated UPLC term has the expected shape: Apply(Lam, Apply(fix, Lam))
            var intType = new PirType.IntegerType();
            var simpleBody = new PirTerm.Lam("n", intType,
                    new PirTerm.Var("n", intType)); // identity (non-recursive but tests structure)

            var letRec = new PirTerm.LetRec(
                    List.of(new PirTerm.Binding("f", simpleBody)),
                    new PirTerm.App(
                            new PirTerm.Var("f", new PirType.FunType(intType, intType)),
                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(42)))));

            var uplc = new UplcGenerator().generate(letRec);
            // Should be Apply(Lam("f", ...), Apply(fix, Lam("f", ...)))
            assertInstanceOf(Term.Apply.class, uplc);
            var app = (Term.Apply) uplc;
            assertInstanceOf(Term.Lam.class, app.function()); // Lam("f", body)
            assertInstanceOf(Term.Apply.class, app.argument()); // Apply(fix, recursiveLam)
        }
    }

    @Nested
    class DeBruijnCorrectness {
        @Test
        void recursiveReferenceHasCorrectIndex() {
            // In the Z-combinator pattern, the recursive reference must resolve correctly
            // fact(3) = 6
            var intType = new PirType.IntegerType();
            var factBody = new PirTerm.Lam("n", intType,
                    new PirTerm.IfThenElse(
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger),
                                            new PirTerm.Var("n", intType)),
                                    new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                            new PirTerm.Const(Constant.integer(BigInteger.ONE)),
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MultiplyInteger),
                                            new PirTerm.Var("n", intType)),
                                    new PirTerm.App(
                                            new PirTerm.Var("fact", new PirType.FunType(intType, intType)),
                                            new PirTerm.App(
                                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.SubtractInteger),
                                                            new PirTerm.Var("n", intType)),
                                                    new PirTerm.Const(Constant.integer(BigInteger.ONE)))))));

            var letRec = new PirTerm.LetRec(
                    List.of(new PirTerm.Binding("fact", factBody)),
                    new PirTerm.App(
                            new PirTerm.Var("fact", new PirType.FunType(intType, intType)),
                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(3)))));

            var uplc = new UplcGenerator().generate(letRec);
            assertEquals(BigInteger.valueOf(6), evalInteger(uplc));
        }

        @Test
        void nestedLetWithRecursion() {
            // let x = 10 in LetRec([fact = ...], fact(x))
            var intType = new PirType.IntegerType();
            var factBody = new PirTerm.Lam("n", intType,
                    new PirTerm.IfThenElse(
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger),
                                            new PirTerm.Var("n", intType)),
                                    new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                            new PirTerm.Const(Constant.integer(BigInteger.ONE)),
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.MultiplyInteger),
                                            new PirTerm.Var("n", intType)),
                                    new PirTerm.App(
                                            new PirTerm.Var("fact", new PirType.FunType(intType, intType)),
                                            new PirTerm.App(
                                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.SubtractInteger),
                                                            new PirTerm.Var("n", intType)),
                                                    new PirTerm.Const(Constant.integer(BigInteger.ONE)))))));

            var inner = new PirTerm.LetRec(
                    List.of(new PirTerm.Binding("fact", factBody)),
                    new PirTerm.App(
                            new PirTerm.Var("fact", new PirType.FunType(intType, intType)),
                            new PirTerm.Var("x", intType)));

            var outer = new PirTerm.Let("x",
                    new PirTerm.Const(Constant.integer(BigInteger.valueOf(4))),
                    inner);

            var uplc = new UplcGenerator().generate(outer);
            assertEquals(BigInteger.valueOf(24), evalInteger(uplc)); // 4! = 24
        }
    }
}
