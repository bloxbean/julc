package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Casts on {@code frontend=JAVAC}: a cast to a reference type is the reference's view, to its bytes; {@code int} and
 * {@code long} casts have Java's value, against the JVM at every level and at the boundaries.
 */
class JavacCastsTest {

    private static final StdlibRegistry STDLIB = StdlibRegistry.defaultRegistry();

    private static String method(String members) {
        return """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcList;
                import org.julclang.core.types.JulcMap;
                import org.julclang.ledger.*;
                import org.julclang.stdlib.Builtins;
                class C {
                %s
                }
                """.formatted(members);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static boolean m(PlutusData d) { JulcMap<PlutusData, PlutusData> m = (JulcMap<PlutusData, PlutusData>)(Object) Builtins.mkNilPairData(); return m.isEmpty(); }",
            "static boolean m(PlutusData d) { JulcMap<PlutusData, PlutusData> m = (JulcMap<PlutusData, PlutusData>)(Object) d; return m.isEmpty(); }",
            "static boolean m(PlutusData d) { PlutusData p = (PlutusData)(Object) Builtins.unListData(d); return Builtins.nullList(p); }",
            "static byte[] m(PubKeyHash h) { return (byte[])(Object) h; }",
            "static BigInteger m(PlutusData d) { JulcList<PlutusData> xs = (JulcList<PlutusData>)(Object) Builtins.unListData(d); return BigInteger.valueOf(xs.size()); }",
            "static long m(BigInteger a) { long x = a.longValue(); return (long) x + (long) (int) 5L; }",
            "static boolean m(boolean b) { return (boolean) b; }",
            "static BigInteger m(Value v) { return ((Value)(Object) v).lovelaceOf(); }",
            "record R(BigInteger a, byte[] b) { } static BigInteger m(PlutusData d) { var r = (R)(Object) d; return r.a(); }",
            "record R(BigInteger a, byte[] b) { } static BigInteger m(PlutusData d) { R r = (R)(Object) d; return r.a(); }",
    })
    void aCastIsTheReferences(String members) {
        LoweringFixture.assertSameMethodAsReference(method(members), "m", STDLIB);
    }

    /** {@code (int)} of a {@code long} keeps the low 32 bits, as Java does, at every level. */
    @Test
    void narrowingHasJavasValueAtEveryLevel() {
        var source = method("static long m(long x) { return (int) x; }");
        var values = new long[]{0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE, (long) Integer.MAX_VALUE + 1,
                (long) Integer.MIN_VALUE - 1, 1L << 32, (1L << 32) + 5, -(1L << 32) - 5, Long.MAX_VALUE, Long.MIN_VALUE};
        for (var level : OptimizationLevel.values()) {
            var program = new JulcCompiler(STDLIB, new CompilerOptions().setOptimizationLevel(level)
                    .setFrontend(CompilerFrontend.JAVAC)).compileMethod(source, "m", List.of()).program();
            for (long x : values) {
                long java = (int) x;
                var result = JulcVm.create().evaluateWithArgs(program, List.of(PlutusData.integer(x)));
                var term = assertInstanceOf(EvalResult.Success.class, result, () -> level + " " + x).resultTerm();
                assertEquals(new Term.Const(Constant.integer(BigInteger.valueOf(java))), term, level + " " + x);
            }
        }
    }

    /** The reference erases the narrowing: it differs from Java out of range (D4's cap, "Closed by M5"). */
    @Test
    void theReferenceErasesTheNarrowing() {
        var source = method("static long m(long x) { return (int) x; }");
        var program = new JulcCompiler(STDLIB).compileMethod(source, "m", List.of()).program();
        var result = JulcVm.create().evaluateWithArgs(program, List.of(PlutusData.integer((1L << 32) + 5)));
        assertNotEquals(new Term.Const(Constant.integer(BigInteger.valueOf(5))),
                assertInstanceOf(EvalResult.Success.class, result).resultTerm());
    }

    @Test
    void aConstantNarrowsAtCompileTime() {
        var source = method("static long m() { return (int) 4294967301L; }");
        var program = new JulcCompiler(STDLIB, new CompilerOptions().setFrontend(CompilerFrontend.JAVAC))
                .compileMethod(source, "m", List.of()).program();
        var result = JulcVm.create().evaluateWithArgs(program, List.of());
        assertEquals(new Term.Const(Constant.integer(BigInteger.valueOf(5))), assertInstanceOf(EvalResult.Success.class, result).resultTerm());
    }

    @ParameterizedTest
    @ValueSource(strings = {"byte", "short", "char"})
    void aCastToANarrowerTypeStops(String type) {
        LoweringFixture.assertMethodNotPorted(method("static long m(long x) { return (" + type + ") x; }"), "m",
                "a cast to " + type, 8);
    }

    /** D6: a raw map receiver is JULC0087 through a cast too; the stdlib's own view of a Value is the one exception. */
    @Test
    void aRawReceiverThroughACastIsJulc0087() {
        var source = method("static boolean m(PlutusData v, PlutusData p) { return ((JulcMap)(Object) v).containsKey(p); }");
        var failure = assertThrows(CompilerException.class, () -> new JulcCompiler(STDLIB,
                new CompilerOptions().setFrontend(CompilerFrontend.JAVAC)).compileMethod(source, "m", List.of()));
        assertEquals("JULC0087", failure.diagnostics().getFirst().code(), failure::getMessage);
        var values = """
                import org.julclang.ledger.Value;
                import org.julclang.stdlib.lib.ValuesLib;
                class C {
                    static boolean m(Value v, byte[] policy) { return ValuesLib.containsPolicy(v, policy); }
                }
                """;
        for (var level : OptimizationLevel.values()) {
            var reference = new JulcCompiler(STDLIB, new CompilerOptions().setOptimizationLevel(level)).compileMethod(values, "m");
            var javac = new JulcCompiler(STDLIB, new CompilerOptions().setOptimizationLevel(level)
                    .setFrontend(CompilerFrontend.JAVAC)).compileMethod(values, "m");
            assertArrayEquals(UplcFlatEncoder.encodeProgram(reference.program()), UplcFlatEncoder.encodeProgram(javac.program()),
                    level::toString);
        }
    }
}
