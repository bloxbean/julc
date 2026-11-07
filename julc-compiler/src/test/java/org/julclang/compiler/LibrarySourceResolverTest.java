package org.julclang.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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

    /** A host that admits {@code admitted} by name and treats {@code markers} as library markers. */
    private static LibrarySourceResolver.CompilationHost host(Map<String, LibrarySource> jarSources,
                                                             Function<String, LibrarySource> admitted,
                                                             Set<String> markers) {
        return new LibrarySourceResolver.CompilationHost(jarSources, admitted, markers::contains);
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

        var resolved = LibrarySourceResolver.resolve(validator, Map.of("lib.Lib", library, "lib.Mark", mark),
                host(Map.of(), name -> null, Set.of("lib.Mark")));

        assertEquals(List.of(library.source(), mark.source()), resolved);
        // A host that answers no marker (the CLI, the testkit, the tools) follows no annotation, as before.
        assertEquals(List.of(library.source()), LibrarySourceResolver.resolve(validator,
                Map.of("lib.Lib", library, "lib.Mark", mark)));
    }

    @Test
    void resolve_annotationReferencesNameOnlyAnnotationInterfacesAndNeverFallBack() {
        // An annotation resolves by import, package or a single wildcard match, and only to what the host calls a
        // marker: a class of the same simple name, or a marker of another package the source does not import, is never
        // added.
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

        var resolved = LibrarySourceResolver.resolve(validator, Map.of("other.Param", otherPackage, "app.Entrypoint", aClass),
                host(Map.of(), name -> null, Set.of("other.Param")));

        assertEquals(List.of(), resolved);
    }

    @Test
    void resolve_asksTheCompilationOnlyAboutImportedNames() {
        // ADR-071 D1: the host is asked only about the source's single-type imports; what it admits is followed, not
        // pooled.
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

        var resolved = LibrarySourceResolver.resolve(validator, Map.of("other.Pooled", pooled), host(Map.of(), name -> {
            asked.add(name);
            return name.equals("lib.Counter") ? admitted : null;
        }, Set.of()));

        // Counter, imported, is admitted and followed; Pooled reaches it through the pool-wide fallback. Bare names and
        // wildcard packages (Local, Far) are never asked about: the resolver does not guess what is a class.
        assertEquals(List.of(admitted.source(), pooled.source()), resolved);
        assertEquals(List.of("lib.Counter"), asked);
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
    void resolve_followsOnlyAnnotationsTheHostCallsMarkers() {
        // A host may pool any project source (the CLI does). An annotation is followed only if the host, which asks
        // javac, calls it a marker; a lookalike @OnchainLibrary of whatever spelling or shadowing never decides it.
        var plain = LibrarySourceResolver.librarySourceFromSimpleName("Audit", """
                package app;
                import org.julclang.stdlib.annotation.*;
                @OnchainLibrary
                @java.lang.annotation.Documented
                public @interface Audit { String[] value(); }
                """);
        var real = LibrarySourceResolver.librarySourceFromSimpleName("Mark", """
                package app;
                import org.julclang.stdlib.annotation.*;
                @OnchainLibrary
                public @interface Mark {}
                """);
        var validator = """
                package app;
                @Audit({"x"}) @Mark
                class V { static boolean f() { return true; } }
                """;

        assertEquals(List.of(real.source()), LibrarySourceResolver.resolve(validator,
                Map.of("app.Audit", plain, "app.Mark", real), host(Map.of(), name -> null, Set.of("app.Mark"))));
        assertEquals(List.of(), LibrarySourceResolver.resolve(validator, Map.of("app.Audit", plain, "app.Mark", real)));
    }

    @Test
    void resolve_aJarSourceGivesWayOnlyToAnImportedName() {
        // A wildcard or bare name may be a variable's, so it is not asked about: the jar's class stays, as before.
        var jar = LibrarySourceResolver.librarySourceFromQualifiedName("other.Counter", """
                package other;
                public class Counter { public static boolean ok() { return false; } }
                """);
        var own = LibrarySourceResolver.librarySourceFromQualifiedName("other.Counter", """
                package other;
                public class Counter { public static boolean ok() { return true; } }
                """);
        var wildcard = """
                package app;
                import other.*;
                class V { static boolean f() { return Counter.ok(); } }
                """;
        var asked = new ArrayList<String>();

        assertEquals(List.of(jar.source()), LibrarySourceResolver.resolve(wildcard, Map.of("other.Counter", jar),
                host(Map.of("other.Counter", jar), name -> {
                    asked.add(name);
                    return own;
                }, Set.of())));
        assertEquals(List.of(), asked);
    }

    @Test
    void resolve_anImportsChoiceHoldsForTheWholeResolution() {
        // V's unused parameter Counter reaches the jar's app.Counter first; Helper then imports app.Counter, which the
        // compilation supplies. The compilation's class is the one compiled, everywhere, and the list is the one the
        // resolution gives when that class is an ordinary pool entry.
        var jar = LibrarySourceResolver.librarySourceFromQualifiedName("app.Counter",
                "package app; public class Counter { public static boolean ok() { return false; } }");
        var own = LibrarySourceResolver.librarySourceFromQualifiedName("app.Counter",
                "package app; public class Counter { public static boolean ok() { return true; } }");
        var helper = LibrarySourceResolver.librarySourceFromQualifiedName("app.Helper", """
                package app;
                import app.Counter;
                public class Helper { public static boolean ok() { return Counter.ok(); } }
                """);
        var validator = """
                package app;
                import app.Helper;
                class V {
                    static boolean unused(java.math.BigInteger Counter) { return Counter.signum() > 0; }
                    static boolean f() { return Helper.ok(); }
                }
                """;

        var resolved = LibrarySourceResolver.resolve(validator, Map.of("app.Counter", jar, "app.Helper", helper),
                host(Map.of("app.Counter", jar), name -> name.equals("app.Counter") ? own : null, Set.of()));
        var asPool = LibrarySourceResolver.resolve(validator, Map.of("app.Counter", own, "app.Helper", helper));

        assertFalse(resolved.contains(jar.source()), resolved.toString());
        assertEquals(asPool, resolved);
    }

    @Test
    void resolve_aMarkersDependenciesNeverReorderTheLibraries() {
        // ADR-071 I3: Counter, marked by fw.Mark whose Class<?> default imports Leaf, resolves its libraries in the order
        // the direct Counter does; the marker comes after them.
        var tail = LibrarySourceResolver.librarySourceFromQualifiedName("app.Tail",
                "package app; public class Tail { static boolean ok() { return Bridge.ok() && Leaf.ok(); } }");
        var bridge = LibrarySourceResolver.librarySourceFromQualifiedName("app.Bridge",
                "package app; public class Bridge { static boolean ok() { return true; } }");
        var leaf = LibrarySourceResolver.librarySourceFromQualifiedName("app.Leaf",
                "package app; public class Leaf { static boolean ok() { return true; } }");
        var mark = LibrarySourceResolver.librarySourceFromQualifiedName("fw.Mark", """
                package fw;
                import app.Leaf;
                public @interface Mark { Class<?> value() default Leaf.class; }
                """);
        var marked = LibrarySourceResolver.librarySourceFromQualifiedName("app.Counter", """
                package app;
                import fw.Mark;
                @Mark
                public class Counter { static boolean ok() { return Tail.ok(); } }
                """);
        var direct = LibrarySourceResolver.librarySourceFromQualifiedName("app.Counter",
                "package app; public class Counter { static boolean ok() { return Tail.ok(); } }");
        var validator = """
                package app;
                import app.Counter;
                class V { static boolean f() { return Counter.ok(); } }
                """;
        Map<String, LibrarySource> pool = Map.of("app.Tail", tail, "app.Bridge", bridge, "app.Leaf", leaf, "fw.Mark", mark);

        var withMarker = LibrarySourceResolver.resolve(validator, pool,
                host(Map.of(), name -> name.equals("app.Counter") ? marked : null, Set.of("fw.Mark")));
        var withoutMarker = LibrarySourceResolver.resolve(validator, pool,
                host(Map.of(), name -> name.equals("app.Counter") ? direct : null, Set.of("fw.Mark")));

        assertEquals(List.of(marked.source(), tail.source(), bridge.source(), leaf.source(), mark.source()), withMarker);
        assertEquals(List.of(direct.source(), tail.source(), bridge.source(), leaf.source()), withoutMarker);
    }

    @Test
    void resolve_anAnnotationIsNeverFollowedThroughAWildcardImport() {
        // javac binds @SuppressWarnings to the validator's own package's plain annotation, which the pool does not
        // hold; a wildcard-imported marker of that name must not be taken instead.
        var marker = LibrarySourceResolver.librarySourceFromQualifiedName("fw.SuppressWarnings", """
                package fw;
                import org.julclang.stdlib.annotation.OnchainLibrary;
                @OnchainLibrary
                public @interface SuppressWarnings { String[] value(); }
                """);
        var validator = """
                package app;
                import fw.*;
                @SuppressWarnings({"unused"})
                class V { static boolean f() { return true; } }
                """;

        assertEquals(List.of(), LibrarySourceResolver.resolve(validator, Map.of("fw.SuppressWarnings", marker),
                host(Map.of(), name -> null, Set.of("fw.SuppressWarnings"))));
    }

    @Test
    void resolve_keepsTheOrderTheSourceNamesLibrariesIn() {
        // A class the compilation supplies keeps its place: the order is the compiler's input, and it shows in the
        // script when the optimizer does not run.
        var counter = LibrarySourceResolver.librarySourceFromQualifiedName("app.Counter",
                "package app; public class Counter { public static boolean ok() { return true; } }");
        var tail = LibrarySourceResolver.librarySourceFromQualifiedName("app.Tail",
                "package app; public class Tail { public static boolean ok() { return true; } }");
        var validator = """
                package app;
                import app.Counter;
                import app.Tail;
                class V { static boolean f() { return Counter.ok() && Tail.ok(); } }
                """;

        assertEquals(List.of(counter.source(), tail.source()), LibrarySourceResolver.resolve(validator,
                Map.of("app.Tail", tail), host(Map.of(), name -> name.equals("app.Counter") ? counter : null, Set.of())));
    }

    @Test
    void resolve_anAnnotationNamingTheSourcesOwnTypeIsNotFollowed() {
        // V declares its own @interface Mark; javac binds @Mark to it, not to the package's unrelated marker app.Mark,
        // whose ambiguous reference must not fail the build.
        var clueX = LibrarySourceResolver.librarySourceFromQualifiedName("x.Clue", "package x; public class Clue {}");
        var clueY = LibrarySourceResolver.librarySourceFromQualifiedName("y.Clue", "package y; public class Clue {}");
        var mark = LibrarySourceResolver.librarySourceFromQualifiedName("app.Mark", """
                package app;
                import x.*;
                import y.*;
                public @interface Mark { int N = Clue.size(); }
                """);
        var validator = """
                package app;
                class V {
                    public @interface Mark {}
                    @Mark static boolean f() { return true; }
                }
                """;

        assertEquals(List.of(), LibrarySourceResolver.resolve(validator,
                Map.of("app.Mark", mark, "x.Clue", clueX, "y.Clue", clueY), host(Map.of(), name -> null, Set.of("app.Mark"))));
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
                host(Map.of("lib.Counter", jar), name -> name.equals("lib.Counter") ? own : null, Set.of())));
        // Without the jar map the pooled entry is a project source and stays.
        assertEquals(List.of(jar.source()), LibrarySourceResolver.resolve(validator, Map.of("lib.Counter", jar),
                host(Map.of(), name -> name.equals("lib.Counter") ? own : null, Set.of())));
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
