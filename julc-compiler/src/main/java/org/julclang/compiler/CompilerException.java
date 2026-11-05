package org.julclang.compiler;

import org.julclang.compiler.error.CompilerDiagnostic;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Exception thrown when compilation fails.
 */
public class CompilerException extends RuntimeException {

    private List<CompilerDiagnostic> diagnostics;

    public CompilerException(List<CompilerDiagnostic> diagnostics) {
        super(formatMessage(diagnostics));
        this.diagnostics = List.copyOf(diagnostics);
    }

    public CompilerException(String message, CompilerDiagnostic diagnostic) {
        super(message);
        this.diagnostics = List.of(diagnostic);
    }

    public CompilerException(String message) {
        super(message);
        this.diagnostics = List.of();
    }

    public List<CompilerDiagnostic> diagnostics() { return diagnostics; }

    /**
     * What a host prints for this failure: each diagnostic with its code, position and fix, or the message when there is
     * none. An exception made from one diagnostic carries only its words as {@link #getMessage()}, which the javac
     * frontend raises for most of its rejections.
     */
    public String describe() {
        return diagnostics.isEmpty() ? getMessage() : formatMessage(diagnostics);
    }

    /** Adds each diagnostic's source line; given the compilation's sources. */
    void attachExcerpts(UnaryOperator<List<CompilerDiagnostic>> attach) {
        diagnostics = List.copyOf(attach.apply(diagnostics));
    }

    private static String formatMessage(List<CompilerDiagnostic> diagnostics) {
        if (diagnostics.isEmpty()) return "Compilation failed";
        var sb = new StringBuilder("Compilation failed with " + diagnostics.size() + " error(s):\n");
        for (var d : diagnostics) {
            sb.append("  ").append(d).append("\n");
        }
        return sb.toString();
    }
}
