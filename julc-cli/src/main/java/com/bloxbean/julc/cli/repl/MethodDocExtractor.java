package com.bloxbean.julc.cli.repl;

import com.bloxbean.julc.cli.output.AnsiColors;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts javadoc and method signatures from stdlib Java source strings.
 * <p>
 * Used by the REPL's {@code :doc} command to show inline documentation
 * without leaving the REPL.
 */
public final class MethodDocExtractor {

    /**
     * Extracted documentation for a single method.
     */
    public record MethodDoc(String className, String methodName, String signature, String javadoc) {}

    /**
     * Extracted documentation for a class.
     */
    public record ClassDoc(String className, String javadoc, List<String> methodNames) {}

    // Map from "ClassName.methodName" -> MethodDoc
    private final Map<String, MethodDoc> methodDocs = new LinkedHashMap<>();
    // Map from "ClassName" -> ClassDoc
    private final Map<String, ClassDoc> classDocs = new LinkedHashMap<>();

    /**
     * Matches a javadoc block: /** ... * /
     * Uses reluctant quantifier to find the nearest closing.
     */
    private static final Pattern JAVADOC_PATTERN =
            Pattern.compile("/\\*\\*(.*?)\\*/", Pattern.DOTALL);

    /**
     * Matches a public static method declaration (possibly preceded by annotations/@SuppressWarnings).
     * Captures: return type, method name, parameter list.
     */
    private static final Pattern METHOD_DECL_PATTERN =
            Pattern.compile("public\\s+static\\s+(\\S+(?:<[^>]+>)?)\\s+(\\w+)\\s*\\(([^)]*)\\)");

    /**
     * Matches a class declaration. Captures the class name.
     */
    private static final Pattern CLASS_DECL_PATTERN =
            Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");

    /**
     * Build the doc extractor from a library pool (className -> Java source).
     */
    public MethodDocExtractor(Map<String, String> libraryPool) {
        for (var entry : libraryPool.entrySet()) {
            String className = entry.getKey();
            String source = entry.getValue();
            extractFromSource(className, source);
        }
    }

    /**
     * Look up documentation for a method.
     *
     * @param key "ClassName.methodName"
     * @return the MethodDoc, or null if not found
     */
    public MethodDoc lookupMethod(String key) {
        return methodDocs.get(key);
    }

    /**
     * Look up documentation for a class.
     *
     * @param className simple class name
     * @return the ClassDoc, or null if not found
     */
    public ClassDoc lookupClass(String className) {
        return classDocs.get(className);
    }

