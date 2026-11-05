package org.julclang.compiler.frontend.javac;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.error.DiagnosticCodes;

import java.util.List;
import java.util.function.Supplier;

/**
 * Where a compilation leaves for javac, on a host that may have no Java compiler API. This class references no compiler-API
 * class, so it loads on such a host, and what it runs fails with {@code JULC0089} there (ADR-068 I6). A JuLC build without
 * its javac resources is {@link BrokenJulcBuildException}, an internal error, never {@code JULC0089}.
 */
public final class JavacRuntime {

    private JavacRuntime() {
    }

    /** Whether {@code e} says that the runtime has no Java compiler API (I6), as opposed to another linkage fault. */
    public static boolean isMissingCompiler(LinkageError e) {
        if (!(e instanceof NoClassDefFoundError)) return false;
        String message = String.valueOf(e.getMessage()).replace('.', '/');
        return message.startsWith("javax/tools/") || message.startsWith("com/sun/source/")
                || message.startsWith("javax/lang/model/");
    }

    /** {@code JULC0089} for a runtime whose compiler API cannot link. */
    public static JavacResult noCompiler(LinkageError e) {
        String version = Runtime.version().toString();
        return new JavacResult(List.of(JavacDiagnostic.of(DiagnosticCodes.JAVAC_UNSUPPORTED, null,
                DiagnosticCodes.JAVAC_UNSUPPORTED.format(version, "this runtime has no Java compiler (" + e + ")"),
                null, 0, 0, -1, -1, null)), version, null, null);
    }

    /** {@code frontend}'s result; a runtime whose compiler API cannot link is {@code JULC0089}. */
    public static <T> T call(Supplier<T> frontend) {
        try {
            return frontend.get();
        } catch (LinkageError e) {
            if (!isMissingCompiler(e)) throw e;
            throw new CompilerException(noCompiler(e).compilerDiagnostics());
        }
    }
}
