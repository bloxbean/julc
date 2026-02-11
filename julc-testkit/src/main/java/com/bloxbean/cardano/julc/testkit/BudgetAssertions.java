package com.bloxbean.cardano.julc.testkit;

import com.bloxbean.cardano.julc.vm.EvalResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Budget-related assertions for Plutus evaluation results.
 * <p>
 * Provides static assertion methods for verifying execution budget,
 * success/failure status, and trace messages.
 * <p>
 * Usage:
 * <pre>{@code
 * EvalResult result = ValidatorTest.evaluate(program, args);
 *
 * BudgetAssertions.assertSuccess(result);
 * BudgetAssertions.assertBudgetUnder(result, 1_000_000, 500_000);
 * BudgetAssertions.assertTrace(result, "expected log message");
 * }</pre>
 */
public final class BudgetAssertions {

    private BudgetAssertions() {
        // utility class
    }

    /**
     * Assert that the evaluation result is a success.
     *
     * @param result the evaluation result
     * @throws AssertionError if the result is not a success
     */
    public static void assertSuccess(EvalResult result) {
        Objects.requireNonNull(result, "result must not be null");
        if (!result.isSuccess()) {
            throw new AssertionError("Expected success but got: " + describeResult(result));
        }
    }

    /**
     * Assert that the evaluation result is a failure (error or budget exhaustion).
     *
     * @param result the evaluation result
     * @throws AssertionError if the result is a success
     */
    public static void assertFailure(EvalResult result) {
        Objects.requireNonNull(result, "result must not be null");
        if (result.isSuccess()) {
            throw new AssertionError("Expected failure but got: " + describeResult(result));
        }
    }

    /**
     * Assert that the budget consumed by the evaluation is within the given limits.
     * <p>
     * Both CPU steps and memory units must be at or below the specified maximums.
     * This assertion works regardless of whether the evaluation succeeded or failed.
     *
     * @param result the evaluation result
     * @param maxCpu the maximum allowed CPU steps
     * @param maxMem the maximum allowed memory units
     * @throws AssertionError if the budget exceeds either limit
     */
    public static void assertBudgetUnder(EvalResult result, long maxCpu, long maxMem) {
        Objects.requireNonNull(result, "result must not be null");
        var consumed = result.budgetConsumed();
        var errors = new ArrayList<String>();
        if (consumed.cpuSteps() > maxCpu) {
            errors.add("CPU steps " + consumed.cpuSteps() + " exceeds maximum " + maxCpu);
        }
        if (consumed.memoryUnits() > maxMem) {
            errors.add("Memory units " + consumed.memoryUnits() + " exceeds maximum " + maxMem);
        }
        if (!errors.isEmpty()) {
            throw new AssertionError("Budget exceeded: " + String.join("; ", errors)
                    + " (consumed: " + consumed + ")");
        }
    }

    /**
     * Assert that the trace messages from evaluation contain all expected strings.
     * <p>
     * Each expected message must appear as a substring in at least one trace entry.
     * Order does not matter. This assertion works regardless of whether the
     * evaluation succeeded or failed.
     *
     * @param result           the evaluation result
     * @param expectedMessages the expected trace message substrings
     * @throws AssertionError if any expected message is not found in the traces
     */
    public static void assertTrace(EvalResult result, String... expectedMessages) {
        Objects.requireNonNull(result, "result must not be null");
        List<String> traces = result.traces();
        var missing = new ArrayList<String>();
        for (String expected : expectedMessages) {
            boolean found = traces.stream().anyMatch(t -> t.contains(expected));
            if (!found) {
                missing.add(expected);
            }
        }
        if (!missing.isEmpty()) {
            throw new AssertionError("Expected trace messages not found: " + missing
                    + "\nActual traces: " + traces);
        }
    }

    /**
     * Assert that the trace messages contain exactly the expected messages in order.
     *
     * @param result           the evaluation result
     * @param expectedMessages the expected trace messages (exact match)
     * @throws AssertionError if the traces do not match exactly
     */
    public static void assertTraceExact(EvalResult result, String... expectedMessages) {
        Objects.requireNonNull(result, "result must not be null");
        List<String> traces = result.traces();
        List<String> expected = List.of(expectedMessages);
        if (!traces.equals(expected)) {
            throw new AssertionError("Expected exact traces: " + expected
                    + "\nActual traces: " + traces);
        }
    }

    /**
     * Assert that the evaluation produced no trace messages.
     *
     * @param result the evaluation result
     * @throws AssertionError if any trace messages are present
     */
    public static void assertNoTraces(EvalResult result) {
        Objects.requireNonNull(result, "result must not be null");
        if (!result.traces().isEmpty()) {
            throw new AssertionError("Expected no trace messages but got: " + result.traces());
        }
    }

    private static String describeResult(EvalResult result) {
        return switch (result) {
            case EvalResult.Success s ->
                    "Success{term=" + s.resultTerm() + ", budget=" + s.consumed() + "}";
            case EvalResult.Failure f ->
                    "Failure{error=" + f.error() + ", budget=" + f.consumed() + "}";
            case EvalResult.BudgetExhausted b ->
                    "BudgetExhausted{budget=" + b.consumed() + "}";
        };
    }
}
