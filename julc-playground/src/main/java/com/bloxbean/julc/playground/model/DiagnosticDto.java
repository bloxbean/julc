package com.bloxbean.julc.playground.model;

import com.bloxbean.cardano.julc.jrl.check.JrlDiagnostic;

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
    public static DiagnosticDto from(JrlDiagnostic d) {
        var range = d.sourceRange();
        return new DiagnosticDto(
                d.level().name(),
                d.code(),
                d.message(),
                range != null ? range.startLine() : null,
                range != null ? range.startCol() : null,
                range != null ? range.endLine() : null,
                range != null ? range.endCol() : null,
                d.suggestion()
        );
    }
}
