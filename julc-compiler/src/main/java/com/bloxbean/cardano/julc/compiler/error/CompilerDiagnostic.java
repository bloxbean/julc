package com.bloxbean.cardano.julc.compiler.error;

/**
 * A compiler diagnostic message with source location and optional suggestion.
 *
 * <p>The {@code code} field is a stable identifier (e.g. {@code "JULC0001"}) that
 * downstream tools (the MCP server's {@code julc_explain_diagnostic} tool, the
 * AI starter pack's error-table, the {@code julc lint} command) use to look up
 * the canonical root-cause + fix for the error. The catalog of known codes is
 * authored at {@code julc-compiler/src/main/resources/diagnostics.json}.
 *
 * <p>The field is nullable for backwards compatibility — existing call sites
 * that have not yet been migrated to use a code continue to work, they simply
 * carry no code in their diagnostic. New error sites should always supply a
 * code.
 */
public record CompilerDiagnostic(Level level, String message, String fileName,
                                 int line, int column, String suggestion, String code) {

    public enum Level { ERROR, WARNING, INFO }

    public CompilerDiagnostic(Level level, String message, String fileName, int line, int column) {
        this(level, message, fileName, line, column, null, null);
    }

    public CompilerDiagnostic(Level level, String message, String fileName,
                              int line, int column, String suggestion) {
        this(level, message, fileName, line, column, suggestion, null);
    }

    public boolean isError() { return level == Level.ERROR; }

    public boolean hasSuggestion() { return suggestion != null && !suggestion.isEmpty(); }

    public boolean hasCode() { return code != null && !code.isEmpty(); }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        sb.append(level);
        if (hasCode()) {
            sb.append("[").append(code).append("]");
        }
        sb.append(" at .(").append(fileName).append(":").append(line).append(")");
        sb.append(":").append(column);
        sb.append(" - ").append(message);
        if (hasSuggestion()) {
            sb.append(" (suggestion: ").append(suggestion).append(")");
        }
        return sb.toString();
    }
}
