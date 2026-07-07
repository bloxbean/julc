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
            DefaultFun.AppendByteString, DefaultFun.MkNilData);

    @Property(tries = 1500)
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
        return terms(4, 0);
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
                Tuple.of(1, cases(sub)));
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
                                Constant.data(PlutusData.bytes(new byte[]{2}))))));
    }

    private Arbitrary<Term> constrs(Arbitrary<Term> sub) {
        return Combinators.combine(
                        Arbitraries.longs().between(0, 2),
                        sub.list().ofMinSize(0).ofMaxSize(3))
                .as(Term.Constr::new);
    }

    private Arbitrary<Term> cases(Arbitrary<Term> sub) {
        return Combinators.combine(sub, sub.list().ofMinSize(1).ofMaxSize(3))
                .as(Term.Case::new);
    }
}
