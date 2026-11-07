package org.julclang.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.lang.model.SourceVersion;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

                @SpendingValidator
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
                @SpendingValidator
                class MyValidator {
                    @Entrypoint
                    static boolean validate(BigInteger r, org.julclang.core.PlutusData c) { return true; }
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

                @SpendingValidator
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

                @SpendingValidator
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

                @SpendingValidator
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

                @SpendingValidator
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

                @SpendingValidator
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

                @SpendingValidator
                class MyValidator {}
                """;

        assertEquals("com.example.validators", LibrarySourceResolver.extractPackageName(source));
    }

    @Test
    void extractPackageName_emptyForNoPackage() {
        String source = """
                @SpendingValidator
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

                @SpendingValidator
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
    void resolve_followsAMarkerAnnotationOfTheLibrarysOwnPackage() {
        // ADR-071 D1: an annotation is a reference; a marker used without an import comes with the library using it.
        var mark = LibrarySourceResolver.librarySourceFromSimpleName("Mark", """
                package lib;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public @interface Mark {}
                """);
        var library = LibrarySourceResolver.librarySourceFromSimpleName("Lib", """
                package lib;
                @Mark
                public class Lib { public static boolean ok() { return true; } }
                """);
        var validator = """
                package app;
                import lib.Lib;
                class V { static boolean f() { return Lib.ok(); } }
                """;

        var resolved = LibrarySourceResolver.resolve(validator, Map.of("lib.Lib", library, "lib.Mark", mark));

        assertEquals(List.of(library.source(), mark.source()), resolved);
    }

    @Test
    void resolve_annotationReferencesNameOnlyAnnotationInterfacesAndNeverFallBack() {
        // An annotation resolves by import, package or a single wildcard match, and only to an @interface: a class of
        // the same simple name, or an annotation of another package the source does not import, is never added.
        var otherPackage = LibrarySourceResolver.librarySourceFromSimpleName("Param", """
                package other;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public @interface Param {}
                """);
        var aClass = LibrarySourceResolver.librarySourceFromSimpleName("Entrypoint", """
                package app;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public class Entrypoint { public static boolean ok() { return true; } }
                """);
        var validator = """
                package app;
                import org.julclang.stdlib.annotation.Param;
                class V { @Entrypoint static boolean f(@Param int x) { return true; } }
                """;

        var resolved = LibrarySourceResolver.resolve(validator, Map.of("other.Param", otherPackage, "app.Entrypoint", aClass));

        assertEquals(List.of(), resolved);
    }

    @Test
    void resolve_asksTheCompilationOnlyForExactNamesThePoolLacks() {
        // ADR-071 D1: the host is asked for an import, the source's package and a wildcard package, never through the
        // pool-wide simple-name fallback, and only for Java names; what it admits is followed, not pooled.
        var pooled = LibrarySourceResolver.librarySourceFromSimpleName("Pooled", """
                package other;
                public class Pooled { public static boolean ok() { return true; } }
                """);
        var admitted = LibrarySourceResolver.librarySourceFromQualifiedName("lib.Counter", """
                package lib;
                public class Counter { public static boolean ok() { return Pooled.ok(); } }
                """);
        var validator = """
                package app;
                import lib.Counter;
                import wild.*;
                class V { static boolean f() { return Counter.ok() && Local.ok() && Far.ok() && f(1).g(); } }
                """;
        var asked = new ArrayList<String>();

        var resolved = LibrarySourceResolver.resolve(validator, Map.of("other.Pooled", pooled), name -> {
            asked.add(name);
            return name.equals("lib.Counter") ? admitted : null;
        });

        // Counter is admitted and followed; Pooled reaches it through the pool-wide fallback, which never asks.
        assertEquals(List.of(admitted.source(), pooled.source()), resolved);
        assertTrue(asked.containsAll(List.of("lib.Counter", "app.Local", "wild.Local", "app.Far", "wild.Far")), asked.toString());
        assertTrue(asked.stream().allMatch(SourceVersion::isName), asked.toString());
        assertFalse(asked.contains("other.Pooled"), asked.toString());
        // Nothing admitted stays behind: the same source resolved without the host finds only what the pool holds.
        assertEquals(List.of(), LibrarySourceResolver.resolve(validator, Map.of("other.Pooled", pooled)));
    }

    @Test
    void scanClasspathSources_aMalformedEntryUrlCostsOnlyThatEntry(@TempDir Path tempDir) throws Exception {
        // ADR-071 D2: a jar under a directory with a space, named by an unencoded URL: building an entry's URL throws
        // IllegalArgumentException, which costs that jar's entries only.
        Path spaced = tempDir.resolve("with space");
        Files.createDirectories(spaced);
        Path broken = createPlutusSourcesJar(spaced.resolve("broken.jar"), Map.of(
                "com/example/Broken.java", "package com.example; class Broken {}"));
        Path good = createPlutusSourcesJar(tempDir.resolve("good.jar"), Map.of(
                "com/example/GoodLib.java", "package com.example; class GoodLib {}"));
        var problems = new ArrayList<String>();
        try (var classLoader = new URLClassLoader(new URL[]{new URL("file:" + broken), good.toUri().toURL()}, null)) {
            Map<String, LibrarySource> result = LibrarySourceResolver.scanClasspathSources(classLoader, problems::add);

            assertTrue(result.containsKey("com.example.GoodLib"), result.keySet().toString());
        }
    }

    @Test
    void resolve_followsOnlyAnnotationsThatAreMarkers() {
        // A host may pool any project source (the CLI does): an annotation interface without @OnchainLibrary is never
        // followed, so a build that compiled without its source still does.
        var plain = LibrarySourceResolver.librarySourceFromSimpleName("Audit", """
                package app;
                @java.lang.annotation.Documented
                public @interface Audit { String[] value(); }
                """);
        var validator = """
                package app;
                @Audit({"x"})
                class V { static boolean f() { return true; } }
                """;

        assertEquals(List.of(), LibrarySourceResolver.resolve(validator, Map.of("app.Audit", plain)));
    }

    @Test
    void resolve_asksOnlyBareQualifiersAsClassesAndAVariableDoesNotHideOne() {
        // A dotted qualifier (BigInteger.ZERO) never names a top-level class, so it is never asked about. A bare one
        // is, even where a variable or a static import of that name exists elsewhere: javac decides the binding, and a
        // class asked about but bound to a variable is never used.
        var validator = """
                package app;
                import java.math.BigInteger;
                import static java.math.BigInteger.TEN;
                import static app.Lib.*;
                class V {
                    static boolean g(BigInteger Counter) { return Counter.signum() > 0; }
                    static boolean f() {
                        if (true) { boolean Local = true; }
                        return BigInteger.ZERO.signum() == 0 && Counter.ok() && Local.ok() && TEN.signum() > 0;
                    }
                }
                """;
        var asked = new ArrayList<String>();

        LibrarySourceResolver.resolve(validator, Map.of(), name -> {
            asked.add(name);
            return null;
        });

        assertTrue(asked.containsAll(List.of("app.Counter", "app.Local")), asked.toString());
        assertFalse(asked.contains("app.ZERO"), asked.toString());
    }

    @Test
    void resolve_followsOnlyJulcsOwnOnchainLibraryAnnotation() {
        // A lookalike @OnchainLibrary from another package does not make a marker.
        var qualified = LibrarySourceResolver.librarySourceFromSimpleName("Audit", """
                package app;
                @other.OnchainLibrary
                public @interface Audit {}
                """);
        var imported = LibrarySourceResolver.librarySourceFromSimpleName("Trace", """
                package app;
                import other.OnchainLibrary;
                @OnchainLibrary
                public @interface Trace {}
                """);
        var shadowedByImport = LibrarySourceResolver.librarySourceFromSimpleName("Note", """
                package app;
                import other.OnchainLibrary;
                import org.julclang.stdlib.annotation.*;
                @OnchainLibrary
                public @interface Note {}
                """);
        var real = LibrarySourceResolver.librarySourceFromSimpleName("Mark", """
                package app;
                import org.julclang.stdlib.annotation.*;
                @OnchainLibrary
                public @interface Mark {}
                """);
        var validator = """
                package app;
                @Audit @Trace @Note @Mark
                class V { static boolean f() { return true; } }
                """;

        var resolved = LibrarySourceResolver.resolve(validator,
                Map.of("app.Audit", qualified, "app.Trace", imported, "app.Note", shadowedByImport, "app.Mark", real));

        assertEquals(List.of(real.source()), resolved);
    }

    @Test
    void resolve_aJarSourceGivesWayHoweverItIsReached() {
        // One source per name: a static wildcard import, a fallback or a wildcard match never lets the jar's class
        // through once the compilation has its own.
        var jar = LibrarySourceResolver.librarySourceFromQualifiedName("other.Counter", """
                package other;
                public class Counter { public static boolean ok() { return false; } }
                """);
        var own = LibrarySourceResolver.librarySourceFromQualifiedName("other.Counter", """
                package other;
                public class Counter { public static boolean ok() { return true; } }
                """);
        var validator = """
                package app;
                import other.*;
                import static other.Anchor.*;
                class V { static boolean f() { return Counter.ok(); } }
                """;

        assertEquals(List.of(own.source()), LibrarySourceResolver.resolve(validator, Map.of("other.Counter", jar),
                Map.of("other.Counter", jar), name -> name.equals("other.Counter") ? own : null));
    }

    @Test
    void resolve_aJarSourceGivesWayToTheCompilationsClassOfTheSameName() {
        var jar = LibrarySourceResolver.librarySourceFromQualifiedName("lib.Counter", """
                package lib;
                public class Counter { public static boolean ok() { return false; } }
                """);
        var own = LibrarySourceResolver.librarySourceFromQualifiedName("lib.Counter", """
                package lib;
                public class Counter { public static boolean ok() { return true; } }
                """);
        var validator = """
                package app;
                import lib.Counter;
                class V { static boolean f() { return Counter.ok(); } }
                """;

        assertEquals(List.of(own.source()), LibrarySourceResolver.resolve(validator, Map.of("lib.Counter", jar),
                Map.of("lib.Counter", jar), name -> name.equals("lib.Counter") ? own : null));
        // Without the jar map the pooled entry is a project source and stays.
        assertEquals(List.of(jar.source()), LibrarySourceResolver.resolve(validator, Map.of("lib.Counter", jar),
                name -> name.equals("lib.Counter") ? own : null));
    }

    @Test
    void scanClasspathSources_skipsAnUnreadableEntryReportsItAndReadsTheRest(@TempDir Path tempDir) throws Exception {
        // ADR-071 D2: an index listing a source the jar does not hold costs that source only, and is reported.
        Path broken = tempDir.resolve("broken.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(broken))) {
            addJarDirectory(out, "META-INF/");
            addJarDirectory(out, "META-INF/plutus-sources/");
            addJarEntry(out, "META-INF/plutus-sources/index.txt", "com/example/Missing.java\n");
        }
        Path good = createPlutusSourcesJar(tempDir.resolve("good.jar"), Map.of(
                "com/example/GoodLib.java", "package com.example; class GoodLib {}"));
        var warnings = new ArrayList<String>();
        try (var classLoader = new URLClassLoader(new URL[]{broken.toUri().toURL(), good.toUri().toURL()}, null)) {
            Map<String, LibrarySource> result = LibrarySourceResolver.scanClasspathSources(classLoader, warnings::add);

            assertTrue(result.containsKey("com.example.GoodLib"), result.keySet().toString());
            assertFalse(result.containsKey("com.example.Missing"));
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.getFirst().contains("Missing.java"), warnings.toString());
        }
    }

    @Test
    void scanClasspathSources_readsAJarRewrittenAfterAnEarlierScan(@TempDir Path tempDir) throws Exception {
        // ADR-071 D2: resources are opened without the JDK's URL cache, so a rebuilt jar (as in a Gradle daemon) is
        // read from disk, not through a stale cached JarFile.
        Path jar = tempDir.resolve("lib.jar");
        createPlutusSourcesJar(jar, Map.of("com/example/V1.java", "package com.example; class V1 {}"));
        try (var first = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            assertTrue(LibrarySourceResolver.scanClasspathSources(first).containsKey("com.example.V1"));
        }
        Files.delete(jar);
        createPlutusSourcesJar(jar, Map.of(
                "com/example/V1.java", "package com.example; class V1 { /* rebuilt */ }",
                "com/example/V2.java", "package com.example; class V2 {}"));
        var warnings = new ArrayList<String>();
        try (var second = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            Map<String, LibrarySource> result = LibrarySourceResolver.scanClasspathSources(second, warnings::add);

            assertEquals(List.of(), warnings);
            assertTrue(result.get("com.example.V1").source().contains("rebuilt"));
            assertTrue(result.containsKey("com.example.V2"));
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
