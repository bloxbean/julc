package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.error.CompilerDiagnostic;

import java.util.List;

/**
 * Exception thrown when compilation fails.
 */
public class CompilerException extends RuntimeException {

    private final List<CompilerDiagnostic> diagnostics;

    public CompilerException(List<CompilerDiagnostic> diagnostics) {
        super(formatMessage(diagnostics));
        this.diagnostics = List.copyOf(diagnostics);
    }

    public CompilerException(String message) {
        super(message);
        this.diagnostics = List.of();
    }

    public List<CompilerDiagnostic> diagnostics() { return diagnostics; }

    private static String formatMessage(List<CompilerDiagnostic> diagnostics) {
        if (diagnostics.isEmpty()) return "Compilation failed";
        var sb = new StringBuilder("Compilation failed with " + diagnostics.size() + " error(s):\n");
        for (var d : diagnostics) {
            sb.append("  ").append(d).append("\n");
        }
        return sb.toString();
    }
}
