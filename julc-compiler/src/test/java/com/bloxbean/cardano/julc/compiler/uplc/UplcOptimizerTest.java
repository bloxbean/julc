package com.bloxbean.cardano.julc.compiler.uplc;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Task 4.7: UPLC Optimizations
 */
class UplcOptimizerTest {

    static JulcVm vm;
    final UplcOptimizer opt = new UplcOptimizer();

    @BeforeAll
    static void setUp() {
        vm = JulcVm.create();
    }

    private BigInteger evalInteger(Term term) {
        var result = vm.evaluate(Program.plutusV3(term));
        assertTrue(result.isSuccess(), "Expected success: " + result);
        var val = ((Term.Const) ((EvalResult.Success) result).resultTerm()).value();
        return ((Constant.IntegerConst) val).value();
    }

    private boolean evalBool(Term term) {
        var result = vm.evaluate(Program.plutusV3(term));
        assertTrue(result.isSuccess(), "Expected success: " + result);
        var val = ((Term.Const) ((EvalResult.Success) result).resultTerm()).value();
        return ((Constant.BoolConst) val).value();
    }

    @Nested
    class ForceDelayCancellation {
        @Test
        void simpleForceDelay() {
            // Force(Delay(Const(42))) → Const(42)
            var term = Term.force(Term.delay(Term.const_(Constant.integer(BigInteger.valueOf(42)))));
            var optimized = opt.forceDelayCancel(term);
            assertInstanceOf(Term.Const.class, optimized);
            assertEquals(BigInteger.valueOf(42),
                    ((Constant.IntegerConst) ((Term.Const) optimized).value()).value());
        }

        @Test
        void nestedForceDelay() {
            // Force(Delay(Force(Delay(Const(7))))) → Const(7)
            var inner = Term.force(Term.delay(Term.const_(Constant.integer(BigInteger.valueOf(7)))));
            var term = Term.force(Term.delay(inner));
            var optimized = opt.optimize(term);
            assertInstanceOf(Term.Const.class, optimized);
        }

        @Test
        void forceDelayInsideLam() {
            // Lam("x", Force(Delay(Var(1)))) → Lam("x", Var(1))
            var term = Term.lam("x", Term.force(Term.delay(Term.var(1))));
            var optimized = opt.forceDelayCancel(term);
            assertInstanceOf(Term.Lam.class, optimized);
            assertInstanceOf(Term.Var.class, ((Term.Lam) optimized).body());
        }

        @Test
        void forceWithoutDelayUnchanged() {
            // Force(Builtin(IfThenElse)) should not be simplified
            var term = Term.force(Term.builtin(DefaultFun.IfThenElse));
            var optimized = opt.forceDelayCancel(term);
            assertInstanceOf(Term.Force.class, optimized);
        }
    }

