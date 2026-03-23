package com.bloxbean.julc.playground.model;

import java.util.List;

public record EvaluateResponse(
        boolean success,
        long budgetCpu,
        long budgetMem,
        List<String> traces,
        String error,
        List<DiagnosticDto> diagnostics
) {}
