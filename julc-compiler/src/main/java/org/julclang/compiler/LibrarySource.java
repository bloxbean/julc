package org.julclang.compiler;

public record LibrarySource(String fqcn, String simpleName, String packageName, String resourcePath, String source) {
}
