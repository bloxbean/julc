package com.bloxbean.cardano.julc.vm.trace;

import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.bloxbean.cardano.julc.core.source.SourceMap;
import com.bloxbean.cardano.julc.vm.EvalResult;

import java.util.List;

/**
 * Builds a {@link FailureReport} from an {@link EvalResult} and optional diagnostic traces.
 */
public final class FailureReportBuilder {

    private FailureReportBuilder() {}

    /**
     * Build a FailureReport from a failed evaluation result.
     *
     * @param result         the failed EvalResult (Failure or BudgetExhausted)
     * @param sourceMap      the source map for resolving error locations (nullable)
     * @param executionTrace the execution trace entries (nullable or empty)
     * @param builtinTrace   the last N builtin executions (nullable or empty)
     * @return the structured failure report, or null if the result is a Success
     */
    public static FailureReport build(EvalResult result, SourceMap sourceMap,
                                       List<ExecutionTraceEntry> executionTrace,
                                       List<BuiltinExecution> builtinTrace) {
        if (result instanceof EvalResult.Success) return null;

        String errorMessage = switch (result) {
            case EvalResult.Failure f -> f.error();
            case EvalResult.BudgetExhausted _ -> "Budget exhausted";
            case EvalResult.Success _ -> throw new IllegalStateException();
        };

        // Resolve source location from failed term
        SourceLocation location = null;
        if (sourceMap != null) {
            var failedTerm = switch (result) {
                case EvalResult.Failure f -> f.failedTerm();
                case EvalResult.BudgetExhausted b -> b.failedTerm();
                case EvalResult.Success _ -> null;
            };
            if (failedTerm != null) {
                location = sourceMap.lookup(failedTerm);
            }
        }

        return new FailureReport(
                errorMessage,
                location,
                builtinTrace != null ? builtinTrace : List.of(),
                executionTrace != null ? executionTrace : List.of(),
                result.budgetConsumed(),
                result.traces()
        );
    }

    /**
     * Build a FailureReport with only a source map (no builtin/execution traces).
     */
    public static FailureReport build(EvalResult result, SourceMap sourceMap) {
        return build(result, sourceMap, List.of(), List.of());
    }

    /**
     * Build a FailureReport with no source map or traces.
     */
    public static FailureReport build(EvalResult result) {
        return build(result, null, List.of(), List.of());
    }
}
