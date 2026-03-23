package com.bloxbean.julc.playground.model;

import java.util.Map;

public record EvaluateRequest(
        String source,
        Map<String, String> paramValues,
        ScenarioOverrides scenario,
        Map<String, String> datum,
        RedeemerInput redeemer
) {}
