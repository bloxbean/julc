package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

/**
 * ADR-070 I3: a cast that needs no check, or that JuLC cannot check, compiles to what it compiled to before (D2). Each
 * program's bytes, diagnostics and source positions at every level, with and without a source map, are frozen
 * ({@link FrozenReference}), and so is its PIR text. These rows are the javac frontend's at ADR-070's M0, recorded before
 * the change, not the JavaParser reference's answers. A milestone that moves one has broken I3.
 */
class Adr070CompatibilityPinsTest {

    private static final String SOURCE = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcList;
            class C {
                sealed interface S { record A(BigInteger v) implements S { } record B(BigInteger v, BigInteger w) implements S { } record D() implements S { } }
                record Box(S.A value) { }
                %s
            }
            """;

    private static String source(String members) {
        return SOURCE.formatted(members);
    }

    /** D2: upcasts, same-type casts, views from Object and PlutusData's own variants, casts to the sealed interface. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static boolean m(S s) { S t = (S) new S.A(BigInteger.ONE); return t instanceof S.A; }",
            "static BigInteger m(S.A a) { S.A b = (S.A) a; return b.v(); }",
            "static BigInteger m(S s) { if (s instanceof S.A a) { S.A b = (S.A) a; return b.v(); } return BigInteger.ZERO; }",
            "static BigInteger m(Box box) { S.A a = (S.A) box.value(); return a.v(); }",
            "static BigInteger m(S s) { S.A a = (S.A) (Object) s; return a.v(); }",
            "static boolean m(S.A a) { S s = (S) (Object) a; return s instanceof S.A; }",
            "static long m(PlutusData d) { PlutusData.IntData i = (PlutusData.IntData) d; return 1; }",
            "static long m(PlutusData d) { PlutusData.MapData map = (PlutusData.MapData) d; return 1; }",
            "static BigInteger m(PlutusData d) { S.A a = PlutusData.cast(d, S.A.class); return a.v(); }",
    })
    void aCastThatStaysAView(String members) {
        var source = source(members);
        LoweringFixture.assertSameMethodAsReference(source, "m", StdlibRegistry.defaultRegistry());
        for (var level : OptimizationLevel.values()) {
            var result = new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setOptimizationLevel(level))
                    .compileMethod(source, "m", List.of());
            FrozenReference.assertSame("adr070 pir|" + source + "|" + level, result.pirFormatted());
        }
    }

    /**
     * Rejections ADR-070 keeps: a bounded type parameter, and an {@code instanceof} of a captured wildcard, which reads no
     * bound (review F6). Each keeps its first diagnostic.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "static <T extends S> BigInteger m(T t) { S.A a = (S.A) t; return a.v(); }",
            "static boolean m(JulcList<S> xs) { return ((JulcList<? extends S>) xs).head() instanceof S.A; }",
    })
    void aRejectionThatStays(String members) {
        LoweringFixture.assertSameMethodRejectionAsReference(source(members), "m", StdlibRegistry.defaultRegistry());
    }
}
