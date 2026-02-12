package com.bloxbean.cardano.julc.testkit;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.compiler.LibrarySourceResolver;
import com.bloxbean.cardano.julc.compiler.JulcCompiler;
import com.bloxbean.cardano.julc.stdlib.StdlibRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Auto-discovers validator source files and their library dependencies
 * for test compilation.
 * <p>
 * Three-tier library discovery:
 * <ol>
 *   <li><b>Tier 1 — Same-project sources:</b> Import statements are resolved
 *       to {@code .java} files under the source root directory</li>
 *   <li><b>Tier 2 — Classpath JAR sources:</b> {@code META-INF/plutus-sources/}
 *       directories are scanned from classpath JARs</li>
 *   <li><b>Tier 3 — Transitive resolution:</b> Each discovered library's imports
 *       are recursively resolved until no new libraries are found</li>
 * </ol>
 */
public final class SourceDiscovery {

    private static final Path DEFAULT_SOURCE_ROOT = Path.of("src/main/java");

    private SourceDiscovery() {}

    /**
     * Derive the Java source file path for a given class.
     *
     * @param clazz      the class to locate
     * @param sourceRoot the root of the source tree (e.g. {@code src/main/java})
     * @return the path to the {@code .java} file
     */
    public static Path sourceFileFor(Class<?> clazz, Path sourceRoot) {
        String relativePath = clazz.getName().replace('.', '/') + ".java";
        return sourceRoot.resolve(relativePath);
    }

    /**
     * Compile a validator class with auto-discovered library dependencies.
     * Uses the default source root ({@code src/main/java}).
     *
     * @param validatorClass the validator class to compile
     * @return the compilation result
     */
    public static CompileResult compile(Class<?> validatorClass) {
        return compile(validatorClass, DEFAULT_SOURCE_ROOT);
    }

    /**
     * Compile a validator by fully-qualified class name with auto-discovered library dependencies.
     * <p>
     * Useful when {@code -proc:only} prevents {@code .class} file generation.
     *
     * @param fqcn       the fully-qualified class name (e.g., "com.example.MyValidator")
     * @param sourceRoot the root of the source tree
     * @return the compilation result
     * @throws AssertionError if the source file cannot be read or compilation fails
     */
    public static CompileResult compile(String fqcn, Path sourceRoot) {
        Path sourceFile = sourceRoot.resolve(fqcn.replace('.', '/') + ".java");
        String validatorSource;
        try {
            validatorSource = Files.readString(sourceFile);
        } catch (IOException e) {
            throw new AssertionError("Cannot read validator source for " + fqcn + ": " + sourceFile, e);
        }

        // Build library pool and resolve dependencies
        Map<String, String> pool = buildLibraryPool(validatorSource, sourceRoot);
        List<String> libSources = LibrarySourceResolver.resolve(validatorSource, pool);

        // Compile with stdlib
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry()::lookup);
        var result = compiler.compile(validatorSource, libSources);

        if (result.hasErrors()) {
            throw new AssertionError("Compilation failed for " + fqcn + ": " + result.diagnostics());
        }

        return result;
    }

    /**
     * Compile a validator by fully-qualified class name.
     * Uses the default source root ({@code src/main/java}).
     *
     * @param fqcn the fully-qualified class name
     * @return the compilation result
     */
    public static CompileResult compile(String fqcn) {
        return compile(fqcn, DEFAULT_SOURCE_ROOT);
    }

    /**
     * Compile a validator class with auto-discovered library dependencies.
     *
     * @param validatorClass the validator class to compile
     * @param sourceRoot     the root of the source tree
     * @return the compilation result
     * @throws AssertionError if the source file cannot be read or compilation fails
     */
    public static CompileResult compile(Class<?> validatorClass, Path sourceRoot) {
        Path sourceFile = sourceFileFor(validatorClass, sourceRoot);
        String validatorSource;
        try {
            validatorSource = Files.readString(sourceFile);
        } catch (IOException e) {
            throw new AssertionError("Cannot read validator source: " + sourceFile, e);
        }

        // Build library pool and resolve dependencies
        Map<String, String> pool = buildLibraryPool(validatorSource, sourceRoot);
        List<String> libSources = LibrarySourceResolver.resolve(validatorSource, pool);

        // Compile with stdlib
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry()::lookup);
        var result = compiler.compile(validatorSource, libSources);

        if (result.hasErrors()) {
            throw new AssertionError("Compilation failed for " + validatorClass.getSimpleName()
                    + ": " + result.diagnostics());
        }

        return result;
    }

    /**
     * Build the available library pool from same-project sources (Tier 1)
     * and classpath META-INF/plutus-sources/ (Tier 2).
     */
    private static Map<String, String> buildLibraryPool(String validatorSource, Path sourceRoot) {
        var pool = new LinkedHashMap<String, String>();

        // Tier 2: Classpath sources (added first, lower precedence)
        pool.putAll(LibrarySourceResolver.scanClasspathSources(
                Thread.currentThread().getContextClassLoader()));

        // Tier 1: Same-project sources from import paths (higher precedence)
        Map<String, String> importPaths = LibrarySourceResolver.extractImportPaths(validatorSource);
        for (var entry : importPaths.entrySet()) {
            String simpleName = entry.getKey();
            String fullPath = entry.getValue();

            // Convert package path to file system path
            Path sourceFile = sourceRoot.resolve(fullPath.replace('.', '/') + ".java");
            if (Files.exists(sourceFile)) {
                try {
                    pool.put(simpleName, Files.readString(sourceFile));
                } catch (IOException e) {
                    // skip unreadable files
                }
            }
        }

        // Tier 1b: Same-package sources (no import needed in Java)
        String pkg = LibrarySourceResolver.extractPackageName(validatorSource);
        if (!pkg.isEmpty()) {
            Path pkgDir = sourceRoot.resolve(pkg.replace('.', '/'));
            if (Files.isDirectory(pkgDir)) {
                try (var stream = Files.list(pkgDir)) {
                    stream.filter(p -> p.toString().endsWith(".java"))
                            .forEach(p -> {
                                try {
                                    String src = Files.readString(p);
                                    if (src.contains("@OnchainLibrary")) {
                                        String name = p.getFileName().toString().replace(".java", "");
                                        pool.putIfAbsent(name, src);
                                    }
                                } catch (IOException ignored) {}
                            });
                } catch (IOException ignored) {}
            }
        }

        // Also scan transitive imports from already-found sources
        // (LibrarySourceResolver.resolve handles this, but we need to add
        //  same-project sources for transitive deps too)
        addTransitiveSameProjectSources(pool, sourceRoot);

        return pool;
    }

    /**
     * Recursively discover same-project sources referenced by already-found libraries.
     */
    private static void addTransitiveSameProjectSources(Map<String, String> pool, Path sourceRoot) {
        boolean changed = true;
        while (changed) {
            changed = false;
            var snapshot = new LinkedHashMap<>(pool);
            for (String libSource : snapshot.values()) {
                Map<String, String> importPaths = LibrarySourceResolver.extractImportPaths(libSource);
                for (var entry : importPaths.entrySet()) {
                    String simpleName = entry.getKey();
                    if (pool.containsKey(simpleName)) continue;

                    String fullPath = entry.getValue();
                    Path sourceFile = sourceRoot.resolve(fullPath.replace('.', '/') + ".java");
                    if (Files.exists(sourceFile)) {
                        try {
                            pool.put(simpleName, Files.readString(sourceFile));
                            changed = true;
                        } catch (IOException e) {
                            // skip
                        }
                    }
                }
            }
        }
    }
}