    /**
     * Format a method doc for REPL display with ANSI syntax highlighting.
     * Signature is bold, javadoc body is dim.
     */
    public static String formatMethodDoc(MethodDoc doc) {
        var sb = new StringBuilder();
        sb.append("  ").append(AnsiColors.bold(doc.signature())).append("\n");
        if (!doc.javadoc().isEmpty()) {
            sb.append("\n");
            for (String line : doc.javadoc().split("\n")) {
                sb.append("  ").append(AnsiColors.dim(line)).append("\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Format a class doc for REPL display with ANSI syntax highlighting.
     * Class name is bold, method list is cyan.
     */
    public static String formatClassDoc(ClassDoc doc) {
        var sb = new StringBuilder();
        if (!doc.javadoc().isEmpty()) {
            sb.append("  ").append(AnsiColors.bold(doc.className()))
                    .append(AnsiColors.dim(" — " + doc.javadoc())).append("\n");
        } else {
            sb.append("  ").append(AnsiColors.bold(doc.className())).append("\n");
        }
        if (!doc.methodNames().isEmpty()) {
            sb.append("  ").append(AnsiColors.dim("Methods: "))
                    .append(AnsiColors.cyan(String.join(", ", doc.methodNames())));
        }
        return sb.toString().stripTrailing();
    }

    private void extractFromSource(String className, String source) {
        // 1. Extract class-level javadoc
        String classJavadoc = extractClassJavadoc(source);
        List<String> methodNames = new ArrayList<>();

        // 2. Extract method-level javadocs
        // Strategy: find all javadoc blocks, then check if a public static method follows
        extractMethodDocs(className, source, methodNames);

        classDocs.put(className, new ClassDoc(className, classJavadoc, List.copyOf(methodNames)));
    }

    private void extractMethodDocs(String className, String source, List<String> methodNames) {
        // Find all javadoc positions
        Matcher javadocMatcher = JAVADOC_PATTERN.matcher(source);
        while (javadocMatcher.find()) {
            int javadocEnd = javadocMatcher.end();
            String javadocBody = javadocMatcher.group(1);

            // Look for a public static method declaration after this javadoc
            // (allowing annotations/whitespace/SuppressWarnings between)
            String afterJavadoc = source.substring(javadocEnd);
            Matcher methodMatcher = METHOD_DECL_PATTERN.matcher(afterJavadoc);
            if (methodMatcher.find() && isOnlyAnnotationsAndWhitespace(afterJavadoc, methodMatcher.start())) {
                String returnType = methodMatcher.group(1);
                String methodName = methodMatcher.group(2);
                String params = methodMatcher.group(3).trim();

                String signature = buildSignature(className, methodName, returnType, params);
                String javadoc = cleanJavadoc(javadocBody);

                String key = className + "." + methodName;
                methodDocs.put(key, new MethodDoc(className, methodName, signature, javadoc));
                methodNames.add(methodName);
            }
        }

        // Also find public static methods WITHOUT javadoc
        Matcher methodMatcher = METHOD_DECL_PATTERN.matcher(source);
        while (methodMatcher.find()) {
            String methodName = methodMatcher.group(2);
            String key = className + "." + methodName;
            if (!methodDocs.containsKey(key)) {
                String returnType = methodMatcher.group(1);
                String params = methodMatcher.group(3).trim();
                String signature = buildSignature(className, methodName, returnType, params);
                methodDocs.put(key, new MethodDoc(className, methodName, signature, ""));
                methodNames.add(methodName);
            }
        }
    }

    /** Regex to match Java annotations like @Override, @SuppressWarnings("unchecked"), etc. */
    private static final Pattern ANNOTATION_PATTERN = Pattern.compile("@\\w+(\\([^)]*\\))?");

    /**
     * Check if the text between javadoc end and method start is only whitespace and annotations.
     */
    private static boolean isOnlyAnnotationsAndWhitespace(String text, int endPos) {
        String between = text.substring(0, endPos).trim();
        if (between.isEmpty()) return true;
        String stripped = ANNOTATION_PATTERN.matcher(between).replaceAll("").trim();
        return stripped.isEmpty();
    }

    static String buildSignature(String className, String methodName, String returnType, String params) {
        String cleanParams = cleanParams(params);
        return className + "." + methodName + "(" + cleanParams + ") -> " + simplifyType(returnType);
    }

    /**
     * Clean parameter list for display — simplify types but keep param names.
     */
    static String cleanParams(String params) {
        if (params.isEmpty()) return "";

        var cleaned = new ArrayList<String>();
        for (String part : splitParams(params)) {
            String trimmed = part.trim();
            // Handle annotations in params
            trimmed = ANNOTATION_PATTERN.matcher(trimmed).replaceAll("").trim();
            // Split into tokens — last token is param name, everything before is the type
            String[] tokens = trimmed.split("\\s+");
            if (tokens.length >= 2) {
                String name = tokens[tokens.length - 1];
                String type = String.join(" ", Arrays.copyOf(tokens, tokens.length - 1));
                cleaned.add(simplifyType(type) + " " + name);
            } else if (tokens.length == 1) {
                cleaned.add(tokens[0]);
            }
        }
        return String.join(", ", cleaned);
    }

    /**
     * Split parameter list on commas, respecting angle bracket nesting.
     * E.g. "JulcMap<PlutusData, PlutusData> map, PlutusData key" -> ["JulcMap<PlutusData, PlutusData> map", "PlutusData key"]
     */
    static List<String> splitParams(String params) {
        var result = new ArrayList<String>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < params.length(); i++) {
            char c = params.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                result.add(params.substring(start, i));
                start = i + 1;
            }
        }
        result.add(params.substring(start));
        return result;
    }

    /**
     * Simplify type for display — strip package prefixes from generic args, shorten common types.
     */
    static String simplifyType(String type) {
        // Already simple (no dots, no generics)
        if (!type.contains(".") && !type.contains("<")) return type;

        // Handle generic types like Tuple2<BigInteger, BigInteger> or Optional<PlutusData>
        int angleStart = type.indexOf('<');
        if (angleStart >= 0) {
            String rawType = simplifyType(type.substring(0, angleStart));
            String innerContent = type.substring(angleStart + 1, type.length() - 1);
            // Simplify each generic arg (respecting nested angle brackets)
            var simplified = new ArrayList<String>();
            for (String arg : splitParams(innerContent)) {
                simplified.add(simplifyType(arg.trim()));
            }
            return rawType + "<" + String.join(", ", simplified) + ">";
        }

        // Strip package prefix: com.foo.Bar -> Bar
        int lastDot = type.lastIndexOf('.');
        if (lastDot >= 0) {
            return type.substring(lastDot + 1);
        }
        return type;
    }

    /**
     * Extract class-level javadoc: the javadoc block immediately before the class declaration.
     */
    static String extractClassJavadoc(String source) {
        Matcher classMatcher = CLASS_DECL_PATTERN.matcher(source);
        if (!classMatcher.find()) return "";

        int classStart = classMatcher.start();
        // Find the last javadoc block before the class declaration
        Matcher javadocMatcher = JAVADOC_PATTERN.matcher(source);
        String lastJavadoc = null;
        int lastEnd = -1;
        while (javadocMatcher.find() && javadocMatcher.end() <= classStart) {
            lastJavadoc = javadocMatcher.group(1);
            lastEnd = javadocMatcher.end();
        }

        if (lastJavadoc == null) return "";

        // Verify only annotations/whitespace between javadoc end and class start
        String between = source.substring(lastEnd, classStart).trim();
        String stripped = ANNOTATION_PATTERN.matcher(between).replaceAll("").trim();
        if (!stripped.isEmpty()) return "";

        return cleanJavadoc(lastJavadoc);
    }

    /**
     * Clean javadoc: strip leading * on each line, remove HTML tags, trim.
     */
    static String cleanJavadoc(String raw) {
        String[] lines = raw.split("\n");
        var cleaned = new ArrayList<String>();
        for (String line : lines) {
            // Strip leading whitespace + optional "* "
            String trimmed = line.trim();
            if (trimmed.startsWith("* ")) {
                trimmed = trimmed.substring(2);
            } else if (trimmed.equals("*")) {
                trimmed = "";
            } else if (trimmed.startsWith("*")) {
                trimmed = trimmed.substring(1);
            }
            // Strip <p> tags
            trimmed = trimmed.replace("<p>", "").trim();
            // Skip @param, @return, @see, @throws javadoc tags
            if (trimmed.startsWith("@param") || trimmed.startsWith("@return")
                    || trimmed.startsWith("@see") || trimmed.startsWith("@throws")) {
                continue;
            }
            cleaned.add(trimmed);
        }

        // Remove leading/trailing blank lines
        while (!cleaned.isEmpty() && cleaned.getFirst().isEmpty()) cleaned.removeFirst();
        while (!cleaned.isEmpty() && cleaned.getLast().isEmpty()) cleaned.removeLast();

        return String.join("\n", cleaned);
    }
}
