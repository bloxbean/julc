package com.bloxbean.cardano.julc.compiler;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Explicit optimizer rollout level, independent from compiler-target legality.
 */
public enum OptimizationLevel {
    /** Disable optimizer rewrites; final target validation still runs. */
    NONE("none", false, false, false),

    /** Preserve the optimizer and lowering behavior that predates ADR-032. */
    BASELINE("baseline", true, false, false),

    /** Baseline plus reviewed PV11 rules that do not depend on input-size costs. */
    PV11_SAFE("pv11-safe", true, true, false),

    /** PV11_SAFE plus rules justified by an explicit pinned cost profile. */
    PV11_COSTED("pv11-costed", true, true, true);

    private final String profileId;
    private final boolean baselineOptimizerEnabled;
    private final boolean pv11SafeRulesEnabled;
    private final boolean costProfileRequired;

    OptimizationLevel(
            String profileId,
            boolean baselineOptimizerEnabled,
            boolean pv11SafeRulesEnabled,
            boolean costProfileRequired) {
        this.profileId = profileId;
        this.baselineOptimizerEnabled = baselineOptimizerEnabled;
        this.pv11SafeRulesEnabled = pv11SafeRulesEnabled;
        this.costProfileRequired = costProfileRequired;
    }

    /** Stable, exact identifier used by public compiler entry points. */
    public String profileId() {
        return profileId;
    }

    /** Resolve an exact, case-sensitive identifier without aliases or fallback. */
    public static OptimizationLevel forProfileId(String profileId) {
        Objects.requireNonNull(profileId, "profileId");
        return Arrays.stream(values())
                .filter(level -> level.profileId.equals(profileId))
                .findFirst()
                .orElseThrow(() -> CompilerTargetDiagnostics.unsupportedOptimizationLevel(
                        profileId, supportedProfileIds()));
    }

    public static List<String> supportedProfileIds() {
        return Arrays.stream(values()).map(OptimizationLevel::profileId).toList();
    }

    public boolean baselineOptimizerEnabled() {
        return baselineOptimizerEnabled;
    }

    public boolean pv11SafeRulesEnabled() {
        return pv11SafeRulesEnabled;
    }

    public boolean costProfileRequired() {
        return costProfileRequired;
    }
}
