package com.bloxbean.cardano.julc.compiler.uplc;

import com.bloxbean.cardano.julc.core.*;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.JulcVm;
import net.jqwik.api.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Differential property test: for any closed UPLC term, the optimizer must
 * preserve the observable evaluation outcome — success vs. failure, the result
 * constant when both succeed with a constant, and the emitted trace log.
 * <p>
 * This mechanically guards every current pass (and future passes: constant
 * propagation, CSE, force hoisting) against the class of soundness bug fixed in
 * adr/issues/julc-dce-soundness-issue.md: rewrites that discard, move, or fold
 * computations whose evaluation can error, diverge, or emit logs.
 * <p>
 * Divergent terms (e.g. randomly generated omega combinators) hit the budget
 * cap; comparisons are skipped when either side exhausts the budget since the
 * optimizer may legitimately change the cost of a diverging term.
 */
class UplcOptimizerPropertyTest {

    static final JulcVm VM = JulcVm.create("Java");
    static final ExBudget BUDGET = new ExBudget(200_000_000L, 20_000_000L);

    static final List<DefaultFun> BUILTIN_POOL = List.of(
            DefaultFun.AddInteger, DefaultFun.SubtractInteger, DefaultFun.MultiplyInteger,
            DefaultFun.DivideInteger, DefaultFun.EqualsInteger, DefaultFun.LessThanInteger,
            DefaultFun.IfThenElse, DefaultFun.ChooseUnit, DefaultFun.Trace,
            DefaultFun.FstPair, DefaultFun.SndPair, DefaultFun.ChooseList,
            DefaultFun.MkCons, DefaultFun.HeadList, DefaultFun.TailList, DefaultFun.NullList,
            DefaultFun.ChooseData, DefaultFun.ConstrData, DefaultFun.IData, DefaultFun.BData,
            DefaultFun.UnIData, DefaultFun.UnBData, DefaultFun.UnConstrData,
            DefaultFun.Sha2_256, DefaultFun.LengthOfByteString, DefaultFun.EqualsByteString,
            DefaultFun.AppendByteString, DefaultFun.MkNilData,
            DefaultFun.ListData, DefaultFun.MapData);

    @Property(tries = 3000)
    void optimizePreservesEvaluationOutcome(@ForAll("closedTerms") Term term) {
        var optimizedTerm = new UplcOptimizer().optimize(term);

        var original = VM.evaluate(Program.plutusV3(term), BUDGET);
        var optimized = VM.evaluate(Program.plutusV3(optimizedTerm), BUDGET);

        if (original instanceof EvalResult.BudgetExhausted
                || optimized instanceof EvalResult.BudgetExhausted) {
            return;
        }

        assertEquals(original.isSuccess(), optimized.isSuccess(),
                () -> "success/failure flip\n  term: " + term + "\n  optimized: " + optimizedTerm
                        + "\n  original: " + original + "\n  optimizedResult: " + optimized);
        assertEquals(original.traces(), optimized.traces(),
                () -> "trace mismatch\n  term: " + term + "\n  optimized: " + optimizedTerm);

        if (original instanceof EvalResult.Success s1
                && optimized instanceof EvalResult.Success s2
                && s1.resultTerm() instanceof Term.Const
                && s2.resultTerm() instanceof Term.Const) {
            assertEquals(s1.resultTerm(), s2.resultTerm(),
                    () -> "result constant mismatch\n  term: " + term
                            + "\n  optimized: " + optimizedTerm);
        }
    }

    @Provide
    Arbitrary<Term> closedTerms() {
        // One in ~6 terms is a TOP-LEVEL discarded saturated builtin over
        // constants. Embedded instances are often unreachable (buried under
        // unapplied lambdas) or unobservable (a sibling error fails both
        // sides identically); at the top of the term the shape is always
        // evaluated, so a certification misclassification always flips.
        return Arbitraries.frequencyOf(
                Tuple.of(5, terms(4, 0)),
                Tuple.of(1, discardedSaturatedBuiltins(terms(2, 1))));
    }

