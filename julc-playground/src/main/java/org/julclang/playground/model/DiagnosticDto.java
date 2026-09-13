package org.julclang.playground.model;

import org.julclang.compiler.error.CompilerDiagnostic;

public record DiagnosticDto(
        String level,
        String code,
        String message,
        Integer startLine,
        Integer startCol,
        Integer endLine,
        Integer endCol,
        String suggestion
) {
    public static DiagnosticDto from(CompilerDiagnostic d) {
        return new DiagnosticDto(
                d.level().name(),
                d.code(),
                d.message(),
                d.line() > 0 ? d.line() : null,
                d.column() > 0 ? d.column() : null,
                null,
                null,
                d.suggestion()
        );
    }
}
