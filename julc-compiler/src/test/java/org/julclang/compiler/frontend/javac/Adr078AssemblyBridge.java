package org.julclang.compiler.frontend.javac;

import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.codegen.ValidatorAssembly.AssemblyObserver;
import org.julclang.stdlib.StdlibRegistry;

import java.util.List;

/**
 * ADR-078 D5's test bridge to an observed assembly (Codex M1a C26): compiles a validator or a {@code compileMethod}
 * target as {@code JulcCompiler} does for project sources, with {@code observer} told every method binding as the
 * frontend's {@code methodBound} callback receives it, and every equal-key choice. Test sources only: production code
 * has no way to set an observer.
 */
public final class Adr078AssemblyBridge {

    private Adr078AssemblyBridge() {
    }

    private static List<JavacFrontend.Unit> pool(List<String> libraries) {
        return libraries.stream().map(text -> new JavacFrontend.Unit(text, null, JavacFrontend.Origin.PROJECT)).toList();
    }

    /** A validator, compiled with its details, observed. */
    public static CompileResult validator(String source, List<String> libraries, CompilerOptions options, AssemblyObserver observer) {
        var context = CompilationContext.resolve(options);
        var request = new JavacCompilation.Request(true, false, null);
        return JavacRuntime.call(() -> JavacCompilation.validator(JavacFrontend.Unit.of(source), pool(libraries),
                StdlibRegistry.defaultRegistry(), context, request, observer).result());
    }

    /** A {@code compileMethod} target, observed. */
    public static CompileResult method(String source, String method, List<String> libraries, CompilerOptions options,
                                       AssemblyObserver observer) {
        var context = CompilationContext.resolve(options);
        return JavacRuntime.call(() -> JavacCompilation.method(JavacFrontend.Unit.of(source), pool(libraries), method,
                StdlibRegistry.defaultRegistry(), context, observer));
    }
}
