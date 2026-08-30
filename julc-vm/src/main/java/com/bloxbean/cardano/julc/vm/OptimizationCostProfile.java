package com.bloxbean.cardano.julc.vm;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable, reproducible cost-model input for compiler profitability decisions.
 *
 * <p>This type carries provenance and opaque ledger parameters. VM backends own
 * parameter interpretation; compiler optimization rules must only use a profile
 * through a separately reviewed cost analysis.
 */
public final class OptimizationCostProfile {

    private final String profileId;
    private final LedgerEvaluationTarget target;
    private final String source;
    private final String parameterHash;
    private final long[] costModelParameters;

    public OptimizationCostProfile(
            String profileId,
            LedgerEvaluationTarget target,
            String source,
            String parameterHash,
            long[] costModelParameters) {
        this.profileId = requireText(profileId, "profileId");
        this.target = Objects.requireNonNull(target, "target");
        this.source = requireText(source, "source");
        this.parameterHash = requireText(parameterHash, "parameterHash");
        this.costModelParameters = Objects.requireNonNull(
                costModelParameters, "costModelParameters").clone();
        if (this.costModelParameters.length == 0) {
            throw new IllegalArgumentException("costModelParameters must not be empty");
        }
    }

    public String profileId() {
        return profileId;
    }

    public LedgerEvaluationTarget target() {
        return target;
    }

    public String source() {
        return source;
    }

    public String parameterHash() {
        return parameterHash;
    }

    /** Return a defensive copy of the canonical ledger parameter array. */
    public long[] costModelParameters() {
        return costModelParameters.clone();
    }

    public int parameterCount() {
        return costModelParameters.length;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof OptimizationCostProfile that)) return false;
        return profileId.equals(that.profileId)
                && target.equals(that.target)
                && source.equals(that.source)
                && parameterHash.equals(that.parameterHash)
                && Arrays.equals(costModelParameters, that.costModelParameters);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(profileId, target, source, parameterHash);
        return 31 * result + Arrays.hashCode(costModelParameters);
    }

    @Override
    public String toString() {
        return profileId + "[" + target + ", sha256=" + parameterHash + "]";
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
