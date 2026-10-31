package org.julclang.compiler.frontend.javac;

import org.julclang.compiler.error.CompilerDiagnostic;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The outcome of a javac analysis: its rejections, in emission order; the environment that produced them, which
 * D3 records with every result: the host's javac version and the platform and stub digests; and the identities of
 * the pool units D8's precedence left out.
 */
public record JavacResult(List<JavacDiagnostic> diagnostics, String javacVersion, String platformDigest,
                          String stubsDigest, Set<String> dropped) {

    public JavacResult {
        diagnostics = List.copyOf(diagnostics);
        dropped = dropped == null ? Set.of() : Set.copyOf(dropped);
    }

    public JavacResult(List<JavacDiagnostic> diagnostics, String javacVersion, String platformDigest,
                       String stubsDigest) {
        this(diagnostics, javacVersion, platformDigest, stubsDigest, Set.of());
    }

    JavacResult withDropped(Set<String> identities) {
        return new JavacResult(diagnostics, javacVersion, platformDigest, stubsDigest, identities);
    }

    /**
     * The effective pool (D8): {@code pool} without the units precedence left out (shadowed units). An enforced
     * compilation hands this to the JavaParser frontend too, so what javac accepted is what is compiled.
     */
    public <T> List<T> effective(List<T> pool, Function<T, String> text) {
        if (dropped.isEmpty()) return pool;
        return pool.stream().filter(unit -> !dropped.contains(JavacPlatform.identity(text.apply(unit)))).toList();
    }

    static JavacResult accepted(JavacPlatform platform) {
        return rejected(List.of(), platform);
    }

    static JavacResult rejected(List<JavacDiagnostic> diagnostics, JavacPlatform platform) {
        return new JavacResult(diagnostics, JavacFrontend.javacVersion(), platform.platformDigest(),
                platform.stubsDigest());
    }

    public boolean isAccepted() {
        return diagnostics.isEmpty();
    }

    public List<CompilerDiagnostic> compilerDiagnostics() {
        return diagnostics.stream().map(JavacDiagnostic::toCompilerDiagnostic).toList();
    }
}
