package org.julclang.compiler;

import org.julclang.compiler.error.DiagnosticCollector;
import org.julclang.compiler.frontend.javac.ExpressionRoles;
import org.julclang.compiler.frontend.javac.JavacSyntax;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import org.julclang.compiler.frontend.javac.SourceOrderScanner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.Comparator;
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
import java.util.function.Consumer;
import java.util.function.Function;
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

    /** A library source whose class javac names {@code fqcn}: the name, not the source's text, gives its package. */
    public static LibrarySource librarySourceFromQualifiedName(String fqcn, String source) {
        Objects.requireNonNull(source, "source");
        return librarySourceFromPath(fqcn, fqcn.replace('.', '/') + ".java", source);
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
     * The sources of a host's merged pool that are JAR sources (ADR-068 D8), by provenance: the entries of
     * {@code pool} that are still the very {@link LibrarySource} {@code jarSources} has for that key; a project source
     * put over one is a project source even when its text is the same. A host passes them to
     * {@link CompilerOptions#setJarLibrarySources}, which compares them by identity; it prunes nothing itself.
     */
    public static List<String> jarSourceTexts(Map<String, LibrarySource> pool, Map<String, LibrarySource> jarSources) {
        var sources = new ArrayList<String>();
        pool.forEach((key, source) -> {
            if (jarSources.get(key) == source) sources.add(source.source());
        });
        return sources;
    }

    /** {@code options} with every source of a class-path pool marked as a JAR source (a host with no project pool). */
    public static CompilerOptions withJarSources(CompilerOptions options, Map<String, LibrarySource> classpathPool) {
        return options.setJarLibrarySources(classpathPool.values().stream().map(LibrarySource::source).toList());
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
        return scanClasspathSources(classLoader, problem -> { });
    }

    /**
     * {@link #scanClasspathSources(ClassLoader)}, reporting every entry it had to skip (ADR-071 D2). Each index and
     * each indexed source is read independently, so one unreadable jar costs only its own sources; resources are
     * opened without the JDK's URL cache, so a jar rebuilt in a long-lived JVM (a Gradle daemon) is read from disk
     * instead of through a stale cached {@code JarFile}.
     *
     * @param problems receives one message per index, source or directory listing that could not be read (a resource
     *                 the class loader lists but that does not exist is not a problem, and is passed over)
     */
    public static Map<String, LibrarySource> scanClasspathSources(ClassLoader classLoader, Consumer<String> problems) {
        var result = new LinkedHashMap<String, LibrarySource>();
        // JARs must ship index.txt for reliable discovery. Loose file-system
        // directories are also scanned to support IDE/dev/test classpaths.
        Enumeration<URL> indexes;
        try {
            indexes = classLoader.getResources(PLUTUS_SOURCES_INDEX);
        } catch (IOException e) {
            problems.accept("cannot list " + PLUTUS_SOURCES_INDEX + " resources: " + e);
            indexes = Collections.emptyEnumeration();
        }
        while (indexes.hasMoreElements()) {
            URL index = indexes.nextElement();
            try {
                scanIndexedSources(index, result, problems);
            } catch (IOException | IllegalArgumentException e) {
                problems.accept("cannot read " + index + ": " + e);
            }
        }
        Enumeration<URL> resources;
        try {
            resources = classLoader.getResources(PLUTUS_SOURCES_DIR);
        } catch (IOException e) {
            problems.accept("cannot list " + PLUTUS_SOURCES_DIR + " resources: " + e);
            resources = Collections.emptyEnumeration();
        }
        while (resources.hasMoreElements()) {
            URL resourceUrl = resources.nextElement();
            if ("file".equals(resourceUrl.getProtocol())) {
                File dir = new File(resourceUrl.getPath());
                scanFileSystemSources(dir, dir, result, problems);
            }
        }
        return result;
    }

    private static void scanIndexedSources(URL indexUrl, Map<String, LibrarySource> result, Consumer<String> problems)
            throws IOException {
        String indexContent;
        try (var indexStream = openUncached(indexUrl)) {
            indexContent = new String(indexStream.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String rawEntry : indexContent.split("\n")) {
            String entry = rawEntry.strip();
            if (entry.isEmpty() || !entry.endsWith(".java")) continue;
            URL sourceUrl = null;
            try {
                sourceUrl = resolveIndexedSourceUrl(indexUrl, entry);
                try (var sourceStream = openUncached(sourceUrl)) {
                    var source = new String(sourceStream.readAllBytes(), StandardCharsets.UTF_8);
                    var fqcn = fqcnFromResourcePath(entry);
                    result.putIfAbsent(fqcn, librarySourceFromPath(fqcn, normalizeResourcePath(entry), source));
                }
            } catch (IOException | IllegalArgumentException e) {
                problems.accept("cannot read " + (sourceUrl != null ? sourceUrl : entry) + " listed in " + indexUrl
                        + ": " + e);
            }
        }
    }

    /** Opens a resource bypassing the JDK's URL cache (a cached JarFile outlives a rebuilt jar). */
    private static InputStream openUncached(URL url) throws IOException {
        var connection = url.openConnection();
        connection.setUseCaches(false);
        return connection.getInputStream();
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

    private static void scanFileSystemSources(File root, File dir, Map<String, LibrarySource> result,
                                              Consumer<String> problems) {
        if (!dir.exists() || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) {
            problems.accept("cannot list " + dir);
            return;
        }
        // In name order: which of two sources of one name is kept must not depend on the file system (I4).
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            if (file.isDirectory()) {
                scanFileSystemSources(root, file, result, problems);
            } else if (file.getName().endsWith(".java")) {
                try {
                    String source = Files.readString(file.toPath());
                    String resourcePath = normalizeResourcePath(root.toPath().relativize(file.toPath()).toString());
                    String fqcn = fqcnFromResourcePath(resourcePath);
                    result.putIfAbsent(fqcn, librarySourceFromPath(fqcn, resourcePath, source));
                } catch (IOException e) {
                    problems.accept("cannot read " + file + ": " + e);
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
    /**
     * The library sources a host gives a compilation from its pool: what the source's imports and references resolve to,
     * transitively, in that order. Both frontends compile exactly these as libraries (ADR-068 D8); the javac frontend also
     * reads the bundled stdlib sources for their types alone.
     */
    public static List<String> resolve(String source, Map<String, ?> availableLibraries) {
        return resolveLibrarySources(source, normalizeLibrarySources(availableLibraries)).stream()
                .map(LibrarySource::source)
                .toList();
    }

    /**
     * {@link #resolve(String, Map)}, also asking {@code classesOfTheCompilation} for a name the pool lacks (ADR-071 D1):
     * a host that compiles inside javac admits a library of its own compilation that it has not collected, such as a
     * class that carries only a framework's marker and was entered in a round the host never saw. It is asked for exact
     * names only (an import, the source's package, a qualified name, a wildcard package), never through the pool-wide
     * simple-name fallback, and what it returns is never added to the pool, so one source's resolution does not depend
     * on another's.
     */
    public static List<String> resolve(String source, Map<String, ?> availableLibraries,
                                       Function<String, LibrarySource> classesOfTheCompilation) {
        return resolveLibrarySources(source, normalizeLibrarySources(availableLibraries), classesOfTheCompilation).stream()
                .map(LibrarySource::source)
                .toList();
    }

    public static List<LibrarySource> resolveLibrarySources(String source, Map<String, LibrarySource> availableLibraries) {
        if (availableLibraries.isEmpty()) {
            return List.of();
        }
        return resolveLibrarySources(source, availableLibraries, fqcn -> null);
    }

    private static List<LibrarySource> resolveLibrarySources(String source, Map<String, LibrarySource> availableLibraries,
                                                             Function<String, LibrarySource> classesOfTheCompilation) {
        var asked = new HashMap<String, Optional<LibrarySource>>();
        Function<String, LibrarySource> admitted = fqcn -> availableLibraries.containsKey(fqcn) ? null
                : asked.computeIfAbsent(fqcn, name -> Optional.ofNullable(classesOfTheCompilation.apply(name))).orElse(null);
        Function<String, LibrarySource> exact = fqcn -> {
            var pooled = availableLibraries.get(fqcn);
            return pooled != null ? pooled : admitted.apply(fqcn);
        };
        var lookup = new Lookup(exact, admitted);
        var bySimpleName = indexBySimpleName(availableLibraries.values());
        var diagnostics = new DiagnosticCollector("<source>");
        var resolved = new LinkedHashMap<String, LibrarySource>();
        var queued = new HashSet<String>();
        Queue<LibrarySource> toProcess = new ArrayDeque<>();

        enqueueReferencedLibraries(source, extractPackageName(source), "<source>", lookup,
                bySimpleName, resolved, queued, toProcess, diagnostics);
        diagnostics.throwIfErrors();

        while (!toProcess.isEmpty()) {
            LibrarySource librarySource = toProcess.poll();
            if (resolved.containsKey(librarySource.fqcn())) {
                continue;
            }
            resolved.put(librarySource.fqcn(), librarySource);
            enqueueReferencedLibraries(librarySource.source(), librarySource.packageName(), librarySource.resourcePath(),
                    lookup, bySimpleName, resolved, queued, toProcess, diagnostics);
            diagnostics.throwIfErrors();
        }

        return new ArrayList<>(resolved.values());
    }

    private static void enqueueReferencedLibraries(String source,
                                                   String packageName,
                                                   String fileName,
                                                   Lookup lookup,
                                                   Map<String, List<LibrarySource>> bySimpleName,
                                                   Map<String, LibrarySource> resolved,
                                                   Set<String> queued,
                                                   Queue<LibrarySource> toProcess,
                                                   DiagnosticCollector diagnostics) {
        diagnostics.setFileName(fileName);
        SourceContext context = parseSourceContext(source, packageName);

        for (String fqcn : context.importsBySimpleName().values()) {
            enqueue(lookup.exact().apply(fqcn), resolved, queued, toProcess);
        }

        for (ClassReference reference : context.staticReferences()) {
            LibrarySource librarySource = resolveStaticReference(reference, context.packageName(),
                    context.importsBySimpleName(), context.wildcardPackages(),
                    lookup, bySimpleName, diagnostics);
            enqueue(librarySource, resolved, queued, toProcess);
        }

        for (ClassReference reference : context.annotationReferences()) {
            enqueue(resolveAnnotationReference(reference, context.packageName(), context.importsBySimpleName(),
                    context.wildcardPackages(), lookup, bySimpleName), resolved, queued, toProcess);
        }
    }

    /**
     * The library an annotation names, if it is an annotation interface of the pool (ADR-071 D1). Only a library marker
     * can be one: a pool holds {@code @OnchainLibrary} classes and the annotation interfaces that carry it. A marker the
     * source uses without an import, from its own package, is a reference like any other, so its source, and the
     * libraries it refers to, are compiled with the source. Resolved by the qualified name, an import, the source's
     * package, or a single wildcard match; never by the pool-wide simple-name fallback, and never with a diagnostic: an
     * annotation that names no library is javac's to report.
     */
    private static LibrarySource resolveAnnotationReference(ClassReference reference,
                                                            String packageName,
                                                            Map<String, String> importsBySimpleName,
                                                            Set<String> wildcardPackages,
                                                            Lookup lookup,
                                                            Map<String, List<LibrarySource>> bySimpleName) {
        LibrarySource candidate;
        if (reference.fqcn() != null) {
            candidate = lookup.exact().apply(reference.fqcn());
        } else if (importsBySimpleName.containsKey(reference.simpleName())) {
            candidate = lookup.exact().apply(importsBySimpleName.get(reference.simpleName()));
        } else {
            candidate = lookup.exact().apply(packageName.isBlank()
                    ? reference.simpleName()
                    : packageName + "." + reference.simpleName());
            if (candidate == null) {
                var wildcard = wildcardCandidates(reference.simpleName(), wildcardPackages, lookup, bySimpleName);
                candidate = wildcard.size() == 1 ? wildcard.getFirst() : null;
            }
        }
        return candidate != null && declaresAnnotationInterface(candidate) ? candidate : null;
    }

    private static boolean declaresAnnotationInterface(LibrarySource librarySource) {
        return Pattern.compile("@\\s*interface\\s+" + Pattern.quote(librarySource.simpleName()) + "\\b")
                .matcher(librarySource.source()).find();
    }

    private static LibrarySource resolveStaticReference(ClassReference reference,
                                                        String packageName,
                                                        Map<String, String> importsBySimpleName,
                                                        Set<String> wildcardPackages,
                                                        Lookup lookup,
                                                        Map<String, List<LibrarySource>> bySimpleName,
                                                        DiagnosticCollector diagnostics) {
        if (reference.fqcn() != null) {
            return lookup.exact().apply(reference.fqcn());
        }

        String importedFqcn = importsBySimpleName.get(reference.simpleName());
        if (importedFqcn != null) {
            return lookup.exact().apply(importedFqcn);
        }

        String samePackageFqcn = packageName.isBlank()
                ? reference.simpleName()
                : packageName + "." + reference.simpleName();
        LibrarySource samePackageSource = lookup.exact().apply(samePackageFqcn);
        if (samePackageSource != null) {
            return samePackageSource;
        }

        List<LibrarySource> wildcardCandidates = wildcardCandidates(reference.simpleName(), wildcardPackages, lookup,
                bySimpleName);
        if (wildcardCandidates.size() == 1) {
            return wildcardCandidates.get(0);
        }
        if (wildcardCandidates.size() > 1) {
            diagnostics.error(reference.line(), reference.column(),
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
            diagnostics.error(reference.line(), reference.column(),
                    "Ambiguous on-chain library reference '" + reference.simpleName() + "'",
                    "Add an explicit import for one of: " + candidates.stream()
                            .map(LibrarySource::fqcn)
                            .sorted()
                            .toList());
        }
        return null;
    }

    /**
     * The pool's libraries named {@code simpleName} in a wildcard-imported package; when the pool has none, those the
     * compilation admits under the same names (ADR-071 D1).
     */
    private static List<LibrarySource> wildcardCandidates(String simpleName, Set<String> wildcardPackages, Lookup lookup,
                                                          Map<String, List<LibrarySource>> bySimpleName) {
        var pooled = bySimpleName.getOrDefault(simpleName, List.of()).stream()
                .filter(librarySource -> wildcardPackages.contains(librarySource.packageName()))
                .toList();
        if (!pooled.isEmpty()) return pooled;
        return wildcardPackages.stream()
                .map(wildcardPackage -> lookup.admitted().apply(wildcardPackage + "." + simpleName))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * How a resolution finds a library by name: {@code exact} looks in the pool, then among the classes the compilation
     * admits; {@code admitted} only among the latter.
     */
    private record Lookup(Function<String, LibrarySource> exact, Function<String, LibrarySource> admitted) {
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
    /** A source's package, imports and class references, as javac's parser reads them; a regex reading if it cannot. */
    static SourceContext parseSourceContext(String source, String packageName) {
        var syntax = JavacSyntax.read(source);
        if (!syntax.hasErrors()) return sourceContext(syntax, packageName);
        return fallbackSourceContext(source, packageName);
    }

    private static SourceContext fallbackSourceContext(String source, String packageName) {
        var fallbackPackageName = packageName == null ? extractPackageName(source) : packageName;
        var staticReferences = extractFallbackClassReferences(source);
        return new SourceContext(fallbackPackageName, extractImportPaths(source),
                extractWildcardImportPackages(source), staticReferences, List.of());
    }

    /**
     * javac's reading: the imports, then every call's qualifier, then every field access's qualifier, each in pre-order,
     * as JavaParser's reading lists them.
     */
    private static SourceContext sourceContext(JavacSyntax syntax, String packageName) {
        var unit = syntax.unit();
        var resolvedPackageName = packageName == null || packageName.isBlank()
                ? (unit.getPackageName() == null ? "" : unit.getPackageName().toString())
                : packageName;
        var importsBySimpleName = new LinkedHashMap<String, String>();
        var wildcardPackages = new LinkedHashSet<String>();
        for (ImportTree importTree : unit.getImports()) {
            var name = (MemberSelectTree) importTree.getQualifiedIdentifier();
            if (name.getIdentifier().contentEquals("*")) {
                if (!importTree.isStatic()) wildcardPackages.add(name.getExpression().toString());
                continue;
            }
            if (importTree.isStatic()) continue;
            String fqcn = name.toString();
            importsBySimpleName.put(simpleName(fqcn), fqcn);
        }
        var staticReferences = new ArrayList<ClassReference>();
        var calls = new ArrayList<Tree>();
        var fieldAccesses = new ArrayList<Tree>();
        var annotations = new ArrayList<Tree>();
        new SourceOrderScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
                if (call.getMethodSelect() instanceof MemberSelectTree select) calls.add(select.getExpression());
                return super.visitMethodInvocation(call, unused);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree select, Void unused) {
                if (ExpressionRoles.isFieldAccess(getCurrentPath())) fieldAccesses.add(select.getExpression());
                return super.visitMemberSelect(select, unused);
            }

            @Override
            public Void visitAnnotation(AnnotationTree annotation, Void unused) {
                annotations.add(annotation.getAnnotationType());
                return super.visitAnnotation(annotation, unused);
            }
        }.scan(unit, null);
        for (Tree scope : calls) javacReference(syntax, scope).ifPresent(staticReferences::add);
        for (Tree scope : fieldAccesses) javacReference(syntax, scope).ifPresent(staticReferences::add);
        var annotationReferences = new ArrayList<ClassReference>();
        for (Tree type : annotations) javacReference(syntax, type).ifPresent(annotationReferences::add);
        return new SourceContext(resolvedPackageName, importsBySimpleName, wildcardPackages, staticReferences,
                annotationReferences);
    }

    private static Optional<ClassReference> javacReference(JavacSyntax syntax, Tree scope) {
        return classReferenceFromText(scope.toString(), syntax.line(scope), syntax.column(scope));
    }

    /**
     * {@link #parseSourceContext} for the ADR-068 tools gate: what each reader reads, as text, with the references that
     * can resolve, those whose name is a Java identifier. A call chain's qualifier such as {@code f(x).g} yields a "name"
     * that is each parser's printing of it, and no library has such a name.
     */
    static String describeSourceContext(String source) {
        var context = parseSourceContext(source, null);
        var references = context.staticReferences().stream()
                .filter(reference -> javax.lang.model.SourceVersion.isIdentifier(reference.simpleName()))
                .toList();
        // The four readings the ADR-068 baseline froze, printed as that record printed them.
        return "SourceContext[packageName=" + context.packageName() + ", importsBySimpleName="
                + context.importsBySimpleName() + ", wildcardPackages=" + context.wildcardPackages()
                + ", staticReferences=" + references + "]";
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
        var syntax = JavacSyntax.read(source);
        if (!syntax.hasErrors()) {
            for (Tree declaration : syntax.unit().getTypeDecls()) {
                if (declaration instanceof ClassTree type) return Optional.of(type.getSimpleName().toString());
            }
        }
        Matcher matcher = TYPE_PATTERN.matcher(source);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
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

    private static List<ClassReference> extractFallbackClassReferences(String source) {
        var result = new ArrayList<ClassReference>();
        Matcher matcher = STATIC_REFERENCE_PATTERN.matcher(source);
        while (matcher.find()) {
            classReferenceFromText(matcher.group(1), 0, 0).ifPresent(result::add);
        }
        return result;
    }

    private static Optional<ClassReference> classReferenceFromText(String scopeText, int line, int column) {
        String className = simpleName(scopeText);
        if (!looksLikeClassName(className)) {
            return Optional.empty();
        }
        String fqcn = scopeText.contains(".") && Character.isLowerCase(scopeText.charAt(0))
                ? scopeText
                : null;
        return Optional.of(new ClassReference(className, fqcn, line, column));
    }

    private static String normalizeResourcePath(String resourcePath) {
        return resourcePath.replace(File.separatorChar, '/').replace('\\', '/');
    }

    private record SourceContext(String packageName,
                                 Map<String, String> importsBySimpleName,
                                 Set<String> wildcardPackages,
                                 List<ClassReference> staticReferences,
                                 List<ClassReference> annotationReferences) {
    }

    /** A class a source names as a call's or a field access's qualifier, and where (0, 0 when unknown). */
    private record ClassReference(String simpleName, String fqcn, int line, int column) {
    }
}
