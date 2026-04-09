package com.bloxbean.julc.playground.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record CompileRequest(String source, String language, String librarySource) {
    public CompileRequest(String source) {
        this(source, null, null);
    }

    public CompileRequest(String source, String language) {
        this(source, language, null);
    }

    @JsonIgnore
    public boolean isJava() {
        return "java".equalsIgnoreCase(language);
    }
}
