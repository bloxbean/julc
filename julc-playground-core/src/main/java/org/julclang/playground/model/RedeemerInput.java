package org.julclang.playground.model;

import java.util.Map;

public record RedeemerInput(
        int variant,
        Map<String, String> fields
) {}
