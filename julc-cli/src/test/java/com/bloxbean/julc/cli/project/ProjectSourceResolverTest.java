package com.bloxbean.julc.cli.project;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProjectSourceResolverTest {

    @Test
    void buildPoolIncludesClasspathSources() {
        var pool = ProjectSourceResolver.buildPool(Map.of());
        // Should include stdlib sources from classpath (ListsLib, MapLib, etc.)
        assertFalse(pool.isEmpty(), "Pool should include classpath stdlib sources");
        assertTrue(pool.containsKey("ListsLib"), "Pool should contain ListsLib");
    }

    @Test
    void buildPoolUserSourcesOverrideClasspath() {
        String customSource = "public class ListsLib { /* custom */ }";
        var pool = ProjectSourceResolver.buildPool(Map.of("ListsLib", customSource));
        assertEquals(customSource, pool.get("ListsLib"));
    }

    @Test
    void resolveFindsTransitiveDependencies() {
        String validatorSource = """
                import com.example.Helper;
                @Validator
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

        var pool = Map.of("Helper", helperSource, "Util", utilSource);
        var resolved = ProjectSourceResolver.resolve(validatorSource, pool);
        assertEquals(2, resolved.size());
    }
}
