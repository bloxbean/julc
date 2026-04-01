package com.bloxbean.cardano.julc.vm;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;

import java.util.List;

/**
 * Service Provider Interface for Plutus VM backends.
 * <p>
 * Implementations are discovered via {@link java.util.ServiceLoader}. Multiple
 * backends can coexist; the one with the highest {@link #priority()} is selected
 * by default.
 * <p>
 * Per-evaluation configuration (source maps, tracing) is passed via {@link EvalOptions}
 * — providers hold no mutable per-evaluation state.
 *
 * @see JulcVm
 */
public interface JulcVmProvider {

    /**
     * Evaluate a UPLC program with per-evaluation options.
     *
     * @param program  the UPLC program to evaluate
     * @param language the Plutus language version
     * @param budget   the maximum allowed budget (null for unlimited)
     * @param options  per-evaluation configuration (source map, tracing flags)
     * @return the evaluation result (including traces)
     */
    EvalResult evaluate(Program program, PlutusLanguage language, ExBudget budget, EvalOptions options);

    /**
     * Evaluate a UPLC program applied to the given arguments, with per-evaluation options.
     *
     * @param program  the UPLC program to evaluate
     * @param language the Plutus language version
     * @param args     the arguments to apply (as PlutusData)
     * @param budget   the maximum allowed budget (null for unlimited)
     * @param options  per-evaluation configuration (source map, tracing flags)
     * @return the evaluation result (including traces)
     */
    EvalResult evaluateWithArgs(Program program, PlutusLanguage language,
                                List<PlutusData> args, ExBudget budget, EvalOptions options);

    /**
     * Evaluate a UPLC program with default options.
     */
    default EvalResult evaluate(Program program, PlutusLanguage language, ExBudget budget) {
        return evaluate(program, language, budget, EvalOptions.DEFAULT);
    }

    /**
     * Evaluate a UPLC program applied to the given arguments, with default options.
     */
    default EvalResult evaluateWithArgs(Program program, PlutusLanguage language,
                                        List<PlutusData> args, ExBudget budget) {
        return evaluateWithArgs(program, language, args, budget, EvalOptions.DEFAULT);
    }

    /**
     * Set the cost model parameter values for a specific Plutus language version.
     * Values must be in the canonical order (matching the on-chain costModels array).
     * <p>
     * If not called, the provider uses its built-in default cost model.
     * <p>
     * Mixed-version transactions require calling this once per language version
     * present in the transaction (V1, V2, and/or V3).
     *
     * @param costModelValues       ordered array of cost model parameter values
     * @param language              the Plutus language version these parameters are for
     * @param protocolMajorVersion  the protocol major version (e.g. 9 for Chang, 10 for Plomin)
     * @param protocolMinorVersion  the protocol minor version
     */
    default void setCostModelParams(long[] costModelValues, PlutusLanguage language,
                                    int protocolMajorVersion, int protocolMinorVersion) {
        // Default: ignore (use built-in defaults)
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
