package org.julclang.playground.model;

import java.util.List;

public record EvalExpressionResponse(
        boolean success,
        String result,
        String type,
        long budgetCpu,
        long budgetMem,
        List<String> traces,
        String error,
        String uplc
) {}
