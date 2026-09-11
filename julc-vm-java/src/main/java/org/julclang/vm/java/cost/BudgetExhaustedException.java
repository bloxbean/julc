package org.julclang.vm.java.cost;

import org.julclang.core.Term;
import org.julclang.vm.java.CekEvaluationException;

/**
 * Thrown when the execution budget (CPU or memory) is exhausted during evaluation.
 */
public class BudgetExhaustedException extends CekEvaluationException {

    public BudgetExhaustedException(String message) {
        super(message);
    }

    public BudgetExhaustedException(String message, Term failedTerm) {
        super(message, failedTerm);
    }
}
