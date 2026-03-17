package com.bloxbean.cardano.julc.vm.java.cost;

import com.bloxbean.cardano.julc.vm.java.CekEvaluationException;

/**
 * Thrown when the execution budget (CPU or memory) is exhausted during evaluation.
 */
public class BudgetExhaustedException extends CekEvaluationException {

    public BudgetExhaustedException(String message) {
        super(message);
    }
}
