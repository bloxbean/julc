package com.bloxbean.julc.playground.model;

import java.util.List;
import java.util.Map;

public record ScenarioDto(
        String name,
        String description,
        List<String> signers,
        Long validRangeAfter,
        Long validRangeBefore,
        Map<String, String> datum,
        Map<String, String> redeemerFields,
        Integer redeemerVariant
) {}
