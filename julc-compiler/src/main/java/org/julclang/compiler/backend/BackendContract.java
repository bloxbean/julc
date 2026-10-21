package org.julclang.compiler.backend;

import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.schema.ContractSchema;
import org.julclang.vm.ProtocolCapability;

import java.util.Locale;
import java.util.TreeSet;

/** The neutral backend's contract revision and capability policy (ADR-059). */
public final class BackendContract {
    /** Revision 1: {@link FrontendProgram} with one FUNCTION, SPEND or MINT entrypoint. */
    public static final int REVISION_1 = 1;
    /** Revision 2: descriptors with trusted imports, verified producer PIR and capabilities. */
    public static final int REVISION_2 = 2;
    /** The newest revision this backend implements. */
    public static final int REVISION = REVISION_2;
    /** The oldest revision this backend still accepts. */
    public static final int MINIMUM_REVISION = REVISION_1;

    /** The boundary policy shared with the Java frontend. */
    public static final String STRICT_BOUNDARY_V1 = "julc-strict-v1";

    private BackendContract() {}

    /** Capabilities for the target resolved by {@code context}. */
    static BackendCapabilities capabilities(CompilationContext context) {
        var capabilities = new TreeSet<BackendCapability>();
        capabilities.add(BackendCapability.FUNCTION_PROGRAM);
        capabilities.add(BackendCapability.STRICT_BOUNDARY_V1);
        capabilities.add(BackendCapability.LIBRARY_IMPORTS);
        capabilities.add(BackendCapability.LIBRARY_REQUEST_EXPORT);
        capabilities.add(BackendCapability.PIR_VERIFIER);
        if (builtinCaseLowering(context)) capabilities.add(BackendCapability.PIR_BUILTIN_CASE);
        capabilities.add(BackendCapability.VALIDATOR_PROGRAM);
        for (var purpose : ContractSchema.Purpose.values()) capabilities.add(purposeCapability(purpose));
        capabilities.add(BackendCapability.SPEND_DATUM_REQUIRED);
        capabilities.add(BackendCapability.SPEND_DATUM_OPTIONAL);
        capabilities.add(BackendCapability.SPEND_DATUM_ABSENT);
        capabilities.add(BackendCapability.VALIDATOR_PARAMETERS);
        capabilities.add(BackendCapability.BOUNDARY_PROGRAMS);
        capabilities.add(BackendCapability.LIBRARY_REQUEST_OPERATION);
        capabilities.add(BackendCapability.LIBRARY_REQUEST_CODEC);
        capabilities.add(BackendCapability.LIBRARY_TYPE_OPERATIONS);
        capabilities.add(BackendCapability.LIBRARY_SCHEMES);
        capabilities.add(BackendCapability.LIBRARY_REQUEST_INSTANTIATE);
        capabilities.add(BackendCapability.LIBRARY_COMPOSITION);
        for (var feature : ProtocolCapability.values())
            if (context.supports(feature)) capabilities.add(targetCapability(feature));
        return new BackendCapabilities(REVISION, MINIMUM_REVISION, context.target(), capabilities);
    }

    /**
     * Whether {@code ListMatch} and {@code PairMatch} can be lowered: the same condition as
     * {@code UplcGenerator}'s PV11 builtin {@code Case} profile.
     */
    static boolean builtinCaseLowering(CompilationContext context) {
        return context.target().equals(CompilerTarget.PLUTUS_V3_PV11)
                && context.optimizationLevel().pv11SafeRulesEnabled()
                && context.supports(ProtocolCapability.CASE_ON_BUILTIN_CONSTANTS);
    }

    /** The capability for handlers of one ledger purpose, e.g. {@code purpose.withdraw}. */
    public static BackendCapability purposeCapability(ContractSchema.Purpose purpose) {
        return new BackendCapability("purpose." + purpose.name().toLowerCase(Locale.ROOT));
    }

    /**
     * The ScriptInfo constructor tag that selects a purpose. Explicit rather than the enum
     * ordinal so reordering {@link ContractSchema.Purpose} cannot change dispatch.
     */
    public static int ledgerTag(ContractSchema.Purpose purpose) {
        return switch (purpose) {
            case MINT -> 0;
            case SPEND -> 1;
            case WITHDRAW -> 2;
            case CERTIFY -> 3;
            case VOTE -> 4;
            case PROPOSE -> 5;
        };
    }

    /** The capability that reports a target protocol feature, e.g. {@code target.constr-case}. */
    public static BackendCapability targetCapability(ProtocolCapability feature) {
        return new BackendCapability(
                "target." + feature.name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }
}
