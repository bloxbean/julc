package org.julclang.compiler.frontend.javac;

/**
 * What javac saw of one compilation (ADR-068 D3): the host's javac version, the digests of the embedded platform and
 * API stubs, the gate's mode and its outcome. It is provenance only: compiled bytes do not depend on it, and it is not
 * written to generated artifacts, so {@code plutus.json} stays the same across JDK patch releases.
 *
 * @param javacVersion   the host's javac version; null unless javac ran
 * @param platformDigest SHA-256 of the java.base blob; null unless javac ran
 * @param stubsDigest    SHA-256 of the API stubs; null unless javac ran
 * @param gate           the gate's mode; null when this compilation's mode is unknown (see {@link #UNAVAILABLE})
 * @param outcome        what the gate did
 */
public record JavacProvenance(String javacVersion, String platformDigest, String stubsDigest, JavacGateMode gate,
                              Outcome outcome) {

    /** What the gate did. An enforced rejection has no result, so {@code REJECTED} is report mode's. */
    public enum Outcome {
        ACCEPTED, REJECTED, NOT_RUN
    }

    /** A result built outside a compilation, which never takes this process's environment for its own. */
    public static final JavacProvenance UNAVAILABLE = notRun(null);

    /** The gate did not run: {@code off}, or report mode when javac itself failed. */
    public static JavacProvenance notRun(JavacGateMode gate) {
        return new JavacProvenance(null, null, null, gate, Outcome.NOT_RUN);
    }

    /** The gate ran in {@code gate} and produced {@code result}. */
    public static JavacProvenance of(JavacGateMode gate, JavacResult result) {
        return new JavacProvenance(result.javacVersion(), result.platformDigest(), result.stubsDigest(), gate,
                result.isAccepted() ? Outcome.ACCEPTED : Outcome.REJECTED);
    }
}
