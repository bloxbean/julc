package com.bloxbean.cardano.julc.core.source;

/**
 * Represents a location in Java source code, used by source maps to map UPLC terms
 * back to the Java code that generated them.
 *
 * @param fileName the Java source file name (e.g., "MyValidator.java")
 * @param line     the 1-based line number
 * @param column   the 1-based column number
 * @param fragment a short snippet of the Java expression at this location (for display)
 */
public record SourceLocation(String fileName, int line, int column, String fragment) {

    /**
     * Format as "FileName.java:line (fragment)" for display in error messages.
     */
    @Override
    public String toString() {
        var sb = new StringBuilder();
        if (fileName != null) sb.append(fileName);
        sb.append(':').append(line);
        if (fragment != null && !fragment.isEmpty()) {
            // Truncate long fragments
            var frag = fragment.length() > 80 ? fragment.substring(0, 77) + "..." : fragment;
            sb.append(" (").append(frag).append(')');
        }
        return sb.toString();
    }
}
