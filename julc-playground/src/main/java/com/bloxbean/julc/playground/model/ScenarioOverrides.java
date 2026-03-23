package com.bloxbean.julc.playground.model;

import java.util.List;

public record ScenarioOverrides(
        List<String> signers,
        Long validRangeAfter,
        Long validRangeBefore
) {}
