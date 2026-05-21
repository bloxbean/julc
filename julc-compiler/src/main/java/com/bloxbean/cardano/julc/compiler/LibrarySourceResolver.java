package com.bloxbean.cardano.julc.compiler;

import com.bloxbean.cardano.julc.compiler.error.DiagnosticCollector;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
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
 * Used by both {@code JulcAnnotationProcessor} (build-time) and
 * {@code SourceDiscovery} (test-time) to avoid duplicating resolution logic.
 */
public final class LibrarySourceResolver {

    private static final String PLUTUS_SOURCES_DIR = "META-INF/plutus-sources/";
    private static final String PLUTUS_SOURCES_INDEX = PLUTUS_SOURCES_DIR + "index.txt";

    /**
     * Regex matching Java import statements.
     * Group 1 = package path (e.g. {@code com.example.util}),
     * Group 2 = simple class name (e.g. {@code SumTest}).
     */
    public static final Pattern IMPORT_PATTERN = Pattern.compile(
            "^\\s*import\\s+([a-zA-Z_][a-zA-Z0-9_.]*)\\.([A-Z][a-zA-Z0-9_]*)\\s*;",
            Pattern.MULTILINE);

    public static final Pattern WILDCARD_IMPORT_PATTERN = Pattern.compile(
            "^\\s*import\\s+([a-zA-Z_][a-zA-Z0-9_.]*)\\.\\*\\s*;",
            Pattern.MULTILINE);

