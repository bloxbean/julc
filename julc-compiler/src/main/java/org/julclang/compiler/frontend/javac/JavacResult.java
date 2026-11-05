package org.julclang.compiler.frontend.javac;

import org.julclang.compiler.error.CompilerDiagnostic;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The outcome of a javac analysis: its rejections, in emission order; the environment that produced them, which
 * D3 records with every result: the host's javac version and the platform and stub digests; and the identities of
 * the pool units D8's precedence left out; and the subset's warnings, which reject nothing.
 */
public record JavacResult(List<JavacDiagnostic> diagnostics, String javacVersion, String platformDigest,
                          String stubsDigest, Set<String> dropped, List<CompilerDiagnostic> warnings) {

    public JavacResult {
        diagnostics = List.copyOf(diagnostics);
        dropped = dropped == null ? Set.of() : Set.copyOf(dropped);
        warnings = List.copyOf(warnings);
    }

    public JavacResult(List<JavacDiagnostic> diagnostics, String javacVersion, String platformDigest,
                       String stubsDigest, Set<String> dropped) {
        this(diagnostics, javacVersion, platformDigest, stubsDigest, dropped, List.of());
    }

    public JavacResult(List<JavacDiagnostic> diagnostics, String javacVersion, String platformDigest,
                       String stubsDigest) {
        this(diagnostics, javacVersion, platformDigest, stubsDigest, Set.of());
    }

    JavacResult withDropped(Set<String> identities) {
        return new JavacResult(diagnostics, javacVersion, platformDigest, stubsDigest, identities, warnings);
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
