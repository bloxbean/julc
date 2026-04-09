package com.bloxbean.julc.playground.api;

/**
 * Shared input validation for playground API controllers.
 */
final class InputValidator {

    static final int MAX_SOURCE_LENGTH = 100_000;      // 100 KB
    static final int MAX_LIBRARY_LENGTH = 100_000;      // 100 KB
    static final int MAX_EXPRESSION_LENGTH = 10_000;    // 10 KB

    private InputValidator() {}

    /**
     * Validate source code length. Returns error message or null if valid.
     */
    static String validateSource(String source) {
        if (source == null || source.isBlank()) {
            return "Source is required";
        }
        if (source.length() > MAX_SOURCE_LENGTH) {
            return "Source exceeds maximum length (" + MAX_SOURCE_LENGTH / 1000 + " KB)";
        }
        return null;
    }

    /**
     * Validate optional library source length. Returns error message or null if valid.
     */
    static String validateLibrary(String librarySource) {
        if (librarySource != null && librarySource.length() > MAX_LIBRARY_LENGTH) {
            return "Library source exceeds maximum length (" + MAX_LIBRARY_LENGTH / 1000 + " KB)";
        }
        return null;
    }

    /**
     * Validate expression length. Returns error message or null if valid.
     */
    static String validateExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            return "Expression is required";
        }
        if (expression.length() > MAX_EXPRESSION_LENGTH) {
            return "Expression exceeds maximum length (" + MAX_EXPRESSION_LENGTH / 1000 + " KB)";
        }
        return null;
    }

    /**
     * Returns a safe generic error message for client response.
     */
    static String sanitizeError(String prefix) {
        return prefix;
    }
}
