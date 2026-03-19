package com.bloxbean.cardano.julc.vm;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.core.source.SourceMap;
import com.bloxbean.cardano.julc.vm.trace.ExecutionTraceEntry;

import java.util.List;

/**
 * Service Provider Interface for Plutus VM backends.
 * <p>
 * Implementations are discovered via {@link java.util.ServiceLoader}. Multiple
 * backends can coexist; the one with the highest {@link #priority()} is selected
 * by default.
 * <p>
 * Current implementations:
 * <ul>
 *   <li>{@code plutus-vm-scalus} — wraps the Scalus CEK machine (priority 50)</li>
 *   <li>{@code plutus-vm-java} — pure Java CEK machine (future, priority 100)</li>
 * </ul>
 *
 * @see JulcVm
 */
public interface JulcVmProvider {

    /**
     * Evaluate a UPLC program.
     *
     * @param program  the UPLC program to evaluate
     * @param language the Plutus language version
     * @param budget   the maximum allowed budget (null for unlimited)
     * @return the evaluation result
     */
    EvalResult evaluate(Program program, PlutusLanguage language, ExBudget budget);

    /**
     * Evaluate a UPLC program applied to the given arguments.
     * <p>
     * Arguments are applied as {@code PlutusData} constants. For V1/V2 scripts,
     * this is typically [datum, redeemer, scriptContext]. For V3, it's [scriptContext].
     *
     * @param program  the UPLC program to evaluate
     * @param language the Plutus language version
     * @param args     the arguments to apply (as PlutusData)
     * @param budget   the maximum allowed budget (null for unlimited)
     * @return the evaluation result
     */
    EvalResult evaluateWithArgs(Program program, PlutusLanguage language,
                                List<PlutusData> args, ExBudget budget);

    /**
     * Set the cost model parameter values for the current language version.
     * Values must be in the canonical order (matching the on-chain costModels array).
     * <p>
     * If not called, the provider uses its built-in default cost model.
     *
     * @param costModelValues       ordered array of cost model parameter values
     * @param protocolMajorVersion  the protocol major version (e.g. 9 for Chang, 10 for Plomin)
     */
    default void setCostModelParams(long[] costModelValues, int protocolMajorVersion) {
        // Default: ignore (use built-in defaults)
    }

    /**
     * Set the source map for debugging support.
     * When set, evaluation errors can include the originating Java source location,
     * and execution tracing can map CEK steps back to Java source lines.
     * <p>
     * Providers that do not support source maps ignore this call.
     *
     * @param sourceMap the source map from compilation (nullable — pass null to disable)
     */
    default void setSourceMap(SourceMap sourceMap) {
        // Default: ignore (provider does not support source maps)
    }

    /**
     * Enable or disable execution tracing.
     * When enabled (and a source map is set), each statement-level CEK step
     * is recorded with its Java source location.
     * <p>
     * Providers that do not support tracing ignore this call.
     *
     * @param enabled true to enable tracing, false to disable
     */
    default void setTracingEnabled(boolean enabled) {
        // Default: ignore (provider does not support tracing)
    }

    /**
     * Returns the execution trace from the most recent evaluation.
     * Empty list if tracing was disabled, no source map was set, or
     * the provider does not support tracing.
     */
    default List<ExecutionTraceEntry> getLastExecutionTrace() {
        return List.of();
    }

    /** The name of this provider (for logging/debugging). */
    String name();

    /**
     * Priority: higher values are preferred when multiple providers are available.
     * <p>
     * Convention: Scalus backend = 50, Pure Java backend = 100.
     */
    int priority();
}
