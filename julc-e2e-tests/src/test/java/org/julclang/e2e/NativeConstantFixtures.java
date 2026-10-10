package org.julclang.e2e;

import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.Constant;
import org.julclang.core.DefaultFun;
import org.julclang.core.DefaultUni;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatDecoder;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.params.provider.Arguments;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Properties;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Same source/input corpus for offline regression pins and real node transactions. */
final class NativeConstantFixtures {
    private NativeConstantFixtures() {}
    /** G1_CONS/G2_CONS (#240): the point and scalar lists built one element at a time by a recursive helper. */
    enum Kind { VALUE, ARRAY, G1, G2, G1_CONS, G2_CONS }

    private static String group(Kind kind) {
        return kind == Kind.G1 || kind == Kind.G1_CONS ? "g1" : "g2";
    }
    record Scenario(String name, long datum, long redeemer) {}
    record Rejection(PlutusData data, String javaCause, String haskellCause) {}

    static void assertGolden(Kind kind, OptimizationLevel level, Scenario scenario, CompileResult compiled,
                             long cpu, long memory) throws IOException {
        var pins = new Properties();
        try (var stream = NativeConstantFixtures.class.getResourceAsStream("/native-constants-pv11.properties")) {
            assertNotNull(stream, "Missing reviewed native constant regression pins");
            pins.load(stream);
        }
        String key = kind + "." + level + "." + scenario.name();
        String actual = JulcScriptAdapter.scriptHash(compiled.program()) + ","
                + UplcFlatEncoder.encodeProgram(compiled.program()).length + "," + cpu + "," + memory;
        assertNotNull(pins.getProperty(key), "Missing regression row " + key);
        assertEquals(pins.getProperty(key), actual, key + ": artifact or cost drift requires review");
    }

    static Stream<Arguments> cases() {
        return Stream.of(Kind.values()).flatMap(kind -> Stream.of(
                OptimizationLevel.BASELINE, OptimizationLevel.PV11_SAFE, OptimizationLevel.PV11_COSTED)
                .map(level -> Arguments.of(kind, level)));
    }

    static String source(Kind kind) {
        String body = switch (kind) {
            case VALUE -> """
                    return NativeValueLib.contains(Builtins.lovelaceValue(7), Builtins.lovelaceValue(redeemer));
                    """;
            case ARRAY -> """
                    JulcArray<BigInteger> table = JulcArray.of(BigInteger.TWO, BigInteger.valueOf(3), BigInteger.valueOf(5));
                    return table.get(redeemer).equals(datum);
                    """;
            case G1, G2 -> """
                    var p = BlsLib.GROUPHashToGroup(new byte[]{1}, new byte[]{});
                    var q = BlsLib.GROUPHashToGroup(new byte[]{2}, new byte[]{});
                    var sum = BlsLib.GROUPMultiScalarMul(Builtins.scalars(redeemer, BigInteger.ONE), Builtins.GROUPPoints(p, q));
                    var expected = BlsLib.GROUPAdd(BlsLib.GROUPScalarMul(datum, p), q);
                    return BlsLib.GROUPEqual(sum, expected);
                    """.replace("GROUP", group(kind));
            case G1_CONS, G2_CONS -> """
                    var p = BlsLib.GROUPHashToGroup(new byte[]{1}, new byte[]{});
                    var q = BlsLib.GROUPHashToGroup(new byte[]{2}, new byte[]{});
                    var sum = BlsLib.GROUPMultiScalarMul(
                            Builtins.scalarsCons(BigInteger.ONE, Builtins.scalarsCons(redeemer, Builtins.scalarsEmpty())), points(BigInteger.TWO));
                    var expected = BlsLib.GROUPAdd(BlsLib.GROUPScalarMul(datum, p), q);
                    return BlsLib.GROUPEqual(sum, expected);
                    """.replace("GROUP", group(kind));
        };
        // The cons kinds build [H(2), H(1)] = [q, p] by recursion, one point per call.
        String helpers = switch (kind) {
            case G1_CONS, G2_CONS -> """
                    static POINTS points(BigInteger i) {
                        if (i.equals(BigInteger.ZERO)) return Builtins.GROUPPointsEmpty();
                        return Builtins.GROUPPointsCons(BlsLib.GROUPHashToGroup(Builtins.integerToByteString(true, 0, i), new byte[]{}),
                                points(i.subtract(BigInteger.ONE)));
                    }
                    """.replace("POINTS", kind == Kind.G1_CONS ? "JulcG1Points" : "JulcG2Points").replace("GROUP", group(kind));
            default -> "";
        };
        return """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcArray;
                import org.julclang.stdlib.Builtins;
                import org.julclang.stdlib.lib.NativeValueLib;
                import org.julclang.stdlib.lib.BlsLib;
                """ + (helpers.isEmpty() ? "" : """
                import org.julclang.core.types.JulcG1Points;
                import org.julclang.core.types.JulcG2Points;
                """) + """
                @SpendingValidator class NativeConstantGate {
                """ + helpers + """
                    @Entrypoint static boolean validate(BigInteger datum, BigInteger redeemer, PlutusData ctx) {
                """ + body + "\n}}";
    }

