package com.bloxbean.cardano.plutus.compiler.error;

/**
 * A compiler diagnostic message with source location and optional suggestion.
 */
public record CompilerDiagnostic(Level level, String message, String fileName, int line, int column, String suggestion) {

    public enum Level { ERROR, WARNING, INFO }

    public CompilerDiagnostic(Level level, String message, String fileName, int line, int column) {
        this(level, message, fileName, line, column, null);
    }

    public boolean isError() { return level == Level.ERROR; }

    public boolean hasSuggestion() { return suggestion != null && !suggestion.isEmpty(); }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        sb.append(level).append(" ").append(fileName).append(":").append(line).append(":").append(column);
        sb.append(" - ").append(message);
        if (hasSuggestion()) {
            sb.append(" (suggestion: ").append(suggestion).append(")");
        }
        return sb.toString();
    }
}
