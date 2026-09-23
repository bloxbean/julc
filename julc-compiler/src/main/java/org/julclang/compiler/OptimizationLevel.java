package org.julclang.compiler;

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

    /** PV11_SAFE plus opt-in structural rules supported by pinned benchmark evidence. */
    PV11_COSTED("pv11-costed", true, true, true);

    /** Default rollout selected when a public compiler entry point omits the level. */
    public static final OptimizationLevel DEFAULT = PV11_SAFE;

    /** Stable profile identifier corresponding to {@link #DEFAULT}. */
    public static final String DEFAULT_PROFILE_ID = "pv11-safe";

    private final String profileId;
    private final boolean baselineOptimizerEnabled;
    private final boolean pv11SafeRulesEnabled;
    private final boolean pv11CostedRulesEnabled;

    OptimizationLevel(
            String profileId,
            boolean baselineOptimizerEnabled,
            boolean pv11SafeRulesEnabled,
            boolean pv11CostedRulesEnabled) {
        this.profileId = profileId;
        this.baselineOptimizerEnabled = baselineOptimizerEnabled;
        this.pv11SafeRulesEnabled = pv11SafeRulesEnabled;
        this.pv11CostedRulesEnabled = pv11CostedRulesEnabled;
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

    /**
     * @deprecated No current level requires numeric costs. Future numeric consumers must
     * require their profile individually, independently of rollout selection.
     */
    @Deprecated(forRemoval = false)
    public boolean costProfileRequired() {
        return false;
    }

    /**
     * Whether opt-in structural rules (ADR-043 O9) are enabled. Their benchmark
     * evidence uses pinned costs, but compilation does not read a cost model.
     */
    public boolean pv11CostedRulesEnabled() {
        return pv11CostedRulesEnabled;
    }
}
