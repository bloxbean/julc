package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTestVm;
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
            "static boolean m(PlutusData d) { JulcMap<PlutusData, PlutusData> m = PlutusData.cast(d, JulcMap.class); return m.isEmpty(); }",
            "static boolean m(JulcMap<PlutusData, PlutusData> x) { PlutusData p = (PlutusData)(Object) x; JulcMap<PlutusData, PlutusData> m = (JulcMap<PlutusData, PlutusData>)(Object) p; return m.isEmpty(); }",
            "static boolean m(JulcMap<PlutusData, PlutusData> x) { var p = (PlutusData)(Object) x; JulcMap<PlutusData, PlutusData> m = (JulcMap<PlutusData, PlutusData>)(Object) p; return m.isEmpty(); }",
            "static boolean m(JulcMap<PlutusData, PlutusData> x, JulcList<PlutusData> ds) { var p = (PlutusData)(Object) x; boolean any = false; for (var e : ds) { any = true; } JulcMap<PlutusData, PlutusData> m = (JulcMap<PlutusData, PlutusData>)(Object) p; return m.isEmpty() || any; }",
            "static boolean m(boolean b, JulcMap<PlutusData, PlutusData> x, JulcMap<PlutusData, PlutusData> y) { JulcMap<PlutusData, PlutusData> m = (JulcMap<PlutusData, PlutusData>)(Object) (b ? (PlutusData)(Object) x : (PlutusData)(Object) y); return m.isEmpty(); }",
    })
    void aCastIsTheReferences(String members) {
        LoweringFixture.assertSameMethodAsReference(method(members), "m", STDLIB);
    }

    /** A {@code var} bound to a cast to Data has the type of what was cast, as the reference reads it: a map has no {@code equals}. */
    @Test
    void aVarOfACastToDataIsWhatWasCast() {
        LoweringFixture.assertSameMethodRejectionAsReference(method(
                "static boolean m(JulcMap<PlutusData, PlutusData> x, PlutusData y) { var d = (PlutusData)(Object) x; return d.equals(y); }"),
                "m", List.of(), STDLIB);
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
        var program = new JulcCompiler(STDLIB, new CompilerOptions().setFrontend(CompilerFrontend.JAVAPARSER)).compileMethod(source, "m", List.of()).program();
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
        LoweringFixture.assertMethodRejected(method("static long m(long x) { return (" + type + ") x; }"), "m", "JULC0124", 8);
    }

    /**
     * A cast to a map reads a {@code var} through its initializer only where nothing assigns it again: one of a type that is no
     * map, assigned in a loop or a branch, may hold another value at the cast. It stops (I8).
     */
    @ParameterizedTest
    @ValueSource(strings = {
            // A view of a map, assigned a Data element in a for-each.
            "static boolean m(JulcMap<PlutusData, PlutusData> x, JulcList<PlutusData> ds) { var p = (PlutusData)(Object) x; for (var e : ds) { p = e; } JulcMap<PlutusData, PlutusData> m = (JulcMap<PlutusData, PlutusData>)(Object) p; return m.isEmpty(); }",
            // Assigned on one branch.
            "static boolean m(boolean b, JulcMap<PlutusData, PlutusData> x, JulcList<PlutusData> ds) { var p = (PlutusData)(Object) x; for (var e : ds) { if (b) { p = e; } } JulcMap<PlutusData, PlutusData> m = (JulcMap<PlutusData, PlutusData>)(Object) p; return m.isEmpty(); }",
            // Compounded in a loop (an increment is rejected wherever it is lowered).
            "static boolean m(JulcList<PlutusData> ds) { var i = 0L; for (var e : ds) { i += 1; } JulcMap<PlutusData, PlutusData> m = (JulcMap<PlutusData, PlutusData>)(Object) i; return m.isEmpty(); }",
    })
    void aCastToAMapOfAVarAssignedAgainStops(String members) {
        LoweringFixture.assertMethodRejected(method(members), "m", "JULC0012", 8);
    }

    /**
     * A variable with a written type is read as that type, as the reference binds it, whether or not it is assigned again,
     * and so is a {@code var} assigned again whose type is a map: the reference's method.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "static boolean m(JulcMap<PlutusData, PlutusData> x, JulcList<PlutusData> ds) { PlutusData p = (PlutusData)(Object) x; for (var e : ds) { p = e; } JulcMap<PlutusData, PlutusData> m = (JulcMap<PlutusData, PlutusData>)(Object) p; return m.isEmpty(); }",
            "static boolean m(JulcMap<PlutusData, PlutusData> x, JulcList<PlutusData> ds) { PlutusData p = ds.head(); for (var e : ds) { p = (PlutusData)(Object) x; } JulcMap<PlutusData, PlutusData> m = (JulcMap<PlutusData, PlutusData>)(Object) p; return m.isEmpty(); }",
            "static boolean m(JulcMap<PlutusData, PlutusData> x, JulcMap<PlutusData, PlutusData> z, JulcList<PlutusData> ds) { JulcMap<PlutusData, PlutusData> y = x; for (var e : ds) { y = z; } JulcMap<PlutusData, PlutusData> q = PlutusData.cast(y, JulcMap.class); return q.isEmpty(); }",
            "static boolean m(JulcMap<PlutusData, PlutusData> x, JulcMap<PlutusData, PlutusData> z, JulcList<PlutusData> ds) { var y = x; for (var e : ds) { y = z; } JulcMap<PlutusData, PlutusData> q = PlutusData.cast(y, JulcMap.class); return q.isEmpty(); }",
    })
    void aVariableReadByItsTypeIsTheReferences(String members) {
        LoweringFixture.assertSameMethodAsReference(method(members), "m", STDLIB);
    }

    /** A {@code var} is read through its initializer, so one that holds a decoded map on one path and Data on the other stops. */
    @Test
    void aVarOfAMapOnOnePathStops() {
        LoweringFixture.assertMethodRejected(method("static boolean m(boolean b, JulcMap<PlutusData, PlutusData> x, PlutusData d) { var p = b ? (PlutusData)(Object) x : d; JulcMap<PlutusData, PlutusData> m = (JulcMap<PlutusData, PlutusData>)(Object) p; return m.isEmpty(); }"),
                "m", "JULC0012", 8);
    }

    /**
     * A view held in a variable of written type {@code PlutusData}, assigned again in a loop: the reference decodes what the
     * variable holds after the loop, and so does {@code JAVAC}, with the reference's bytes. The JVM throws at the view (#288).
     */
    @Test
    void aViewAssignedAgainInALoopIsReadAsData() {
        var source = method("static boolean m(JulcMap<PlutusData, PlutusData> x, JulcList<PlutusData> ds) { PlutusData p = (PlutusData)(Object) x; for (var e : ds) { p = e; } JulcMap<PlutusData, PlutusData> m = (JulcMap<PlutusData, PlutusData>)(Object) p; return m.isEmpty(); }");
        var x = new PlutusData.MapData(List.of(new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(2))));
        var oneEmpty = new PlutusData.ListData(List.of(new PlutusData.MapData(List.of())));
        var none = new PlutusData.ListData(List.of());
        for (var level : OptimizationLevel.values()) {
            for (var frontend : List.of(CompilerFrontend.JAVAPARSER, CompilerFrontend.JAVAC)) {
                var program = new JulcCompiler(STDLIB, new CompilerOptions().setOptimizationLevel(level).setFrontend(frontend))
                        .compileMethod(source, "m", List.of()).program();
                assertEquals(new Term.Const(Constant.bool(true)), assertInstanceOf(EvalResult.Success.class,
                        CompilerTestVm.pv11().evaluateWithArgs(program, List.of(x, oneEmpty)), level::toString).resultTerm());
                assertInstanceOf(EvalResult.Failure.class, CompilerTestVm.pv11().evaluateWithArgs(program, List.of(x, none)), level::toString);
            }
        }
        LoweringFixture.assertSameMethodAsReference(source, "m", STDLIB);
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
            var reference = new JulcCompiler(STDLIB, new CompilerOptions().setFrontend(CompilerFrontend.JAVAPARSER).setOptimizationLevel(level)).compileMethod(values, "m");
            var javac = new JulcCompiler(STDLIB, new CompilerOptions().setOptimizationLevel(level)
                    .setFrontend(CompilerFrontend.JAVAC)).compileMethod(values, "m");
            assertArrayEquals(UplcFlatEncoder.encodeProgram(reference.program()), UplcFlatEncoder.encodeProgram(javac.program()),
                    level::toString);
        }
    }
}
