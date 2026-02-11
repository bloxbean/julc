package com.bloxbean.cardano.julc.vm;

import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.Term;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EvalResultTest {

    @Test
    void successIsSuccess() {
        var result = new EvalResult.Success(
                Term.const_(Constant.unit()),
                new ExBudget(100, 50),
                List.of("trace1"));
        assertTrue(result.isSuccess());
    }

    @Test
    void failureIsNotSuccess() {
        var result = new EvalResult.Failure(
                "error term",
                new ExBudget(100, 50),
                List.of());
        assertFalse(result.isSuccess());
    }

    @Test
    void budgetExhaustedIsNotSuccess() {
        var result = new EvalResult.BudgetExhausted(
                new ExBudget(1000, 500),
                List.of("trace"));
        assertFalse(result.isSuccess());
    }

    @Test
    void budgetConsumedOnSuccess() {
        var budget = new ExBudget(100, 50);
        var result = new EvalResult.Success(
                Term.const_(Constant.unit()), budget, List.of());
        assertEquals(budget, result.budgetConsumed());
    }

    @Test
    void budgetConsumedOnFailure() {
        var budget = new ExBudget(75, 30);
        var result = new EvalResult.Failure("err", budget, List.of());
        assertEquals(budget, result.budgetConsumed());
    }

    @Test
    void budgetConsumedOnBudgetExhausted() {
        var budget = new ExBudget(1000, 500);
        var result = new EvalResult.BudgetExhausted(budget, List.of());
        assertEquals(budget, result.budgetConsumed());
    }

    @Test
    void tracesOnSuccess() {
        var traces = List.of("msg1", "msg2");
        var result = new EvalResult.Success(
                Term.const_(Constant.unit()),
                ExBudget.ZERO, traces);
        assertEquals(traces, result.traces());
    }

    @Test
    void tracesOnFailure() {
        var traces = List.of("before error");
        var result = new EvalResult.Failure("err", ExBudget.ZERO, traces);
        assertEquals(traces, result.traces());
    }

    @Test
    void tracesAreImmutable() {
        var result = new EvalResult.Success(
                Term.const_(Constant.unit()),
                ExBudget.ZERO, List.of("msg"));
        assertThrows(UnsupportedOperationException.class,
                () -> result.traces().add("mutate"));
    }

    @Test
    void successResultTerm() {
        var term = Term.const_(Constant.integer(42));
        var result = new EvalResult.Success(term, ExBudget.ZERO, List.of());
        assertEquals(term, result.resultTerm());
    }

    @Test
    void failureErrorMessage() {
        var result = new EvalResult.Failure("division by zero", ExBudget.ZERO, List.of());
        assertEquals("division by zero", result.error());
    }

    @Test
    void nullTermThrows() {
        assertThrows(NullPointerException.class,
                () -> new EvalResult.Success(null, ExBudget.ZERO, List.of()));
    }

    @Test
    void nullErrorThrows() {
        assertThrows(NullPointerException.class,
                () -> new EvalResult.Failure(null, ExBudget.ZERO, List.of()));
    }

    @Test
    void nullBudgetThrows() {
        assertThrows(NullPointerException.class,
                () -> new EvalResult.Success(Term.error(), null, List.of()));
    }
}
