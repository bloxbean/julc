package org.julclang.compiler.frontend.javac;

import org.julclang.compiler.error.CompilerDiagnostic;

import java.util.List;

/**
 * The outcome of a javac analysis: its rejections, in emission order, and the environment that produced them, which
 * D3 records with every result: the host's javac version and the platform and stub digests.
 */
public record JavacResult(List<JavacDiagnostic> diagnostics, String javacVersion, String platformDigest,
                          String stubsDigest) {

    public JavacResult {
        diagnostics = List.copyOf(diagnostics);
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