    /**
     * Regex matching Java package declarations.
     * Group 1 = the package name (e.g. {@code com.example.util}).
     */
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "^\\s*package\\s+([a-zA-Z_][a-zA-Z0-9_.]*);", Pattern.MULTILINE);

    private static final Pattern TYPE_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:public\\s+)?(?:final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+)*" +
                    "(?:class|record|interface|enum)\\s+([A-Z][a-zA-Z0-9_]*)");

    private static final Pattern STATIC_REFERENCE_PATTERN = Pattern.compile(
            "\\b(([a-z_][a-zA-Z0-9_]*\\.)*[A-Z][a-zA-Z0-9_]*(?:\\.[A-Z][a-zA-Z0-9_]*)*)" +
                    "\\s*\\.\\s*[a-zA-Z_][a-zA-Z0-9_]*\\s*(?:\\(|\\b)");

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
     * Extract class names referenced via static {@code ClassName.member} patterns.
     * This catches same-package references that don't require imports.
     *
     * @param source the Java source code
     * @return set of simple class names referenced in static member patterns
     */
    public static Set<String> extractReferencedClassNames(String source) {
        var result = new LinkedHashSet<String>();
        for (ClassReference reference : extractFallbackClassReferences(source)) {
            result.add(reference.simpleName());
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

    public static Set<String> extractWildcardImportPackages(String source) {
        var packages = new LinkedHashSet<String>();
        Matcher matcher = WILDCARD_IMPORT_PATTERN.matcher(source);
        while (matcher.find()) {
            packages.add(matcher.group(1));
        }
        return packages;
    }

    public static LibrarySource librarySource(String source) {
        return librarySourceFromSimpleName(null, source);
    }

    public static LibrarySource librarySourceFromSimpleName(String simpleName, String source) {
        Objects.requireNonNull(source, "source");
        String packageName = extractPackageName(source);
        String resolvedSimpleName = simpleName == null || simpleName.isBlank()
                ? extractTopLevelTypeName(source).orElseThrow(() ->
                new IllegalArgumentException("Could not determine library class name from source"))
                : simpleName(simpleName);
        String fqcn = packageName.isBlank() ? resolvedSimpleName : packageName + "." + resolvedSimpleName;
        String resourcePath = packageName.isBlank()
                ? resolvedSimpleName + ".java"
                : packageName.replace('.', '/') + "/" + resolvedSimpleName + ".java";
        return new LibrarySource(fqcn, resolvedSimpleName, packageName, resourcePath, source);
    }

    public static Map<String, LibrarySource> librarySourcesFrom(Map<String, String> simpleNameToSource) {
        var result = new LinkedHashMap<String, LibrarySource>();
        simpleNameToSource.forEach((simpleName, source) -> putLibrarySource(result, simpleName, source));
        return result;
    }

    /**
     * Add a legacy string library source to an FQCN-keyed pool.
     * <p>
     * The source should declare its package. If it is intentionally package-less,
     * pass an FQCN as {@code simpleName} so imports can still resolve it.
     */
    public static void putLibrarySource(Map<String, LibrarySource> target, String simpleName, String source) {
        LibrarySource librarySource;
        try {
            librarySource = librarySourceFromLegacyKey(simpleName, source);
        } catch (IllegalArgumentException e) {
            librarySource = librarySourceFromSimpleName(simpleName, source);
        }
        target.put(librarySource.fqcn(), librarySource);
    }

    /**
     * Scan classpath for {@code META-INF/plutus-sources/} entries and collect
     * all {@code .java} files found within them.
     * <p>
     * Uses an {@code index.txt} manifest to discover sources reliably from both
     * file-system directories and JAR archives.
     *
     * @param classLoader the classloader to scan
     * @return map of FQCN to source metadata
     */
    public static Map<String, LibrarySource> scanClasspathSources(ClassLoader classLoader) {
        var result = new LinkedHashMap<String, LibrarySource>();
        // JARs must ship index.txt for reliable discovery. Loose file-system
        // directories are also scanned to support IDE/dev/test classpaths.
        try {
            Enumeration<URL> indexes = classLoader.getResources(PLUTUS_SOURCES_INDEX);
            while (indexes.hasMoreElements()) {
                scanIndexedSources(indexes.nextElement(), result);
            }
        } catch (IOException e) {
            // Fall through to directory scan
        }
        try {
            Enumeration<URL> resources = classLoader.getResources(PLUTUS_SOURCES_DIR);
            while (resources.hasMoreElements()) {
                URL resourceUrl = resources.nextElement();
                if ("file".equals(resourceUrl.getProtocol())) {
                    File dir = new File(resourceUrl.getPath());
                    scanFileSystemSources(dir, dir, result);
                }
            }
        } catch (IOException e) {
            // Silently ignore
        }
        return result;
    }

    private static void scanIndexedSources(URL indexUrl, Map<String, LibrarySource> result) throws IOException {
        try (var indexStream = indexUrl.openStream()) {
            var indexContent = new String(indexStream.readAllBytes(), StandardCharsets.UTF_8);
            for (String rawEntry : indexContent.split("\n")) {
                String entry = rawEntry.strip();
                if (entry.isEmpty() || !entry.endsWith(".java")) continue;
                try (var sourceStream = resolveIndexedSourceUrl(indexUrl, entry).openStream()) {
                    var source = new String(sourceStream.readAllBytes(), StandardCharsets.UTF_8);
                    var fqcn = fqcnFromResourcePath(entry);
                    result.putIfAbsent(fqcn, librarySourceFromPath(fqcn, normalizeResourcePath(entry), source));
                }
            }
        }
    }

    private static URL resolveIndexedSourceUrl(URL indexUrl, String entry) throws IOException {
        String indexPath = indexUrl.toExternalForm();
        int lastSlash = indexPath.lastIndexOf('/');
        if (lastSlash < 0) {
            throw new IOException("Invalid source index URL: " + indexUrl);
        }
        return URI.create(indexPath.substring(0, lastSlash + 1) + encodeResourcePath(entry)).toURL();
    }

    private static String encodeResourcePath(String resourcePath) {
        return Arrays.stream(resourcePath.split("/", -1))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .reduce((left, right) -> left + "/" + right)
                .orElse("");
    }

    private static void scanFileSystemSources(File root, File dir, Map<String, LibrarySource> result) {
        if (!dir.exists() || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                scanFileSystemSources(root, file, result);
            } else if (file.getName().endsWith(".java")) {
                try {
                    String source = Files.readString(file.toPath());
                    String resourcePath = normalizeResourcePath(root.toPath().relativize(file.toPath()).toString());
                    String fqcn = fqcnFromResourcePath(resourcePath);
                    result.putIfAbsent(fqcn, librarySourceFromPath(fqcn, resourcePath, source));
                } catch (IOException e) {
                    // skip unreadable files
                }
            }
        }
    }

    /**
     * Resolve library sources transitively from a pool of available libraries.
     * <p>
     * Starting from the given source's imports and static {@code ClassName.member}
     * references, looks up each library by fully qualified class name. Ambiguous
     * unqualified references fail with a compiler diagnostic instead of picking an
     * arbitrary classpath winner.
     *
     * @param source             the root source whose imports to resolve
     * @param availableLibraries map of FQCN to library source metadata, or a legacy
     *                           map of class name/FQCN to source string
     * @return list of resolved library source strings (in discovery order)
     */
    public static List<String> resolve(String source, Map<String, ?> availableLibraries) {
        return resolveLibrarySources(source, normalizeLibrarySources(availableLibraries)).stream()
                .map(LibrarySource::source)
                .toList();
    }

    public static List<LibrarySource> resolveLibrarySources(String source, Map<String, LibrarySource> availableLibraries) {
        if (availableLibraries.isEmpty()) {
            return List.of();
        }

        var bySimpleName = indexBySimpleName(availableLibraries.values());
        var diagnostics = new DiagnosticCollector("<source>");
        var resolved = new LinkedHashMap<String, LibrarySource>();
        var queued = new HashSet<String>();
        Queue<LibrarySource> toProcess = new ArrayDeque<>();

        enqueueReferencedLibraries(source, extractPackageName(source), "<source>", availableLibraries,
                bySimpleName, resolved, queued, toProcess, diagnostics);
        diagnostics.throwIfErrors();

        while (!toProcess.isEmpty()) {
            LibrarySource librarySource = toProcess.poll();
            if (resolved.containsKey(librarySource.fqcn())) {
                continue;
            }
            resolved.put(librarySource.fqcn(), librarySource);
            enqueueReferencedLibraries(librarySource.source(), librarySource.packageName(), librarySource.resourcePath(),
                    availableLibraries, bySimpleName, resolved, queued, toProcess, diagnostics);
            diagnostics.throwIfErrors();
        }

        return new ArrayList<>(resolved.values());
    }

    private static void enqueueReferencedLibraries(String source,
                                                   String packageName,
                                                   String fileName,
                                                   Map<String, LibrarySource> availableLibraries,
                                                   Map<String, List<LibrarySource>> bySimpleName,
                                                   Map<String, LibrarySource> resolved,
                                                   Set<String> queued,
                                                   Queue<LibrarySource> toProcess,
                                                   DiagnosticCollector diagnostics) {
        diagnostics.setFileName(fileName);
        SourceContext context = parseSourceContext(source, packageName, diagnostics);

        for (String fqcn : context.importsBySimpleName().values()) {
            enqueue(availableLibraries.get(fqcn), resolved, queued, toProcess);
        }

        for (ClassReference reference : context.staticReferences()) {
            LibrarySource librarySource = resolveStaticReference(reference, context.packageName(),
                    context.importsBySimpleName(), context.wildcardPackages(),
                    availableLibraries, bySimpleName, diagnostics);
            enqueue(librarySource, resolved, queued, toProcess);
        }
    }

    private static LibrarySource resolveStaticReference(ClassReference reference,
                                                        String packageName,
                                                        Map<String, String> importsBySimpleName,
                                                        Set<String> wildcardPackages,
                                                        Map<String, LibrarySource> availableLibraries,
                                                        Map<String, List<LibrarySource>> bySimpleName,
                                                        DiagnosticCollector diagnostics) {
        if (reference.fqcn() != null) {
            return availableLibraries.get(reference.fqcn());
        }

        String importedFqcn = importsBySimpleName.get(reference.simpleName());
        if (importedFqcn != null) {
            return availableLibraries.get(importedFqcn);
        }

        String samePackageFqcn = packageName.isBlank()
                ? reference.simpleName()
                : packageName + "." + reference.simpleName();
        LibrarySource samePackageSource = availableLibraries.get(samePackageFqcn);
        if (samePackageSource != null) {
            return samePackageSource;
        }

        List<LibrarySource> wildcardCandidates = bySimpleName.getOrDefault(reference.simpleName(), List.of()).stream()
                .filter(librarySource -> wildcardPackages.contains(librarySource.packageName()))
                .toList();
        if (wildcardCandidates.size() == 1) {
            return wildcardCandidates.get(0);
        }
        if (wildcardCandidates.size() > 1) {
            diagnostics.error(reference.node(),
                    "Ambiguous on-chain library reference '" + reference.simpleName() + "'",
                    "Add an explicit import for one of: " + wildcardCandidates.stream()
                            .map(LibrarySource::fqcn)
                            .sorted()
                            .toList());
            return null;
        }

        List<LibrarySource> candidates = bySimpleName.getOrDefault(reference.simpleName(), List.of());
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.size() > 1) {
            diagnostics.error(reference.node(),
                    "Ambiguous on-chain library reference '" + reference.simpleName() + "'",
                    "Add an explicit import for one of: " + candidates.stream()
                            .map(LibrarySource::fqcn)
                            .sorted()
                            .toList());
        }
        return null;
    }

    private static void enqueue(LibrarySource librarySource,
                                Map<String, LibrarySource> resolved,
                                Set<String> queued,
                                Queue<LibrarySource> toProcess) {
        if (librarySource == null || resolved.containsKey(librarySource.fqcn()) || !queued.add(librarySource.fqcn())) {
            return;
        }
        toProcess.add(librarySource);
    }

    private static SourceContext parseSourceContext(String source,
                                                    String packageName,
                                                    DiagnosticCollector diagnostics) {
        try {
            var cu = parseCompilationUnit(source);
            var resolvedPackageName = packageName == null || packageName.isBlank()
                    ? cu.getPackageDeclaration().map(pd -> pd.getName().asString()).orElse("")
                    : packageName;
            var importsBySimpleName = new LinkedHashMap<String, String>();
            var wildcardPackages = new LinkedHashSet<String>();
            var staticReferences = new ArrayList<ClassReference>();

            for (ImportDeclaration importDeclaration : cu.getImports()) {
                if (importDeclaration.isAsterisk()) {
                    if (!importDeclaration.isStatic()) {
                        wildcardPackages.add(importDeclaration.getName().asString());
                    }
                    continue;
                }
                if (importDeclaration.isStatic()) {
                    continue;
                }
                String fqcn = importDeclaration.getName().asString();
                importsBySimpleName.put(simpleName(fqcn), fqcn);
            }

            cu.findAll(MethodCallExpr.class).forEach(methodCall -> methodCall.getScope().ifPresent(scope -> {
                classReferenceFromScope(scope).ifPresent(staticReferences::add);
            }));
            cu.findAll(FieldAccessExpr.class).forEach(fieldAccess ->
                    classReferenceFromScope(fieldAccess.getScope()).ifPresent(staticReferences::add));

            return new SourceContext(resolvedPackageName, importsBySimpleName, wildcardPackages, staticReferences);
        } catch (RuntimeException e) {
            var fallbackPackageName = packageName == null ? extractPackageName(source) : packageName;
            var staticReferences = extractFallbackClassReferences(source);
            return new SourceContext(fallbackPackageName, extractImportPaths(source),
                    extractWildcardImportPackages(source), staticReferences);
        }
    }

    private static Map<String, LibrarySource> normalizeLibrarySources(Map<String, ?> availableLibraries) {
        var result = new LinkedHashMap<String, LibrarySource>();
        for (var entry : availableLibraries.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof LibrarySource librarySource) {
                result.put(librarySource.fqcn(), librarySource);
            } else if (value instanceof String source) {
                putLibrarySource(result, entry.getKey(), source);
            } else if (value != null) {
                throw new IllegalArgumentException("Unsupported library source value type: "
                        + value.getClass().getName());
            }
        }
        return result;
    }

    private static Map<String, List<LibrarySource>> indexBySimpleName(Collection<LibrarySource> librarySources) {
        var result = new HashMap<String, List<LibrarySource>>();
        for (LibrarySource librarySource : librarySources) {
            result.computeIfAbsent(librarySource.simpleName(), ignored -> new ArrayList<>()).add(librarySource);
        }
        result.replaceAll((ignored, sources) -> Collections.unmodifiableList(sources));
        return result;
    }

    private static LibrarySource librarySourceFromPath(String fqcn, String resourcePath, String source) {
        String simpleName = simpleName(fqcn);
        String packageName = packageName(fqcn);
        return new LibrarySource(fqcn, simpleName, packageName, resourcePath, source);
    }

    private static LibrarySource librarySourceFromLegacyKey(String key, String source) {
        Objects.requireNonNull(source, "source");
        if (key == null || key.isBlank()) {
            return librarySource(source);
        }
        String packageName = extractPackageName(source);
        if (packageName.isBlank() && key.contains(".")) {
            String fqcn = key;
            String resolvedSimpleName = simpleName(fqcn);
            String resolvedPackageName = packageName(fqcn);
            String resourcePath = resolvedPackageName.replace('.', '/') + "/" + resolvedSimpleName + ".java";
            return new LibrarySource(fqcn, resolvedSimpleName, resolvedPackageName, resourcePath, source);
        }
        return librarySource(source);
    }

    private static String fqcnFromResourcePath(String resourcePath) {
        String normalized = normalizeResourcePath(resourcePath);
        return normalized.substring(0, normalized.length() - ".java".length()).replace('/', '.');
    }

    public static Optional<String> extractTopLevelTypeName(String source) {
        try {
            var cu = parseCompilationUnit(source);
            if (!cu.getTypes().isEmpty()) {
                return Optional.of(cu.getTypes().get(0).getNameAsString());
            }
        } catch (RuntimeException ignored) {
            // Fall through to regex extraction for partial or otherwise unparsable source.
        }
        Matcher matcher = TYPE_PATTERN.matcher(source);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static CompilationUnit parseCompilationUnit(String source) {
        var configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        var result = new JavaParser(configuration).parse(source);
        if (!result.isSuccessful()) {
            throw new IllegalArgumentException("Could not parse Java source: " + result.getProblems());
        }
        return result.getResult().orElseThrow(() ->
                new IllegalArgumentException("Could not parse Java source: " + result.getProblems()));
    }

    private static String simpleName(String fqcn) {
        int lastDot = fqcn.lastIndexOf('.');
        return lastDot < 0 ? fqcn : fqcn.substring(lastDot + 1);
    }

    private static String packageName(String fqcn) {
        int lastDot = fqcn.lastIndexOf('.');
        return lastDot < 0 ? "" : fqcn.substring(0, lastDot);
    }

    private static boolean looksLikeClassName(String name) {
        return !name.isBlank() && Character.isUpperCase(name.charAt(0));
    }

    private static Optional<ClassReference> classReferenceFromScope(Node scope) {
        String scopeText = scope.toString();
        return classReferenceFromText(scopeText, scope);
    }

    private static List<ClassReference> extractFallbackClassReferences(String source) {
        var result = new ArrayList<ClassReference>();
        Matcher matcher = STATIC_REFERENCE_PATTERN.matcher(source);
        while (matcher.find()) {
            classReferenceFromText(matcher.group(1), null).ifPresent(result::add);
        }
        return result;
    }

    private static Optional<ClassReference> classReferenceFromText(String scopeText, Node node) {
        String className = simpleName(scopeText);
        if (!looksLikeClassName(className)) {
            return Optional.empty();
        }
        String fqcn = scopeText.contains(".") && Character.isLowerCase(scopeText.charAt(0))
                ? scopeText
                : null;
        return Optional.of(new ClassReference(className, fqcn, node));
    }

    private static String normalizeResourcePath(String resourcePath) {
        return resourcePath.replace(File.separatorChar, '/').replace('\\', '/');
    }

    private record SourceContext(String packageName,
                                 Map<String, String> importsBySimpleName,
                                 Set<String> wildcardPackages,
                                 List<ClassReference> staticReferences) {
    }

    private record ClassReference(String simpleName, String fqcn, Node node) {
    }
}
