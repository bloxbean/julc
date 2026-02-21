package com.bloxbean.cardano.julc.analysis;

import java.util.Set;

/**
 * Configuration for the security analysis.
 *
 * @param enableRules    run rule-based detectors (default: true)
 * @param enableAi       run AI analysis (default: true if analyzer configured)
 * @param skipCategories categories to ignore in results
 */
public record AnalysisOptions(
        boolean enableRules,
        boolean enableAi,
        Set<Category> skipCategories
) {
    public static AnalysisOptions defaults() {
        return new AnalysisOptions(true, true, Set.of());
    }

    public static AnalysisOptions rulesOnly() {
        return new AnalysisOptions(true, false, Set.of());
    }
}
