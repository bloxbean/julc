package com.bloxbean.cardano.julc.vm;

/**
 * Execution budget tracking CPU steps and memory units.
 * <p>
 * On Cardano, every Plutus script evaluation consumes a measurable amount of
 * CPU and memory. The {@link ExBudget} records these costs. Protocol parameters
 * define maximum per-transaction and per-script budgets.
 *
 * @param cpuSteps    CPU steps consumed
 * @param memoryUnits memory units consumed
 */
public record ExBudget(long cpuSteps, long memoryUnits) {

    /** An empty budget (zero cost). */
    public static final ExBudget ZERO = new ExBudget(0, 0);

    /** Add two budgets together. Uses saturating arithmetic to prevent overflow. */
    public ExBudget add(ExBudget other) {
        return new ExBudget(
                saturateAdd(cpuSteps, other.cpuSteps),
                saturateAdd(memoryUnits, other.memoryUnits));
    }

    /** Check if either dimension is negative (indicates exhaustion). */
    public boolean isExhausted() {
        return cpuSteps < 0 || memoryUnits < 0;
    }

    private static long saturateAdd(long a, long b) {
        long result = a + b;
        // Positive overflow: both positive but result negative
        if (a > 0 && b > 0 && result < 0) return Long.MAX_VALUE;
        // Negative underflow: both negative but result positive
        if (a < 0 && b < 0 && result > 0) return Long.MIN_VALUE;
        return result;
    }

    @Override
    public String toString() {
        return "ExBudget{cpu=" + cpuSteps + ", mem=" + memoryUnits + "}";
    }
}
