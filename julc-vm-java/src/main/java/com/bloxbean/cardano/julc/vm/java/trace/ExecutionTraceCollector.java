package com.bloxbean.cardano.julc.vm.java.trace;

import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.bloxbean.cardano.julc.vm.java.cost.CostTracker;
import com.bloxbean.cardano.julc.vm.trace.ExecutionTraceEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects execution trace entries during UPLC evaluation.
 * <p>
 * Consecutive entries for the same file+line are deduplicated — the CEK machine
 * often hits the same source line multiple times (e.g., partial builtin application).
 * When deduplicating, budget deltas are <em>accumulated</em> into the existing entry
 * so no cost is lost.
 * <p>
 * When a {@link CostTracker} is provided, each entry captures the CPU/memory delta
 * since the previous trace point.
 */
public final class ExecutionTraceCollector {

    private final List<ExecutionTraceEntry> entries = new ArrayList<>();
    private final CostTracker costTracker;
    private String lastFileName;
    private int lastLine;
    private long lastCpu;
    private long lastMem;

    /**
     * Create a collector without budget tracking (backward-compatible).
     */
    public ExecutionTraceCollector() {
        this(null);
    }

    /**
     * Create a collector with budget delta tracking.
     *
     * @param costTracker the cost tracker to snapshot (nullable)
     */
    public ExecutionTraceCollector(CostTracker costTracker) {
        this.costTracker = costTracker;
        if (costTracker != null) {
            this.lastCpu = costTracker.cpuConsumed();
            this.lastMem = costTracker.memConsumed();
        }
    }

    /**
     * Record a trace entry if the source location differs from the previous entry.
     * When the same file:line repeats consecutively, the budget delta is accumulated
     * into the existing entry.
     *
     * @param loc      the source location (nullable — skipped if null)
     * @param nodeType the CEK step type ("Apply", "Force", "Case", "Error")
     */
    public void record(SourceLocation loc, String nodeType) {
        if (loc == null) return;

        long cpuDelta = 0, memDelta = 0;
        if (costTracker != null) {
            long nowCpu = costTracker.cpuConsumed();
            long nowMem = costTracker.memConsumed();
            cpuDelta = nowCpu - lastCpu;
            memDelta = nowMem - lastMem;
            lastCpu = nowCpu;
            lastMem = nowMem;
        }

        if (loc.fileName().equals(lastFileName) && loc.line() == lastLine) {
            // Accumulate budget into existing entry
            if (cpuDelta > 0 || memDelta > 0) {
                int lastIdx = entries.size() - 1;
                var prev = entries.get(lastIdx);
                entries.set(lastIdx, new ExecutionTraceEntry(
                        prev.fileName(), prev.line(), prev.fragment(), prev.nodeType(),
                        prev.cpuDelta() + cpuDelta, prev.memDelta() + memDelta));
            }
            return;
        }
        lastFileName = loc.fileName();
        lastLine = loc.line();
        entries.add(new ExecutionTraceEntry(loc.fileName(), loc.line(), loc.fragment(), nodeType,
                cpuDelta, memDelta));
    }

    /**
     * Returns the collected trace entries (unmodifiable).
     */
    public List<ExecutionTraceEntry> getEntries() {
        return List.copyOf(entries);
    }
}
