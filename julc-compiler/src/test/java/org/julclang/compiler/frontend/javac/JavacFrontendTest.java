package org.julclang.compiler.frontend.javac;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.lang.model.SourceVersion;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-068 D3, D5: the javac environment, its diagnostics and its checks before javac. */
class JavacFrontendTest {

    static final String VALID = """
            package com.example;

            import java.math.BigInteger;
            import org.julclang.ledger.ScriptContext;
            import org.julclang.core.PlutusData;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;
            import org.julclang.stdlib.lib.ContextsLib;

            @SpendingValidator
            public class Gate {
                @Entrypoint
                public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                    BigInteger fee = ctx.txInfo().fee();
                    return fee.compareTo(BigInteger.valueOf(2)) > 0 && ContextsLib.signedBy(ctx.txInfo(), new byte[0]);
                }
            }
            """;

    static JavacResult analyze(String root, String... pool) {
        return JavacFrontend.shared().analyze(List.of(JavacFrontend.Unit.of(root)),
                Stream.of(pool).map(JavacFrontend.Unit::of).toList());
    }

    static String keys(JavacResult result) {
        return String.join(",", result.diagnostics().stream().map(d -> d.key() == null ? d.code() : d.key()).toList());
    }

    static String bundledSource(String fqcn) throws Exception {
        try (var in = JavacFrontendTest.class.getClassLoader()
                .getResourceAsStream("META-INF/plutus-sources/" + fqcn.replace('.', '/') + ".java")) {
            assertNotNull(in, fqcn + " is not bundled on the test class path");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Every bundled source: the pool the class-loader scan offers. */
    static String[] bundledPool() throws Exception {
        var names = JavacFrontend.BundledSources.load().digests().keySet().stream().sorted().toList();
        assertEquals(16, names.size(), names.toString());
        var texts = new String[names.size()];
        for (int i = 0; i < texts.length; i++) texts[i] = bundledSource(names.get(i));
        return texts;
    }

    @Test
    void acceptsAJavaValidatorWithTheBundledLibrariesAsPool() throws Exception {
        var result = analyze(VALID, bundledPool());
        assertTrue(result.isAccepted(), () -> result.diagnostics().toString());
        assertEquals(JavacFrontend.javacVersion(), result.javacVersion());
        assertEquals(JavacPlatform.shared().platformDigest(), result.platformDigest());
        assertEquals(JavacPlatform.shared().stubsDigest(), result.stubsDigest());
    }

    @Test
    void theBundledLibrariesAreAlwaysInThePool() {
        assertTrue(analyze(VALID).isAccepted(), () -> analyze(VALID).toString());
        assertEquals(16, JavacFrontend.BundledSources.load().texts().size());
    }

    @Test
    void aLibraryOutsideThePoolIsJavacsCantResolve() {
        String user = VALID.replace("import org.julclang.stdlib.lib.ContextsLib;", "import com.example.lib.Fees;")
                .replace("ContextsLib.signedBy(ctx.txInfo(), new byte[0])", "Fees.ok()");
        var result = analyze(user);
        assertEquals("compiler.err.doesnt.exist", result.diagnostics().getFirst().key(), result.toString());
        assertTrue(analyze(user, """
                package com.example.lib;
                public class Fees { public static boolean ok() { return true; } }
                """).isAccepted());
    }

    @Test
    void dialectOperatorIsJavacErrorWithKeyAndPosition() {
        String source = """
                import java.math.BigInteger;

                class D {
                \tstatic BigInteger f(BigInteger a, BigInteger b) { return a + b; }
                }
                """;
        var result = analyze(source);
        assertEquals(1, result.diagnostics().size(), result.toString());
        var d = result.diagnostics().getFirst();
        assertEquals("JULC0085", d.code());
        assertEquals("compiler.err.operator.cant.be.applied.1", d.key());
        assertEquals("D.java", d.fileName());
        assertEquals(4, d.line());
        // I7: a character offset from the line start (the tab counts as one), not javac's tab-expanded column.
        assertEquals(source.lines().toList().get(3).indexOf("a + b") + 3, d.column());
        assertTrue(d.message().startsWith("bad operand types for binary operator '+'"), d.message());
        assertTrue(d.message().endsWith("(compiler.err.operator.cant.be.applied.1)"), d.message());
        var reported = d.toCompilerDiagnostic();
        assertEquals("JULC0085", reported.code());
        assertTrue(reported.isError());
    }

    @Test
    void warningsAreNotReported() {
        var result = analyze("""
                class W {
                    @Deprecated static int old() { return 1; }
                    static int use() { return old(); }
                }
                """);
        assertTrue(result.isAccepted(), result.toString());
    }

    @Test
    void negativeControlWithoutBigIntegerJavaMathDoesNotExist() {
        var blob = new TreeMap<>(JavacPlatform.read(JavacPlatform.resource(JavacPlatform.PLATFORM_RESOURCE)));
        assertNotNull(blob.remove("java.base/java/math/BigInteger.class"));
        var platform = JavacPlatform.of(JavacPlatform.write(blob), JavacPlatform.resource(JavacPlatform.STUBS_RESOURCE));
        var frontend = new JavacFrontend(platform, ToolProvider::getSystemJavaCompiler,
                JavacFrontend.BundledSources.none());
        var result = frontend.analyze(List.of(JavacFrontend.Unit.of("""
                import java.math.BigInteger;
                class N { static BigInteger one() { return BigInteger.ONE; } }
                """)), List.of());
        assertFalse(result.isAccepted());
        var first = result.diagnostics().getFirst();
        assertEquals("compiler.err.doesnt.exist", first.key());
        assertTrue(first.message().startsWith("package java.math does not exist"), first.message());
        assertTrue(first.hint().contains("not available on-chain"), first.hint());
    }

    @Test
    void sameInputsGiveTheSameDiagnostics() {
        String source = """
                import java.math.BigInteger;
                import java.util.ArrayList;
                class T {
                    static BigInteger f(BigInteger a) { return a > 1 ? a : 2; }
                    static Object g() { return new ArrayList<String>(); }
                }
                """;
        var first = analyze(source);
        var fresh = new JavacFrontend(JavacPlatform.of(JavacPlatform.resource(JavacPlatform.PLATFORM_RESOURCE),
                JavacPlatform.resource(JavacPlatform.STUBS_RESOURCE)), ToolProvider::getSystemJavaCompiler,
                JavacFrontend.BundledSources.load()).analyze(List.of(JavacFrontend.Unit.of(source)), List.of());
        assertFalse(first.isAccepted());
        assertEquals(first.diagnostics(), analyze(source).diagnostics());
        assertEquals(first.diagnostics(), fresh.diagnostics());
    }

    // --- D3: the supported javac ---

    static JavaCompiler compilerWithSourceVersions(Set<SourceVersion> versions) {
        JavaCompiler real = ToolProvider.getSystemJavaCompiler();
        return (JavaCompiler) Proxy.newProxyInstance(JavaCompiler.class.getClassLoader(),
                new Class<?>[]{JavaCompiler.class}, (proxy, method, args) ->
                        method.getName().equals("getSourceVersions") ? versions : method.invoke(real, args));
    }

    @Test
    void javacWhoseHighestSourceVersionIsNot25IsJulc0089() {
        var older = new JavacFrontend(JavacPlatform.shared(),
                () -> compilerWithSourceVersions(EnumSet.range(SourceVersion.RELEASE_0, SourceVersion.RELEASE_24)),
                JavacFrontend.BundledSources.load());
        var result = older.analyze(List.of(JavacFrontend.Unit.of(VALID)), List.of());
        assertEquals(1, result.diagnostics().size());
        var d = result.diagnostics().getFirst();
        assertEquals("JULC0089", d.code());
        assertNull(d.key());
        assertTrue(d.message().contains("RELEASE_24"), d.message());
        assertFalse(older.supported().isAccepted());
        assertTrue(JavacFrontend.shared().supported().isAccepted());
    }

    @Test
    void runtimeWithoutJavacIsJulc0089() {
        var none = new JavacFrontend(JavacPlatform.shared(), () -> null, JavacFrontend.BundledSources.load());
        var d = none.analyze(List.of(JavacFrontend.Unit.of(VALID)), List.of()).diagnostics().getFirst();
        assertEquals("JULC0089", d.code());
        assertTrue(d.message().contains("jdk.compiler"), d.message());
    }

    // --- D3: ownership by provenance ---

    @Test
    void bundledSourceIsAcceptedByDigestAsARootAndInThePool() throws Exception {
        String mapLib = bundledSource("org.julclang.stdlib.lib.MapLib");
        assertTrue(JavacFrontend.BundledSources.load().digests().containsKey("org.julclang.stdlib.lib.MapLib"));
        var asRoot = JavacFrontend.shared().analyze(List.of(JavacFrontend.Unit.of(mapLib)),
                Stream.of(bundledPool()).map(JavacFrontend.Unit::of).toList());
        assertTrue(asRoot.isAccepted(), asRoot::toString);
        assertTrue(analyze(VALID, bundledPool()).isAccepted());
    }

    @Test
    void replacedBundledSourceIsJulc0081() throws Exception {
        String changed = bundledSource("org.julclang.stdlib.lib.MapLib") + "\n// changed\n";
        var d = analyze(changed).diagnostics();
        assertEquals(List.of("JULC0081"), d.stream().map(JavacDiagnostic::code).toList(), d.toString());
        assertTrue(d.getFirst().message().contains("org.julclang.stdlib.lib.MapLib"), d.getFirst().message());
        assertEquals("JULC0081", analyze(VALID, changed).diagnostics().getFirst().code());
    }

    @Test
    void extendingABundledOrApiPackageIsJulc0081() {
        var extra = analyze("""
                package org.julclang.stdlib.lib;
                public class ExtraLib { public static int one() { return 1; } }
                """).diagnostics();
        assertEquals("JULC0081", extra.getFirst().code());
        assertTrue(extra.getFirst().message().contains("bundled-library package org.julclang.stdlib.lib"),
                extra.getFirst().message());
        var ledger = analyze("""
                package org.julclang.ledger;
                public record Value(java.math.BigInteger lovelace) {}
                """).diagnostics();
        assertEquals("JULC0081", ledger.getFirst().code());
        assertTrue(ledger.getFirst().message().contains("JuLC's API"), ledger.getFirst().message());
        var newType = analyze("""
                package org.julclang.ledger;
                record Helper(int x) {}
                """).diagnostics();
        assertTrue(newType.getFirst().message().contains("API package org.julclang.ledger"),
                newType.getFirst().message());
    }

    @Test
    void rootWinsOverAPoolUnitOfTheSameName() {
        String lib = """
                package com.example;
                public class Util { public static int one() { return 1; } }
                """;
        String broken = """
                package com.example;
                public class Util { public static int one() { return "x"; } }
                """;
        String user = """
                package com.example;
                class User { static int two() { return Util.one() + 1; } }
                """;
        assertTrue(JavacFrontend.shared().analyze(List.of(JavacFrontend.Unit.of(user), JavacFrontend.Unit.of(lib)),
                List.of(JavacFrontend.Unit.of(broken))).isAccepted());
        assertEquals("compiler.err.prob.found.req", keys(analyze(user, broken)));
        // The first pool unit of a name wins.
        assertTrue(analyze(user, lib, broken).isAccepted());
    }

    // --- I2c: Java 22-25 features are rejected under -source 21 ---

    static Stream<Arguments> java22to25() {
        return Stream.of(
                Arguments.of("module imports", "compiler.err.feature.not.supported.in.source.plural", """
                        import module java.base;
                        class M { }
                        """),
                Arguments.of("unnamed variables", "compiler.err.feature.not.supported.in.source.plural", """
                        class U { static int f() { int _ = 1; return 2; } }
                        """),
                Arguments.of("unnamed variables", "compiler.err.feature.not.supported.in.source.plural", """
                        class P {
                            sealed interface S permits A, B {}
                            record A(int x) implements S {}
                            record B(int y) implements S {}
                            static int f(S s) { return switch (s) { case A(_) -> 1; case B b -> 2; }; }
                        }
                        """),
                Arguments.of("implicitly declared classes", "compiler.err.feature.not.supported.in.source.plural",
                        """
                        void main() { }
                        """),
                Arguments.of("flexible constructors", "compiler.err.feature.not.supported.in.source", """
                        class C {
                            final int v;
                            C(int v) { this.v = v; }
                            C(String s) { int n = s.length(); this(n); }
                        }
                        """));
    }

    /** One rejection per Java 22-25 feature (JEP 456, 511, 512, 513): javac's own, under {@code -source 21}. */
    @ParameterizedTest(name = "{0}: {2}")
    @MethodSource("java22to25")
    void java22To25FeatureIsRejected(String feature, String key, String source) {
        var result = analyze(source);
        assertFalse(result.isAccepted(), feature);
        var d = result.diagnostics().getFirst();
        assertEquals("JULC0085", d.code());
        assertEquals(key, d.key());
        assertTrue(d.message().startsWith(feature + " ") && d.message().contains("not supported in -source 21"),
                d.message());
    }

    // --- D5: hints ---

    @Test
    void jdkTypeOutsideTheBlobGetsAHint() {
        var d = analyze("""
                import java.util.TreeSet;
                class H { }
                """).diagnostics().getFirst();
        assertEquals("compiler.err.cant.resolve.location", d.key());
        assertTrue(d.hint().startsWith("java.util.TreeSet is not available on-chain"), d.hint());
        var list = analyze("""
                import java.util.LinkedList;
                class H { }
                """).diagnostics().getFirst();
        assertEquals("java.util.LinkedList is not available on-chain; use JulcList", list.hint());
        var pkg = analyze("""
                import java.util.concurrent.atomic.AtomicInteger;
                class H { }
                """).diagnostics().getFirst();
        assertEquals("compiler.err.doesnt.exist", pkg.key());
        assertTrue(pkg.hint().startsWith("Package java.util.concurrent.atomic is not available on-chain"), pkg.hint());
    }

    @Test
    void annotationOutsideTheApiGetsAHint() {
        var d = analyze("""
                class A {
                    @Nonnull static int f() { return 1; }
                }
                """).diagnostics().getFirst();
        assertEquals("compiler.err.cant.resolve", d.key().substring(0, "compiler.err.cant.resolve".length()));
        assertTrue(d.hint().startsWith("Annotation @Nonnull is not available to JuLC"), d.hint());
        var foreign = analyze("""
                import lombok.Data;
                class A { }
                """).diagnostics().getFirst();
        assertTrue(foreign.hint().startsWith("Package lombok is not visible to JuLC"), foreign.hint());
    }

    @Test
    void verificationAnnotationsAndLedgerTypesAreVisible() {
        var result = analyze("""
                import org.julclang.verification.annotation.RequiresSigner;
                import org.julclang.ledger.PubKeyHash;
                import org.julclang.core.types.JulcList;
                class V {
                    static boolean any(JulcList<PubKeyHash> keys) { return keys.isEmpty(); }
                }
                """);
        assertTrue(result.isAccepted(), result.toString());
    }

    // --- D8: the empty provider's metadata-only task ---

    @Test
    void metadataTaskExposesOnlyElementsAndTypes() {
        var methods = Stream.of(JavacMetadata.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .map(m -> m.getName()).sorted().toList();
        assertEquals(List.of("elements", "types"), methods);
        assertEquals(Object.class, JavacMetadata.class.getSuperclass());
        assertEquals(0, JavacMetadata.class.getInterfaces().length);
        var metadata = JavacFrontend.shared().metadata();
        assertNotNull(metadata.elements().getTypeElement("org.julclang.ledger.TxInfo"));
        assertNull(metadata.elements().getTypeElement("org.julclang.stdlib.lib.MapLib"),
                "@OnchainLibrary classes are sources only, never stubs (D3)");
        assertNull(metadata.elements().getTypeElement("org.julclang.stdlib.StdlibRegistry"),
                "compiler-facing stdlib classes are not stubbed (I10)");
    }

    @Test
    void columnIsACharacterOffsetFromTheLineStart() {
        assertEquals(0, JavacHints.lineStart("ab", 1) - 0);
        assertEquals(3, JavacHints.lineStart("ab\ncd", 4));
        assertEquals(3, JavacHints.lineStart("ab\rcd", 3));
        assertEquals(4, JavacHints.lineStart("ab\r\ncd", 5));
    }

    @Test
    void pathUnitsKeepTheirFileName(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path file = dir.resolve("Broken.java");
        String text = "class Broken { int f() { return \"x\"; } }\n";
        Files.writeString(file, text);
        var d = JavacFrontend.shared().analyze(List.of(new JavacFrontend.Unit(text, file)), List.of())
                .diagnostics().getFirst();
        assertEquals(file.toString(), d.fileName());
        assertEquals(1, d.line());
        assertEquals(text.indexOf("\"x\"") + 1, d.column());
    }
}
