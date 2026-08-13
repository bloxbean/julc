package com.bloxbean.cardano.julc.compiler;

import java.util.Objects;

/** Tooling-visible identity for the on-chain encoding accepted at typed boundaries. */
public final class DataBoundarySemantics {
    public static final String STRICT_V1 = "strict-data-v1";
    public static final String LEGACY_V0 = "legacy-leading-field-v0";
    public static final String EXTERNAL_UNCLASSIFIED = "external-unclassified";

    private static final String VERSION_MARKER = "boundary." + STRICT_V1;

    private DataBoundarySemantics() { }

    /** Add strict-boundary semantics as SemVer build metadata. */
    public static String compilerIdentityVersion(String compilerVersion) {
        Objects.requireNonNull(compilerVersion, "compilerVersion");
        if (hasStrictMarker(compilerVersion)) return compilerVersion;
        if (compilerVersion.contains("boundary.")) {
            throw new IllegalArgumentException(
                    "Compiler version already contains an unrecognized boundary marker");
        }
        return compilerVersion + (compilerVersion.contains("+") ? "." : "+") + VERSION_MARKER;
    }

    /** Classify a CIP-57 compiler identity without treating unknown compilers as JuLC. */
    public static String fromCompilerIdentity(String compilerName, String compilerVersion) {
        if (!"julc".equalsIgnoreCase(compilerName)) return EXTERNAL_UNCLASSIFIED;
        if (compilerVersion == null || compilerVersion.isBlank()) return LEGACY_V0;
        if (hasStrictMarker(compilerVersion)) return STRICT_V1;
        if (compilerVersion.contains("boundary.")) {
            throw new IllegalArgumentException(
                    "Unrecognized JuLC data-boundary semantics in compiler version "
                            + compilerVersion);
        }
        return LEGACY_V0;
    }

    private static boolean hasStrictMarker(String compilerVersion) {
        return compilerVersion.endsWith("+" + VERSION_MARKER)
                || compilerVersion.endsWith("." + VERSION_MARKER);
    }
}
