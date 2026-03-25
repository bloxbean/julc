package com.bloxbean.cardano.julc.testkit.jqwik;

import com.bloxbean.cardano.julc.vm.EvalResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects budget data across property test trials for statistical analysis.
 * <p>
 * Thread-safe: all mutating methods are synchronized.
 * Designed for typical property test sizes (up to ~10,000 trials).
 * <p>
 * Usage:
 * <pre>{@code
 * BudgetCollector collector = new BudgetCollector();
 *
 * @Property(tries = 1000)
 * void budgetProperty(@ForAll PubKeyHash pkh) {
 *     var result = ValidatorTest.evaluate(program, ctx);
 *     BudgetAssertions.assertSuccess(result);
 *     collector.record(result);
 * }
 *
 * @AfterProperty
 * void reportBudget() {
 *     System.out.println(collector.summary());
 * }
 * }</pre>
 */
public final class BudgetCollector {

    private final List<Long> cpuValues = new ArrayList<>();
    private final List<Long> memValues = new ArrayList<>();
    private boolean sorted = true;

    /**
     * Record budget data from an evaluation result.
     */
    public synchronized void record(EvalResult result) {
        long cpu = result.budgetConsumed().cpuSteps();
        long mem = result.budgetConsumed().memoryUnits();
        cpuValues.add(cpu);
        memValues.add(mem);
        sorted = false;
    }

    /** Number of recorded trials. */
    public synchronized long count() {
        return cpuValues.size();
    }

    /** Maximum CPU steps across all trials. */
    public synchronized long maxCpu() {
        return cpuValues.stream().mapToLong(Long::longValue).max().orElse(0);
    }

    /** Maximum memory units across all trials. */
    public synchronized long maxMem() {
        return memValues.stream().mapToLong(Long::longValue).max().orElse(0);
    }

    /** Average CPU steps (truncated to long). */
    public synchronized long avgCpu() {
        return (long) cpuValues.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    /** Average memory units (truncated to long). */
    public synchronized long avgMem() {
        return (long) memValues.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    /** 99th percentile CPU steps. */
    public synchronized long p99Cpu() {
        return percentile(cpuValues, 99);
    }

    /** 99th percentile memory units. */
    public synchronized long p99Mem() {
        return percentile(memValues, 99);
    }

    /** Minimum CPU steps across all trials. */
    public synchronized long minCpu() {
        return cpuValues.stream().mapToLong(Long::longValue).min().orElse(0);
    }

    /** Minimum memory units across all trials. */
    public synchronized long minMem() {
        return memValues.stream().mapToLong(Long::longValue).min().orElse(0);
    }

    /**
     * Human-readable summary of budget statistics.
     */
    public synchronized String summary() {
        if (cpuValues.isEmpty()) {
            return "Budget: no trials recorded";
        }
        ensureSorted();
        return String.format(
                "Budget over %d trials:%n" +
                "  CPU: avg=%s, p99=%s, max=%s, min=%s%n" +
                "  Mem: avg=%s, p99=%s, max=%s, min=%s",
                cpuValues.size(),
                formatNumber(avg(cpuValues)), formatNumber(percentile(cpuValues, 99)),
                formatNumber(max(cpuValues)), formatNumber(min(cpuValues)),
                formatNumber(avg(memValues)), formatNumber(percentile(memValues, 99)),
                formatNumber(max(memValues)), formatNumber(min(memValues)));
    }

    private void ensureSorted() {
        if (!sorted) {
            Collections.sort(cpuValues);
            Collections.sort(memValues);
            sorted = true;
        }
    }

    private long percentile(List<Long> values, int pct) {
        if (values.isEmpty()) return 0;
        ensureSorted();
        int index = (int) Math.ceil(pct / 100.0 * values.size()) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }

    private static long max(List<Long> values) {
        long m = Long.MIN_VALUE;
        for (long v : values) if (v > m) m = v;
        return m;
    }

    private static long min(List<Long> values) {
        long m = Long.MAX_VALUE;
        for (long v : values) if (v < m) m = v;
        return m;
    }

    private static long avg(List<Long> values) {
        long sum = 0;
        for (long v : values) sum += v;
        return sum / values.size();
    }

    private static String formatNumber(long n) {
        if (n >= 1_000_000_000) return String.format("%.1fB", n / 1_000_000_000.0);
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }
}
