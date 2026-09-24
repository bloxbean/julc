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

    /** Revision-2 {@link ValidatorProgram} descriptors with purpose dispatch. */
    public static final BackendCapability VALIDATOR_PROGRAM = new BackendCapability("program.validator");
    /** Minting handlers. */
    public static final BackendCapability PURPOSE_MINT = new BackendCapability("purpose.mint");
    /** Spending handlers. */
    public static final BackendCapability PURPOSE_SPEND = new BackendCapability("purpose.spend");
    /** Withdrawal (rewarding) handlers. */
    public static final BackendCapability PURPOSE_WITHDRAW = new BackendCapability("purpose.withdraw");
    /** Certifying handlers. */
    public static final BackendCapability PURPOSE_CERTIFY = new BackendCapability("purpose.certify");
    /** Voting handlers. */
    public static final BackendCapability PURPOSE_VOTE = new BackendCapability("purpose.vote");
    /** Proposing handlers. */
    public static final BackendCapability PURPOSE_PROPOSE = new BackendCapability("purpose.propose");
    /** Spending handlers with a required datum ({@link DatumProfile#REQUIRED}). */
    public static final BackendCapability SPEND_DATUM_REQUIRED =
            new BackendCapability("spend.datum.required");

    /** Deployment parameters ({@link Parameter}); available from milestone #184. */
    public static final BackendCapability VALIDATOR_PARAMETERS = new BackendCapability("validator.parameters");
    /** Spending handlers with an optional datum; available from milestone #184. */
    public static final BackendCapability SPEND_DATUM_OPTIONAL =
            new BackendCapability("spend.datum.optional");
    /** Spending handlers without a datum argument; available from milestone #184. */
    public static final BackendCapability SPEND_DATUM_ABSENT = new BackendCapability("spend.datum.absent");

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
