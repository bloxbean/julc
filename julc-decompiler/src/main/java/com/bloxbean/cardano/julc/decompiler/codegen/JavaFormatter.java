package com.bloxbean.cardano.julc.decompiler.codegen;

/**
 * Formats Java source code with consistent indentation and style.
 * Currently uses a simple 4-space indent. Future enhancements may
 * include line wrapping and import organization.
 */
public final class JavaFormatter {

    private JavaFormatter() {}

    /**
     * Format the given Java source code.
     * Currently a pass-through as the generator already produces formatted output.
     */
    public static String format(String source) {
        return source;
    }
}
