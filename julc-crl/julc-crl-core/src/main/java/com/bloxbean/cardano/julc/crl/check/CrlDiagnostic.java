package com.bloxbean.cardano.julc.crl.check;

import com.bloxbean.cardano.julc.crl.ast.SourceRange;

/**
 * A diagnostic message from CRL parsing, type checking, or transpilation.
 *
 * @param level       severity
 * @param code        categorized error code (e.g. "CRL001")
 * @param message     human-readable description
 * @param sourceRange location in the CRL source file
 * @param suggestion  nullable fix suggestion
 */
public record CrlDiagnostic(
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

    public static CrlDiagnostic error(String code, String message, SourceRange range) {
        return new CrlDiagnostic(Level.ERROR, code, message, range, null);
    }

    public static CrlDiagnostic error(String code, String message, SourceRange range, String suggestion) {
        return new CrlDiagnostic(Level.ERROR, code, message, range, suggestion);
    }

    public static CrlDiagnostic warning(String code, String message, SourceRange range) {
        return new CrlDiagnostic(Level.WARNING, code, message, range, null);
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