    @Nested
    class ConstantFolding {
        @Test
        void foldAddInteger() {
            // Apply(Apply(Builtin(AddInteger), Const(3)), Const(4)) → Const(7)
            var term = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.AddInteger),
                            Term.const_(Constant.integer(BigInteger.valueOf(3)))),
                    Term.const_(Constant.integer(BigInteger.valueOf(4))));
            var optimized = opt.constantFold(term);
            assertInstanceOf(Term.Const.class, optimized);
            assertEquals(BigInteger.valueOf(7),
                    ((Constant.IntegerConst) ((Term.Const) optimized).value()).value());
        }

        @Test
        void foldSubtractInteger() {
            var term = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.SubtractInteger),
                            Term.const_(Constant.integer(BigInteger.TEN))),
                    Term.const_(Constant.integer(BigInteger.valueOf(3))));
            var optimized = opt.constantFold(term);
            assertInstanceOf(Term.Const.class, optimized);
            assertEquals(BigInteger.valueOf(7),
                    ((Constant.IntegerConst) ((Term.Const) optimized).value()).value());
        }

        @Test
        void foldMultiplyInteger() {
            var term = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.MultiplyInteger),
                            Term.const_(Constant.integer(BigInteger.valueOf(6)))),
                    Term.const_(Constant.integer(BigInteger.valueOf(7))));
            var optimized = opt.constantFold(term);
            assertInstanceOf(Term.Const.class, optimized);
            assertEquals(BigInteger.valueOf(42),
                    ((Constant.IntegerConst) ((Term.Const) optimized).value()).value());
        }

        @Test
        void foldEqualsIntegerTrue() {
            var term = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.EqualsInteger),
                            Term.const_(Constant.integer(BigInteger.valueOf(5)))),
                    Term.const_(Constant.integer(BigInteger.valueOf(5))));
            var optimized = opt.constantFold(term);
            assertInstanceOf(Term.Const.class, optimized);
            assertTrue(((Constant.BoolConst) ((Term.Const) optimized).value()).value());
        }

        @Test
        void foldEqualsIntegerFalse() {
            var term = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.EqualsInteger),
                            Term.const_(Constant.integer(BigInteger.valueOf(5)))),
                    Term.const_(Constant.integer(BigInteger.valueOf(3))));
            var optimized = opt.constantFold(term);
            assertInstanceOf(Term.Const.class, optimized);
            assertFalse(((Constant.BoolConst) ((Term.Const) optimized).value()).value());
        }

        @Test
        void foldLessThanInteger() {
            var term = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.LessThanInteger),
                            Term.const_(Constant.integer(BigInteger.valueOf(3)))),
                    Term.const_(Constant.integer(BigInteger.valueOf(5))));
            var optimized = opt.constantFold(term);
            assertInstanceOf(Term.Const.class, optimized);
            assertTrue(((Constant.BoolConst) ((Term.Const) optimized).value()).value());
        }

        @Test
        void noFoldNonConstArgs() {
            // Apply(Apply(Builtin(AddInteger), Var(1)), Const(4)) → no change
            var term = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.AddInteger), Term.var(1)),
                    Term.const_(Constant.integer(BigInteger.valueOf(4))));
            var optimized = opt.constantFold(term);
            assertInstanceOf(Term.Apply.class, optimized);
        }
    }

    @Nested
    class DeadCodeElimination {
        @Test
        void removeUnusedLet() {
            // Apply(Lam("x", Const(42)), Const(99)) → Const(42)
            // x (index 1) not used in body
            var term = Term.apply(
                    Term.lam("x", Term.const_(Constant.integer(BigInteger.valueOf(42)))),
                    Term.const_(Constant.integer(BigInteger.valueOf(99))));
            var optimized = opt.deadCodeElimination(term);
            assertInstanceOf(Term.Const.class, optimized);
            assertEquals(BigInteger.valueOf(42),
                    ((Constant.IntegerConst) ((Term.Const) optimized).value()).value());
        }

        @Test
        void keepUsedLet() {
            // Apply(Lam("x", Var(1)), Const(42)) → keep as is (x is used)
            var term = Term.apply(
                    Term.lam("x", Term.var(1)),
                    Term.const_(Constant.integer(BigInteger.valueOf(42))));
            var optimized = opt.deadCodeElimination(term);
            assertInstanceOf(Term.Apply.class, optimized);
        }

        @Test
        void dcePreservesSemantics() {
            // let x = 5 + 3 in 42 → 42 (dead binding removed)
            var binding = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.AddInteger),
                            Term.const_(Constant.integer(BigInteger.valueOf(5)))),
                    Term.const_(Constant.integer(BigInteger.valueOf(3))));
            var term = Term.apply(
                    Term.lam("x", Term.const_(Constant.integer(BigInteger.valueOf(42)))),
                    binding);
            var optimized = opt.optimize(term);
            assertEquals(BigInteger.valueOf(42), evalInteger(optimized));
        }
    }

    @Nested
    class BetaReduction {
        @Test
        void betaReduceSimple() {
            // Apply(Lam("x", Var(1)), Const(42)) → Const(42)
            var term = Term.apply(
                    Term.lam("x", Term.var(1)),
                    Term.const_(Constant.integer(BigInteger.valueOf(42))));
            var optimized = opt.betaReduce(term);
            assertInstanceOf(Term.Const.class, optimized);
            assertEquals(BigInteger.valueOf(42),
                    ((Constant.IntegerConst) ((Term.Const) optimized).value()).value());
        }

        @Test
        void betaReduceWithBuiltin() {
            // Apply(Lam("x", Apply(Apply(Builtin(AddInteger), Var(1)), Const(1))), Const(5))
            // → Apply(Apply(Builtin(AddInteger), Const(5)), Const(1))
            // Then constant folding → Const(6)
            var term = Term.apply(
                    Term.lam("x", Term.apply(
                            Term.apply(Term.builtin(DefaultFun.AddInteger), Term.var(1)),
                            Term.const_(Constant.integer(BigInteger.ONE)))),
                    Term.const_(Constant.integer(BigInteger.valueOf(5))));
            var optimized = opt.optimize(term); // beta + const fold
            assertInstanceOf(Term.Const.class, optimized);
            assertEquals(BigInteger.valueOf(6),
                    ((Constant.IntegerConst) ((Term.Const) optimized).value()).value());
        }

        @Test
        void noBetaForMultiUse() {
            // Apply(Lam("x", Apply(Apply(Builtin(AddInteger), Var(1)), Var(1))), Var(2))
            // x used twice — don't beta-reduce a non-simple argument
            var body = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.AddInteger), Term.var(1)),
                    Term.var(1));
            var term = Term.apply(Term.lam("x", body), Term.var(2));
            var optimized = opt.betaReduce(term);
            assertInstanceOf(Term.Apply.class, optimized); // unchanged
        }
    }

    @Nested
    class EtaReduction {
        @Test
        void etaReduceSimple() {
            // Lam("x", Apply(Builtin(AddInteger), Var(1))) → Builtin(AddInteger)
            // Note: AddInteger is Var-1 free
            var term = Term.lam("x", Term.apply(Term.builtin(DefaultFun.AddInteger), Term.var(1)));
            var optimized = opt.etaReduce(term);
            assertInstanceOf(Term.Builtin.class, optimized);
        }

        @Test
        void noEtaWhenVarUsedInFunction() {
            // Lam("x", Apply(Var(1), Var(1))) → no reduction (x free in function)
            var term = Term.lam("x", Term.apply(Term.var(1), Term.var(1)));
            var optimized = opt.etaReduce(term);
            assertInstanceOf(Term.Lam.class, optimized);
        }
    }

    @Nested
    class ConstrCaseReduction {
        @Test
        void reduceCaseOnConstr() {
            // Case(Constr(0, [42]), [Lam("v", Var(1))]) → Const(42) after beta
            var constr = new Term.Constr(0, List.of(Term.const_(Constant.integer(BigInteger.valueOf(42)))));
            var branch = Term.lam("v", Term.var(1));
            var term = new Term.Case(constr, List.of(branch));
            var optimized = opt.constrCaseReduce(term);
            // Should become Apply(Lam("v", Var(1)), Const(42))
            assertInstanceOf(Term.Apply.class, optimized);
            // Full optimization should reduce to Const(42)
            var fullyOptimized = opt.optimize(term);
            assertInstanceOf(Term.Const.class, fullyOptimized);
            assertEquals(BigInteger.valueOf(42),
                    ((Constant.IntegerConst) ((Term.Const) fullyOptimized).value()).value());
        }

        @Test
        void reduceCaseOnSecondConstr() {
            // Case(Constr(1, [99]), [Lam("x", Const(0)), Lam("y", Var(1))])
            var constr = new Term.Constr(1, List.of(Term.const_(Constant.integer(BigInteger.valueOf(99)))));
            var branch0 = Term.lam("x", Term.const_(Constant.integer(BigInteger.ZERO)));
            var branch1 = Term.lam("y", Term.var(1));
            var term = new Term.Case(constr, List.of(branch0, branch1));
            var optimized = opt.optimize(term);
            assertInstanceOf(Term.Const.class, optimized);
            assertEquals(BigInteger.valueOf(99),
                    ((Constant.IntegerConst) ((Term.Const) optimized).value()).value());
        }

        @Test
        void noCaseReductionOnVariable() {
            // Case(Var(1), [Lam("x", Var(1))]) → no change
            var term = new Term.Case(Term.var(1), List.of(Term.lam("x", Term.var(1))));
            var optimized = opt.constrCaseReduce(term);
            assertInstanceOf(Term.Case.class, optimized);
        }
    }

    @Nested
    class MultiPassFixpoint {
        @Test
        void forceDelayCombinedWithConstFold() {
            // Force(Delay(Apply(Apply(Builtin(AddInteger), Const(3)), Const(4))))
            // → (force/delay cancel) Apply(Apply(Builtin(AddInteger), Const(3)), Const(4))
            // → (const fold) Const(7)
            var inner = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.AddInteger),
                            Term.const_(Constant.integer(BigInteger.valueOf(3)))),
                    Term.const_(Constant.integer(BigInteger.valueOf(4))));
            var term = Term.force(Term.delay(inner));
            var optimized = opt.optimize(term);
            assertInstanceOf(Term.Const.class, optimized);
            assertEquals(BigInteger.valueOf(7),
                    ((Constant.IntegerConst) ((Term.Const) optimized).value()).value());
        }

        @Test
        void chainedConstantFolding() {
            // (2 + 3) + 4 → 5 + 4 → 9
            var twoPlus3 = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.AddInteger),
                            Term.const_(Constant.integer(BigInteger.TWO))),
                    Term.const_(Constant.integer(BigInteger.valueOf(3))));
            var term = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.AddInteger), twoPlus3),
                    Term.const_(Constant.integer(BigInteger.valueOf(4))));
            var optimized = opt.optimize(term);
            assertInstanceOf(Term.Const.class, optimized);
            assertEquals(BigInteger.valueOf(9),
                    ((Constant.IntegerConst) ((Term.Const) optimized).value()).value());
        }
    }

    @Nested
    class SemanticsPreservation {
        @Test
        void optimizedProgramEvaluatesSame() {
            // let x = 3 + 4 in x * 2
            // Before: Apply(Lam("x", Apply(Apply(Builtin(Mul), Var(1)), Const(2))), Apply(Apply(Builtin(Add), Const(3)), Const(4)))
            // After optimization: Const(14)
            var binding = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.AddInteger),
                            Term.const_(Constant.integer(BigInteger.valueOf(3)))),
                    Term.const_(Constant.integer(BigInteger.valueOf(4))));
            var body = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.MultiplyInteger), Term.var(1)),
                    Term.const_(Constant.integer(BigInteger.TWO)));
            var term = Term.apply(Term.lam("x", body), binding);

            var original = evalInteger(term);
            var optimized = opt.optimize(term);
            var optimizedResult = evalInteger(optimized);

            assertEquals(original, optimizedResult);
            assertEquals(BigInteger.valueOf(14), optimizedResult);
        }

        @Test
        void optimizerDoesNotBreakBuiltins() {
            // Force(Builtin(IfThenElse)) should be preserved
            var ifBuiltin = Term.force(Term.builtin(DefaultFun.IfThenElse));
            var term = Term.force(
                    Term.apply(
                            Term.apply(
                                    Term.apply(ifBuiltin, Term.const_(Constant.bool(true))),
                                    Term.delay(Term.const_(Constant.integer(BigInteger.ONE)))),
                            Term.delay(Term.const_(Constant.integer(BigInteger.TWO)))));
            var optimized = opt.optimize(term);
            assertEquals(BigInteger.ONE, evalInteger(optimized));
        }
    }

    @Nested
    class DeBruijnUtilities {
        @Test
        void isFreeDetectsUsage() {
            assertTrue(UplcOptimizer.isFree(1, Term.var(1)));
            assertFalse(UplcOptimizer.isFree(1, Term.var(2)));
            assertFalse(UplcOptimizer.isFree(1, Term.const_(Constant.integer(BigInteger.ZERO))));
        }

        @Test
        void isFreeUnderLam() {
            // Lam("y", Var(2)) — Var(2) refers to index 1 outside the lam
            assertTrue(UplcOptimizer.isFree(1, Term.lam("y", Term.var(2))));
            // Lam("y", Var(1)) — Var(1) refers to y, not index 1 outside
            assertFalse(UplcOptimizer.isFree(1, Term.lam("y", Term.var(1))));
        }

        @Test
        void countUsesMultiple() {
            // Apply(Var(1), Var(1)) — Var(1) used twice
            var term = Term.apply(Term.var(1), Term.var(1));
            assertEquals(2, UplcOptimizer.countUses(1, term));
        }

        @Test
        void substituteReplacesCorrectly() {
            // body = Var(1), substitute index 1 with Const(42) → Const(42)
            var result = UplcOptimizer.substitute(Term.var(1), 1, Term.const_(Constant.integer(BigInteger.valueOf(42))));
            assertInstanceOf(Term.Const.class, result);
        }

        @Test
        void substituteShiftsHigherIndices() {
            // body = Var(2), substitute index 1 → Var(1) (shifted down)
            var result = UplcOptimizer.substitute(Term.var(2), 1, Term.const_(Constant.integer(BigInteger.ZERO)));
            assertInstanceOf(Term.Var.class, result);
            assertEquals(1, ((Term.Var) result).name().index());
        }
    }
}
