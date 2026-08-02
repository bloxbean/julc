package com.bloxbean.cardano.julc.vm;

import com.bloxbean.cardano.julc.core.DefaultFun;
import com.bloxbean.cardano.julc.core.Program;

import java.util.Objects;
import java.util.Set;

/**
 * The immutable set of protocol-sensitive decisions for one ledger target.
 * Instances are resolved by {@link ProtocolFeatureRegistry}; callers must not
 * choose a semantics variant independently.
 */
public record ProtocolFeatureProfile(
        LedgerEvaluationTarget target,
        BuiltinSemanticsVariant semanticsVariant,
        Set<DefaultFun> availableBuiltins,
        Set<UplcVersion> availableUplcVersions,
        boolean caseOnBuiltinConstants,
        DecodeLimits decodeLimits,
        CostModelSchema costModelSchema) {

    public ProtocolFeatureProfile {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(semanticsVariant, "semanticsVariant");
        availableBuiltins = Set.copyOf(availableBuiltins);
        availableUplcVersions = Set.copyOf(availableUplcVersions);
        Objects.requireNonNull(decodeLimits, "decodeLimits");
        Objects.requireNonNull(costModelSchema, "costModelSchema");
    }

    public boolean isBuiltinAvailable(DefaultFun builtin) {
        return availableBuiltins.contains(builtin);
    }

    public boolean isUplcVersionAvailable(UplcVersion version) {
        return availableUplcVersions.contains(version);
    }

    /** Validate the program version before CEK execution. */
    public void validateProgramVersion(Program program) {
        var version = UplcVersion.from(program);
        if (!isUplcVersionAvailable(version)) {
            throw new UnsupportedLedgerTargetException(
                    "UPLC " + version + " is not available for " + target
                            + "; available versions: " + availableUplcVersions);
        }
    }
}
