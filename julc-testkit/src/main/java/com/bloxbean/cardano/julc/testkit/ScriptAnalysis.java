package com.bloxbean.cardano.julc.testkit;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.core.flat.UplcFlatEncoder;
import com.bloxbean.cardano.julc.vm.ExBudget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Analysis of a compiled Plutus script, including size metrics and warnings.
 * <p>
 * The Cardano ledger imposes a 16,384-byte limit on script sizes (FLAT encoding).
 * This record provides visibility into how close a script is to that limit.
 * <p>
 * Usage:
 * <pre>{@code
 * CompileResult result = compiler.compile(source);
 * ScriptAnalysis analysis = ScriptAnalysis.of(result);
 *
 * System.out.println("FLAT size: " + analysis.flatSizeBytes());
 * System.out.println("Exceeds limit: " + analysis.exceedsMaxSize());
 * analysis.warnings().forEach(System.out::println);
 * }</pre>
 *
 * @param flatSizeBytes  FLAT-encoded script size in bytes (on-chain size)
 * @param sampleBudget   execution budget from sample evaluation, or {@code null} if not evaluated
 * @param exceedsMaxSize whether the script exceeds the 16 KB on-chain limit
 * @param warnings       list of warnings about potential issues
 */
public record ScriptAnalysis(
        int flatSizeBytes,
        ExBudget sampleBudget,
        boolean exceedsMaxSize,
        List<String> warnings
) {
    /** Maximum script size in bytes (Cardano protocol parameter). */
    public static final int MAX_SCRIPT_SIZE = 16_384;

    /** Warning threshold — scripts above this size are approaching the limit. */
    public static final int WARNING_THRESHOLD = 12_288; // 12 KB

    public ScriptAnalysis {
        warnings = List.copyOf(warnings);
    }

    /**
     * Analyze a compiled script.
     *
     * @param result the compilation result
     * @return analysis with size metrics and warnings
     */
    public static ScriptAnalysis of(CompileResult result) {
        int flatSize = result.scriptSizeBytes();
        boolean exceeds = flatSize > MAX_SCRIPT_SIZE;

        var warnings = new ArrayList<String>();
        if (exceeds) {
            warnings.add("Script size (" + flatSize + " bytes) exceeds the 16 KB on-chain limit ("
                    + MAX_SCRIPT_SIZE + " bytes). The script cannot be submitted to the Cardano network.");
        } else if (flatSize > WARNING_THRESHOLD) {
            warnings.add("Script size (" + flatSize + " bytes) approaches the 16 KB on-chain limit. "
                    + "Consider optimizing to leave room for future changes.");
        }

        return new ScriptAnalysis(flatSize, null, exceeds, warnings);
    }

    /**
     * Analyze a compiled script with a sample execution budget.
     *
     * @param result       the compilation result
     * @param sampleBudget budget consumed during a sample evaluation
     * @return analysis with size metrics, budget, and warnings
     */
    public static ScriptAnalysis of(CompileResult result, ExBudget sampleBudget) {
        var base = of(result);
        return new ScriptAnalysis(base.flatSizeBytes, sampleBudget, base.exceedsMaxSize, base.warnings);
    }

    /**
     * Human-readable FLAT size, e.g. "342 B" or "14.2 KB".
     */
    public String flatSizeFormatted() {
        if (flatSizeBytes < 1024) return flatSizeBytes + " B";
        double kb = flatSizeBytes / 1024.0;
        if (kb < 10) return String.format("%.1f KB", kb);
        return String.format("%.0f KB", kb);
    }
}
