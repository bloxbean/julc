package com.bloxbean.julc.playground.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record TranspileRequest(String source, String language) {
    public TranspileRequest(String source) {
        this(source, null);
    }

    @JsonIgnore
    public boolean isJava() {
        return "java".equalsIgnoreCase(language);
    }
}
