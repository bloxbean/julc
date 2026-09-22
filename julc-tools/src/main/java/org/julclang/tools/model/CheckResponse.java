package org.julclang.tools.model;

import java.util.List;

public record CheckResponse(
        boolean valid,
        String contractName,
        String purpose,
        List<FieldDto> params,
        String datumName,
        List<FieldDto> datumFields,
        List<VariantDto> redeemerVariants,
        List<FieldDto> redeemerFields,
        List<DiagnosticDto> diagnostics
) {}
