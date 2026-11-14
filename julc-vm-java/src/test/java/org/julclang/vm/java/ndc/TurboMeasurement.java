package org.julclang.vm.java.ndc;

import com.sun.management.ThreadMXBean;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.julclang.vm.java.JavaVmProvider;
import org.julclang.vm.java.PreparedProgram;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

/**
 * Measures one uplc-turbo script as the scenario prescribes: {@value #WARMUP} unmeasured evaluations, then
 * {@value #SAMPLES} measured ones, of which the best {@value #RETAINED} of each dimension are averaged. The program is
 * checked and costed beforehand ({@link JavaVmProvider#prepare}), so the time is {@link System#nanoTime()} around CEK
 * evaluation alone; the memory is what the evaluating thread allocated
 * ({@link ThreadMXBean#getCurrentThreadAllocatedBytes()}), the JVM's counterpart of an arena's bytes used.
 *
 * @param cpuUs    mean evaluation time in microseconds, to 0.1
 * @param memBytes mean allocated bytes, rounded up
 * @param result   the result of the last evaluation
 */
record TurboMeasurement(double cpuUs, long memBytes, EvalResult result) {

    static final int WARMUP = 5;
    static final int SAMPLES = 50;
    static final int RETAINED = 30;

    /**
     * Unlimited, as the reference reports measure (Amaru uses {@code ExBudget::max}): the scenario page's 10B / 10M
     * would stop the scripts that use more than 10M memory units, whose whole budgets the reference reports carry.
     */
    static final ExBudget BUDGET = new ExBudget(Long.MAX_VALUE, Long.MAX_VALUE);

    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    boolean succeeded() {
        return result instanceof EvalResult.Success;
    }

    ExBudget budget() {
        return switch (result) {
            case EvalResult.Success s -> s.consumed();
            case EvalResult.Failure f -> f.consumed();
            case EvalResult.BudgetExhausted b -> b.consumed();
        };
    }

    /** Evaluates once, unmeasured (JIT warm-up). */
    static EvalResult evaluate(PreparedProgram program) {
        return program.evaluate(BUDGET, UplcEvaluation.OPTIONS);
    }

    static TurboMeasurement measure(PreparedProgram program) {
        for (int i = 0; i < WARMUP; i++) {
            evaluate(program);
        }
        long[] nanos = new long[SAMPLES];
        long[] bytes = new long[SAMPLES];
        EvalResult result = null;
        for (int i = 0; i < SAMPLES; i++) {
            long allocated = THREADS.getCurrentThreadAllocatedBytes();
            long start = System.nanoTime();
            result = evaluate(program);
            nanos[i] = System.nanoTime() - start;
            bytes[i] = THREADS.getCurrentThreadAllocatedBytes() - allocated;
        }
        return new TurboMeasurement(Math.round(meanOfBest(nanos) / 100.0) / 10.0, divideUp(sumOfBest(bytes), RETAINED),
                result);
    }

    /** @return the mean of the {@value #RETAINED} smallest values */
    static double meanOfBest(long[] values) {
        return (double) sumOfBest(values) / Math.min(RETAINED, values.length);
    }

    private static long sumOfBest(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        long sum = 0;
        for (int i = 0; i < Math.min(RETAINED, sorted.length); i++) {
            sum += sorted[i];
        }
        return sum;
    }

    private static long divideUp(long value, long divisor) {
        return (value + divisor - 1) / divisor;
    }
}
