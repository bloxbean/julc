package org.julclang.compiler.frontend.javac;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JavaLibraryProvider;
import org.julclang.compiler.JulcCompiler;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        // The pool unit is left out whole, so its other type is not visible: the root's declaration wins.
        var first = analyze("""
                package app;
                record Second(int x) {}
                class M { static int f(First s) { return s.x(); } }
                """, SECONDARIES);
        assertEquals("compiler.err.cant.resolve.location", first.diagnostics().getFirst().key(), first.toString());
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
        assertTrue(wildcard.get(0).hint().startsWith("TreeSet is not available on-chain: JuLC compiles against"),
                wildcard.get(0).hint());
        assertTrue(wildcard.get(0).hint().endsWith("so import java.util.* does not provide it"), wildcard.get(0).hint());
        assertTrue(wildcard.get(1).hint().startsWith("LinkedList is not available on-chain; use JulcList"),
                wildcard.get(1).hint());
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
    void metadataTaskIsCachedPerProcess() {
        var first = JavacGate.metadata(REPORT);
        assertNotNull(first);
        assertSame(first, JavacGate.metadata(ENFORCE));
        assertSame(first, JavacGate.metadata(REPORT));
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
}
