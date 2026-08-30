package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.vm.OptimizationCostProfile;

import java.util.List;
import java.util.Objects;

/** Deterministic optimization provenance retained with a compilation result. */
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
