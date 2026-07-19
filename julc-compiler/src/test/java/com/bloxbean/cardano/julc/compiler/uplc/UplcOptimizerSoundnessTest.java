package com.bloxbean.cardano.julc.compiler.uplc;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Soundness tests for {@link UplcOptimizer}: no rewrite may change the observable
 * outcome (success/failure, result, traces) of a term under strict (call-by-value)
 * evaluation. Each negative test here is a confirmed pre-fix behavior flip.
 * The positive tests pin the optimizations that must keep firing so the sound
 * gates do not become over-conservative.
 */
class UplcOptimizerSoundnessTest {

    static JulcVm vm;
    final UplcOptimizer opt = new UplcOptimizer();

    static final ExBudget BUDGET = new ExBudget(1_000_000_000L, 100_000_000L);

    @BeforeAll
    static void setUp() {
        vm = JulcVm.create("Java");
    }

    // ---- term helpers ----

    private static Term intConst(long v) {
        return Term.const_(Constant.integer(BigInteger.valueOf(v)));
    }

    private static Term boolConst(boolean v) {
        return Term.const_(Constant.bool(v));
    }

    private static Term emptyIntList() {
        return Term.const_(new Constant.ListConst(DefaultUni.INTEGER, List.of()));
    }

    /** (\_ -> True) arg */
    private static Term discard(Term arg) {
        return Term.apply(Term.lam("_", Term.const_(Constant.bool(true))), arg);
    }

    /** The Z combinator exactly as UplcGenerator.generateLetRec emits it. */
    private static Term zCombinator() {
        var innerBody = Term.lam("v",
                Term.apply(Term.apply(Term.var(2), Term.var(2)), Term.var(1)));
        var branch = Term.lam("x", Term.apply(Term.var(2), innerBody));
        return Term.lam("f", Term.apply(branch, branch));
    }

    /** headList [] — errors at runtime */
    private static Term headOfEmpty() {
        return Term.apply(Term.force(Term.builtin(DefaultFun.HeadList)), emptyIntList());
    }

    /** divideInteger 1 0 — errors at runtime */
    private static Term divByZero() {
        return Term.apply(
                Term.apply(Term.builtin(DefaultFun.DivideInteger), intConst(1)),
                intConst(0));
    }

    /** (force trace) "msg" k — evaluates k after emitting "msg" */
    private static Term traceApp(String msg, Term k) {
        return Term.apply(
                Term.apply(Term.force(Term.builtin(DefaultFun.Trace)),
                        Term.const_(Constant.string(msg))),
                k);
    }

    /**
     * Assert original and fully optimized term agree on outcome class,
     * result constant (when both succeed with constants), and emitted traces.
     */
    private void assertEvalAgreement(Term term) {
        var original = vm.evaluate(Program.plutusV3(term), BUDGET);
        var optimized = vm.evaluate(Program.plutusV3(opt.optimize(term)), BUDGET);
        assertEquals(original.isSuccess(), optimized.isSuccess(),
                "optimizer changed success/failure of: " + term);
        assertEquals(original.traces(), optimized.traces(),
                "optimizer changed traces of: " + term);
        if (original instanceof EvalResult.Success(Term r1, var c1, var t1, var e1, var b1)
                && optimized instanceof EvalResult.Success(Term r2, var c2, var t2, var e2, var b2)
                && r1 instanceof Term.Const && r2 instanceof Term.Const) {
            assertEquals(r1, r2, "optimizer changed result of: " + term);
        }
    }

    // ---- Pass 3: dead code elimination ----

    @Nested
    class DeadCodeEliminationSoundness {

        @Test
        void doesNotDropErrorArgument() {
            // (\_ -> True) error — must still error
            var term = discard(Term.error());
            assertEquals(term, opt.deadCodeElimination(term));
            assertFalse(vm.evaluate(Program.plutusV3(opt.optimize(term)), BUDGET).isSuccess());
            assertEvalAgreement(term);
        }

