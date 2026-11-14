package org.julclang.vm.java.ndc;

/**
 * The site's acceptance rule for one uplc-turbo benchmark ({@code scenarios/uplc-turbo/build.py}). One CPU unit is
 * taken as one picosecond and one memory unit as one byte:
 * <ul>
 *   <li>CPU passes when {@code cpu_us} is at most twice the budget's time ({@code budget_cpu / 1e6} us), or within
 *       {@value #CPU_TOLERANCE_US} us of it;</li>
 *   <li>memory passes when {@code mem_bytes} is at most twice {@code budget_mem}, or within
 *       {@value #MEMORY_TOLERANCE_BYTES} bytes of it.</li>
 * </ul>
 * The site also requires the budgets to agree with the majority of nodes; {@link UplcTurboNdcScenario} checks them
 * against cardano-node's.
 */
final class TurboScore {

    static final double DEVIATION_LIMIT = 1.0;
    static final double CPU_TOLERANCE_US = 200;
    static final long MEMORY_TOLERANCE_BYTES = 320 * 1024;

    private TurboScore() {
    }

    /** @return {@code (cpu_us - budget) / budget}, the budget in microseconds */
    static double cpuDelta(double cpuUs, long budgetCpu) {
        double budgetUs = budgetCpu / 1_000_000.0;
        return (cpuUs - budgetUs) / budgetUs;
    }

    static double memDelta(long memBytes, long budgetMem) {
        return (double) (memBytes - budgetMem) / budgetMem;
    }

    static boolean cpuPasses(double cpuUs, long budgetCpu) {
        if (budgetCpu <= 0) {
            return true;
        }
        double budgetUs = budgetCpu / 1_000_000.0;
        return cpuDelta(cpuUs, budgetCpu) <= DEVIATION_LIMIT || Math.abs(cpuUs - budgetUs) <= CPU_TOLERANCE_US;
    }

    static boolean memPasses(long memBytes, long budgetMem) {
        if (budgetMem <= 0) {
            return true;
        }
        return memDelta(memBytes, budgetMem) <= DEVIATION_LIMIT
                || Math.abs(memBytes - budgetMem) <= MEMORY_TOLERANCE_BYTES;
    }
}
