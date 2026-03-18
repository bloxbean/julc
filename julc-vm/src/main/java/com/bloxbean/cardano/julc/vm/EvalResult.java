package com.bloxbean.cardano.julc.vm;

import com.bloxbean.cardano.julc.core.Term;

import java.util.List;
import java.util.Objects;

/**
 * The result of evaluating a UPLC program.
 * <p>
 * Three possible outcomes:
 * <ul>
 *   <li>{@link Success} — evaluation completed, produces a value</li>
 *   <li>{@link Failure} — evaluation failed (error term, type error, etc.)</li>
 *   <li>{@link BudgetExhausted} — evaluation exceeded the allowed budget</li>
 * </ul>
 */
public sealed interface EvalResult {

    /**
     * Successful evaluation.
     *
     * @param resultTerm the evaluated term
     * @param consumed   the budget consumed during evaluation
     * @param traces     trace messages emitted during evaluation
     */
    record Success(Term resultTerm, ExBudget consumed, List<String> traces) implements EvalResult {
        public Success {
            Objects.requireNonNull(resultTerm);
            Objects.requireNonNull(consumed);
            traces = List.copyOf(traces);
        }
    }

    /**
     * Failed evaluation.
     *
     * @param error      description of the failure
     * @param consumed   the budget consumed before failure
     * @param traces     trace messages emitted before failure
     * @param failedTerm the UPLC term that was being evaluated when the error occurred (nullable)
     */
    record Failure(String error, ExBudget consumed, List<String> traces, Term failedTerm) implements EvalResult {
        public Failure {
            Objects.requireNonNull(error);
            Objects.requireNonNull(consumed);
            traces = List.copyOf(traces);
        }

        /** Backward-compatible constructor (no failedTerm). */
        public Failure(String error, ExBudget consumed, List<String> traces) {
            this(error, consumed, traces, null);
        }
    }

    /**
     * Budget exhausted — evaluation exceeded the allowed budget.
     *
     * @param consumed   the budget consumed before exhaustion
     * @param traces     trace messages emitted before exhaustion
     * @param failedTerm the UPLC term that was being evaluated when the budget was exhausted (nullable)
     */
    record BudgetExhausted(ExBudget consumed, List<String> traces, Term failedTerm) implements EvalResult {
        public BudgetExhausted {
            Objects.requireNonNull(consumed);
            traces = List.copyOf(traces);
        }

        /** Backward-compatible constructor (no failedTerm). */
        public BudgetExhausted(ExBudget consumed, List<String> traces) {
            this(consumed, traces, null);
        }
    }

    /** Check if evaluation was successful. */
    default boolean isSuccess() {
        return this instanceof Success;
    }

    /** Get the budget consumed during evaluation (available on all result types). */
    default ExBudget budgetConsumed() {
        return switch (this) {
            case Success s -> s.consumed();
            case Failure f -> f.consumed();
            case BudgetExhausted b -> b.consumed();
        };
    }

    /** Get trace messages (available on all result types). */
    default List<String> traces() {
        return switch (this) {
            case Success s -> s.traces();
            case Failure f -> f.traces();
            case BudgetExhausted b -> b.traces();
        };
    }
}
