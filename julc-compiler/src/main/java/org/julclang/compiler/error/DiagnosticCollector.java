package org.julclang.compiler.error;

import org.julclang.compiler.CompilerException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects compiler diagnostics (errors, warnings, info) during compilation,
 * enabling multi-error reporting instead of fail-fast behavior.
 * <p>
 * This is the standard pattern for error collection across all compiler phases.
 * Errors can be accumulated and thrown together at the end of a phase.
 * <p>
 * Usage:
 * <pre>{@code
 * var collector = new DiagnosticCollector("MyValidator.java");
 *
 * // Collect errors during processing
 * collector.error(node, "Undefined variable 'x'", "Check spelling or ensure 'x' is declared before use");
 * collector.warning(node, "Unreachable code");
 *
 * // At the end, throw if there were errors
 * collector.throwIfErrors();
 * }</pre>
 */
public class DiagnosticCollector {

    private final List<CompilerDiagnostic> diagnostics = new ArrayList<>();
    private String fileName;

    public DiagnosticCollector() {
        this.fileName = "<source>";
    }

    public DiagnosticCollector(String fileName) {
        this.fileName = fileName;
    }

    /**
     * Set the current file name for diagnostics.
     */
    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    /**
     * Record an error without a source node (structural errors).
     */
    /** An error at a 1-based line and column (0 when unknown), as {@link #error(Node, String, String)} records it. */
    public void error(int line, int column, String message, String suggestion) {
        diagnostics.add(new CompilerDiagnostic(
                CompilerDiagnostic.Level.ERROR, message, fileName, line, column, suggestion, null));
    }

    public void error(String message) {
        error(message, (String) null);
    }

    /**
     * Record an error without a source node but with a suggestion.
     */
    public void error(String message, String suggestion) {
        diagnostics.add(new CompilerDiagnostic(
                CompilerDiagnostic.Level.ERROR, message, fileName, 0, 0, suggestion));
    }

    /**
     * Record a warning without a source node.
     */
    public void warning(String message) {
        diagnostics.add(new CompilerDiagnostic(
                CompilerDiagnostic.Level.WARNING, message, fileName, 0, 0));
    }

    /**
     * Check if any errors have been collected.
     */
    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(CompilerDiagnostic::isError);
    }

    /**
     * Get all collected diagnostics.
     */
    public List<CompilerDiagnostic> getDiagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    /**
     * Get only error-level diagnostics.
     */
    public List<CompilerDiagnostic> getErrors() {
        return diagnostics.stream()
                .filter(CompilerDiagnostic::isError)
                .toList();
    }

    /**
     * Get only warning-level diagnostics.
     */
    public List<CompilerDiagnostic> getWarnings() {
        return diagnostics.stream()
                .filter(d -> d.level() == CompilerDiagnostic.Level.WARNING)
                .toList();
    }

    /**
     * Throw a CompilerException if any errors have been collected.
     */
    public void throwIfErrors() {
        if (hasErrors()) {
            throw new CompilerException(diagnostics);
        }
    }

    /**
     * Add all diagnostics from another collector.
     */
    public void addAll(DiagnosticCollector other) {
        diagnostics.addAll(other.diagnostics);
    }

    /**
     * Add all diagnostics from a list.
     */
    public void addAll(List<CompilerDiagnostic> otherDiagnostics) {
        diagnostics.addAll(otherDiagnostics);
    }

    /**
     * Clear all collected diagnostics.
     */
    public void clear() {
        diagnostics.clear();
    }

    /**
     * Number of diagnostics collected.
     */
    public int size() {
        return diagnostics.size();
    }

    /**
     * Check if no diagnostics have been collected.
     */
    public boolean isEmpty() {
        return diagnostics.isEmpty();
    }
}
