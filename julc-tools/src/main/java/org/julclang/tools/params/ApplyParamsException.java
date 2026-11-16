package org.julclang.tools.params;

/**
 * A request {@code vm.applyParams} refuses (ADR-077 D6a, D6b). The three kinds are never mixed: a refusal of the request
 * itself, a fault of the blueprint's schemas, and a value that does not match its schema.
 */
public final class ApplyParamsException extends RuntimeException {

    /** The error kinds, with the status and code the engine reports. */
    public enum Kind {
        /** A D6a refusal: the operation will not guess. */
        INVALID_REQUEST(400, "invalid-request"),
        /** The blueprint's schemas are outside D6b's domain or ill-formed. */
        INVALID_SCHEMA(422, "invalid-schema"),
        /** A value does not match its declared schema. */
        PARAMETER_MISMATCH(422, "parameter-mismatch");

        private final int status;
        private final String code;

        Kind(int status, String code) {
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    private final Kind kind;

    public ApplyParamsException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    static ApplyParamsException invalidRequest(String message) {
        return new ApplyParamsException(Kind.INVALID_REQUEST, message);
    }

    static ApplyParamsException invalidSchema(String message) {
        return new ApplyParamsException(Kind.INVALID_SCHEMA, message);
    }

    static ApplyParamsException mismatch(String message) {
        return new ApplyParamsException(Kind.PARAMETER_MISMATCH, message);
    }
}