        @Test
        void doesNotDropPartialBuiltinApplication() {
            // (\_ -> True) (headList []) — must still error
            var term = discard(headOfEmpty());
            assertEquals(term, opt.deadCodeElimination(term));
            assertFalse(vm.evaluate(Program.plutusV3(opt.optimize(term)), BUDGET).isSuccess());
            assertEvalAgreement(term);
        }

        @Test
        void doesNotDropDivisionByZero() {
            var term = discard(divByZero());
            assertEquals(term, opt.deadCodeElimination(term));
            assertFalse(vm.evaluate(Program.plutusV3(opt.optimize(term)), BUDGET).isSuccess());
            assertEvalAgreement(term);
        }

        @Test
        void doesNotDropSaturatedTrace() {
            // (\_ -> True) ((force trace) "msg" 1) — trace must still be emitted
            var term = discard(traceApp("must-survive", intConst(1)));
            assertEquals(term, opt.deadCodeElimination(term));
            var result = vm.evaluate(Program.plutusV3(opt.optimize(term)), BUDGET);
            assertTrue(result.isSuccess());
            assertEquals(List.of("must-survive"), result.traces());
            assertEvalAgreement(term);
        }

        @Test
        void conservativelyKeepsSucceedingPartialBuiltin() {
            // (\_ -> True) (unIData (Data 1)) would succeed, but UnIData is a
            // partial builtin — the conservative gate keeps it. Documents the
            // accepted conservatism; evaluation is unchanged either way.
            var term = discard(Term.apply(Term.builtin(DefaultFun.UnIData),
                    Term.const_(Constant.data(PlutusData.integer(1)))));
            assertEquals(term, opt.deadCodeElimination(term));
            assertEvalAgreement(term);
        }

        // -- values and pure applications must still be dropped --

        @Test
        void dropsConstantArgument() {
            var term = discard(intConst(99));
            assertEquals(boolConst(true), opt.deadCodeElimination(term));
        }

        @Test
        void dropsVariableArgument() {
            // \y -> (\_ -> True) y
            var term = Term.lam("y", discard(Term.var(1)));
            assertEquals(Term.lam("y", boolConst(true)), opt.deadCodeElimination(term));
        }

        @Test
        void dropsLambdaArgument() {
            var term = discard(Term.lam("z", Term.var(1)));
            assertEquals(boolConst(true), opt.deadCodeElimination(term));
        }

        @Test
        void dropsDelayedErrorArgument() {
            // delay error is a value — the thunk is never forced, dropping is sound
            var term = discard(Term.delay(Term.error()));
            assertEquals(boolConst(true), opt.deadCodeElimination(term));
            assertEvalAgreement(term);
        }

        @Test
        void dropsTypeInstantiatedBuiltin() {
            // force headList (no args) is a value
            var term = discard(Term.force(Term.builtin(DefaultFun.HeadList)));
            assertEquals(boolConst(true), opt.deadCodeElimination(term));
        }

        @Test
        void dropsUnderSaturatedBuiltinApplication() {
            // addInteger 1 (one arg of two) is a value
            var term = discard(Term.apply(Term.builtin(DefaultFun.AddInteger), intConst(1)));
            assertEquals(boolConst(true), opt.deadCodeElimination(term));
            assertEvalAgreement(term);
        }

        @Test
        void dropsUnderSaturatedTrace() {
            // (force trace) "msg" (one arg of two) is a value — no log is emitted
            var term = discard(Term.apply(
                    Term.force(Term.builtin(DefaultFun.Trace)),
                    Term.const_(Constant.string("never-emitted"))));
            assertEquals(boolConst(true), opt.deadCodeElimination(term));
            var result = vm.evaluate(Program.plutusV3(opt.optimize(term)), BUDGET);
            assertTrue(result.isSuccess());
            assertEquals(List.of(), result.traces());
            assertEvalAgreement(term);
        }

