package org.julclang.compiler;

import org.julclang.vm.OptimizationCostProfile;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic optimization provenance retained with a compilation result.
 * Cost identity/hash describe an actual numeric dependency, not an unused option.
 * Both are absent for every currently shipped compiler rule.
 */
public record OptimizationReport(
        OptimizationLevel level,
        String costProfileId,
        String costParameterHash,
        List<String> appliedRules) {

    public OptimizationReport {
        Objects.requireNonNull(level, "level");
        appliedRules = List.copyOf(appliedRules);
        if ((costProfileId == null) != (costParameterHash == null)) {
            throw new IllegalArgumentException(
                    "costProfileId and costParameterHash must both be present or absent");
        }
    }

    /** Build version of the compiler distribution producing this report. */
    public String compilerVersion() {
        return CompilerVersion.VERSION;
    }

    public static OptimizationReport baseline() {
        return new OptimizationReport(OptimizationLevel.BASELINE, null, null, List.of());
    }

    static OptimizationReport of(
            OptimizationLevel level,
            OptimizationCostProfile costProfile,
            List<String> appliedRules) {
        return new OptimizationReport(
                level,
                costProfile != null ? costProfile.profileId() : null,
                costProfile != null ? costProfile.parameterHash() : null,
                appliedRules);
    }
}
