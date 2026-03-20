package com.bloxbean.cardano.julc.vm.java.cost;

import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.java.CekEvaluationException;

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
