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

    /**
     * Query a named protocol capability without recreating version thresholds
     * in compiler or evaluator consumers.
     */
    public boolean supports(ProtocolCapability capability) {
        Objects.requireNonNull(capability, "capability");
        return switch (capability) {
            case CONSTR_CASE -> availableUplcVersions.stream()
                    .anyMatch(UplcVersion::supportsConstrAndCase);
            case CASE_ON_BUILTIN_CONSTANTS -> caseOnBuiltinConstants;
            case BLS_CONSTANTS -> isBuiltinAvailable(DefaultFun.Bls12_381_G1_add);
            case ARRAY_CONSTANTS -> isBuiltinAvailable(DefaultFun.ListToArray);
            case VALUE_CONSTANTS -> isBuiltinAvailable(DefaultFun.ValueData);
        };
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
