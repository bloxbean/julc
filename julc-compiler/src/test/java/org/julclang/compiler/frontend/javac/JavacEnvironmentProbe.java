package org.julclang.compiler.frontend.javac;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

/**
 * F510's probe, run in a JVM without julc-stdlib on its class path ({@link JavacGateEnvironmentTest}): the gate in
 * enforce mode, first with no bundled set, then with julc-stdlib supplied through the thread context loader.
 * Arguments: julc-stdlib's class-path entries.
 */
public final class JavacEnvironmentProbe {

    static final String USES_STDLIB = """
            import java.math.BigInteger;
            import org.julclang.stdlib.lib.MathLib;
            class UsesStdlib { static BigInteger f(BigInteger a) { return MathLib.max(a, a); } }
            """;
    static final String IN_STDLIB_PACKAGE = """
            package org.julclang.stdlib.lib;
            public class Extra { public static int one() { return 1; } }
            """;

    static String outcome(String source) {
        try {
            var result = JavacGate.check("probe", List.of(JavacFrontend.Unit.of(source)), List.of(),
                    new CompilerOptions().setJavacGate(JavacGateMode.ENFORCE));
            return result.isAccepted() ? "accepted" : "rejected?";
        } catch (CompilerException e) {
            var d = e.diagnostics().getFirst();
            return "rejected " + d.code() + (d.message().contains("(compiler.err.") ? " "
                    + d.message().substring(d.message().lastIndexOf("(compiler.err.") + 1, d.message().length() - 1) : "");
        }
    }

    static void print() {
        System.out.println("bundled=" + JavacFrontend.shared().bundled().digests().size()
                + " usesStdlib=" + outcome(USES_STDLIB) + " inStdlibPackage=" + outcome(IN_STDLIB_PACKAGE));
    }

    public static void main(String[] args) throws Exception {
        print();
        var urls = new URL[args.length];
        for (int i = 0; i < args.length; i++) urls[i] = Path.of(args[i]).toUri().toURL();
        Thread.currentThread().setContextClassLoader(new URLClassLoader(urls, null));
        print();
    }
}