    static List<Scenario> scenarios(Kind kind) {
        return switch (kind) {
            case VALUE -> List.of(new Scenario("zero", 0, 0), new Scenario("exact", 0, 7));
            case ARRAY -> List.of(new Scenario("first", 2, 0), new Scenario("last", 5, 2));
            case G1, G2, G1_CONS, G2_CONS -> List.of(new Scenario("zero-scalar", 0, 0), new Scenario("two", 2, 2));
        };
    }

    /** Used against the first scenario; malformed integer also checks the typed boundary. */
    static List<Rejection> rejected(Kind kind) {
        var wrongKind = new Rejection(PlutusData.bytes(new byte[]{0}), "UnIData", "(builtin unIData)");
        return switch (kind) {
            case VALUE -> List.of(falseResult(8), new Rejection(PlutusData.integer(-1),
                    "ValueContains", "(builtin valueContains)"), wrongKind);
            case ARRAY -> List.of(falseResult(1), new Rejection(PlutusData.integer(-1), "IndexArray", "(builtin indexArray)"),
                    new Rejection(PlutusData.integer(3), "IndexArray", "(builtin indexArray)"), wrongKind);
            case G1, G2, G1_CONS, G2_CONS -> List.of(falseResult(1), new Rejection(PlutusData.integer(BigInteger.ONE.shiftLeft(4095)),
                    "scalar too large", group(kind).equals("g1") ? "(builtin bls12_381_G1_multiScalarMul)"
                            : "(builtin bls12_381_G2_multiScalarMul)"), wrongKind);
        };
    }

    private static Rejection falseResult(long value) {
        return new Rejection(PlutusData.integer(value), "Error term encountered", "Caused by: (error)");
    }

    static CompileResult compile(Kind kind, OptimizationLevel level) {
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setOptimizationLevel(level));
        var compiled = compiler.compile(source(kind));
        assertFalse(compiled.hasErrors(), compiled.diagnostics().toString());
        var flat = UplcFlatEncoder.encodeProgram(compiled.program());
        assertArrayEquals(flat, UplcFlatEncoder.encodeProgram(compiler.compile(source(kind)).program()));
        var decoded = UplcFlatDecoder.decodeProgram(flat);
        assertArrayEquals(flat, UplcFlatEncoder.encodeProgram(decoded));
        boolean safe = level != OptimizationLevel.BASELINE;
        switch (kind) {
            case VALUE -> {
                assertTrue(any(decoded.term(), t -> t instanceof Term.Const c && c.value() instanceof Constant.ValueConst));
                if (safe) assertTrue(any(decoded.term(), t -> t instanceof Term.Const c
                        && c.value() instanceof Constant.ValueConst v && !v.entries().isEmpty()));
                assertEquals(safe, compiled.optimizationReport().appliedRules().contains("pv11.o14.value-literal-fold"));
            }
            case ARRAY -> {
                assertEquals(safe, any(decoded.term(), t -> t instanceof Term.Const c && c.value() instanceof Constant.ArrayConst));
                assertTrue(any(decoded.term(), t -> t instanceof Term.Builtin b && b.fun() == DefaultFun.IndexArray));
                assertEquals(safe, compiled.optimizationReport().appliedRules().contains("pv11.o10.array-literal-fold"));
            }
            case G1, G2, G1_CONS, G2_CONS -> {
                boolean g1 = group(kind).equals("g1");
                var group = g1 ? DefaultUni.BLS12_381_G1 : DefaultUni.BLS12_381_G2;
                var msm = g1 ? DefaultFun.Bls12_381_G1_multiScalarMul : DefaultFun.Bls12_381_G2_multiScalarMul;
                assertTrue(any(decoded.term(), t -> t instanceof Term.Const c && c.value() instanceof Constant.ListConst l
                        && l.elemType().equals(group)), "native point-list constant must survive serialization");
                assertTrue(any(decoded.term(), t -> t instanceof Term.Builtin b && b.fun() == msm));
                if (kind == Kind.G1_CONS || kind == Kind.G2_CONS) {
                    assertTrue(any(decoded.term(), t -> t instanceof Term.Builtin b && b.fun() == DefaultFun.MkCons),
                            "the incremental list must be built by MkCons at runtime");
                }
            }
        }
        return compiled;
    }

    private static boolean any(Term term, Predicate<Term> predicate) {
        if (predicate.test(term)) return true;
        return switch (term) {
            case Term.Apply a -> any(a.function(), predicate) || any(a.argument(), predicate);
            case Term.Lam l -> any(l.body(), predicate);
            case Term.Force f -> any(f.term(), predicate);
            case Term.Delay d -> any(d.term(), predicate);
            case Term.Case c -> any(c.scrutinee(), predicate) || c.branches().stream().anyMatch(t -> any(t, predicate));
            case Term.Constr c -> c.fields().stream().anyMatch(t -> any(t, predicate));
            default -> false;
        };
    }
}
