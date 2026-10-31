package org.julclang.compiler.frontend.javac;

/**
 * The javac environment every compilation of this process is checked in (ADR-068 D3): the host's javac version and
 * the digests of the embedded platform and API stubs. It is the same for every compilation of a process, so it is
 * exposed once here, additively, rather than on {@code CompileResult} (a record whose shape I5 fixes); per-result
 * metadata is an M1 exit item.
 *
 * @param javacVersion   the host runtime's version, which is its javac's
 * @param supported      whether that javac is the supported release (D3); when false, compilations are JULC0089
 * @param platformDigest SHA-256 of the java.base blob
 * @param stubsDigest    SHA-256 of the API stubs
 */
public record JavacEnvironment(String javacVersion, boolean supported, String platformDigest, String stubsDigest) {

    /** This process's environment; a JuLC build without its javac resources is {@link BrokenJulcBuildException}. */
    public static JavacEnvironment current() {
        var frontend = JavacFrontend.shared();
        return new JavacEnvironment(JavacFrontend.javacVersion(), frontend.supported().isAccepted(),
                frontend.platform().platformDigest(), frontend.platform().stubsDigest());
    }
}
