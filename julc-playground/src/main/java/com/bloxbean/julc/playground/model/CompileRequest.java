package com.bloxbean.julc.playground.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record CompileRequest(
        String source, String language, String librarySource, Boolean blueprint) {
    public CompileRequest(String source) {
        this(source, null, null, null);
    }

    public CompileRequest(String source, String language) {
        this(source, language, null, null);
    }

    public CompileRequest(String source, String language, String librarySource) {
        this(source, language, librarySource, null);
    }

    @JsonIgnore
    public boolean isJava() {
        return "java".equalsIgnoreCase(language);
    }

    @JsonIgnore
    public boolean blueprintEnabled() {
        return blueprint == null || blueprint;
    }
}
