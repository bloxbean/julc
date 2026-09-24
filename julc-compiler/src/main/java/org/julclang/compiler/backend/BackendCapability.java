package org.julclang.compiler.backend;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A stable, discoverable backend capability identifier (ADR-059). Identifiers are strings
 * rather than enum constants so a producer built for a newer backend receives an actionable
 * {@code JULC0045} diagnostic from an older one instead of a linkage error.
 */
public record BackendCapability(String id) implements Comparable<BackendCapability> {
    private static final Pattern FORMAT = Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9]+)*");

    /** Revision-2 {@link FunctionProgram} entries. */
    public static final BackendCapability FUNCTION_PROGRAM = new BackendCapability("program.function");
    /** The {@code julc-strict-v1} datum/redeemer boundary policy. */
    public static final BackendCapability STRICT_BOUNDARY_V1 =
            new BackendCapability("boundary.julc-strict-v1");
    /** Trusted provider import groups ({@link LibraryImports}). */
    public static final BackendCapability LIBRARY_IMPORTS = new BackendCapability("library.imports");
    /** Materialization of concrete exports through {@link LibraryRequest.Export}. */
    public static final BackendCapability LIBRARY_REQUEST_EXPORT =
            new BackendCapability("library.request.export");
    /** Structural verification of producer PIR, contract version 1 ({@link PirVerifier}). */
    public static final BackendCapability PIR_VERIFIER = new BackendCapability("pir.verifier.1");
    /**
     * {@code ListMatch} and {@code PairMatch} lowering, available only under the PV11 safe
     * optimization profile with builtin {@code Case}.
     */
    public static final BackendCapability PIR_BUILTIN_CASE = new BackendCapability("pir.builtin-case");

    public BackendCapability {
        Objects.requireNonNull(id, "id");
        if (!FORMAT.matcher(id).matches())
            throw new IllegalArgumentException("Invalid backend capability identifier: " + id);
    }

    @Override
    public int compareTo(BackendCapability other) {
        return id.compareTo(other.id);
    }

    @Override
    public String toString() {
        return id;
    }
}
