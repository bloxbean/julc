package org.julclang.compiler;

import org.julclang.vm.OptimizationCostProfiles;

/** Exact, fail-closed resolution of public optimizer configuration. */
public final class OptimizationConfiguration {

    private OptimizationConfiguration() {
    }

    /** Apply stable public identifiers to compiler options. */
    public static CompilerOptions apply(
            CompilerOptions options,
            String optimizationProfileId,
            String costProfileId) {
        var level = OptimizationLevel.forProfileId(optimizationProfileId);
        options.setOptimizationLevel(level);
        if (costProfileId != null && !costProfileId.isBlank()) {
            try {
                options.setOptimizationCostProfile(OptimizationCostProfiles.forId(costProfileId));
            } catch (IllegalArgumentException e) {
                throw CompilerTargetDiagnostics.unsupportedOptimizationCostProfile(
                        costProfileId, OptimizationCostProfiles.supportedProfileIds());
            }
        }
        return options;
    }
}