        @Test
        void dropsUnusedRecursiveBinding() {
            // let rec f = \n -> f n in True — lowered as (\f -> True) (Z (\f -> \n -> f n));
            // the fixpoint application only builds a closure, so the unused
            // binding is safely discardable
            var term = discard(Term.apply(zCombinator(),
                    Term.lam("f", Term.lam("n", Term.apply(Term.var(2), Term.var(1))))));
            assertEquals(boolConst(true), opt.deadCodeElimination(term));
            assertEvalAgreement(term);
        }

        @Test
        void keepsFixpointWithNonValueBody() {
            // Z (\f -> error) — evaluating the fixpoint runs the body, which errors
            var term = discard(Term.apply(zCombinator(), Term.lam("f", Term.error())));
            assertEquals(term, opt.deadCodeElimination(term));
            assertFalse(vm.evaluate(Program.plutusV3(opt.optimize(term)), BUDGET).isSuccess());
            assertEvalAgreement(term);
        }

        @Test
        void keepsMutatedFixpointShape() {
            // A term that is almost the Z combinator but with a different inner
            // application structure must not be recognized as pure
            var innerBody = Term.lam("v",
                    Term.apply(Term.apply(Term.var(2), Term.var(1)), Term.var(1)));
            var branch = Term.lam("x", Term.apply(Term.var(2), innerBody));
            var notQuiteZ = Term.lam("f", Term.apply(branch, branch));
            var term = discard(Term.apply(notQuiteZ,
                    Term.lam("f", Term.lam("n", Term.var(1)))));
            assertEquals(term, opt.deadCodeElimination(term));
            assertEvalAgreement(term);
        }

        @Test
        void dropsSaturatedTotalBuiltinOnTypedConstants() {
            // sha2_256 #01 is total on a bytestring constant — pure, dropped
            var term = discard(Term.apply(Term.builtin(DefaultFun.Sha2_256),
                    Term.const_(Constant.byteString(new byte[]{1}))));
            assertEquals(boolConst(true), opt.deadCodeElimination(term));
            assertEvalAgreement(term);
        }

        @Test
        void keepsSaturatedTotalBuiltinOnNonConstantArgs() {
            // \y -> (\_ -> True) (sha2_256 y): y's runtime type is unknown at the
            // term level, so the application may error — must be kept
            var term = Term.lam("y", discard(
                    Term.apply(Term.builtin(DefaultFun.Sha2_256), Term.var(1))));
            assertEquals(term, opt.deadCodeElimination(term));
        }

        @Test
        void keepsSaturatedTotalBuiltinOnWronglyTypedConstant() {
            // sha2_256 42 errors at runtime (integer, not bytestring) — must be kept
            var term = discard(Term.apply(Term.builtin(DefaultFun.Sha2_256), intConst(42)));
            assertEquals(term, opt.deadCodeElimination(term));
            assertEvalAgreement(term);
        }

