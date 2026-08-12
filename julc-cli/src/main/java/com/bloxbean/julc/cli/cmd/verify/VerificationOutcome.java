package com.bloxbean.julc.cli.cmd.verify;

/** Stable verification classifications shared by the CLI and result document. */
public enum VerificationOutcome {
    SMT_VALID("SMT-VALID"),
    KERNEL_PROVED("KERNEL-PROVED"),
    REFUTED("REFUTED"),
    UNDETERMINED("UNDETERMINED"),
    COULD_NOT_EVALUATE("COULD-NOT-EVALUATE");

    private final String externalName;

    VerificationOutcome(String externalName) {
        this.externalName = externalName;
    }

    public String externalName() {
        return externalName;
    }

    public int exitCode() {
        return switch (this) {
            case SMT_VALID, KERNEL_PROVED -> 0;
            case REFUTED -> 3;
            case UNDETERMINED, COULD_NOT_EVALUATE -> 2;
        };
    }

    public static VerificationOutcome parse(String value) {
        for (var outcome : values()) {
            if (outcome.externalName.equalsIgnoreCase(value)
                    || outcome.name().equalsIgnoreCase(value.replace('-', '_'))) {
                return outcome;
            }
        }
        throw new IllegalArgumentException("Unknown verification outcome '" + value + "'");
    }
}
