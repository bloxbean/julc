package com.bloxbean.cardano.julc.vm;

import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.vm.trace.BuiltinExecution;
import com.bloxbean.cardano.julc.vm.trace.ExecutionTraceEntry;

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
 * <p>
 * Each result variant carries execution and builtin traces captured during evaluation,
 * making results self-contained and thread-safe. Traces are empty lists when tracing
 * is disabled or the provider does not support tracing.
 */
public sealed interface EvalResult {

    /**
     * Successful evaluation.
     *
     * @param resultTerm     the evaluated term
     * @param consumed       the budget consumed during evaluation
     * @param traces         trace messages emitted during evaluation
     * @param executionTrace per-step execution trace entries (empty if tracing disabled)
     * @param builtinTrace   last N builtin executions (empty if not supported)
     */
    record Success(Term resultTerm, ExBudget consumed, List<String> traces,
                   List<ExecutionTraceEntry> executionTrace,
                   List<BuiltinExecution> builtinTrace) implements EvalResult {
        public Success {
            Objects.requireNonNull(resultTerm);
            Objects.requireNonNull(consumed);
            traces = List.copyOf(traces);
            executionTrace = List.copyOf(executionTrace);
            builtinTrace = List.copyOf(builtinTrace);
        }

        /** Backward-compatible constructor (no trace fields). */
        public Success(Term resultTerm, ExBudget consumed, List<String> traces) {
            this(resultTerm, consumed, traces, List.of(), List.of());
        }
    }

    /**
     * Failed evaluation.
     *
     * @param error          description of the failure
     * @param consumed       the budget consumed before failure
     * @param traces         trace messages emitted before failure
     * @param failedTerm     the UPLC term that was being evaluated when the error occurred (nullable)
     * @param executionTrace per-step execution trace entries (empty if tracing disabled)
     * @param builtinTrace   last N builtin executions (empty if not supported)
     */
    record Failure(String error, ExBudget consumed, List<String> traces, Term failedTerm,
                   List<ExecutionTraceEntry> executionTrace,
                   List<BuiltinExecution> builtinTrace) implements EvalResult {
        public Failure {
            Objects.requireNonNull(error);
            Objects.requireNonNull(consumed);
            traces = List.copyOf(traces);
            executionTrace = List.copyOf(executionTrace);
            builtinTrace = List.copyOf(builtinTrace);
        }

        /** Backward-compatible constructor (no failedTerm or trace fields). */
        public Failure(String error, ExBudget consumed, List<String> traces) {
            this(error, consumed, traces, null, List.of(), List.of());
        }

        /** Backward-compatible constructor (no trace fields). */
        public Failure(String error, ExBudget consumed, List<String> traces, Term failedTerm) {
            this(error, consumed, traces, failedTerm, List.of(), List.of());
        }
    }

    /**
     * Budget exhausted — evaluation exceeded the allowed budget.
     *
     * @param consumed       the budget consumed before exhaustion
     * @param traces         trace messages emitted before exhaustion
     * @param failedTerm     the UPLC term that was being evaluated when the budget was exhausted (nullable)
     * @param executionTrace per-step execution trace entries (empty if tracing disabled)
     * @param builtinTrace   last N builtin executions (empty if not supported)
     */
    record BudgetExhausted(ExBudget consumed, List<String> traces, Term failedTerm,
                           List<ExecutionTraceEntry> executionTrace,
                           List<BuiltinExecution> builtinTrace) implements EvalResult {
        public BudgetExhausted {
            Objects.requireNonNull(consumed);
            traces = List.copyOf(traces);
            executionTrace = List.copyOf(executionTrace);
            builtinTrace = List.copyOf(builtinTrace);
        }

        /** Backward-compatible constructor (no failedTerm or trace fields). */
        public BudgetExhausted(ExBudget consumed, List<String> traces) {
            this(consumed, traces, null, List.of(), List.of());
        }

        /** Backward-compatible constructor (no trace fields). */
        public BudgetExhausted(ExBudget consumed, List<String> traces, Term failedTerm) {
            this(consumed, traces, failedTerm, List.of(), List.of());
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

    /** Get the execution trace from this evaluation (empty if tracing was disabled). */
    default List<ExecutionTraceEntry> executionTrace() {
        return switch (this) {
            case Success s -> s.executionTrace();
            case Failure f -> f.executionTrace();
            case BudgetExhausted b -> b.executionTrace();
        };
    }

    /** Get the builtin trace from this evaluation (empty if not supported by provider). */
    default List<BuiltinExecution> builtinTrace() {
        return switch (this) {
            case Success s -> s.builtinTrace();
            case Failure f -> f.builtinTrace();
            case BudgetExhausted b -> b.builtinTrace();
        };
    }
}
