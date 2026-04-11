package com.bloxbean.julc.playground.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record CheckRequest(String source, String language) {
    public CheckRequest(String source) {
        this(source, null);
    }

    @JsonIgnore
    public boolean isJava() {
        return "java".equalsIgnoreCase(language);
    }
}
