package org.julclang.compiler.frontend.javac;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.corpus.CorpusRecorder;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
        // First stays visible (F511), but javac reads its unit whole, which declares the root's Second too: the
        // compilation fails closed, naming the contested name.
        var first = analyze("""
                package app;
                record Second(int x) {}
                class M { static int f(First s) { return s.x(); } }
                """, SECONDARIES);
        assertEquals("compiler.err.duplicate.class", first.diagnostics().getFirst().key(), first.toString());
        assertTrue(first.diagnostics().getFirst().message().startsWith("duplicate class: app.Second"),
                first.toString());
    }

    // --- F511: precedence per qualified name; no non-conflicting type is hidden; pool order does not matter ---

    static final String FOO = """
            package app;
            class Foo {} class Shared {}
            """;
    static final String BAR = """
            package app;
            class Bar {} class Shared {}
            """;

    @Test
    void aContestedNameFailsClosedInEitherPoolOrderAndHidesNothing() {
        for (var pool : List.of(new String[]{FOO, BAR}, new String[]{BAR, FOO})) {
            for (String used : List.of("Bar", "Foo", "Shared")) {
                var result = analyze("package app;\nclass Main { " + used + " b; }\n", pool);
                assertEquals(1, result.diagnostics().size(), used + " " + result);
                var d = result.diagnostics().getFirst();
                assertEquals("JULC0085", d.code());
                assertEquals("compiler.err.duplicate.class", d.key());
                assertTrue(d.message().startsWith("duplicate class: app.Shared (declared by "), d.message());
                assertFalse(d.message().contains("cant.resolve"), d.message());
            }
            // A root that uses neither unit is accepted, in both orders.
            assertTrue(analyze("package app;\nclass Main { int x; }\n", pool).isAccepted());
        }
        // Without the contest every declaration of both units is visible.
        assertTrue(analyze("package app;\nclass Main { Foo f; Bar b; }\n", """
                package app;
                class Foo {} class OnlyFoo {}
                """, """
                package app;
                class Bar {} class OnlyBar {}
                """).isAccepted());
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
