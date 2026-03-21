package com.bloxbean.julc.cli.check;

import com.bloxbean.cardano.julc.vm.ExBudget;

import java.util.List;

/**
 * Result of running a single test method.
 */
public record TestResult(
        String className,
        String methodName,
        boolean passed,
        ExBudget budget,
        List<String> traces,
        String error
) {
    public static TestResult pass(String className, String methodName, ExBudget budget, List<String> traces) {
        return new TestResult(className, methodName, true, budget, traces, null);
    }

    public static TestResult fail(String className, String methodName, ExBudget budget, List<String> traces, String error) {
        return new TestResult(className, methodName, false, budget, traces, error);
    }
}
