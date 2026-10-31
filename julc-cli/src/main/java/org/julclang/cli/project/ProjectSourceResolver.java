package org.julclang.cli.project;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.LibrarySource;
import org.julclang.compiler.LibrarySourceResolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges classpath stdlib sources with user project sources.
 */
public final class ProjectSourceResolver {

    private ProjectSourceResolver() {}

    /**
     * Build a complete library pool from classpath stdlib + user library sources.
     *
     * @param userLibraries user library name->source map from ProjectScanner
     * @return merged library pool
     */
    public static Map<String, LibrarySource> buildPool(Map<String, String> userLibraries) {
        // 1. Classpath stdlib (META-INF/plutus-sources/ from julc-stdlib JAR)
        var pool = new LinkedHashMap<>(classpathSources());

        // 2. User sources override classpath sources
        LibrarySourceResolver.librarySourcesFrom(userLibraries).forEach(pool::put);

        return pool;
    }

    private static Map<String, LibrarySource> classpathSources() {
        return LibrarySourceResolver.scanClasspathSources(ProjectSourceResolver.class.getClassLoader());
    }

    /**
     * Compiler options for a pool built by {@link #buildPool}: its class-path sources are JAR sources, which the javac
     * gate ranks below the project's (ADR-068 D8).
     */
    public static CompilerOptions options(CompilerOptions options, Map<String, ?> pool) {
        var sources = new LinkedHashMap<String, LibrarySource>();
        pool.forEach((key, value) -> {
            if (value instanceof LibrarySource source) sources.put(key, source);
        });
        return options.setJarLibrarySources(LibrarySourceResolver.jarSourceTexts(sources, classpathSources()));
    }

    /**
     * Resolve libraries for a validator source.
     *
     * @param validatorSource the validator source code
     * @param pool            the complete library pool
     * @return resolved library sources in dependency order
     */
    public static List<String> resolve(String validatorSource, Map<String, ?> pool) {
        return LibrarySourceResolver.resolve(validatorSource, pool);
    }
}
