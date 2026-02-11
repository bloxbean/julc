package com.bloxbean.cardano.julc.vm;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.Term;

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

    /** The name of this provider (for logging/debugging). */
    String name();

    /**
     * Priority: higher values are preferred when multiple providers are available.
     * <p>
     * Convention: Scalus backend = 50, Pure Java backend = 100.
     */
    int priority();
}
