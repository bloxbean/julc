package com.bloxbean.julc.playground.model;

import java.util.List;

public record CompileResponse(
        String uplcText,
        String javaSource,
        int scriptSizeBytes,
        String scriptSizeFormatted,
        List<FieldDto> params,
        List<DiagnosticDto> diagnostics
) {}
