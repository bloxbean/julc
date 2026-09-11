package org.julclang.playground.model;

import java.util.List;

public record TranspileResponse(
        String javaSource,
        List<DiagnosticDto> diagnostics
) {}
