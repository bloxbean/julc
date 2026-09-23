package org.julclang.tools.model;

import java.util.List;

public record ScenarioOverrides(
        List<String> signers,
        Long validRangeAfter,
        Long validRangeBefore
) {}
