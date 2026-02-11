package com.bloxbean.cardano.plutus.compiler;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared utility for resolving library source files from imports.
 * <p>
 * Provides three capabilities:
 * <ol>
 *   <li>Extract import class names from Java source</li>
 *   <li>Scan classpath for {@code META-INF/plutus-sources/} entries</li>
 *   <li>Transitively resolve library sources from a pool of available libraries</li>
 * </ol>
 * <p>
 * Used by both {@code PlutusAnnotationProcessor} (build-time) and
 * {@code SourceDiscovery} (test-time) to avoid duplicating resolution logic.
 */
public final class LibrarySourceResolver {

    /**
     * Regex matching Java import statements.
     * Group 1 = package path (e.g. {@code com.example.util}),
     * Group 2 = simple class name (e.g. {@code SumTest}).
     */
    public static final Pattern IMPORT_PATTERN = Pattern.compile(
            "^\\s*import\\s+([a-zA-Z_][a-zA-Z0-9_.]*)\\.([A-Z][a-zA-Z0-9_]*)\\s*;",
            Pattern.MULTILINE);

    /**
     * Regex matching Java package declarations.
     * Group 1 = the package name (e.g. {@code com.example.util}).
     */
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "^\\s*package\\s+([a-zA-Z_][a-zA-Z0-9_.]*);", Pattern.MULTILINE);

    private LibrarySourceResolver() {}

    /**
     * Extract simple class names from import statements in Java source.
     *
     * @param source the Java source code
     * @return set of simple class names (e.g. {@code SumTest}, {@code BigInteger})
     */
    public static Set<String> extractImportedClassNames(String source) {
        var classNames = new LinkedHashSet<String>();
        Matcher matcher = IMPORT_PATTERN.matcher(source);
        while (matcher.find()) {
            classNames.add(matcher.group(2));
        }
        return classNames;
    }

    /**
     * Extract the package name from a Java source file.
     *
     * @param source the Java source code
     * @return the package name, or empty string if no package declaration
     */
    public static String extractPackageName(String source) {
        Matcher m = PACKAGE_PATTERN.matcher(source);
        return m.find() ? m.group(1) : "";
    }

    /**
     * Extract class names referenced via {@code ClassName.method(} patterns.
     * This catches same-package references that don't require imports.
     *
     * @param source the Java source code
     * @return set of simple class names referenced in static-call patterns
     */
    public static Set<String> extractReferencedClassNames(String source) {
        var pattern = Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)\\s*\\.\\s*[a-z][a-zA-Z0-9_]*\\s*\\(");
        var result = new LinkedHashSet<String>();
        var matcher = pattern.matcher(source);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    /**
     * Extract full import paths from import statements in Java source.
     * Returns a map from simple class name to fully qualified name.
     *
     * @param source the Java source code
     * @return map of simpleName to fullPath (e.g. {@code "SumTest" -> "com.example.util.SumTest"})
     */
    public static Map<String, String> extractImportPaths(String source) {
        var paths = new LinkedHashMap<String, String>();
        Matcher matcher = IMPORT_PATTERN.matcher(source);
        while (matcher.find()) {
            String pkg = matcher.group(1);
            String simpleName = matcher.group(2);
            paths.put(simpleName, pkg + "." + simpleName);
        }
        return paths;
    }

    /**
     * Scan classpath for {@code META-INF/plutus-sources/} entries and collect
     * all {@code .java} files found within them.
     * <p>
     * Uses an {@code index.txt} manifest to discover sources reliably from both
     * file-system directories and JAR archives.
     *
     * @param classLoader the classloader to scan
     * @return map of simpleName to source code
     */
    public static Map<String, String> scanClasspathSources(ClassLoader classLoader) {
        var result = new LinkedHashMap<String, String>();
        // Primary: use index.txt manifest (works with both file: and jar: protocols)
        try (var indexStream = classLoader.getResourceAsStream("META-INF/plutus-sources/index.txt")) {
            if (indexStream != null) {
                var indexContent = new String(indexStream.readAllBytes(), StandardCharsets.UTF_8);
                for (String entry : indexContent.split("\n")) {
                    entry = entry.strip();
                    if (entry.isEmpty() || !entry.endsWith(".java")) continue;
                    var resourcePath = "META-INF/plutus-sources/" + entry;
                    try (var sourceStream = classLoader.getResourceAsStream(resourcePath)) {
                        if (sourceStream != null) {
                            var source = new String(sourceStream.readAllBytes(), StandardCharsets.UTF_8);
                            var fileName = entry.contains("/")
                                    ? entry.substring(entry.lastIndexOf('/') + 1) : entry;
                            var simpleName = fileName.replace(".java", "");
                            result.putIfAbsent(simpleName, source);
                        }
                    }
                }
                return result;
            }
        } catch (IOException e) {
            // Fall through to directory scan
        }
        // Fallback: file-system directory scan (for development/IDE usage)
        try {
            var resources = classLoader.getResources("META-INF/plutus-sources/");
            while (resources.hasMoreElements()) {
                URL resourceUrl = resources.nextElement();
                if ("file".equals(resourceUrl.getProtocol())) {
                    scanFileSystemSources(new File(resourceUrl.getPath()), result);
                }
            }
        } catch (IOException e) {
            // Silently ignore
        }
        return result;
    }

    private static void scanFileSystemSources(File dir, Map<String, String> result) {
        if (!dir.exists() || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                scanFileSystemSources(file, result);
            } else if (file.getName().endsWith(".java")) {
                try {
                    String source = Files.readString(file.toPath());
                    String simpleName = file.getName().replace(".java", "");
                    result.putIfAbsent(simpleName, source);
                } catch (IOException e) {
                    // skip unreadable files
                }
            }
        }
    }

    /**
     * Resolve library sources transitively from a pool of available libraries.
     * <p>
     * Starting from the given source's imports, looks up each simple class name
     * in the pool. For each match, adds the source and recursively resolves
     * its imports until no new libraries are found.
     *
     * @param source             the root source whose imports to resolve
     * @param availableLibraries map of simpleName to source code
     * @return list of resolved library source strings (in discovery order)
     */
    public static List<String> resolve(String source, Map<String, String> availableLibraries) {
        var resolved = new LinkedHashMap<String, String>();
        var toProcess = new ArrayDeque<>(extractImportedClassNames(source));
        // Also seed from ClassName.method() references (same-package, no import needed)
        toProcess.addAll(extractReferencedClassNames(source));
        var seen = new HashSet<String>();

        while (!toProcess.isEmpty()) {
            String simpleName = toProcess.poll();
            if (seen.contains(simpleName) || resolved.containsKey(simpleName)) {
                continue;
            }
            seen.add(simpleName);

            String libSource = availableLibraries.get(simpleName);
            if (libSource == null) {
                continue;
            }

            resolved.put(simpleName, libSource);

            // Transitively resolve both imports and referenced class names
            for (String transitiveName : extractImportedClassNames(libSource)) {
                if (!seen.contains(transitiveName) && !resolved.containsKey(transitiveName)) {
                    toProcess.add(transitiveName);
                }
            }
            for (String transitiveName : extractReferencedClassNames(libSource)) {
                if (!seen.contains(transitiveName) && !resolved.containsKey(transitiveName)) {
                    toProcess.add(transitiveName);
                }
            }
        }

        return new ArrayList<>(resolved.values());
    }
}
