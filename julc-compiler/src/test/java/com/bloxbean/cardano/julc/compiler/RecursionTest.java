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

    @Nested
    class MultiBindingLetRecTests {

        /**
         * Two non-mutual bindings: reverse is self-recursive, go references reverse + self.
         * Accumulate-and-reverse pattern for taking first N elements from a list.
         *
         * reverse = \list \acc -> if null(list) then acc else reverse(tail(list), cons(head(list), acc))
         * go = \list \n \acc -> if n==0 then reverse(acc, []) else go(tail(list), n-1, cons(head(list), acc))
         *
         * For simplicity, we test with integer arithmetic equivalent:
         * doubleIt = \n -> n * 2
         * addThenDouble = \a \b -> doubleIt(a + b)
         * LetRec([doubleIt, addThenDouble], addThenDouble(3, 4)) = 14
         */
        @Test
        void twoBindingsNonMutual() {
            var intType = new PirType.IntegerType();

            // doubleIt = \n -> n * 2
            var doubleItBody = new PirTerm.Lam("n", intType,
                    new PirTerm.App(
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.MultiplyInteger),
                                    new PirTerm.Var("n", intType)),
                            new PirTerm.Const(Constant.integer(BigInteger.TWO))));

            // addThenDouble = \a \b -> doubleIt(a + b)
            var addThenDoubleBody = new PirTerm.Lam("a", intType,
                    new PirTerm.Lam("b", intType,
                            new PirTerm.App(
                                    new PirTerm.Var("doubleIt", new PirType.FunType(intType, intType)),
                                    new PirTerm.App(
                                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger),
                                                    new PirTerm.Var("a", intType)),
                                            new PirTerm.Var("b", intType)))));

            var letRec = new PirTerm.LetRec(
                    List.of(
                            new PirTerm.Binding("doubleIt", doubleItBody),
                            new PirTerm.Binding("addThenDouble", addThenDoubleBody)),
                    new PirTerm.App(
                            new PirTerm.App(
                                    new PirTerm.Var("addThenDouble",
                                            new PirType.FunType(intType, new PirType.FunType(intType, intType))),
                                    new PirTerm.Const(Constant.integer(BigInteger.valueOf(3)))),
                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(4)))));

            var uplc = new UplcGenerator().generate(letRec);
            assertEquals(BigInteger.valueOf(14), evalInteger(uplc)); // (3+4)*2 = 14
        }

        /**
         * Accumulate-and-reverse pattern: sum first N elements using reverse helper.
         * reverse = \n \acc -> if n==0 then acc else reverse(n-1, acc+n)  [self-recursive sum]
         * go = \n -> reverse(n, 0)
         * LetRec([go, reverse], go(5)) = 15
         */
        @Test
        void twoBindingsNonMutualResult() {
            var intType = new PirType.IntegerType();

            // sumUp = \n \acc -> if n==0 then acc else sumUp(n-1, acc+n)
            var sumUpBody = new PirTerm.Lam("n", intType,
                    new PirTerm.Lam("acc", intType,
                            new PirTerm.IfThenElse(
                                    new PirTerm.App(
                                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger),
                                                    new PirTerm.Var("n", intType)),
                                            new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                                    new PirTerm.Var("acc", intType),
                                    new PirTerm.App(
                                            new PirTerm.App(
                                                    new PirTerm.Var("sumUp",
                                                            new PirType.FunType(intType, new PirType.FunType(intType, intType))),
                                                    new PirTerm.App(
                                                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.SubtractInteger),
                                                                    new PirTerm.Var("n", intType)),
                                                            new PirTerm.Const(Constant.integer(BigInteger.ONE)))),
                                            new PirTerm.App(
                                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger),
                                                            new PirTerm.Var("acc", intType)),
                                                    new PirTerm.Var("n", intType))))));

            // go = \n -> sumUp(n, 0)
            var goBody = new PirTerm.Lam("n", intType,
                    new PirTerm.App(
                            new PirTerm.App(
                                    new PirTerm.Var("sumUp",
                                            new PirType.FunType(intType, new PirType.FunType(intType, intType))),
                                    new PirTerm.Var("n", intType)),
                            new PirTerm.Const(Constant.integer(BigInteger.ZERO))));

            var letRec = new PirTerm.LetRec(
                    List.of(
                            new PirTerm.Binding("go", goBody),
                            new PirTerm.Binding("sumUp", sumUpBody)),
                    new PirTerm.App(
                            new PirTerm.Var("go", new PirType.FunType(intType, intType)),
                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(5)))));

            var uplc = new UplcGenerator().generate(letRec);
            assertEquals(BigInteger.valueOf(15), evalInteger(uplc)); // 1+2+3+4+5 = 15
        }

        /**
         * Mutual recursion: isEven/isOdd.
         * isEven = \n -> if n==0 then true else isOdd(n-1)
         * isOdd  = \n -> if n==0 then false else isEven(n-1)
         * LetRec([isEven, isOdd], isEven(4)) = true
         */
        @Test
        void mutualRecursionIsEvenIsOdd() {
            var intType = new PirType.IntegerType();
            var boolType = new PirType.BoolType();

            // isEven = \n -> if n==0 then true else isOdd(n-1)
            var isEvenBody = new PirTerm.Lam("n", intType,
                    new PirTerm.IfThenElse(
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger),
                                            new PirTerm.Var("n", intType)),
                                    new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                            new PirTerm.Const(Constant.bool(true)),
                            new PirTerm.App(
                                    new PirTerm.Var("isOdd", new PirType.FunType(intType, boolType)),
                                    new PirTerm.App(
                                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.SubtractInteger),
                                                    new PirTerm.Var("n", intType)),
                                            new PirTerm.Const(Constant.integer(BigInteger.ONE))))));

            // isOdd = \n -> if n==0 then false else isEven(n-1)
            var isOddBody = new PirTerm.Lam("n", intType,
                    new PirTerm.IfThenElse(
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger),
                                            new PirTerm.Var("n", intType)),
                                    new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                            new PirTerm.Const(Constant.bool(false)),
                            new PirTerm.App(
                                    new PirTerm.Var("isEven", new PirType.FunType(intType, boolType)),
                                    new PirTerm.App(
                                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.SubtractInteger),
                                                    new PirTerm.Var("n", intType)),
                                            new PirTerm.Const(Constant.integer(BigInteger.ONE))))));

            // isEven(4) should be true
            var letRec = new PirTerm.LetRec(
                    List.of(
                            new PirTerm.Binding("isEven", isEvenBody),
                            new PirTerm.Binding("isOdd", isOddBody)),
                    new PirTerm.App(
                            new PirTerm.Var("isEven", new PirType.FunType(intType, boolType)),
                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(4)))));

            var uplc = new UplcGenerator().generate(letRec);
            assertTrue(evalBool(uplc));
        }

        /**
         * isOdd(3) = true, isEven(3) = false
         */
        @Test
        void mutualRecursionIsEvenOdd() {
            var intType = new PirType.IntegerType();
            var boolType = new PirType.BoolType();

            var isEvenBody = new PirTerm.Lam("n", intType,
                    new PirTerm.IfThenElse(
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger),
                                            new PirTerm.Var("n", intType)),
                                    new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                            new PirTerm.Const(Constant.bool(true)),
                            new PirTerm.App(
                                    new PirTerm.Var("isOdd", new PirType.FunType(intType, boolType)),
                                    new PirTerm.App(
                                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.SubtractInteger),
                                                    new PirTerm.Var("n", intType)),
                                            new PirTerm.Const(Constant.integer(BigInteger.ONE))))));

            var isOddBody = new PirTerm.Lam("n", intType,
                    new PirTerm.IfThenElse(
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger),
                                            new PirTerm.Var("n", intType)),
                                    new PirTerm.Const(Constant.integer(BigInteger.ZERO))),
                            new PirTerm.Const(Constant.bool(false)),
                            new PirTerm.App(
                                    new PirTerm.Var("isEven", new PirType.FunType(intType, boolType)),
                                    new PirTerm.App(
                                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.SubtractInteger),
                                                    new PirTerm.Var("n", intType)),
                                            new PirTerm.Const(Constant.integer(BigInteger.ONE))))));

            // isOdd(3) = true
            var letRecOdd = new PirTerm.LetRec(
                    List.of(
                            new PirTerm.Binding("isEven", isEvenBody),
                            new PirTerm.Binding("isOdd", isOddBody)),
                    new PirTerm.App(
                            new PirTerm.Var("isOdd", new PirType.FunType(intType, boolType)),
                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(3)))));
            assertTrue(evalBool(new UplcGenerator().generate(letRecOdd)));

            // isEven(3) = false
            var letRecEven = new PirTerm.LetRec(
                    List.of(
                            new PirTerm.Binding("isEven", isEvenBody),
                            new PirTerm.Binding("isOdd", isOddBody)),
                    new PirTerm.App(
                            new PirTerm.Var("isEven", new PirType.FunType(intType, boolType)),
                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(3)))));
            assertFalse(evalBool(new UplcGenerator().generate(letRecEven)));
        }

        /**
         * Three non-mutual bindings with dependency chain: A depends on B, B depends on C.
         * C = \n -> n + 1
         * B = \n -> C(n) * 2
         * A = \n -> B(n) + 10
         * LetRec([A, B, C], A(5)) = (C(5) * 2) + 10 = ((5+1)*2)+10 = 22
         */
        @Test
        void threeBindingsNonMutual() {
            var intType = new PirType.IntegerType();

            var cBody = new PirTerm.Lam("n", intType,
                    new PirTerm.App(
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger),
                                    new PirTerm.Var("n", intType)),
                            new PirTerm.Const(Constant.integer(BigInteger.ONE))));

            var bBody = new PirTerm.Lam("n", intType,
                    new PirTerm.App(
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.MultiplyInteger),
                                    new PirTerm.App(
                                            new PirTerm.Var("inc", new PirType.FunType(intType, intType)),
                                            new PirTerm.Var("n", intType))),
                            new PirTerm.Const(Constant.integer(BigInteger.TWO))));

            var aBody = new PirTerm.Lam("n", intType,
                    new PirTerm.App(
                            new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger),
                                    new PirTerm.App(
                                            new PirTerm.Var("double", new PirType.FunType(intType, intType)),
                                            new PirTerm.Var("n", intType))),
                            new PirTerm.Const(Constant.integer(BigInteger.TEN))));

            var letRec = new PirTerm.LetRec(
                    List.of(
                            new PirTerm.Binding("compute", aBody),
                            new PirTerm.Binding("double", bBody),
                            new PirTerm.Binding("inc", cBody)),
                    new PirTerm.App(
                            new PirTerm.Var("compute", new PirType.FunType(intType, intType)),
                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(5)))));

            var uplc = new UplcGenerator().generate(letRec);
            assertEquals(BigInteger.valueOf(22), evalInteger(uplc));
        }

        /**
         * Single-binding regression: verify existing factorial still works with multi-binding code path.
         */
        @Test
        void singleBindingRegression() {
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

        /**
         * Non-recursive binding: A=5, B=A+1. Should be treated as Let, not LetRec.
         */
        @Test
        void nonRecursiveBinding() {
            var intType = new PirType.IntegerType();

            var aValue = new PirTerm.Const(Constant.integer(BigInteger.valueOf(5)));
            var bValue = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger),
                            new PirTerm.Var("a", intType)),
                    new PirTerm.Const(Constant.integer(BigInteger.ONE)));

            var letRec = new PirTerm.LetRec(
                    List.of(
                            new PirTerm.Binding("a", aValue),
                            new PirTerm.Binding("b", bValue)),
                    new PirTerm.Var("b", intType));

            var uplc = new UplcGenerator().generate(letRec);
            assertEquals(BigInteger.valueOf(6), evalInteger(uplc));
        }
    }
}
