package org.julclang.compiler.corpus;

import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.stdlib.StdlibRegistry;

/** One method compiled on each frontend and on the default, for {@code CorpusRecorderTest}'s frontend markers. */
final class CorpusRecorderFrontendProbe {

    static final String METHOD = """
            import java.math.BigInteger;
            class P { static BigInteger m(BigInteger a) { return a.add(a); } }
            """;

    static final String JAVAC_ONLY = """
            import java.math.BigInteger;
            class Q { static BigInteger m(BigInteger a) { return a.multiply(a); } }
            """;

    private CorpusRecorderFrontendProbe() {
    }

    public static void main(String[] args) {
        for (var frontend : CompilerFrontend.values()) {
            new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setFrontend(frontend)).compileMethod(METHOD, "m");
        }
        new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(JAVAC_ONLY, "m");
        System.out.println("ok");
    }
}
