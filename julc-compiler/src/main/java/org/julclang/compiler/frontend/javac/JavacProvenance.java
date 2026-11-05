package org.julclang.compiler.frontend.javac;

/**
 * What javac saw of a compilation (ADR-068 D3): on a {@code CompileResult}, so that a build can name the environment it ran in.
 *
 * @param javacVersion   the host's javac version; null unless javac ran
 * @param platformDigest SHA-256 of the java.base blob; null unless javac ran
 * @param stubsDigest    SHA-256 of the API stubs; null unless javac ran
 * @param outcome        what javac made of the sources
 */
public record JavacProvenance(String javacVersion, String platformDigest, String stubsDigest, Outcome outcome) {

    /** What became of the sources. */
    public enum Outcome {
        ACCEPTED, REJECTED, NOT_RUN
    }

    /** A result built outside a compilation, which never takes this process's environment for its own. */
    public static final JavacProvenance UNAVAILABLE = new JavacProvenance(null, null, null, Outcome.NOT_RUN);

    /** The javac frontend read the compilation. */
    public static JavacProvenance frontend(JavacResult result) {
        return new JavacProvenance(result.javacVersion(), result.platformDigest(), result.stubsDigest(),
                result.isAccepted() ? Outcome.ACCEPTED : Outcome.REJECTED);
    }
}
