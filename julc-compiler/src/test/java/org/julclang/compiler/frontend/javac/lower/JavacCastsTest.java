package org.julclang.compiler.frontend.javac.lower;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTestVm;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.function.LongUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** {@link #method} with the stdlib's byte strings: the two-argument entry finds the library on the class path. */
    private static Program compiledWithLibrary(String members, CompilerFrontend frontend, OptimizationLevel level) {
        var source = "import org.julclang.stdlib.lib.ByteStringLib;\n" + method(members);
        return new JulcCompiler(STDLIB, new CompilerOptions().setOptimizationLevel(level).setFrontend(frontend))
                .compileMethod(source, "m").program();
    }

    private static byte[] bytesOf(String members, CompilerFrontend frontend, OptimizationLevel level) {
        return UplcFlatEncoder.encodeProgram(compiledWithLibrary(members, frontend, level));
    }

    /** ADR-068 r18: where the operand's value is shown to be in range, the script is what the reference's erasure gives. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static long m(byte[] b, long i) { return (int) Builtins.indexByteString(b, i); }",
            "static long m(byte[] b, long i) { return (int) ByteStringLib.at(b, i); }",
            "static long m(byte[] b, long i) { long v = ByteStringLib.at(b, i); int x = (int) v; return x + 1; }",
            "static long h(byte[] b) { return Builtins.indexByteString(b, 0); } static long m(byte[] b) { return (int) h(b); }",
            "static long five() { return 5L; } static long m() { return (int) five(); }",
            "static long m(long x) { return (int) (x % 16); }",
            "static long m(long x) { return (int) (x % 2147483648L); }",
            "static long m(BigInteger a) { return (int) a.mod(BigInteger.valueOf(256)).longValue(); }",
            "static int nibble(byte[] self, int index) { int v = (int) ByteStringLib.at(self, index / 2); "
                    + "if (index % 2 == 0) { return v / 16; } else { return v % 16; } } static long m(byte[] p, int i) { return nibble(p, i); }",
    })
    void aCastInRangeHasNoWrapAtAnyLevel(String members) {
        for (var level : OptimizationLevel.values()) {
            assertArrayEquals(bytesOf(members, CompilerFrontend.JAVAPARSER, level), bytesOf(members, CompilerFrontend.JAVAC, level), level + " " + members);
        }
    }

    /** Where the value is not shown to be in range the wrap stays, so the script differs from the reference's erasure. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static long m(long x) { return (int) x; }",
            "static long m(long x) { return (int) (x + 1); }",
            // A length has no upper bound.
            "static long m(byte[] b) { return (int) Builtins.lengthOfByteString(b); }",
            "static long m(byte[] b) { return (int) ByteStringLib.length(b); }",
            // 2^32 - 1 is out of range.
            "static long m(long x) { return (int) (x % 4294967296L); }",
            "static long m(long x, long k) { return (int) (x % k); }",
            "static long m(BigInteger a) { return (int) a.mod(BigInteger.valueOf(4294967296L)).longValue(); }",
            // An arm of a choice, and a loop's result: a local assigned again is what its last assignment left.
            "static long m(boolean c, byte[] b, long x) { long v = c ? ByteStringLib.at(b, 0) : x; return (int) v; }",
            "static long m(byte[] b, JulcList<BigInteger> xs) { long v = ByteStringLib.at(b, 0); for (var y : xs) { v = v + y.longValue(); } return (int) v; }",
            // A parameter, and a recursive method.
            "static long id(long x) { return x; } static long m(byte[] b) { return (int) id(ByteStringLib.at(b, 0)); }",
            "static long r(long n) { if (n == 0) { return 5L; } else { return r(n - 1); } } static long m(long n) { return (int) r(n); }",
    })
    void aCastNotShownInRangeKeepsItsWrap(String members) {
        for (var level : OptimizationLevel.values()) {
            assertNotEquals(HexFormat.of().formatHex(bytesOf(members, CompilerFrontend.JAVAPARSER, level)),
                    HexFormat.of().formatHex(bytesOf(members, CompilerFrontend.JAVAC, level)), level + " " + members);
        }
    }

    /**
     * The pass matches a term's shape, so a wrap written out by hand goes too, on both frontends and at every level, where its operand is shown
     * in range; it stays where the operand is not. Each is Java's value at the boundaries.
     */
    @Test
    void aWrapWrittenByHandIsRemovedOnBothFrontends() {
        var template = "static BigInteger m(BigInteger x) { BigInteger y = x.mod(BigInteger.valueOf(%sL)); "
                + "return y.add(BigInteger.valueOf(2147483648L)).mod(BigInteger.valueOf(4294967296L)).subtract(BigInteger.valueOf(2147483648L)); }";
        var inRange = template.formatted("16");
        var outOfRange = template.formatted("8589934592");
        for (var frontend : CompilerFrontend.values()) {
            for (var level : OptimizationLevel.values()) {
                assertTrue(bytesOf(inRange, frontend, level).length < bytesOf(outOfRange, frontend, level).length, frontend + " " + level);
                for (long x : new long[]{-1, 0, 15, 16, 2147483647L, 2147483648L, 4294967296L, -2147483649L, 8589934591L}) {
                    var in = BigInteger.valueOf(x).mod(BigInteger.valueOf(16));
                    var out = BigInteger.valueOf(x).mod(BigInteger.valueOf(8589934592L));
                    assertEquals(wrapOf(in), evaluatedInteger(compiledWithLibrary(inRange, frontend, level), x), frontend + " " + level + " in " + x);
                    assertEquals(wrapOf(out), evaluatedInteger(compiledWithLibrary(outOfRange, frontend, level), x), frontend + " " + level + " out " + x);
                }
            }
        }
    }

    private static Term evaluatedInteger(Program compiled, long x) {
        var result = JulcVm.create().evaluateWithArgs(compiled, List.of(PlutusData.integer(x)));
        return assertInstanceOf(EvalResult.Success.class, result).resultTerm();
    }

    private static Term wrapOf(BigInteger y) {
        var half = BigInteger.valueOf(2147483648L);
        return new Term.Const(Constant.integer(y.add(half).mod(half.shiftLeft(1)).subtract(half)));
    }

    /** A byte with its wrap removed is the JVM's unsigned value, 0 to 255, at every level, and past the end it still fails. */
    @Test
    void aReadByteIsJavasValueWithItsWrapRemoved() {
        var program = "static long m(byte[] b, long i) { return (int) ByteStringLib.at(b, i); }";
        var data = new byte[]{0, 1, 127, (byte) 128, (byte) 255};
        for (var level : OptimizationLevel.values()) {
            var compiled = compiledWithLibrary(program, CompilerFrontend.JAVAC, level);
            for (int i = 0; i < data.length; i++) {
                long java = (int) (long) Byte.toUnsignedInt(data[i]);
                var result = JulcVm.create().evaluateWithArgs(compiled, List.of(PlutusData.bytes(data), PlutusData.integer(i)));
                assertEquals(new Term.Const(Constant.integer(BigInteger.valueOf(java))),
                        assertInstanceOf(EvalResult.Success.class, result, () -> level + " " + data).resultTerm(), level.toString());
            }
            // The operand still runs: an index past the end fails, as it does in Java.
            assertInstanceOf(EvalResult.Failure.class, JulcVm.create().evaluateWithArgs(compiled, List.of(PlutusData.bytes(data), PlutusData.integer(5))));
        }
    }

    /** A value shown in range and one not: the JVM's answer on each side of the bound. */
    @Test
    void remainderAndModuloHaveJavasValue() {
        record Row(String members, LongUnaryOperator java) {}
        var rows = List.of(
                new Row("static long m(long x) { return (int) (x % 16); }", x -> (int) (x % 16)),
                new Row("static long m(long x) { return (int) (x % 2147483648L); }", x -> (int) (x % 2147483648L)),
                new Row("static long m(long x) { return (int) (x % 4294967296L); }", x -> (int) (x % 4294967296L)),
                new Row("static long m(BigInteger x) { return (int) x.mod(BigInteger.valueOf(256)).longValue(); }", x -> (int) Math.floorMod(x, 256L)),
                new Row("static long m(BigInteger x) { return (int) x.mod(BigInteger.valueOf(4294967296L)).longValue(); }", x -> (int) Math.floorMod(x, 4294967296L)));
        var values = new long[]{0, 1, -1, 15, 16, -16, -17, 255, 256, 2147483647L, 2147483648L, -2147483648L, -2147483649L, 4294967295L, 4294967296L, -4294967297L};
        for (var row : rows) {
            for (var level : OptimizationLevel.values()) {
                var compiled = compiledWithLibrary(row.members(), CompilerFrontend.JAVAC, level);
                for (long x : values) {
                    var result = JulcVm.create().evaluateWithArgs(compiled, List.of(PlutusData.integer(x)));
                    assertEquals(new Term.Const(Constant.integer(BigInteger.valueOf(row.java().applyAsLong(x)))),
                            assertInstanceOf(EvalResult.Success.class, result, () -> row.members() + " " + x).resultTerm(), row.members() + " " + level + " " + x);
                }
            }
        }
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
