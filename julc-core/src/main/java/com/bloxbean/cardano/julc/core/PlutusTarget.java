package com.bloxbean.cardano.julc.core;

/**
 * JuLC's compile target for generated scripts.
 * <p>
 * JuLC currently emits Plutus V3 scripts under the PV11 feature contract. The
 * VM/evaluator can execute other language/protocol profiles when given external
 * scripts, but the compiler target is intentionally fixed until configurable
 * compiler targeting is implemented.
 */
public enum PlutusTarget {
    V3("v3", "PlutusScriptV3", 1, 1, 0);

    public static final PlutusTarget CURRENT = V3;

    private final String languageVersion;
    private final String textEnvelopeType;
    private final int major;
    private final int minor;
    private final int patch;

    PlutusTarget(String languageVersion, String textEnvelopeType,
                 int major, int minor, int patch) {
        this.languageVersion = languageVersion;
        this.textEnvelopeType = textEnvelopeType;
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    public String languageVersion() {
        return languageVersion;
    }

    public String textEnvelopeType() {
        return textEnvelopeType;
    }

    public Program program(Term term) {
        return new Program(major, minor, patch, term);
    }
}
