package com.bloxbean.julc.playground.model;

import java.util.List;

public record CompileResponse(
        String uplcText,
        String javaSource,
        String pirText,
        String blueprintJson,
        String compiledCode,
        String scriptHash,
        int scriptSizeBytes,
        String scriptSizeFormatted,
        List<FieldDto> params,
        List<DiagnosticDto> diagnostics
) {}
