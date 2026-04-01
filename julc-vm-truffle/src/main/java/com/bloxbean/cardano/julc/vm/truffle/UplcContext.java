package com.bloxbean.cardano.julc.vm.truffle;

import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.java.CekValue;
import com.bloxbean.cardano.julc.vm.java.builtins.BuiltinTable;
import com.bloxbean.cardano.julc.vm.java.cost.CostTracker;
import com.bloxbean.cardano.julc.vm.java.trace.BuiltinTraceCollector;
import com.bloxbean.cardano.julc.vm.java.trace.ExecutionTraceCollector;
import com.bloxbean.cardano.julc.vm.trace.BuiltinExecution;
import com.bloxbean.cardano.julc.vm.trace.ExecutionTraceEntry;
import com.bloxbean.cardano.julc.vm.truffle.node.UplcNode;

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
    private final BuiltinTraceCollector builtinTraceCollector;

    public UplcContext(CostTracker costTracker, PlutusLanguage language) {
        this(costTracker, language, false, true);
    }

    public UplcContext(CostTracker costTracker, PlutusLanguage language,
                       boolean tracingEnabled, boolean builtinTraceEnabled) {
        this.costTracker = costTracker;
        this.language = language;
        this.builtinTable = BuiltinTable.forLanguage(language);
        this.executionTraceCollector = tracingEnabled ? new ExecutionTraceCollector(costTracker) : null;
        this.builtinTraceCollector = builtinTraceEnabled ? new BuiltinTraceCollector(20) : null;
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

    /**
     * Record a successful builtin execution.
     * No-op when builtin tracing is disabled.
     */
    public void recordBuiltin(DefaultFun fun, List<CekValue> args, CekValue result) {
        if (builtinTraceCollector != null) {
            builtinTraceCollector.record(fun, args, result);
        }
    }

    /**
     * Record a failed builtin execution.
     * No-op when builtin tracing is disabled.
     */
    public void recordBuiltinError(DefaultFun fun, List<CekValue> args, Exception e) {
        if (builtinTraceCollector != null) {
            builtinTraceCollector.recordError(fun, args, e);
        }
    }

    /**
     * Returns the collected builtin trace entries.
     * Empty list when builtin tracing is disabled.
     */
    public List<BuiltinExecution> getBuiltinTrace() {
        if (builtinTraceCollector == null) return List.of();
        return builtinTraceCollector.getEntries();
    }

    // --- Result capture (for debugger polyglot execution) ---

    private Object capturedResult;

    /**
     * Store the evaluation result for later retrieval by the debugger.
     */
    public void setCapturedResult(Object result) {
        this.capturedResult = result;
    }

    /**
     * Retrieve the captured evaluation result.
     */
    public Object getCapturedResult() {
        return capturedResult;
    }
}
