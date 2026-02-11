package com.bloxbean.cardano.plutus.compiler;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LibrarySourceResolverTest {

    @Test
    void extractImportedClassNames_findsUserImports() {
        String source = """
                import com.example.util.SumTest;
                import com.example.lib.MathUtils;
                import java.math.BigInteger;

                @Validator
                class MyValidator {}
                """;

        Set<String> names = LibrarySourceResolver.extractImportedClassNames(source);

        assertTrue(names.contains("SumTest"));
        assertTrue(names.contains("MathUtils"));
        assertTrue(names.contains("BigInteger")); // regex doesn't filter framework imports
        assertEquals(3, names.size());
    }

    @Test
    void extractImportedClassNames_emptyForNoImports() {
        String source = """
                @Validator
                class MyValidator {
                    @Entrypoint
                    static boolean validate(BigInteger r, BigInteger c) { return true; }
                }
                """;

        Set<String> names = LibrarySourceResolver.extractImportedClassNames(source);
        assertTrue(names.isEmpty());
    }

    @Test
    void extractImportPaths_returnsFullPaths() {
        String source = """
                import com.example.util.SumTest;
                import com.example.lib.MathUtils;
                """;

        Map<String, String> paths = LibrarySourceResolver.extractImportPaths(source);

        assertEquals("com.example.util.SumTest", paths.get("SumTest"));
        assertEquals("com.example.lib.MathUtils", paths.get("MathUtils"));
        assertEquals(2, paths.size());
    }

    @Test
    void resolve_findsDirectDependency() {
        String validatorSource = """
                import com.example.util.SumTest;

                @Validator
                class MyValidator {}
                """;

        String sumTestSource = "class SumTest { static int sum(int a, int b) { return a + b; } }";

        Map<String, String> pool = new LinkedHashMap<>();
        pool.put("SumTest", sumTestSource);

        var resolved = LibrarySourceResolver.resolve(validatorSource, pool);

        assertEquals(1, resolved.size());
        assertEquals(sumTestSource, resolved.get(0));
    }

    @Test
    void resolve_findsTransitiveDependency() {
        String validatorSource = """
                import com.example.util.MathUtils;

                @Validator
                class MyValidator {}
                """;

        String mathUtilsSource = """
                import com.example.util.Helper;

                class MathUtils { static int max(int a, int b) { return Helper.compare(a, b); } }
                """;

        String helperSource = "class Helper { static int compare(int a, int b) { return a > b ? a : b; } }";

        Map<String, String> pool = new LinkedHashMap<>();
        pool.put("MathUtils", mathUtilsSource);
        pool.put("Helper", helperSource);

        var resolved = LibrarySourceResolver.resolve(validatorSource, pool);

        assertEquals(2, resolved.size());
        assertEquals(mathUtilsSource, resolved.get(0));
        assertEquals(helperSource, resolved.get(1));
    }

    @Test
    void resolve_deduplicates() {
        String validatorSource = """
                import com.example.A;
                import com.example.B;

                @Validator
                class MyValidator {}
                """;

        // Both A and B depend on C
        String aSource = "import com.example.C;\nclass A {}";
        String bSource = "import com.example.C;\nclass B {}";
        String cSource = "class C {}";

        Map<String, String> pool = new LinkedHashMap<>();
        pool.put("A", aSource);
        pool.put("B", bSource);
        pool.put("C", cSource);

        var resolved = LibrarySourceResolver.resolve(validatorSource, pool);

        assertEquals(3, resolved.size());
        // C should appear exactly once
        long cCount = resolved.stream().filter(s -> s.equals(cSource)).count();
        assertEquals(1, cCount);
    }

    @Test
    void resolve_ignoresUnknownImports() {
        String validatorSource = """
                import java.math.BigInteger;
                import com.example.Unknown;

                @Validator
                class MyValidator {}
                """;

        Map<String, String> pool = new LinkedHashMap<>();
        // Pool is empty — no libraries available

        var resolved = LibrarySourceResolver.resolve(validatorSource, pool);

        assertTrue(resolved.isEmpty());
    }

    @Test
    void extractPackageName_findsPackage() {
        String source = """
                package com.example.validators;

                @Validator
                class MyValidator {}
                """;

        assertEquals("com.example.validators", LibrarySourceResolver.extractPackageName(source));
    }

    @Test
    void extractPackageName_emptyForNoPackage() {
        String source = """
                @Validator
                class MyValidator {}
                """;

        assertEquals("", LibrarySourceResolver.extractPackageName(source));
    }

    @Test
    void extractReferencedClassNames_findsStaticCalls() {
        String source = """
                package com.example;

                class MyValidator {
                    static boolean validate() {
                        var x = MathLib.abs(y);
                        var z = ListsLib.length(list);
                        return true;
                    }
                }
                """;

        Set<String> names = LibrarySourceResolver.extractReferencedClassNames(source);

        assertTrue(names.contains("MathLib"));
        assertTrue(names.contains("ListsLib"));
    }

    @Test
    void extractReferencedClassNames_ignoresLowerCaseStarting() {
        String source = """
                var x = someVar.method(args);
                """;

        Set<String> names = LibrarySourceResolver.extractReferencedClassNames(source);
        assertFalse(names.contains("someVar"));
    }

    @Test
    void resolve_findsSamePackageReferences() {
        // Validator calls MathLib.abs() without import (same package)
        String validatorSource = """
                package com.example;

                @Validator
                class MyValidator {
                    static boolean validate() {
                        var x = MathLib.abs(y);
                        return true;
                    }
                }
                """;

        String mathLibSource = """
                package com.example;

                @OnchainLibrary
                class MathLib {
                    static long abs(long x) { if (x < 0) { return 0 - x; } else { return x; } }
                }
                """;

        Map<String, String> pool = new LinkedHashMap<>();
        pool.put("MathLib", mathLibSource);

        var resolved = LibrarySourceResolver.resolve(validatorSource, pool);

        assertEquals(1, resolved.size());
        assertEquals(mathLibSource, resolved.get(0));
    }

    @Test
    void resolve_findsTransitiveSamePackageRef() {
        // Validator imports A, and A calls B.helper() without importing B (same package)
        String validatorSource = """
                import com.example.A;

                class MyValidator {}
                """;

        String aSource = """
                package com.example;
                class A {
                    static int calc() { return B.helper(42); }
                }
                """;

        String bSource = """
                package com.example;
                class B {
                    static int helper(int x) { return x; }
                }
                """;

        Map<String, String> pool = new LinkedHashMap<>();
        pool.put("A", aSource);
        pool.put("B", bSource);

        var resolved = LibrarySourceResolver.resolve(validatorSource, pool);

        assertEquals(2, resolved.size());
    }

    @Test
    void scanClasspathSources_returnsEmptyWhenNothingOnClasspath() {
        // Use a classloader that has no META-INF/plutus-sources/
        var result = LibrarySourceResolver.scanClasspathSources(
                ClassLoader.getSystemClassLoader());

        assertNotNull(result);
        // May or may not be empty depending on test classpath, but should not throw
    }
}
