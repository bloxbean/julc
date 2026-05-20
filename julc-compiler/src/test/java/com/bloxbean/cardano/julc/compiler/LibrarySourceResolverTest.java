package com.bloxbean.cardano.julc.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

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

        String sumTestSource = "package com.example.util; class SumTest { static int sum(int a, int b) { return a + b; } }";

        Map<String, LibrarySource> pool = pool(sumTestSource);

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
                package com.example.util;
                import com.example.util.Helper;

                class MathUtils { static int max(int a, int b) { return Helper.compare(a, b); } }
                """;

        String helperSource = """
                package com.example.util;
                class Helper { static int compare(int a, int b) { return a > b ? a : b; } }
                """;

        Map<String, LibrarySource> pool = pool(mathUtilsSource, helperSource);

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
        String aSource = "package com.example;\nimport com.example.C;\nclass A {}";
        String bSource = "package com.example;\nimport com.example.C;\nclass B {}";
        String cSource = "package com.example;\nclass C {}";

        Map<String, LibrarySource> pool = pool(aSource, bSource, cSource);

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

        Map<String, LibrarySource> pool = Map.of();
        // Pool is empty — no libraries available

        var resolved = LibrarySourceResolver.resolve(validatorSource, pool);

        assertTrue(resolved.isEmpty());
    }

    @Test
    void resolve_acceptsLegacyStringSourcePool() {
        String validatorSource = """
                import com.example.util.SumTest;

                @Validator
                class MyValidator {}
                """;

        String sumTestSource = "package com.example.util; class SumTest {}";
        Map<String, String> legacyPool = Map.of("SumTest", sumTestSource);

        var resolved = LibrarySourceResolver.resolve(validatorSource, legacyPool);

        assertEquals(List.of(sumTestSource), resolved);
    }

    @Test
    void resolve_acceptsLegacyFqcnKeyForPackageLessSource() {
        String validatorSource = """
                import com.example.util.SumTest;

                class MyValidator {
                    static boolean validate() {
                        return SumTest.check();
                    }
                }
                """;

        String sumTestSource = "class SumTest { static boolean check() { return true; } }";
        Map<String, String> legacyPool = Map.of("com.example.util.SumTest", sumTestSource);

        var resolved = LibrarySourceResolver.resolve(validatorSource, legacyPool);

        assertEquals(List.of(sumTestSource), resolved);
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

        Map<String, LibrarySource> pool = pool(mathLibSource);

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

        Map<String, LibrarySource> pool = pool(aSource, bSource);

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

    @Test
    void scanClasspathSources_readsEveryIndexOnClasspath(@TempDir Path tempDir) throws Exception {
        Path firstJar = createPlutusSourcesJar(tempDir.resolve("first.jar"), Map.of(
                "com/example/FirstLib.java", "package com.example; class FirstLib {}"
        ));
        Path secondJar = createPlutusSourcesJar(tempDir.resolve("second.jar"), Map.of(
                "com/example/SecondLib.java", "package com.example; class SecondLib {}"
        ));

        try (var classLoader = new URLClassLoader(new URL[]{
                firstJar.toUri().toURL(),
                secondJar.toUri().toURL()
        }, null)) {
            Map<String, LibrarySource> result = LibrarySourceResolver.scanClasspathSources(classLoader);

            assertEquals(2, result.size());
            assertTrue(result.get("com.example.FirstLib").source().contains("class FirstLib"));
            assertTrue(result.get("com.example.SecondLib").source().contains("class SecondLib"));
        }
    }

    @Test
    void scanClasspathSources_doesNotDiscoverJarSourcesWithoutIndex(@TempDir Path tempDir) throws Exception {
        Path jar = tempDir.resolve("source-only.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            addJarDirectory(out, "META-INF/");
            addJarDirectory(out, "META-INF/plutus-sources/");
            addJarDirectory(out, "META-INF/plutus-sources/com/");
            addJarDirectory(out, "META-INF/plutus-sources/com/example/");
            addJarEntry(out,
                    "META-INF/plutus-sources/com/example/SourceOnlyLib.java",
                    "package com.example; class SourceOnlyLib {}");
        }

        try (var classLoader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            Map<String, LibrarySource> result = LibrarySourceResolver.scanClasspathSources(classLoader);

            assertTrue(result.isEmpty());
        }
    }

    @Test
    void scanClasspathSources_readsIndexedJarsAndLooseFilesystemDirs(@TempDir Path tempDir) throws Exception {
        Path jar = createPlutusSourcesJar(tempDir.resolve("indexed.jar"), Map.of(
                "com/example/SharedLib.java", "package com.example; class SharedLib { static int fromJar() { return 1; } }",
                "com/example/IndexedOnlyLib.java", "package com.example; class IndexedOnlyLib {}"
        ));

        Path classesDir = tempDir.resolve("classes");
        Path looseSourcesDir = classesDir.resolve("META-INF/plutus-sources/com/example");
        Files.createDirectories(looseSourcesDir);
        Files.writeString(looseSourcesDir.resolve("SharedLib.java"),
                "package com.example; class SharedLib { static int fromLooseDir() { return 2; } }");
        Files.writeString(looseSourcesDir.resolve("LooseOnlyLib.java"),
                "package com.example; class LooseOnlyLib {}");

        try (var classLoader = new URLClassLoader(new URL[]{
                jar.toUri().toURL(),
                classesDir.toUri().toURL()
        }, null)) {
            Map<String, LibrarySource> result = LibrarySourceResolver.scanClasspathSources(classLoader);

            assertEquals(3, result.size());
            assertTrue(result.get("com.example.SharedLib").source().contains("fromJar"));
            assertFalse(result.get("com.example.SharedLib").source().contains("fromLooseDir"));
            assertTrue(result.get("com.example.IndexedOnlyLib").source().contains("class IndexedOnlyLib"));
            assertTrue(result.get("com.example.LooseOnlyLib").source().contains("class LooseOnlyLib"));
        }
    }

    @Test
    void resolve_usesExplicitImportToDisambiguateDuplicateSimpleNames() {
        String validatorSource = """
                import com.left.Utils;

                class MyValidator {
                    static boolean validate() {
                        return Utils.check();
                    }
                }
                """;

        String leftSource = """
                package com.left;
                class Utils { static boolean check() { return true; } }
                """;
        String rightSource = """
                package com.right;
                class Utils { static boolean check() { return false; } }
                """;

        var resolved = LibrarySourceResolver.resolve(validatorSource, pool(leftSource, rightSource));

        assertEquals(1, resolved.size());
        assertEquals(leftSource, resolved.get(0));
    }

    @Test
    void resolve_explicitImportWinsOverSamePackageSimpleName() {
        String validatorSource = """
                package com.validator;

                import com.left.Utils;

                class MyValidator {
                    static boolean validate() {
                        return Utils.check();
                    }
                }
                """;

        String leftSource = """
                package com.left;
                class Utils { static boolean check() { return true; } }
                """;
        String samePackageSource = """
                package com.validator;
                class Utils { static boolean check() { return false; } }
                """;

        var resolved = LibrarySourceResolver.resolve(validatorSource, pool(leftSource, samePackageSource));

        assertEquals(1, resolved.size());
        assertEquals(leftSource, resolved.get(0));
    }

    @Test
    void resolve_findsFullyQualifiedStaticCall() {
        String validatorSource = """
                class MyValidator {
                    static boolean validate() {
                        return com.example.Utils.check();
                    }
                }
                """;

        String source = """
                package com.example;
                class Utils { static boolean check() { return true; } }
                """;

        var resolved = LibrarySourceResolver.resolve(validatorSource, pool(source));

        assertEquals(List.of(source), resolved);
    }

    @Test
    void resolve_findsStaticFieldReference() {
        String validatorSource = """
                package com.example;

                class MyValidator {
                    static boolean validate() {
                        return Constants.ALLOWED;
                    }
                }
                """;

        String source = """
                package com.example;
                class Constants { static final boolean ALLOWED = true; }
                """;

        var resolved = LibrarySourceResolver.resolve(validatorSource, pool(source));

        assertEquals(List.of(source), resolved);
    }

    @Test
    void resolve_findsFullyQualifiedStaticFieldReference() {
        String validatorSource = """
                class MyValidator {
                    static boolean validate() {
                        return com.left.Constants.ALLOWED;
                    }
                }
                """;

        String leftSource = """
                package com.left;
                class Constants { static final boolean ALLOWED = true; }
                """;
        String rightSource = """
                package com.right;
                class Constants { static final boolean ALLOWED = false; }
                """;

        var resolved = LibrarySourceResolver.resolve(validatorSource, pool(leftSource, rightSource));

        assertEquals(List.of(leftSource), resolved);
    }

    @Test
    void resolve_parseFallbackPreservesFullyQualifiedStaticCall() {
        String validatorSource = """
                class MyValidator {
                    static boolean validate() {
                        return com.left.Utils.check(;
                    }
                }
                """;

        String leftSource = "package com.left; class Utils { static boolean check() { return true; } }";
        String rightSource = "package com.right; class Utils { static boolean check() { return false; } }";

        var resolved = LibrarySourceResolver.resolve(validatorSource, pool(leftSource, rightSource));

        assertEquals(List.of(leftSource), resolved);
    }

    @Test
    void resolve_reportsAmbiguousUnqualifiedSimpleName() {
        String validatorSource = """
                class MyValidator {
                    static boolean validate() {
                        return Utils.check();
                    }
                }
                """;

        String leftSource = "package com.left; class Utils { static boolean check() { return true; } }";
        String rightSource = "package com.right; class Utils { static boolean check() { return false; } }";

        CompilerException ex = assertThrows(CompilerException.class,
                () -> LibrarySourceResolver.resolve(validatorSource, pool(leftSource, rightSource)));

        assertTrue(ex.getMessage().contains("Ambiguous on-chain library reference 'Utils'"));
        assertTrue(ex.getMessage().contains("com.left.Utils"));
        assertTrue(ex.getMessage().contains("com.right.Utils"));
    }

    @Test
    void resolve_supportsWildcardImportsAsPackageScan() {
        String validatorSource = """
                import com.example.*;

                class MyValidator {
                    static boolean validate() {
                        return Utils.check();
                    }
                }
                """;

        String source = "package com.example; class Utils { static boolean check() { return true; } }";

        var resolved = LibrarySourceResolver.resolve(validatorSource, pool(source));

        assertEquals(List.of(source), resolved);
    }

    private static Path createPlutusSourcesJar(Path jar, Map<String, String> sources) throws Exception {
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            addJarEntry(out, "META-INF/plutus-sources/index.txt",
                    String.join("\n", sources.keySet()) + "\n");
            for (var entry : sources.entrySet()) {
                addJarEntry(out, "META-INF/plutus-sources/" + entry.getKey(), entry.getValue());
            }
        }
        return jar;
    }

    private static void addJarDirectory(JarOutputStream out, String name) throws Exception {
        out.putNextEntry(new JarEntry(name));
        out.closeEntry();
    }

    private static void addJarEntry(JarOutputStream out, String name, String content) throws Exception {
        out.putNextEntry(new JarEntry(name));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static Map<String, LibrarySource> pool(String... sources) {
        var pool = new java.util.LinkedHashMap<String, LibrarySource>();
        for (String source : sources) {
            LibrarySource librarySource = LibrarySourceResolver.librarySource(source);
            pool.put(librarySource.fqcn(), librarySource);
        }
        return pool;
    }
}
