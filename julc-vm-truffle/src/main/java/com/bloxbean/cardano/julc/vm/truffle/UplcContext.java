package com.bloxbean.cardano.julc.vm.truffle;

import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.java.builtins.BuiltinTable;
import com.bloxbean.cardano.julc.vm.java.cost.CostTracker;
import com.bloxbean.cardano.julc.vm.truffle.node.UplcNode;
import com.bloxbean.cardano.julc.vm.java.trace.ExecutionTraceCollector;
import com.bloxbean.cardano.julc.vm.trace.ExecutionTraceEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Truffle execution context — holds the cost tracker, trace list, and language version.
 * <p>
 * One context is created per evaluation. All nodes access it to charge costs
 * and record trace messages.
 */
public final class UplcContext {

    private final CostTracker costTracker;
    private final PlutusLanguage language;
    private final BuiltinTable.VersionedBuiltinTable builtinTable;
    private final List<String> traces = new ArrayList<>();
    private final ExecutionTraceCollector executionTraceCollector;

    public UplcContext(CostTracker costTracker, PlutusLanguage language) {
        this(costTracker, language, false);
    }

    public UplcContext(CostTracker costTracker, PlutusLanguage language, boolean tracingEnabled) {
        this.costTracker = costTracker;
        this.language = language;
        this.builtinTable = BuiltinTable.forLanguage(language);
        this.executionTraceCollector = tracingEnabled ? new ExecutionTraceCollector(costTracker) : null;
    }

    public CostTracker getCostTracker() {
        return costTracker;
    }

    public PlutusLanguage getLanguage() {
        return language;
    }

    public BuiltinTable.VersionedBuiltinTable getBuiltinTable() {
        return builtinTable;
    }

    public void addTrace(String message) {
        traces.add(message);
    }

    public List<String> getTraces() {
        return List.copyOf(traces);
    }

    /**
     * Record an execution trace entry for the given node.
     * No-op when tracing is disabled or the node has no source location.
     */
    public void recordTrace(UplcNode node, String nodeType) {
        if (executionTraceCollector != null) {
            executionTraceCollector.record(node.getSourceLocation(), nodeType);
        }
    }

    /**
     * Returns the collected execution trace entries.
     * Empty list when tracing is disabled.
     */
    public List<ExecutionTraceEntry> getExecutionTrace() {
        if (executionTraceCollector == null) return List.of();
        return executionTraceCollector.getEntries();
    }
}
