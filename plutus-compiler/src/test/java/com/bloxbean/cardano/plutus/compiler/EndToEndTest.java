package com.bloxbean.cardano.plutus.compiler;

import com.bloxbean.cardano.plutus.compiler.pir.PirTerm;
import com.bloxbean.cardano.plutus.compiler.pir.PirType;
import com.bloxbean.cardano.plutus.compiler.uplc.UplcGenerator;
import com.bloxbean.cardano.plutus.core.Constant;
import com.bloxbean.cardano.plutus.core.DefaultFun;
import com.bloxbean.cardano.plutus.core.Program;
import com.bloxbean.cardano.plutus.core.Term;
import com.bloxbean.cardano.plutus.vm.PlutusVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests: compile PIR/UPLC -> evaluate via PlutusVm -> verify results.
 */
class EndToEndTest {

    static PlutusVm vm;

    @BeforeAll
    static void setUp() {
        vm = PlutusVm.create();
    }

    @Nested
    class ArithmeticTests {
        @Test
        void addTwoNumbers() {
            // Program: (\x -> \y -> AddInteger x y) applied to 3 and 4
            var term = Term.apply(
                    Term.apply(
                            Term.lam("x",
                                    Term.lam("y",
                                            Term.apply(
                                                    Term.apply(Term.builtin(DefaultFun.AddInteger),
                                                            Term.var(2)),
                                                    Term.var(1)))),
                            Term.const_(Constant.integer(BigInteger.valueOf(3)))),
                    Term.const_(Constant.integer(BigInteger.valueOf(4))));
            var program = Program.plutusV3(term);
            var result = vm.evaluate(program);
            assertTrue(result.isSuccess(), "Expected success but got: " + result);
            assertInstanceOf(Term.Const.class, ((com.bloxbean.cardano.plutus.vm.EvalResult.Success) result).resultTerm());
            var val = ((Term.Const) ((com.bloxbean.cardano.plutus.vm.EvalResult.Success) result).resultTerm()).value();
            assertInstanceOf(Constant.IntegerConst.class, val);
            assertEquals(BigInteger.valueOf(7), ((Constant.IntegerConst) val).value());
        }

