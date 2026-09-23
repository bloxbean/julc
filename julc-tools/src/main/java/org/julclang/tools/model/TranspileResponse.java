package org.julclang.tools.model;

import java.util.List;

public record TranspileResponse(
        String javaSource,
        List<DiagnosticDto> diagnostics
) {}