    /**
     * Recursively build an arbitrary for terms of at most the given depth that
     * are closed under {@code binders} enclosing lambda binders.
     */
    private Arbitrary<Term> terms(int depth, int binders) {
        Arbitrary<Term> leaf = leaves(binders);
        if (depth <= 0) {
            return leaf;
        }
        Arbitrary<Term> sub = terms(depth - 1, binders);
        Arbitrary<Term> subUnderLam = terms(depth - 1, binders + 1);

        return Arbitraries.frequencyOf(
                Tuple.of(4, leaf),
                Tuple.of(3, Combinators.combine(sub, sub).as(Term::apply)),
                Tuple.of(3, subUnderLam.map(body -> Term.lam("x", body))),
                Tuple.of(2, sub.map(Term::force)),
                Tuple.of(2, sub.map(Term::delay)),
                Tuple.of(1, constrs(sub)),
                Tuple.of(1, cases(sub)),
                // Redex-dense shapes aimed at the optimizer's own decision
                // points — uniform randomness essentially never composes them
                // (verified by mutation-testing the reverted soundness fixes):
                // a let-style binding whose argument is often a builtin
                // application spine (the exact shape DCE's purity gate judges;
                // the body may or may not use the bound variable) ...
                Tuple.of(3, Combinators.combine(subUnderLam,
                                Arbitraries.oneOf(sub, builtinSpines(sub)))
                        .as((body, arg) -> Term.apply(Term.lam("x", body), arg))),
                // ... a Case whose scrutinee is a Constr (Pass 6 target) ...
                Tuple.of(2, Combinators.combine(constrs(sub), sub.list().ofMinSize(1).ofMaxSize(3))
                        .as(Term.Case::new)),
                // ... a discarded saturated builtin over constant arguments —
                // the exact purity-certification surface: dropping is sound
                // only if the application provably cannot fail on those
                // constants (wrong types, malformed lists, out-of-range tags) ...
                Tuple.of(2, discardedSaturatedBuiltins(subUnderLam)),
                // ... plus standalone builtin spines and emitting traces, so
                // effects and arity edges appear inside every other shape
                Tuple.of(2, builtinSpines(sub)),
                Tuple.of(2, traceApps(sub)));
    }

    /**
     * {@code (\x -> body) (Force^typeArity(builtin) const...)} — a saturated
     * builtin application over constant arguments in discard position. This is
     * the decision surface of DCE's purity certification: the argument may be
     * dropped only when the saturated application provably cannot fail on
     * exactly those constants. Constants include wrong-typed values, content-
     * mismatched lists, and out-of-range integers, so misclassifications in
     * the totality/argument-type tables surface as success/failure flips.
     */
    private Arbitrary<Term> discardedSaturatedBuiltins(Arbitrary<Term> subUnderLam) {
        return Arbitraries.of(BUILTIN_POOL).flatMap(fun -> {
            var sig = BuiltinSemantics.find(fun);
            // Body biased toward constants: a dropped-computation flip is only
            // observable when the surviving continuation itself succeeds, and
            // random bodies error too often to expose it
            return Combinators.combine(
                            Arbitraries.oneOf(constants(), subUnderLam),
                            constants().list().ofSize(sig.valueArity()))
                    .as((body, args) -> {
                        Term t = Term.builtin(fun);
                        for (int i = 0; i < sig.typeArity(); i++) {
                            t = Term.force(t);
                        }
                        for (var arg : args) {
                            t = Term.apply(t, arg);
                        }
                        return Term.apply(Term.lam("x", body), t);
                    });
        });
    }

    /**
     * A builtin application spine: {@code Force^k(builtin) args...}, with k
     * within ±1 of the builtin's type arity (exercising over/under-force
     * edges) and 0..valueArity arguments biased toward constants — including
     * the content-mismatched list constants. These are the shapes the
     * optimizer's value/purity analysis actually judges.
     */
    private Arbitrary<Term> builtinSpines(Arbitrary<Term> sub) {
        return Arbitraries.of(BUILTIN_POOL).flatMap(fun -> {
            var sig = BuiltinSemantics.find(fun);
            return Combinators.combine(
                            Arbitraries.integers().between(
                                    Math.max(0, sig.typeArity() - 1), sig.typeArity() + 1),
                            Arbitraries.oneOf(constants(), sub)
                                    .list().ofMinSize(0).ofMaxSize(sig.valueArity()))
                    .as((forces, args) -> {
                        Term t = Term.builtin(fun);
                        for (int i = 0; i < forces; i++) {
                            t = Term.force(t);
                        }
                        for (var arg : args) {
                            t = Term.apply(t, arg);
                        }
                        return t;
                    });
        });
    }

