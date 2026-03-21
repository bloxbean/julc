package com.bloxbean.julc.cli.scaffold;

/**
 * Utilities for Java package name handling in project scaffolding.
 */
public final class PackageNameUtils {

    private PackageNameUtils() {}

    /**
     * Sanitizes a project name into a valid Java package segment.
     * Lowercases, removes hyphens/dots/spaces, strips leading digits.
     * Falls back to "myproject" if the result is empty.
     */
    public static String sanitize(String name) {
        if (name == null || name.isBlank()) {
            return "myproject";
        }
        // Lowercase and remove invalid characters (keep letters, digits, underscores)
        String result = name.toLowerCase()
                .replaceAll("[^a-z0-9_]", "");
        // Strip leading digits
        result = result.replaceFirst("^\\d+", "");
        if (result.isEmpty()) {
            return "myproject";
        }
        // Ensure it doesn't start with a Java keyword segment
        return result;
    }

    /**
     * Converts a dot-separated package name to a directory path.
     * e.g. "com.example.foo" → "com/example/foo"
     */
    public static String toPath(String packageName) {
        return packageName.replace('.', '/');
    }

    /**
     * Validates that the given string is a valid Java package name.
     * Returns null if valid, or an error message if invalid.
     */
    public static String validate(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return "Package name cannot be empty";
        }
        String[] parts = packageName.split("\\.");
        for (String part : parts) {
            if (part.isEmpty()) {
                return "Package name contains empty segment";
            }
            if (!part.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                return "Invalid package segment: '" + part + "'";
            }
            if (isJavaKeyword(part)) {
                return "Package segment is a Java keyword: '" + part + "'";
            }
        }
        return null;
    }

    private static boolean isJavaKeyword(String word) {
        return switch (word) {
            case "abstract", "assert", "boolean", "break", "byte", "case", "catch",
                 "char", "class", "const", "continue", "default", "do", "double",
                 "else", "enum", "extends", "final", "finally", "float", "for",
                 "goto", "if", "implements", "import", "instanceof", "int",
                 "interface", "long", "native", "new", "package", "private",
                 "protected", "public", "return", "short", "static", "strictfp",
                 "super", "switch", "synchronized", "this", "throw", "throws",
                 "transient", "try", "void", "volatile", "while" -> true;
            default -> false;
        };
    }
}
