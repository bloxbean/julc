package com.bloxbean.cardano.julc.vm.java;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.bloxbean.cardano.julc.core.source.SourceMap;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.ExBudget;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.java.cost.BudgetExhaustedException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for source map VM integration: CekEvaluationException.failedTerm,
 * EvalResult.failedTerm, and BudgetExhaustedException.failedTerm.
 */
class SourceMapVmTest {

    @Test
    void cekEvaluationException_carriesFailedTerm() {
        var term = Term.error();
        var ex = new CekEvaluationException("test error", term);
        assertSame(term, ex.failedTerm());
        assertEquals("test error", ex.getMessage());
    }

    @Test
    void cekEvaluationException_noFailedTerm_backwardCompatible() {
        var ex = new CekEvaluationException("test error");
        assertNull(ex.failedTerm());
    }

    @Test
    void cekEvaluationException_withCause_noFailedTerm() {
        var cause = new RuntimeException("inner");
        var ex = new CekEvaluationException("test error", cause);
        assertNull(ex.failedTerm());
        assertSame(cause, ex.getCause());
    }

    @Test
    void budgetExhaustedException_carriesFailedTerm() {
        var term = Term.const_(Constant.integer(42));
        var ex = new BudgetExhaustedException("budget exceeded", term);
        assertSame(term, ex.failedTerm());
    }

    @Test
    void budgetExhaustedException_noFailedTerm_backwardCompatible() {
        var ex = new BudgetExhaustedException("budget exceeded");
        assertNull(ex.failedTerm());
    }

    @Test
    void evalResult_failure_withFailedTerm() {
        var term = Term.error();
        var budget = new ExBudget(1000, 500);
        var failure = new EvalResult.Failure("Error", budget, List.of(), term);

        assertEquals("Error", failure.error());
        assertSame(term, failure.failedTerm());
    }

    @Test
    void evalResult_failure_backwardCompatible() {
        var budget = new ExBudget(1000, 500);
        var failure = new EvalResult.Failure("Error", budget, List.of());
        assertNull(failure.failedTerm());
    }

    @Test
    void evalResult_budgetExhausted_withFailedTerm() {
        var term = Term.const_(Constant.integer(1));
        var budget = new ExBudget(1000, 500);
        var exhausted = new EvalResult.BudgetExhausted(budget, List.of(), term);
        assertSame(term, exhausted.failedTerm());
    }

    @Test
    void evalResult_budgetExhausted_backwardCompatible() {
        var budget = new ExBudget(1000, 500);
        var exhausted = new EvalResult.BudgetExhausted(budget, List.of());
        assertNull(exhausted.failedTerm());
    }

    @Test
    void cekMachine_errorTerm_throwsWithCurrentTerm() {
        // Evaluate a program that immediately errors
        var errorTerm = Term.error();
        var machine = new CekMachine();

        var ex = assertThrows(CekEvaluationException.class, () -> machine.evaluate(errorTerm));
        assertEquals("Error term encountered", ex.getMessage());
        assertSame(errorTerm, ex.failedTerm());
    }

    @Test
    void cekMachine_errorInBranch_throwsWithCorrectTerm() {
        // if true then error else unit
        // Force(Apply(Apply(Apply(Force(IfThenElse), true), Delay(error)), Delay(unit)))
        var ifBuiltin = Term.force(Term.builtin(com.bloxbean.cardano.julc.core.DefaultFun.IfThenElse));
        var errorInDelay = Term.delay(Term.error());
        var unitInDelay = Term.delay(Term.const_(Constant.unit()));
        var trueCond = Term.const_(Constant.bool(true));

        var term = Term.force(
                Term.apply(
                        Term.apply(
                                Term.apply(ifBuiltin, trueCond),
                                errorInDelay),
                        unitInDelay));

        var machine = new CekMachine();
        var ex = assertThrows(CekEvaluationException.class, () -> machine.evaluate(term));
        assertEquals("Error term encountered", ex.getMessage());
        assertNotNull(ex.failedTerm());
    }

    @Test
    void javaVmProvider_errorEvaluation_failureHasFailedTerm() {
        // Build a program: error
        var program = com.bloxbean.cardano.julc.core.Program.plutusV3(Term.error());
        var provider = new JavaVmProvider();

        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        assertInstanceOf(EvalResult.Failure.class, result);
        var failure = (EvalResult.Failure) result;
        assertNotNull(failure.failedTerm());
    }

    @Test
    void javaVmProvider_successEvaluation_noFailedTerm() {
        var program = com.bloxbean.cardano.julc.core.Program.plutusV3(
                Term.const_(Constant.integer(42)));
        var provider = new JavaVmProvider();

        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
        assertInstanceOf(EvalResult.Success.class, result);
    }

    @Test
    void sourceMap_lookup_withFailedTerm() {
        var errorTerm = Term.error();
        var location = new SourceLocation("Test.java", 42, 5, "Builtins.error()");
        var sourceMap = SourceMap.of(Map.of(errorTerm, location));

        // Evaluate and get failure
        var program = com.bloxbean.cardano.julc.core.Program.plutusV3(errorTerm);
        var provider = new JavaVmProvider();
        var result = provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);

        assertInstanceOf(EvalResult.Failure.class, result);
        var failure = (EvalResult.Failure) result;
        assertNotNull(failure.failedTerm());

        // Look up in source map — the failed term should be the exact same object
        var resolved = sourceMap.lookup(failure.failedTerm());
        assertEquals(location, resolved);
        assertEquals(42, resolved.line());
        assertEquals("Builtins.error()", resolved.fragment());
    }
}
