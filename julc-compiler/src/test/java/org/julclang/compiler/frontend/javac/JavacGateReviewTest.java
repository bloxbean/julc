package org.julclang.compiler.frontend.javac;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySource;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.compiler.LibrarySources;
import org.julclang.compiler.corpus.CorpusRecorder;
import org.julclang.stdlib.StdlibRegistry;
import com.sun.source.util.TaskEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Regressions for the M1a review findings F500-F505 (Codex) and F402-F403 (Claude reviewer). */
class JavacGateReviewTest {

    static final CompilerOptions ENFORCE = new CompilerOptions().setJavacGate(JavacGateMode.ENFORCE);
    static final CompilerOptions REPORT = new CompilerOptions().setJavacGate(JavacGateMode.REPORT);

    static JavacResult analyze(String root, String... pool) {
        return JavacFrontend.shared().analyze(List.of(JavacFrontend.Unit.of(root)),
                Stream.of(pool).map(JavacFrontend.Unit::of).toList());
    }

    static <T> T withProperty(String key, String value, java.util.function.Supplier<T> body) {
        String saved = System.getProperty(key);
        try {
            System.setProperty(key, value);
            return body.get();
        } finally {
            if (saved == null) System.clearProperty(key);
            else System.setProperty(key, saved);
        }
    }

    // --- F500: the cache key is an unambiguous encoding ---

    /** A valid library whose last line is an open comment. */
    static final String A = """
            package p;
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.OnchainLibrary;
            @OnchainLibrary
            public class A { public static BigInteger one() { return BigInteger.ONE; } }
            //""";
    /** A one-line library javac rejects. */
    static final String B = "package p; import java.math.BigInteger; import org.julclang.stdlib.annotation.OnchainLibrary;"
            + " @OnchainLibrary public class B { public static BigInteger f(BigInteger x) { return x + x; } }";

    @Test
    void cacheKeyDistinguishesUnitBoundariesAndPaths() {
        String joined = A + "\0-\0" + B;
        assertNotEquals(JavacGate.key(List.of(JavacFrontend.Unit.of(A), JavacFrontend.Unit.of(B)), List.of()),
                JavacGate.key(List.of(JavacFrontend.Unit.of(joined)), List.of()));
        assertNotEquals(JavacGate.key(List.of(JavacFrontend.Unit.of(A)), List.of(JavacFrontend.Unit.of(B))),
                JavacGate.key(List.of(JavacFrontend.Unit.of(A), JavacFrontend.Unit.of(B)), List.of()));
        assertNotEquals(JavacGate.key(List.of(new JavacFrontend.Unit(A, Path.of("-"))), List.of()),
                JavacGate.key(List.of(JavacFrontend.Unit.of(A)), List.of()));
    }

    @Test
    void aCollidingPrimedResultCannotAcceptAProviderInEnforceMode() {
        // The joined text is one valid unit: A's trailing comment swallows B's only line.
        var primed = JavacGate.check("JavaLibraryProvider", List.of(JavacFrontend.Unit.of(A + "\0-\0" + B)), List.of(),
                REPORT);
        assertTrue(primed.isAccepted(), primed::toString);
        var e = assertThrows(CompilerException.class,
                () -> new JavaLibraryProvider(List.of(A, B), StdlibRegistry.defaultRegistry(), ENFORCE));
        assertEquals("JULC0085", e.diagnostics().getFirst().code(), e.diagnostics().toString());
        assertEquals("B.java", e.diagnostics().getFirst().fileName());
    }

    // --- F501: reporting is best-effort ---

    static final String DIALECT_METHOD = """
            import java.math.BigInteger;
            class M { static BigInteger twice(BigInteger x) { return x + x; } }
            """;

