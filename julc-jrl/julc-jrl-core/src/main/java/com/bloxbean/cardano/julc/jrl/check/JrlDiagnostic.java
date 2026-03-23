package com.bloxbean.cardano.julc.jrl.check;

import com.bloxbean.cardano.julc.jrl.ast.SourceRange;

/**
 * A diagnostic message from JRL parsing, type checking, or transpilation.
 *
 * @param level       severity
 * @param code        categorized error code (e.g. "JRL001")
 * @param message     human-readable description
 * @param sourceRange location in the JRL source file
 * @param suggestion  nullable fix suggestion
 */
public record JrlDiagnostic(
        Level level,
        String code,
        String message,
        SourceRange sourceRange,
        String suggestion
) {
    public enum Level { ERROR, WARNING, INFO }

    public boolean isError() {
        return level == Level.ERROR;
    }

    public static JrlDiagnostic error(String code, String message, SourceRange range) {
        return new JrlDiagnostic(Level.ERROR, code, message, range, null);
    }

    public static JrlDiagnostic error(String code, String message, SourceRange range, String suggestion) {
        return new JrlDiagnostic(Level.ERROR, code, message, range, suggestion);
    }

    public static JrlDiagnostic warning(String code, String message, SourceRange range) {
        return new JrlDiagnostic(Level.WARNING, code, message, range, null);
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        sb.append(level).append(" ").append(code).append(": ").append(message);
        if (sourceRange != null) {
            sb.append(" at ").append(sourceRange);
        }
        if (suggestion != null) {
            sb.append(" (").append(suggestion).append(")");
        }
        return sb.toString();
    }
}