    /**
     * A saturated, emitting trace: {@code (force trace) "msg" k}. Bare
     * {@code Trace} builtins from the leaf pool almost never get force-and-
     * string-saturated by chance, so without this shape trace-reordering bugs
     * are invisible to the differential property.
     */
    private Arbitrary<Term> traceApps(Arbitrary<Term> sub) {
        return Combinators.combine(Arbitraries.of("t1", "t2"), sub)
                .as((msg, k) -> Term.apply(
                        Term.apply(Term.force(Term.builtin(DefaultFun.Trace)),
                                Term.const_(Constant.string(msg))),
                        k));
    }

    private Arbitrary<Term> leaves(int binders) {
        var choices = new ArrayList<Arbitrary<Term>>();
        choices.add(constants());
        choices.add(Arbitraries.of(BUILTIN_POOL).map(Term::builtin));
        choices.add(Arbitraries.just(Term.error()));
        if (binders > 0) {
            choices.add(Arbitraries.integers().between(1, binders).map(Term::var));
        }
        return Arbitraries.oneOf(choices);
    }

    private Arbitrary<Term> constants() {
        return Arbitraries.oneOf(
                Arbitraries.longs().between(-1_000_000, 1_000_000)
                        .map(v -> Term.const_(Constant.integer(BigInteger.valueOf(v)))),
                Arbitraries.of(
                        Term.const_(Constant.integer(BigInteger.ZERO)),
                        Term.const_(Constant.bool(true)),
                        Term.const_(Constant.bool(false)),
                        Term.const_(Constant.unit()),
                        Term.const_(Constant.string("s")),
                        Term.const_(Constant.string("")),
                        Term.const_(Constant.byteString(new byte[0])),
                        Term.const_(Constant.byteString(new byte[]{1, 2, 3})),
                        Term.const_(Constant.data(PlutusData.integer(7))),
                        Term.const_(Constant.data(PlutusData.bytes(new byte[]{9}))),
                        Term.const_(Constant.data(PlutusData.constr(0, PlutusData.integer(1)))),
                        Term.const_(Constant.data(PlutusData.list(PlutusData.integer(1)))),
                        Term.const_(new Constant.ListConst(DefaultUni.INTEGER, List.of())),
                        Term.const_(new Constant.ListConst(DefaultUni.INTEGER,
                                List.of(Constant.integer(1), Constant.integer(2)))),
                        Term.const_(new Constant.ListConst(DefaultUni.DATA,
                                List.of(Constant.data(PlutusData.integer(3))))),
                        Term.const_(new Constant.PairConst(
                                Constant.data(PlutusData.integer(1)),
                                Constant.data(PlutusData.bytes(new byte[]{2})))),
                        // Content-mismatched list constants: ListConst does not
                        // enforce its declared element type, and the optimizer
                        // must not trust the declaration (listData/mapData/mkCons
                        // reject the contents at runtime). Not producible by the
                        // compiler or the flat decoder, but hand-constructible.
                        Term.const_(new Constant.ListConst(DefaultUni.DATA,
                                List.of(Constant.integer(1)))),
                        Term.const_(new Constant.ListConst(
                                DefaultUni.pairOf(DefaultUni.DATA, DefaultUni.DATA),
                                List.of(new Constant.PairConst(
                                        Constant.integer(1), Constant.integer(2)))))));
    }

    private Arbitrary<Term> constrs(Arbitrary<Term> sub) {
        // Fields biased toward emitting traces: Constr evaluates fields
        // eagerly, so effectful fields are what expose field/branch
        // reordering bugs in Case reduction
        return Combinators.combine(
                        Arbitraries.longs().between(0, 2),
                        Arbitraries.oneOf(sub, traceApps(sub)).list().ofMinSize(0).ofMaxSize(3))
                .as(Term.Constr::new);
    }

    private Arbitrary<Term> cases(Arbitrary<Term> sub) {
        return Combinators.combine(sub, sub.list().ofMinSize(1).ofMaxSize(3))
                .as(Term.Case::new);
    }
}