        @Test
        void dropsConstantDataLiteralConstruction() {
            // constrData 0 (mkCons (iData 5) (mkNilData ())) — a nested constant
            // Data literal (e.g. IntervalLib.always); every layer is a certified
            // total application, so the whole construction is pure
            var literal = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.ConstrData), intConst(0)),
                    Term.apply(
                            Term.apply(Term.force(Term.builtin(DefaultFun.MkCons)),
                                    Term.apply(Term.builtin(DefaultFun.IData), intConst(5))),
                            Term.apply(Term.builtin(DefaultFun.MkNilData), Term.const_(Constant.unit()))));
            var term = discard(literal);
            assertEquals(boolConst(true), opt.deadCodeElimination(term));
            assertEvalAgreement(term);
        }

        @Test
        void dropsDataLiteralWithLazyBooleanField() {
            // The stdlib encodes boolean record fields lazily:
            //   force ((force ifThenElse) True (delay (constrData 1 [])) (delay (constrData 0 [])))
            // Inside a constant Data literal this must certify as pure
            var nilData = Term.apply(Term.builtin(DefaultFun.MkNilData), Term.const_(Constant.unit()));
            var lazyBool = Term.force(Term.apply(
                    Term.apply(
                            Term.apply(Term.force(Term.builtin(DefaultFun.IfThenElse)), boolConst(true)),
                            Term.delay(Term.apply(
                                    Term.apply(Term.builtin(DefaultFun.ConstrData), intConst(1)), nilData))),
                    Term.delay(Term.apply(
                            Term.apply(Term.builtin(DefaultFun.ConstrData), intConst(0)), nilData))));
            var literal = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.ConstrData), intConst(0)),
                    Term.apply(
                            Term.apply(Term.force(Term.builtin(DefaultFun.MkCons)), lazyBool),
                            nilData));
            var term = discard(literal);
            assertEquals(boolConst(true), opt.deadCodeElimination(term));
            assertEvalAgreement(term);
        }

        @Test
        void keepsLazyIfWithNonBooleanCondition() {
            // force ((force ifThenElse) 1 (delay 2) (delay 3)) errors at runtime
            var term = discard(Term.force(Term.apply(
                    Term.apply(
                            Term.apply(Term.force(Term.builtin(DefaultFun.IfThenElse)), intConst(1)),
                            Term.delay(intConst(2))),
                    Term.delay(intConst(3)))));
            assertEquals(term, opt.deadCodeElimination(term));
            assertEvalAgreement(term);
        }

        @Test
        void keepsLazyIfWithErroringSelectedBranch() {
            // force ((force ifThenElse) True (delay error) (delay 1)) errors
            var term = discard(Term.force(Term.apply(
                    Term.apply(
                            Term.apply(Term.force(Term.builtin(DefaultFun.IfThenElse)), boolConst(true)),
                            Term.delay(Term.error())),
                    Term.delay(intConst(1)))));
            assertEquals(term, opt.deadCodeElimination(term));
            assertEvalAgreement(term);
        }

        @Test
        void keepsConstrDataWithOutOfRangeTag() {
            // constrData 2^40 [] errors at runtime (tag out of int range)
            var term = discard(Term.apply(
                    Term.apply(Term.builtin(DefaultFun.ConstrData),
                            Term.const_(Constant.integer(BigInteger.TWO.pow(40)))),
                    Term.apply(Term.builtin(DefaultFun.MkNilData), Term.const_(Constant.unit()))));
            assertEquals(term, opt.deadCodeElimination(term));
            assertEvalAgreement(term);
        }

        @Test
        void keepsMkConsWithNonDataElement() {
            // mkCons True (mkNilData ()) errors at runtime (bool into a data list)
            var term = discard(Term.apply(
                    Term.apply(Term.force(Term.builtin(DefaultFun.MkCons)), boolConst(true)),
                    Term.apply(Term.builtin(DefaultFun.MkNilData), Term.const_(Constant.unit()))));
            assertEquals(term, opt.deadCodeElimination(term));
            assertEvalAgreement(term);
        }

        @Test
        void keepsListDataOnContentMismatchedConstant() {
            // ListConst does not enforce its declared element type on its
            // contents: a (list data) constant holding an integer element makes
            // listData error at runtime, so the declared type alone must not
            // certify the application as pure
            var term = discard(Term.apply(Term.builtin(DefaultFun.ListData),
                    contentMismatchedDataList()));
            assertEquals(term, opt.deadCodeElimination(term));
            assertFalse(vm.evaluate(Program.plutusV3(opt.optimize(term)), BUDGET).isSuccess());
            assertEvalAgreement(term);
        }

        @Test
        void keepsMapDataOnContentMismatchedConstant() {
            // (list (pair data data)) constant holding a pair of integers —
            // mapData rejects non-Data pair elements at runtime
            var malformed = Term.const_(new Constant.ListConst(
                    DefaultUni.pairOf(DefaultUni.DATA, DefaultUni.DATA),
                    List.of(new Constant.PairConst(Constant.integer(1), Constant.integer(2)))));
            var term = discard(Term.apply(Term.builtin(DefaultFun.MapData), malformed));
            assertEquals(term, opt.deadCodeElimination(term));
            assertFalse(vm.evaluate(Program.plutusV3(opt.optimize(term)), BUDGET).isSuccess());
            assertEvalAgreement(term);
        }

        @Test
        void dropsListDataOnWellFormedConstantList() {
            // listData over a well-formed (list data) constant is total — pure
            var term = discard(Term.apply(Term.builtin(DefaultFun.ListData),
                    wellFormedDataList()));
            assertEquals(boolConst(true), opt.deadCodeElimination(term));
            assertEvalAgreement(term);
        }

        @Test
        void keepsLazyIfReturningContentMismatchedList() {
            // Certification of lazy-if branches goes through constantType, not
            // constantMatches — a content-mismatched list must not certify as
            // LIST_DATA on that path either, or listData(<lazy-if>) would be
            // considered pure while erroring at runtime
            var term = discard(Term.apply(Term.builtin(DefaultFun.ListData),
                    lazyIfBothBranches(contentMismatchedDataList())));
            assertEquals(term, opt.deadCodeElimination(term));
            assertFalse(vm.evaluate(Program.plutusV3(opt.optimize(term)), BUDGET).isSuccess());
            assertEvalAgreement(term);
        }

        @Test
        void dropsLazyIfReturningWellFormedList() {
            // The same lazy-if shape with a well-formed list still certifies
            var term = discard(Term.apply(Term.builtin(DefaultFun.ListData),
                    lazyIfBothBranches(wellFormedDataList())));
            assertEquals(boolConst(true), opt.deadCodeElimination(term));
            assertEvalAgreement(term);
        }

        /** (list data) constant whose element is not Data — invalid, but constructible. */
        private static Term contentMismatchedDataList() {
            return Term.const_(new Constant.ListConst(DefaultUni.DATA,
                    List.of(Constant.integer(1))));
        }

        private static Term wellFormedDataList() {
            return Term.const_(new Constant.ListConst(DefaultUni.DATA,
                    List.of(Constant.data(PlutusData.integer(1)))));
        }

        /** force ((force ifThenElse) True (delay branch) (delay branch)) */
        private static Term lazyIfBothBranches(Term branch) {
            return Term.force(Term.apply(
                    Term.apply(
                            Term.apply(Term.force(Term.builtin(DefaultFun.IfThenElse)), boolConst(true)),
                            Term.delay(branch)),
                    Term.delay(branch)));
        }

        @Test
        void dropsIfThenElseOnBoolConstantWithPureBranches() {
            // (force ifThenElse) True 1 2 — total, typed condition, pure branches
            var term = discard(Term.apply(
                    Term.apply(
                            Term.apply(Term.force(Term.builtin(DefaultFun.IfThenElse)), boolConst(true)),
                            intConst(1)),
                    intConst(2)));
            assertEquals(boolConst(true), opt.deadCodeElimination(term));
            assertEvalAgreement(term);
        }

        @Test
        void keepsIfThenElseWithErroringBranchArgument() {
            // (force ifThenElse) True 1 (headList []) — the else-branch argument is
            // evaluated eagerly under CBV and errors — must be kept
            var term = discard(Term.apply(
                    Term.apply(
                            Term.apply(Term.force(Term.builtin(DefaultFun.IfThenElse)), boolConst(true)),
                            intConst(1)),
                    headOfEmpty()));
            assertEquals(term, opt.deadCodeElimination(term));
            assertEvalAgreement(term);
        }
    }

    // ---- Pass 4: beta reduction ----

    @Nested
    class BetaReductionSoundness {

        @Test
        void doesNotSubstituteForcedConstant() {
            // (\x -> delay x) (force 1) — argument errors eagerly; substituting it
            // under the delay would skip the error
            var term = Term.apply(Term.lam("x", Term.delay(Term.var(1))),
                    Term.force(intConst(1)));
            assertEquals(term, opt.betaReduce(term));
            assertFalse(vm.evaluate(Program.plutusV3(opt.optimize(term)), BUDGET).isSuccess());
            assertEvalAgreement(term);
        }

        @Test
        void doesNotSubstituteOverForcedBuiltin() {
            // (\x -> delay x) (force addInteger) — addInteger takes no type args
            var term = Term.apply(Term.lam("x", Term.delay(Term.var(1))),
                    Term.force(Term.builtin(DefaultFun.AddInteger)));
            assertEquals(term, opt.betaReduce(term));
            assertEvalAgreement(term);
        }

        @Test
        void doesNotSubstituteDoublyForcedSingleForceBuiltin() {
            // force (force headList) — one force too many
            var term = Term.apply(Term.lam("x", Term.delay(Term.var(1))),
                    Term.force(Term.force(Term.builtin(DefaultFun.HeadList))));
            assertEquals(term, opt.betaReduce(term));
            assertEvalAgreement(term);
        }

        @Test
        void doesNotSubstituteForcedVariable() {
            // \v -> (\x -> delay x) (force v) — forcing v runs an arbitrary thunk
            var term = Term.lam("v", Term.apply(
                    Term.lam("x", Term.delay(Term.var(1))),
                    Term.force(Term.var(1))));
            assertEquals(term, opt.betaReduce(term));
        }

        @Test
        void substitutesConstant() {
            var term = Term.apply(Term.lam("x", Term.var(1)), intConst(42));
            assertEquals(intConst(42), opt.betaReduce(term));
        }

        @Test
        void substitutesCorrectlyForcedBuiltin() {
            // (\f -> f xs) (force headList) → (force headList) xs
            var xs = emptyIntList();
            var term = Term.apply(
                    Term.lam("f", Term.apply(Term.var(1), xs)),
                    Term.force(Term.builtin(DefaultFun.HeadList)));
            assertEquals(Term.apply(Term.force(Term.builtin(DefaultFun.HeadList)), xs),
                    opt.betaReduce(term));
        }

        @Test
        void substitutesPartiallyForcedBuiltin() {
            // force fstPair (1 of 2 forces) is still a value
            var term = Term.apply(
                    Term.lam("f", Term.delay(Term.var(1))),
                    Term.force(Term.builtin(DefaultFun.FstPair)));
            assertEquals(Term.delay(Term.force(Term.builtin(DefaultFun.FstPair))),
                    opt.betaReduce(term));
        }
    }

    // ---- Pass 5: eta reduction ----

    @Nested
    class EtaReductionSoundness {

        @Test
        void doesNotEtaReduceForcedConstant() {
            // \x -> (force 1) x — the lam is a value; the reduced term errors
            var term = Term.lam("x", Term.apply(Term.force(intConst(1)), Term.var(1)));
            assertEquals(term, opt.etaReduce(term));
            assertTrue(vm.evaluate(Program.plutusV3(opt.optimize(term)), BUDGET).isSuccess());
            assertEvalAgreement(term);
        }

        @Test
        void doesNotEtaReduceVariableTarget() {
            // \v -> \x -> v x: v may be bound to a Delay/Constr, which behaves
            // differently from a lambda under force/case elimination
            var term = Term.lam("v", Term.lam("x", Term.apply(Term.var(2), Term.var(1))));
            assertEquals(term, opt.etaReduce(term));
        }

        @Test
        void doesNotEtaReduceDelayTarget() {
            // \x -> (delay 1) x: forcing the reduced term would succeed where
            // forcing the original lambda errors
            var term = Term.lam("x", Term.apply(Term.delay(intConst(1)), Term.var(1)));
            assertEquals(term, opt.etaReduce(term));
        }

        @Test
        void doesNotEtaReduceConstantTarget() {
            var term = Term.lam("x", Term.apply(intConst(5), Term.var(1)));
            assertEquals(term, opt.etaReduce(term));
        }

        @Test
        void doesNotEtaReduceUnderForcedBuiltin() {
            // \x -> (force fstPair) x — fstPair needs 2 forces; forcing the reduced
            // term would succeed where forcing the original lambda errors
            var term = Term.lam("x",
                    Term.apply(Term.force(Term.builtin(DefaultFun.FstPair)), Term.var(1)));
            assertEquals(term, opt.etaReduce(term));
        }

        @Test
        void etaReducesBareZeroForceBuiltin() {
            var term = Term.lam("x", Term.apply(Term.builtin(DefaultFun.AddInteger), Term.var(1)));
            assertEquals(Term.builtin(DefaultFun.AddInteger), opt.etaReduce(term));
        }

        @Test
        void etaReducesFullyForcedBuiltin() {
            // \x -> (force (force fstPair)) x → force (force fstPair)
            var target = Term.force(Term.force(Term.builtin(DefaultFun.FstPair)));
            var term = Term.lam("x", Term.apply(target, Term.var(1)));
            assertEquals(target, opt.etaReduce(term));
        }

        @Test
        void etaReducesPartialBuiltinApplication() {
            // \x -> (addInteger 1) x → addInteger 1
            var target = Term.apply(Term.builtin(DefaultFun.AddInteger), intConst(1));
            var term = Term.lam("x", Term.apply(target, Term.var(1)));
            assertEquals(target, opt.etaReduce(term));
        }

        @Test
        void etaReducesLambdaTarget() {
            // \x -> (\y -> y) x → \y -> y
            var target = Term.lam("y", Term.var(1));
            var term = Term.lam("x", Term.apply(target, Term.var(1)));
            assertEquals(target, opt.etaReduce(term));
        }

        @Test
        void doesNotEtaReduceSaturatingApplication() {
            // \x -> (addInteger 1 2) x — target is already saturated; its
            // evaluation is real work (and here, applying its result errors)
            var target = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.AddInteger), intConst(1)), intConst(2));
            var term = Term.lam("x", Term.apply(target, Term.var(1)));
            assertEquals(term, opt.etaReduce(term));
        }
    }

    // ---- Pass 2: constant folding ----

    @Nested
    class ConstantFoldingSoundness {

        @Test
        void doesNotFoldForcedBuiltinApplication() {
            // (force addInteger) 3 4 errors at runtime — folding would hide it
            var term = Term.apply(
                    Term.apply(Term.force(Term.builtin(DefaultFun.AddInteger)), intConst(3)),
                    intConst(4));
            assertEquals(term, opt.constantFold(term));
            assertFalse(vm.evaluate(Program.plutusV3(opt.optimize(term)), BUDGET).isSuccess());
            assertEvalAgreement(term);
        }

        @Test
        void stillFoldsBareBuiltinApplication() {
            var term = Term.apply(
                    Term.apply(Term.builtin(DefaultFun.AddInteger), intConst(3)),
                    intConst(4));
            assertEquals(intConst(7), opt.constantFold(term));
        }
    }

    // ---- Pass 6: Constr/Case reduction ----

    @Nested
    class ConstrCaseSoundness {

        @Test
        void doesNotReduceWhenLaterFieldIsEffectful() {
            // Case(Constr(0, [1, trace "second-field" 2]), [\first -> error]):
            // CEK evaluates ALL fields (emitting the trace) before the branch
            // body errors. The reduced form ((\first -> error) 1) (trace ...)
            // would run the branch body after the first application — erroring
            // before the second field is evaluated and losing its trace.
            var fields = List.of(intConst(1), traceApp("second-field", intConst(2)));
            var branch = Term.lam("first", Term.error());
            var term = new Term.Case(new Term.Constr(0, fields), List.of(branch));
            assertEquals(term, opt.constrCaseReduce(term));
            var result = vm.evaluate(Program.plutusV3(opt.optimize(term)), BUDGET);
            assertFalse(result.isSuccess());
            assertEquals(List.of("second-field"), result.traces());
            assertEvalAgreement(term);
        }

        @Test
        void reducesEffectfulBranchWhenFieldsAreValues() {
            // branch is an application, but every field is a value — field
            // evaluation is effect-free, so moving the branch computation
            // across it is unobservable and the reduction may fire
            var branch = Term.apply(
                    Term.apply(Term.force(Term.builtin(DefaultFun.Trace)),
                            Term.const_(Constant.string("branch"))),
                    Term.lam("v", Term.var(1)));
            var term = new Term.Case(new Term.Constr(0, List.of(intConst(1))), List.of(branch));
            assertEquals(Term.apply(branch, intConst(1)), opt.constrCaseReduce(term));
            assertEvalAgreement(term);
        }

        @Test
        void reducesWhenBranchIsALambda() {
            var branch = Term.lam("v", Term.var(1));
            var term = new Term.Case(new Term.Constr(0, List.of(intConst(42))), List.of(branch));
            var reduced = opt.constrCaseReduce(term);
            assertEquals(Term.apply(branch, intConst(42)), reduced);
        }

        @Test
        void preservesFieldEffects() {
            // Case(Constr(0, [trace "field" 1]), [\v -> v]) — the effectful
            // field blocks the rewrite, so its trace survives trivially
            var field = traceApp("field", intConst(1));
            var term = new Term.Case(new Term.Constr(0, List.of(field)),
                    List.of(Term.lam("v", Term.var(1))));
            var result = vm.evaluate(Program.plutusV3(opt.optimize(term)), BUDGET);
            assertTrue(result.isSuccess());
            assertEquals(List.of("field"), result.traces());
            assertEvalAgreement(term);
        }

        @Test
        void doesNotReduceOutOfRangeTag() {
            var term = new Term.Case(new Term.Constr(5, List.of()),
                    List.of(Term.lam("v", Term.var(1))));
            assertEquals(term, opt.constrCaseReduce(term));
            assertEvalAgreement(term);
        }
    }

    // ---- value/purity predicate unit checks ----

    @Nested
    class PredicateChecks {

        @Test
        void errorIsNeitherValueNorPure() {
            assertFalse(UplcOptimizer.isValue(Term.error()));
            assertFalse(UplcOptimizer.isPure(Term.error()));
        }

        @Test
        void constrOfValuesIsValue() {
            assertTrue(UplcOptimizer.isValue(new Term.Constr(0, List.of(intConst(1), Term.lam("x", Term.var(1))))));
        }

        @Test
        void constrWithErroringFieldIsNotValue() {
            assertFalse(UplcOptimizer.isValue(new Term.Constr(0, List.of(Term.error()))));
            assertFalse(UplcOptimizer.isValue(new Term.Constr(0, List.of(headOfEmpty()))));
        }

        @Test
        void forceChainRespectsTypeArity() {
            // headList: 1 force
            assertTrue(UplcOptimizer.isValue(Term.force(Term.builtin(DefaultFun.HeadList))));
            assertFalse(UplcOptimizer.isValue(Term.force(Term.force(Term.builtin(DefaultFun.HeadList)))));
            // fstPair: 2 forces
            assertTrue(UplcOptimizer.isValue(Term.force(Term.force(Term.builtin(DefaultFun.FstPair)))));
            // addInteger: 0 forces
            assertFalse(UplcOptimizer.isValue(Term.force(Term.builtin(DefaultFun.AddInteger))));
        }

        @Test
        void interleavedForceIsNotValue() {
            // force (trace "x") — force between builtin and its first arg is a
            // runtime error shape, not a spine
            var term = Term.force(Term.apply(
                    Term.force(Term.builtin(DefaultFun.Trace)),
                    Term.const_(Constant.string("x"))));
            assertFalse(UplcOptimizer.isValue(term));
            assertFalse(UplcOptimizer.isPure(term));
        }

        @Test
        void saturatedPartialBuiltinIsNotPureEvenOnConstants() {
            assertFalse(UplcOptimizer.isPure(divByZero()));
            assertFalse(UplcOptimizer.isPure(headOfEmpty()));
        }

        @Test
        void saturatedTraceIsNotPure() {
            assertFalse(UplcOptimizer.isPure(traceApp("m", intConst(1))));
        }

        @Test
        void oversaturatedApplicationIsNotPure() {
            // (addInteger 1 2) 3 — applying the integer result errors
            var term = Term.apply(
                    Term.apply(Term.apply(Term.builtin(DefaultFun.AddInteger), intConst(1)), intConst(2)),
                    intConst(3));
            assertFalse(UplcOptimizer.isPure(term));
            assertFalse(UplcOptimizer.isValue(term));
        }
    }
}
