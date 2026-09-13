package org.julclang.cli.project;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProjectSourceResolverTest {

    @Test
    void buildPoolIncludesClasspathSources() {
        var pool = ProjectSourceResolver.buildPool(Map.of());
        // Should include stdlib sources from classpath (ListsLib, MapLib, etc.)
        assertFalse(pool.isEmpty(), "Pool should include classpath stdlib sources");
        assertTrue(pool.containsKey("org.julclang.stdlib.lib.ListsLib"), "Pool should contain ListsLib");
    }

    @Test
    void buildPoolUserSourcesOverrideClasspath() {
        String customSource = "package org.julclang.stdlib.lib; public class ListsLib { /* custom */ }";
        var pool = ProjectSourceResolver.buildPool(Map.of("ListsLib", customSource));
        assertEquals(customSource, pool.get("org.julclang.stdlib.lib.ListsLib").source());
    }

    @Test
    void resolveFindsTransitiveDependencies() {
        String validatorSource = """
                import com.example.Helper;
                @SpendingValidator
                public class V { }
                """;
        String helperSource = """
                package com.example;
                import com.example.Util;
                public class Helper { }
                """;
        String utilSource = """
                package com.example;
                public class Util { }
                """;

        var pool = org.julclang.compiler.LibrarySourceResolver.librarySourcesFrom(
                Map.of("Helper", helperSource, "Util", utilSource));
        var resolved = ProjectSourceResolver.resolve(validatorSource, pool);
        assertEquals(2, resolved.size());
    }
}
