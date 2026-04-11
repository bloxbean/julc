package com.bloxbean.julc.playground.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Map;

public record EvaluateRequest(
        String source,
        String language,
        String librarySource,
        Map<String, String> paramValues,
        ScenarioOverrides scenario,
        Map<String, String> datum,
        RedeemerInput redeemer
) {
    public EvaluateRequest(String source, Map<String, String> paramValues,
                           ScenarioOverrides scenario, Map<String, String> datum,
                           RedeemerInput redeemer) {
        this(source, null, null, paramValues, scenario, datum, redeemer);
    }

    @JsonIgnore
    public boolean isJava() {
        return "java".equalsIgnoreCase(language);
    }
}