        @Test
        void subtractNumbers() {
            var term = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.SubtractInteger),
                            Term.const_(Constant.integer(BigInteger.TEN))),
                    Term.const_(Constant.integer(BigInteger.valueOf(3))));
            var result = vm.evaluate(Program.plutusV3(term));
            assertTrue(result.isSuccess());
            var val = ((Term.Const) ((com.bloxbean.cardano.plutus.vm.EvalResult.Success) result).resultTerm()).value();
            assertEquals(BigInteger.valueOf(7), ((Constant.IntegerConst) val).value());
        }

        @Test
        void multiplyNumbers() {
            var term = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.MultiplyInteger),
                            Term.const_(Constant.integer(BigInteger.valueOf(6)))),
                    Term.const_(Constant.integer(BigInteger.valueOf(7))));
            var result = vm.evaluate(Program.plutusV3(term));
            assertTrue(result.isSuccess());
            var val = ((Term.Const) ((com.bloxbean.cardano.plutus.vm.EvalResult.Success) result).resultTerm()).value();
            assertEquals(BigInteger.valueOf(42), ((Constant.IntegerConst) val).value());
        }
    }

    @Nested
    class BooleanLogicTests {
        @Test
        void ifThenElseTrue() {
            // Force(Apply(Apply(Apply(Force(IfThenElse), True), Delay(1)), Delay(0)))
            var term = Term.force(
                    Term.apply(
                            Term.apply(
                                    Term.apply(
                                            Term.force(Term.builtin(DefaultFun.IfThenElse)),
                                            Term.const_(Constant.bool(true))),
                                    Term.delay(Term.const_(Constant.integer(BigInteger.ONE)))),
                            Term.delay(Term.const_(Constant.integer(BigInteger.ZERO)))));
            var result = vm.evaluate(Program.plutusV3(term));
            assertTrue(result.isSuccess());
            var val = ((Term.Const) ((com.bloxbean.cardano.plutus.vm.EvalResult.Success) result).resultTerm()).value();
            assertEquals(BigInteger.ONE, ((Constant.IntegerConst) val).value());
        }

        @Test
        void ifThenElseFalse() {
            var term = Term.force(
                    Term.apply(
                            Term.apply(
                                    Term.apply(
                                            Term.force(Term.builtin(DefaultFun.IfThenElse)),
                                            Term.const_(Constant.bool(false))),
                                    Term.delay(Term.const_(Constant.integer(BigInteger.ONE)))),
                            Term.delay(Term.const_(Constant.integer(BigInteger.ZERO)))));
            var result = vm.evaluate(Program.plutusV3(term));
            assertTrue(result.isSuccess());
            var val = ((Term.Const) ((com.bloxbean.cardano.plutus.vm.EvalResult.Success) result).resultTerm()).value();
            assertEquals(BigInteger.ZERO, ((Constant.IntegerConst) val).value());
        }

        @Test
        void equalsIntegerTrue() {
            var term = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.EqualsInteger),
                            Term.const_(Constant.integer(BigInteger.valueOf(42)))),
                    Term.const_(Constant.integer(BigInteger.valueOf(42))));
            var result = vm.evaluate(Program.plutusV3(term));
            assertTrue(result.isSuccess());
            var val = ((Term.Const) ((com.bloxbean.cardano.plutus.vm.EvalResult.Success) result).resultTerm()).value();
            assertTrue(((Constant.BoolConst) val).value());
        }

        @Test
        void equalsIntegerFalse() {
            var term = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.EqualsInteger),
                            Term.const_(Constant.integer(BigInteger.ONE))),
                    Term.const_(Constant.integer(BigInteger.TWO)));
            var result = vm.evaluate(Program.plutusV3(term));
            assertTrue(result.isSuccess());
            var val = ((Term.Const) ((com.bloxbean.cardano.plutus.vm.EvalResult.Success) result).resultTerm()).value();
            assertFalse(((Constant.BoolConst) val).value());
        }

        @Test
        void lessThanTrue() {
            var term = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.LessThanInteger),
                            Term.const_(Constant.integer(BigInteger.ONE))),
                    Term.const_(Constant.integer(BigInteger.TEN)));
            var result = vm.evaluate(Program.plutusV3(term));
            assertTrue(result.isSuccess());
            assertTrue(((Constant.BoolConst) ((Term.Const) ((com.bloxbean.cardano.plutus.vm.EvalResult.Success) result).resultTerm()).value()).value());
        }
    }

    @Nested
    class LetBindingTests {
        @Test
        void letBinding() {
            // let x = 10 in AddInteger x 5 -> Apply(Lam("x", body), 10)
            var term = Term.apply(
                    Term.lam("x",
                            Term.apply(
                                    Term.apply(Term.builtin(DefaultFun.AddInteger),
                                            Term.var(1)),
                                    Term.const_(Constant.integer(BigInteger.valueOf(5))))),
                    Term.const_(Constant.integer(BigInteger.TEN)));
            var result = vm.evaluate(Program.plutusV3(term));
            assertTrue(result.isSuccess());
            var val = ((Term.Const) ((com.bloxbean.cardano.plutus.vm.EvalResult.Success) result).resultTerm()).value();
            assertEquals(BigInteger.valueOf(15), ((Constant.IntegerConst) val).value());
        }
    }

    @Nested
    class PirToUplcEvalTests {
        @Test
        void pirAdditionEval() {
            // PIR: \x -> \y -> AddInteger x y
            var pir = new PirTerm.Lam("x", new PirType.IntegerType(),
                    new PirTerm.Lam("y", new PirType.IntegerType(),
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger),
                                            new PirTerm.Var("x", new PirType.IntegerType())),
                                    new PirTerm.Var("y", new PirType.IntegerType()))));
            var uplc = new UplcGenerator().generate(pir);
            // Apply to 3 and 4
            var applied = Term.apply(Term.apply(uplc,
                    Term.const_(Constant.integer(BigInteger.valueOf(3)))),
                    Term.const_(Constant.integer(BigInteger.valueOf(4))));
            var result = vm.evaluate(Program.plutusV3(applied));
            assertTrue(result.isSuccess(), "Result: " + result);
            var val = ((Term.Const) ((com.bloxbean.cardano.plutus.vm.EvalResult.Success) result).resultTerm()).value();
            assertEquals(BigInteger.valueOf(7), ((Constant.IntegerConst) val).value());
        }

        @Test
        void pirLetBindingEval() {
            // PIR: let x = 10 in let y = 5 in AddInteger x y
            var pir = new PirTerm.Let("x",
                    new PirTerm.Const(Constant.integer(BigInteger.TEN)),
                    new PirTerm.Let("y",
                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(5))),
                            new PirTerm.App(
                                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.AddInteger),
                                            new PirTerm.Var("x", new PirType.IntegerType())),
                                    new PirTerm.Var("y", new PirType.IntegerType()))));
            var uplc = new UplcGenerator().generate(pir);
            var result = vm.evaluate(Program.plutusV3(uplc));
            assertTrue(result.isSuccess(), "Result: " + result);
            var val = ((Term.Const) ((com.bloxbean.cardano.plutus.vm.EvalResult.Success) result).resultTerm()).value();
            assertEquals(BigInteger.valueOf(15), ((Constant.IntegerConst) val).value());
        }

        @Test
        void pirIfThenElseEval() {
            // PIR: IfThenElse(True, 42, 0)
            var pir = new PirTerm.IfThenElse(
                    new PirTerm.Const(Constant.bool(true)),
                    new PirTerm.Const(Constant.integer(BigInteger.valueOf(42))),
                    new PirTerm.Const(Constant.integer(BigInteger.ZERO)));
            var uplc = new UplcGenerator().generate(pir);
            var result = vm.evaluate(Program.plutusV3(uplc));
            assertTrue(result.isSuccess(), "Result: " + result);
            var val = ((Term.Const) ((com.bloxbean.cardano.plutus.vm.EvalResult.Success) result).resultTerm()).value();
            assertEquals(BigInteger.valueOf(42), ((Constant.IntegerConst) val).value());
        }

        @Test
        void pirComparisonEval() {
            // PIR: EqualsInteger(5, 5)
            var pir = new PirTerm.App(
                    new PirTerm.App(new PirTerm.Builtin(DefaultFun.EqualsInteger),
                            new PirTerm.Const(Constant.integer(BigInteger.valueOf(5)))),
                    new PirTerm.Const(Constant.integer(BigInteger.valueOf(5))));
            var uplc = new UplcGenerator().generate(pir);
            var result = vm.evaluate(Program.plutusV3(uplc));
            assertTrue(result.isSuccess(), "Result: " + result);
            var val = ((Term.Const) ((com.bloxbean.cardano.plutus.vm.EvalResult.Success) result).resultTerm()).value();
            assertTrue(((Constant.BoolConst) val).value());
        }

        @Test
        void pirBooleanAndEval() {
            // PIR: IfThenElse(True, False, False) -- True && False = False
            var pir = new PirTerm.IfThenElse(
                    new PirTerm.Const(Constant.bool(true)),
                    new PirTerm.Const(Constant.bool(false)),
                    new PirTerm.Const(Constant.bool(false)));
            var uplc = new UplcGenerator().generate(pir);
            var result = vm.evaluate(Program.plutusV3(uplc));
            assertTrue(result.isSuccess());
            var val = ((Term.Const) ((com.bloxbean.cardano.plutus.vm.EvalResult.Success) result).resultTerm()).value();
            assertFalse(((Constant.BoolConst) val).value());
        }

        @Test
        void pirBooleanOrEval() {
            // PIR: IfThenElse(False, True, True) -- False || True = True
            var pir = new PirTerm.IfThenElse(
                    new PirTerm.Const(Constant.bool(false)),
                    new PirTerm.Const(Constant.bool(true)),
                    new PirTerm.Const(Constant.bool(true)));
            var uplc = new UplcGenerator().generate(pir);
            var result = vm.evaluate(Program.plutusV3(uplc));
            assertTrue(result.isSuccess());
            assertTrue(((Constant.BoolConst) ((Term.Const) ((com.bloxbean.cardano.plutus.vm.EvalResult.Success) result).resultTerm()).value()).value());
        }
    }
}
