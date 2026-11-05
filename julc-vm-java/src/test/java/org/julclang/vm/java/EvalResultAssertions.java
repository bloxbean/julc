package org.julclang.vm.java;

import org.julclang.core.text.UplcPrinter;
import org.julclang.vm.EvalResult;

import static org.junit.jupiter.api.Assertions.*;

/** Compares two evaluations of the same program: result kind, budget, traces, result term and failed term. */
final class EvalResultAssertions {

    private EvalResultAssertions() {
    }

    static void assertSameResult(EvalResult expected, EvalResult actual, String label) {
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
