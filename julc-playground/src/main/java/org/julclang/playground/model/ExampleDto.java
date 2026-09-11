package org.julclang.playground.model;

public record ExampleDto(String name, String source, String language) {
    public ExampleDto(String name, String source) {
        this(name, source, "java");
    }
}