    @Test
    void anUnwritableOrInvalidReportPathNeverFailsACompilation(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("a-file"), "not a directory");
        for (String where : List.of("/dev/null", file.toString(), file.resolve("below").toString(), "bad\0path")) {
            var result = withProperty(JavacGate.REPORT_PROPERTY, where, () ->
                    new JulcCompiler(StdlibRegistry.defaultRegistry(), REPORT).compileMethod(DIALECT_METHOD, "twice"));
            assertFalse(result.hasErrors(), where);
            withProperty(JavacGate.REPORT_PROPERTY, where, () -> {
                JavacGateReport.recordFailure("compile", List.of(JavacFrontend.Unit.of(DIALECT_METHOD)), List.of(),
                        new IllegalStateException("probe"));
                return null;
            });
        }
    }

    // --- F502: every top-level declaration of a pool unit, with root precedence over all of them ---

    static final String SECONDARIES = """
            package app;
            record First(int x) {}
            record Second(int x) {}
            """;

    @Test
    void aSecondaryTopLevelTypeOfAPoolUnitResolves() {
        var result = analyze("""
                package app;
                class M { static int f(Second s) { return s.x(); } }
                """, SECONDARIES);
        assertTrue(result.isAccepted(), result::toString);
        assertTrue(analyze("""
                package app;
                class M { static int f(First a, Second b) { return a.x() + b.x(); } }
                """, SECONDARIES).isAccepted());
    }

    @Test
    void aRootWinsOverEveryDeclarationOfAPoolUnitWithoutDuplicateClass() {
        var result = analyze("""
                package app;
                record Second(int x) {}
                class M { static int f(Second s) { return s.x(); } }
                """, SECONDARIES);
        assertTrue(result.isAccepted(), result::toString);
    }

    // --- F520/F533 (D8 "Whole units"): a shadowed unit is not read; needing another of its declarations is rejected ---

    /** {@code Helper} and the root's {@code Shared}; the body is invalid, so reading the unit would show. */
    static final String HELPER_AND_SHARED = """
            package app;
            public class Helper { static int one() { return; } }
            class Shared {}
            """;

    static JavacResult analyzeRecording(List<String> roots, List<String> pool, List<TaskEvent> events) {
        return JavacFrontend.shared().analyze(roots.stream().map(JavacFrontend.Unit::of).toList(),
                pool.stream().map(JavacFrontend.Unit::of).toList(), events::add);
    }

    static boolean read(List<TaskEvent> events, String fileName) {
        return events.stream().anyMatch(e -> e.getSourceFile() != null
                && e.getSourceFile().toUri().getPath().endsWith("/" + fileName)
                && (e.getKind() == TaskEvent.Kind.PARSE || e.getKind() == TaskEvent.Kind.ENTER));
    }

    @Test
    void aShadowedUnitIsNeverReadAndNeedingItIsRejectedNamingBoth() {
        var events = new ArrayList<TaskEvent>();
        var accepted = analyzeRecording(List.of("""
                package app;
                class Shared {}
                class Main { Shared s; }
                """), List.of(HELPER_AND_SHARED), events);
        assertTrue(accepted.isAccepted(), accepted::toString);
        assertFalse(read(events, "Helper.java"), "a shadowed unit is not parsed or entered");

        events.clear();
        var rejected = analyzeRecording(List.of("""
                package app;
                class Shared {}
                public class Main { int f() { return Helper.one(); } }
                """), List.of(HELPER_AND_SHARED), events);
        assertFalse(read(events, "Helper.java"), "a shadowed unit is not parsed or entered");
        assertEquals(1, rejected.diagnostics().size(), rejected.toString());
        var d = rejected.diagnostics().getFirst();
        assertEquals("JULC0085", d.code());
        assertEquals("compiler.err.duplicate.class", d.key());
        assertEquals("duplicate class: app.Shared (declared by Main.java and Helper.java; the root's declaration"
                + " shadows Helper.java, which javac therefore does not read, and the program needs its app.Helper)"
                + " (compiler.err.duplicate.class)", d.message());
        assertEquals("Main.java", d.fileName());
        assertEquals(3, d.line());

    }

    @Test
    void aShadowedUnitReachedByImportIsNamedExactly() {
        var shadowed = JavacFrontend.shared().analyze(List.of(JavacFrontend.Unit.of("""
                package app;
                import lib.Helper;
                class Main { int f() { return Helper.one(); } }
                """), JavacFrontend.Unit.of("""
                package lib;
                public class Clash {}
                """)), List.of(JavacFrontend.Unit.of("""
                package lib;
                public class Helper { public static int one() { return 1; } }
                class Clash {}
                """)));
        assertFalse(shadowed.diagnostics().isEmpty(), shadowed::toString);
        assertTrue(shadowed.diagnostics().stream().allMatch(x -> x.message().startsWith(
                "duplicate class: lib.Clash (declared by Clash.java and Helper.java;")), shadowed.toString());
    }

    // --- F532: conflicts are attached to exact qualified names, once each ---

    @Test
    void conflictsInTwoPackagesAreNamedExactlyInEitherPoolOrder() {
        String defaultRoot = """
                class Shared {}
                class UseHelper { int f() { return Helper.one(); } }
                """;
        String appRoot = """
                package app;
                class Shared {}
                class UseOther { int f() { return Other.two(); } }
                """;
        String defaultPool = """
                public class Helper { static int one() { return 1; } }
                class Shared {}
                """;
        String appPool = """
                package app;
                public class Other { static int two() { return 2; } }
                class Shared {}
                """;
        var outcomes = new ArrayList<List<String>>();
        for (var pool : List.of(List.of(defaultPool, appPool), List.of(appPool, defaultPool))) {
            var result = JavacFrontend.shared().analyze(
                    List.of(JavacFrontend.Unit.of(defaultRoot), JavacFrontend.Unit.of(appRoot)),
                    pool.stream().map(JavacFrontend.Unit::of).toList());
            outcomes.add(result.diagnostics().stream().map(JavacDiagnostic::message).sorted().toList());
        }
        assertEquals(outcomes.get(0), outcomes.get(1));
        assertEquals(2, outcomes.get(0).size(), outcomes.toString());
        assertTrue(outcomes.get(0).get(0).startsWith("duplicate class: Shared (declared by Shared.java and Helper.java;")
                && outcomes.get(0).get(0).contains("needs its Helper)"), outcomes.toString());
        assertTrue(outcomes.get(0).get(1).startsWith("duplicate class: app.Shared (declared by Shared.java and"
                + " Other.java;") && outcomes.get(0).get(1).contains("needs its app.Other)"), outcomes.toString());
    }

    // --- F531: same-project wins over JAR over every declared name ---

    @Test
    void aProjectUnitWinsOverAJarUnitDeclaringItsSecondaryName() {
        String current = """
                package com.example;
                public class Current { public static int one() { return Shared.TWO - 1; } }
                class Shared { static final int TWO = 2; }
                """;
        String jarShared = """
                package com.example;
                public class Shared { public static final int TWO = 3; }
                """;
        var jar = new LinkedHashMap<String, LibrarySource>();
        jar.put("com.example.Shared", LibrarySourceResolver.librarySource(jarShared));
        var pool = new LinkedHashMap<>(jar);
        pool.put("com.example.Current", LibrarySourceResolver.librarySource(current));
        String root = """
                package com.example;
                class Main { int f() { return Current.one() + Shared.TWO; } }
                """;
        var both = analyze(root, pool.values().stream().map(LibrarySource::source).toArray(String[]::new));
        assertTrue(both.diagnostics().getFirst().message().contains("two library sources of equal standing"),
                both.toString());
        var merged = LibrarySourceResolver.withProjectPrecedence(pool, jar);
        assertEquals(List.of("com.example.Current"), List.copyOf(merged.keySet()));
        var result = analyze(root, merged.values().stream().map(LibrarySource::source).toArray(String[]::new));
        assertTrue(result.isAccepted(), result::toString);
        // With the gate off, B0's merge by primary name.
        assertEquals(pool, withProperty(JavacGate.MODE_PROPERTY, "off",
                () -> LibrarySourceResolver.withProjectPrecedence(pool, jar)));
    }

    // --- F521 (D8): pool units of equal standing declaring one name are an error, referenced or not ---

    static final String FOO = """
            package app;
            class Foo {} class Shared {}
            """;
    static final String BAR = """
            package app;
            class Bar {} class Shared {}
            """;

    @Test
    void twoPoolUnitsDeclaringOneNameAreRejectedInEitherOrder() {
        for (var pool : List.of(new String[]{FOO, BAR}, new String[]{BAR, FOO})) {
            for (String root : List.of("package app;\nclass Main {}\n", "package app;\nclass Main { Bar b; }\n",
                    "package app;\nclass Main { Foo f; }\n")) {
                var result = analyze(root, pool);
                assertEquals(1, result.diagnostics().size(), root + result);
                var d = result.diagnostics().getFirst();
                assertEquals("JULC0085", d.code());
                assertEquals("compiler.err.duplicate.class", d.key());
                assertTrue(d.message().startsWith("duplicate class: app.Shared (declared by "), d.message());
                assertTrue(d.message().contains("two library sources of equal standing declare it"), d.message());
            }
        }
        // Without the duplicate, every declaration of both units is visible.
        assertTrue(analyze("package app;\nclass Main { Foo f; Bar b; OnlyFoo g; OnlyBar h; }\n", """
                package app;
                class Foo {} class OnlyFoo {}
                """, """
                package app;
                class Bar {} class OnlyBar {}
                """).isAccepted());
    }

    @Test
    void readJarsRejectsTwoJarsDeclaringOneName(@TempDir Path dir) throws Exception {
        Path foo = jar(dir.resolve("foo.jar"), "app/Foo.java", FOO);
        Path bar = jar(dir.resolve("bar.jar"), "app/Bar.java", BAR);
        for (var jars : List.of(List.of(foo, bar), List.of(bar, foo))) {
            var e = assertThrows(IllegalArgumentException.class, () -> LibrarySources.readJars(jars));
            assertTrue(e.getMessage().startsWith("Duplicate library declaration: app.Shared is declared in "),
                    e.getMessage());
        }
        Path other = jar(dir.resolve("other.jar"), "app/Other.java", "package app;\nclass Other {} class Extra {}\n");
        assertEquals(2, LibrarySources.readJars(List.of(foo, other)).size());
    }

    static Path jar(Path file, String entry, String text) throws Exception {
        try (var out = new JarOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new JarEntry("META-INF/plutus-sources/" + entry));
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return file;
    }

    @Test
    void compileMethodWithASecondaryPoolTypeIsAcceptedInEnforceMode() {
        String root = """
                package app;
                class M { static int f(Second s) { return s.x(); } }
                """;
        var off = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setJavacGate(JavacGateMode.OFF)).compileMethod(root, "f", List.of(SECONDARIES));
        assertFalse(off.hasErrors(), off.diagnostics().toString());
        var enforced = new JulcCompiler(StdlibRegistry.defaultRegistry(), ENFORCE)
                .compileMethod(root, "f", List.of(SECONDARIES));
        assertFalse(enforced.hasErrors(), enforced.diagnostics().toString());
    }

    // --- F503: malformed units are javac's diagnostics, not exceptions ---

    @Test
    void malformedDeclarationsAreJulc0085() {
        for (String source : List.of("class {}", "package 1x; class A {}", "@interface {}", "package a.; class B {}")) {
            var result = analyze(source);
            assertFalse(result.isAccepted(), source);
            var d = result.diagnostics().getFirst();
            assertEquals("JULC0085", d.code(), source);
            assertTrue(d.key().startsWith("compiler.err."), source + ": " + d.key());
            assertFalse(d.fileName().contains("<"), d.fileName());
        }
        var e = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry(), ENFORCE).compileMethod("class {}", "f"));
        assertEquals("JULC0085", e.diagnostics().getFirst().code());
        // A malformed pool unit is offered without failing the analysis.
        assertTrue(analyze("class Fine { static int f() { return 1; } }", "class {}").isAccepted());
    }

    // --- F505: hints from the parsed unit ---

    @Test
    void wildcardImportsAndCommentedAnnotationsGetHints() {
        var wildcard = analyze("""
                import java.util.*;
                class A { TreeSet<String> x; LinkedList<String> y; }
                """).diagnostics();
        assertEquals(2, wildcard.size(), wildcard.toString());
        assertTrue(wildcard.get(0).hint().startsWith("If TreeSet is a JDK type (from import java.util.*)"),
                wildcard.get(0).hint());
        assertEquals("java.util.LinkedList is not available on-chain; use JulcList", wildcard.get(1).hint());
        var annotation = analyze("""
                @/*comment*/Missing
                class A {}
                """).diagnostics().getFirst();
        assertTrue(annotation.hint().startsWith("Annotation @Missing is not available to JuLC"), annotation.hint());
        var spaced = analyze("""
                class A { @
                    Gone static int f() { return 1; } }
                """).diagnostics().getFirst();
        assertTrue(spaced.hint().startsWith("Annotation @Gone"), spaced.hint());
        // A missing local name is not an annotation, and no JDK wildcard import is in play.
        var plain = analyze("class A { static int f() { return missing; } }").diagnostics().getFirst();
        assertNull(plain.hint());
    }

    // --- F402: the metadata-only task is built once per process ---

    @Test
    void metadataTaskIsCachedPerProcessAndLocksItself() throws Exception {
        var first = JavacGate.metadata(REPORT);
        assertNotNull(first);
        assertSame(first, JavacGate.metadata(ENFORCE));
        assertSame(first, JavacGate.metadata(REPORT));
        // F406: concurrent queries need no caller lock.
        var pool = Executors.newFixedThreadPool(8);
        try {
            var futures = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 64; i++) {
                futures.add(pool.submit(() -> first.read((elements, types) ->
                        elements.getTypeElement("org.julclang.ledger.TxInfo").getRecordComponents().size())));
            }
            for (var f : futures) assertEquals(16, f.get());
        } finally {
            pool.shutdown();
        }
    }

    // --- F403: a broken JuLC build is not JULC0089, and a missing manifest is loud ---

    @Test
    void aMissingBlobIsABrokenBuildNotAnUnsupportedJavac() {
        var e = assertThrows(BrokenJulcBuildException.class, () -> JavacPlatform.resource("/no/such/platform.bin"));
        assertTrue(e.getMessage().startsWith("broken JuLC build: missing resource"), e.getMessage());
        assertFalse(JavacGate.isMissingCompiler(new ExceptionInInitializerError(e)));
        assertTrue(JavacGate.isMissingCompiler(new NoClassDefFoundError("javax/tools/ToolProvider")));
        assertTrue(JavacGate.isMissingCompiler(new NoClassDefFoundError("com/sun/source/util/JavacTask")));
        assertFalse(JavacGate.isMissingCompiler(new NoClassDefFoundError("org/julclang/Something")));
    }

    @Test
    void stdlibWithoutItsManifestFailsLoudly() {
        ClassLoader stdlibWithoutManifest = new ClassLoader(null) {
            @Override
            public URL getResource(String name) {
                return name.equals(JavacFrontend.BundledSources.STDLIB_MARKER)
                        ? JavacGateReviewTest.class.getResource("JavacGateReviewTest.class") : null;
            }

            @Override
            public InputStream getResourceAsStream(String name) {
                return null;
            }
        };
        var e = assertThrows(BrokenJulcBuildException.class,
                () -> JavacFrontend.BundledSources.load(stdlibWithoutManifest));
        assertTrue(e.getMessage().contains(JavacFrontend.BundledSources.RESOURCE), e.getMessage());
        // Without julc-stdlib at all (the compiler alone) the set is legitimately empty.
        assertTrue(JavacFrontend.BundledSources.load(new ClassLoader(null) { }).digests().isEmpty());
    }

    // --- F401: the environment, additively ---

    @Test
    void environmentReportsVersionAndDigests() {
        var env = JavacEnvironment.current();
        assertTrue(env.supported());
        assertEquals(Runtime.version().toString(), env.javacVersion());
        assertEquals(JavacPlatform.shared().platformDigest(), env.platformDigest());
        assertEquals("8e5e498ecf0f9aeefd36284cf89629ee0ac9ec39ab9cc80ff26226866d9dc53f", env.platformDigest());
        assertEquals(JavacPlatform.shared().stubsDigest(), env.stubsDigest());
    }

    // --- F512: identities are injective on strings, unpaired surrogates included ---

    @Test
    void aSurrogateTextAndItsStoredFormNeverShareAKey() {
        String s = "class Probe500 { static int f() { return 1; } }//" + (char) 0xD880;
        String t = new String(CorpusRecorder.bytes(s), StandardCharsets.UTF_8);
        assertNotEquals(s, t);
        assertNotEquals(JavacGate.key(List.of(JavacFrontend.Unit.of(s)), List.of()),
                JavacGate.key(List.of(JavacFrontend.Unit.of(t)), List.of()));
        assertNotEquals(JavacPlatform.identity(s), JavacPlatform.identity(t));
        assertFalse(Arrays.equals(CorpusRecorder.bytes(s),
                CorpusRecorder.bytes(t)));
        assertEquals(t, CorpusRecorder.text(CorpusRecorder.bytes(t)));
        // Priming with s cannot decide t's outcome.
        var direct = analyze(t);
        assertTrue(JavacGate.check("probe", List.of(JavacFrontend.Unit.of(s)), List.of(), REPORT).isAccepted());
        if (direct.isAccepted()) {
            assertTrue(JavacGate.check("probe", List.of(JavacFrontend.Unit.of(t)), List.of(), ENFORCE).isAccepted());
        } else {
            assertThrows(CompilerException.class,
                    () -> JavacGate.check("probe", List.of(JavacFrontend.Unit.of(t)), List.of(), ENFORCE));
        }
    }

    // --- F513: a wildcard import does not make every unresolved class a JDK type ---

    @Test
    void anUnknownNameUnderAJdkWildcardImportGetsConditionalWording() {
        var d = analyze("""
                import java.util.*;
                class M { ProjectThing x; }
                """).diagnostics().getFirst();
        assertTrue(d.hint().startsWith("If ProjectThing is a JDK type (from import java.util.*), it is not available"
                + " on-chain"), d.hint());
        assertTrue(d.hint().endsWith("otherwise check the import or the source that declares it"), d.hint());
    }
}
