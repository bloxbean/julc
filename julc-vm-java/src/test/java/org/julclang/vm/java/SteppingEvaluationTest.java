package org.julclang.vm.java;

import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.text.UplcParser;
import org.julclang.core.text.UplcPrinter;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.julclang.vm.LedgerEvaluationTarget;
import org.julclang.vm.PlutusLanguage;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class SteppingEvaluationTest {

    private static final LedgerEvaluationTarget V3 = LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3);
    private final JavaVmProvider provider = new JavaVmProvider();

    @Test
    void steppingEveryConformanceProgramMatchesEvaluation() throws Exception {
        var dir = Paths.get(Objects.requireNonNull(getClass().getResource("/conformance")).toURI());
        List<Path> files;
        try (var walk = Files.walk(dir)) {
            files = walk.filter(p -> p.toString().endsWith(".uplc")).sorted().toList();
        }
        int compared = 0;
        for (Path file : files) {
            Program program;
            try {
                program = UplcParser.parseProgram(Files.readString(file));
            } catch (Exception parseError) {
                continue;
            }
            for (ExBudget budget : new ExBudget[]{null, new ExBudget(30_000, 3_000)}) {
                var expected = provider.evaluateWithArgs(program, V3, List.of(), budget, EvalOptions.DEFAULT);
                var stepping = provider.startStepping(program, V3, List.of(), budget, EvalOptions.DEFAULT);
                long guard = 0;
                while (stepping.step()) {
                    assertTrue(++guard < 50_000_000, "runaway stepping for " + file);
                }
                assertSameResult(expected, stepping.result(), dir.relativize(file) + " budget=" + budget);
            }
            compared++;
        }
        assertTrue(compared > 900, "compared " + compared + " conformance programs");
    }

    @Test
    void eachStepExposesTheMachineState() {
        // [(lam x x) (con integer 42)]
        var program = Program.plutusV3(Term.apply(Term.lam("x", Term.var(1)), Term.const_(Constant.integer(42))));
        var evaluation = provider.startStepping(program, V3, List.of(), null, EvalOptions.DEFAULT);
        var machine = evaluation.machine();

        assertTrue(machine.isComputing());
        assertInstanceOf(Term.Apply.class, machine.currentTerm());

        assertTrue(evaluation.step()); // Apply: push argument frame, compute the function
        assertInstanceOf(Term.Lam.class, machine.currentTerm());
        assertEquals(1, machine.stackDepth());
        assertInstanceOf(CekFrame.ComputeArgFrame.class, machine.frames(5).getFirst());

        assertTrue(evaluation.step()); // Lam: return the closure
        assertFalse(machine.isComputing());
        assertInstanceOf(CekValue.VLam.class, machine.currentValue());

        assertTrue(evaluation.step()); // return to the argument frame: compute the argument
        assertInstanceOf(CekFrame.ApplyArgFrame.class, machine.frames(5).getFirst());
        assertInstanceOf(Term.Const.class, machine.currentTerm());

        assertTrue(evaluation.step()); // Const: return 42
        assertTrue(evaluation.step()); // apply the closure: compute the body with x bound
        assertInstanceOf(Term.Var.class, machine.currentTerm());
        assertEquals(1, machine.currentEnvironment().size());
        assertEquals(0, machine.stackDepth());

        assertFalse(evaluation.step()); // Var: return 42, nothing left
        assertEquals(6, evaluation.steps());
        assertFalse(evaluation.step());
        assertEquals(6, evaluation.steps());
        var success = assertInstanceOf(EvalResult.Success.class, evaluation.result());
        assertEquals("(con integer 42)", UplcPrinter.print(success.resultTerm()));
        assertEquals(provider.evaluate(program, V3, null, EvalOptions.DEFAULT).budgetConsumed(),
                success.consumed());
    }

    @Test
    void inspectedFramesCannotMutateEvaluationState() {
        var program = Program.plutusV3(Term.constr(0,
                Term.const_(Constant.integer(1)), Term.const_(Constant.integer(2))));
        var evaluation = provider.startStepping(program, V3, List.of(), null, EvalOptions.DEFAULT);

        assertTrue(evaluation.step()); // Constr: push its frame and compute the first field
        assertTrue(evaluation.step()); // Const: return the first field to that frame
        var frame = assertInstanceOf(CekFrame.ConstrFrame.class,
                evaluation.machine().frames(1).getFirst());

        assertThrows(UnsupportedOperationException.class,
                () -> frame.evaluatedFields().add(new CekValue.VCon(Constant.integer(99))));
        assertThrows(UnsupportedOperationException.class,
                () -> evaluation.machine().frames(1).clear());
        assertThrows(IllegalArgumentException.class, () -> evaluation.machine().frames(-1));

        while (evaluation.step()) {
            // Complete the same machine after inspection.
        }
        assertSameResult(provider.evaluate(program, V3, null, EvalOptions.DEFAULT),
                evaluation.result(), "inspection is read-only");
    }

    @Test
    void failureRecordsTheFailedTermAndTraces() {
        // (force [(force (builtin trace)) (con string "before") (delay (error))])
        Term error = Term.error();
        var program = Program.plutusV3(Term.force(Term.apply(Term.apply(Term.force(Term.builtin(DefaultFun.Trace)),
                Term.const_(Constant.string("before"))), Term.delay(error))));
        var evaluation = provider.startStepping(program, V3, List.of(), null, EvalOptions.DEFAULT);
        while (evaluation.step()) {
            assertNull(evaluation.result());
        }

        var failure = assertInstanceOf(EvalResult.Failure.class, evaluation.result());
        assertSame(error, failure.failedTerm());
        assertEquals(List.of("before"), failure.traces());
        assertSameResult(provider.evaluate(program, V3, null, EvalOptions.DEFAULT), failure, "trace then error");
    }

    @Test
    void budgetBelowStartupCostFinishesImmediately() {
        var program = Program.plutusV3(Term.const_(Constant.unit()));
        var evaluation = provider.startStepping(program, V3, List.of(), new ExBudget(1, 1), EvalOptions.DEFAULT);

        assertTrue(evaluation.isFinished());
        assertInstanceOf(EvalResult.BudgetExhausted.class, evaluation.result());
        assertFalse(evaluation.step());
        assertEquals(0, evaluation.steps());
    }

    @Test
    void programRejectedByTheProfileHasNoMachine() {
        // Constr is not available in UPLC 1.0.0
        var program = new Program(1, 0, 0, Term.constr(0));
        var evaluation = provider.startStepping(program, V3, List.of(), null, EvalOptions.DEFAULT);

        assertTrue(evaluation.isFinished());
        assertNull(evaluation.machine());
        assertInstanceOf(EvalResult.Failure.class, evaluation.result());
        assertEquals(provider.evaluate(program, V3, null, EvalOptions.DEFAULT), evaluation.result());
    }

    private static void assertSameResult(EvalResult expected, EvalResult actual, String label) {
        assertNotNull(actual, label);
        assertEquals(expected.getClass(), actual.getClass(), label);
        assertEquals(expected.budgetConsumed(), actual.budgetConsumed(), label);
        assertEquals(expected.traces(), actual.traces(), label);
        switch (expected) {
            case EvalResult.Success s -> assertEquals(UplcPrinter.print(s.resultTerm()),
                    UplcPrinter.print(((EvalResult.Success) actual).resultTerm()), label);
            case EvalResult.Failure f -> {
                var other = (EvalResult.Failure) actual;
                assertEquals(f.error(), other.error(), label);
                assertSame(f.failedTerm(), other.failedTerm(), label);
            }
            case EvalResult.BudgetExhausted b ->
                    assertSame(b.failedTerm(), ((EvalResult.BudgetExhausted) actual).failedTerm(), label);
        }
    }
}
