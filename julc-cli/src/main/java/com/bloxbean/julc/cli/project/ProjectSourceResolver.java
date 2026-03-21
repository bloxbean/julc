package com.bloxbean.julc.cli.project;

import com.bloxbean.cardano.julc.compiler.LibrarySourceResolver;

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
    public static Map<String, String> buildPool(Map<String, String> userLibraries) {
        // 1. Classpath stdlib (META-INF/plutus-sources/ from julc-stdlib JAR)
        var pool = new LinkedHashMap<>(
                LibrarySourceResolver.scanClasspathSources(
                        ProjectSourceResolver.class.getClassLoader()));

        // 2. User sources override classpath sources
        pool.putAll(userLibraries);

        return pool;
    }

    /**
     * Resolve libraries for a validator source.
     *
     * @param validatorSource the validator source code
     * @param pool            the complete library pool
     * @return resolved library sources in dependency order
     */
    public static List<String> resolve(String validatorSource, Map<String, String> pool) {
        return LibrarySourceResolver.resolve(validatorSource, pool);
    }
}
