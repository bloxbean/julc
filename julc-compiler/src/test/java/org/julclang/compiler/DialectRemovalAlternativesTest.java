package org.julclang.compiler;

import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.core.Constant;
import org.julclang.compiler.frontend.javac.JavacGateMode;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.julclang.vm.OptimizationCostProfiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What replaces the dialect D6 removes (ADR-068 D6, table U of the M1b evidence). Each alternative compiles under the
 * default {@code enforce} gate and evaluates on the VM, because users are told to use it; each removed form is
 * {@code JULC0085}.
 */
class DialectRemovalAlternativesTest {

    private static final String IMPORTS = """
            import java.math.BigInteger;
            import org.julclang.core.*;
            import org.julclang.core.types.*;
            import org.julclang.ledger.*;
            import org.julclang.stdlib.*;
            import org.julclang.stdlib.lib.*;
            """;

    private static final String TYPES = """
                record Point(BigInteger n) {}
                sealed interface Shape permits Circle {}
                record Circle(BigInteger n) implements Shape {}
            """;

    private static EvalResult run(JavacGateMode gate, String method, List<PlutusData> args) {
        var options = new CompilerOptions();
        if (gate != null) options.setJavacGate(gate);
        var compiled = new JulcCompiler(StdlibRegistry.defaultRegistry(), options)
                .compileMethod(IMPORTS + "class Alternatives {\n" + TYPES + method + "\n}\n", "f");
        var vm = JulcVm.create("Java");
        var costs = OptimizationCostProfiles.PLUTUS_V3_PV11_COSTS_V1;
        vm.setCostModelParams(costs.costModelParameters(), costs.target());
        return vm.evaluateWithArgs(compiled.program(), args);
    }

    private static Object value(EvalResult result) {
        var success = assertInstanceOf(EvalResult.Success.class, result, () -> result.toString());
        var constant = assertInstanceOf(Term.Const.class, success.resultTerm(), () -> success.resultTerm().toString());
        return constant.value();
    }

    private static BigInteger integer(String body, PlutusData... args) {
        var result = run(null, "    static BigInteger f(PlutusData d) {\n" + body + "\n    }", List.of(args));
        return ((Constant.IntegerConst) value(result)).value();
    }

    private static final PlutusData NOTHING = PlutusData.integer(0);

    @Test
    void u1_mapKeysAndValuesReplaceIteratingTheMap() {
        assertEquals(BigInteger.valueOf(3), integer("""
                JulcMap<BigInteger, BigInteger> empty = JulcMap.empty();
                var m = empty.insert(BigInteger.ONE, BigInteger.TWO);
                BigInteger n = BigInteger.ZERO;
                for (var k : m.keys()) { n = n.add(k); }
                for (var v : m.values()) { n = n.add(v); }
                return n;
                """, NOTHING));
    }

    @Test
    void u2_anInstanceMethodReplacesARegistryHigherOrderFunction() {
        assertEquals(BigInteger.valueOf(5), integer("""
                JulcList<BigInteger> xs = JulcList.of(BigInteger.ONE, BigInteger.TWO);
                JulcList<BigInteger> ys = xs.map(x -> x.add(BigInteger.ONE));
                BigInteger n = BigInteger.ZERO;
                for (var y : ys) { n = n.add(y); }
                return n;
                """, NOTHING));
    }

    @Test
    void u2_aLoopFoldsFromTheLeftLikeFoldl() {
        assertEquals(BigInteger.valueOf(7), integer("""
                JulcList<BigInteger> xs = JulcList.of(BigInteger.ONE, BigInteger.TWO);
                BigInteger acc = BigInteger.TEN;
                for (var x : xs) { acc = acc.subtract(x); }
                return acc;
                """, NOTHING));
    }

    @Test
    void u2_aLoopOverTwoListsStopsAtTheShorterLikeZip() {
        assertEquals(BigInteger.valueOf(12), integer("""
                JulcList<BigInteger> xs = JulcList.of(BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3));
                JulcList<BigInteger> ys = JulcList.of(BigInteger.TEN, BigInteger.ONE);
                BigInteger n = BigInteger.ZERO;
                while (!xs.isEmpty() && !ys.isEmpty()) {
                    n = n.add(xs.head().multiply(ys.head()));
                    xs = xs.tail();
                    ys = ys.tail();
                }
                return n;
                """, NOTHING));
    }

    @Test
    void u4_explicitConversionsReplaceImplicitCoercion() {
        assertEquals(BigInteger.TWO, integer("""
                PlutusData b = Builtins.bData(new byte[] {1, 2});
                return BigInteger.valueOf(Builtins.lengthOfByteString(Builtins.unBData(b)));
                """, NOTHING));
    }

    @Test
    void u5_declaredBuiltinsReplaceDialectMethods() {
        assertEquals(BigInteger.valueOf(32), integer("""
                byte[] b = new byte[] {1, 2};
                return BigInteger.valueOf(Builtins.lengthOfByteString(Builtins.sha2_256(b)));
                """, NOTHING));
    }

    @Test
    void u6_castViewsDataAsAUserRecordOnChain() {
        assertEquals(BigInteger.valueOf(42), integer("""
                Point p = PlutusData.cast(d, Point.class);
                return p.n();
                """, PlutusData.constr(0, PlutusData.integer(42))));
    }

    @Test
    void u6_castViewsDataAsASealedInterfaceOnChain() {
        assertEquals(BigInteger.valueOf(42), integer("""
                Shape s = PlutusData.cast(d, Shape.class);
                return switch (s) { case Circle c -> c.n(); };
                """, PlutusData.constr(0, PlutusData.integer(42))));
    }

    @Test
    void u6_castDoesNotDecodeDataOnTheJvm() {
        record Point(BigInteger n) {
        }
        assertThrows(ClassCastException.class, () -> {
            Point ignored = PlutusData.cast(PlutusData.constr(0, PlutusData.integer(42)), Point.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "JulcMap<BigInteger, BigInteger> m = JulcMap.empty(); BigInteger n = BigInteger.ZERO; for (var e : m) { n = n.add(BigInteger.ONE); } return n;",
            "return ListsLib.foldl((a, x) -> a, BigInteger.ZERO, JulcList.of(BigInteger.ONE));",
            "var f = (BigInteger x) -> x; return BigInteger.ONE;",
            "byte[] b = new byte[] {1}; return BigInteger.valueOf(b.length());",
            "Point p = Point.fromPlutusData(d); return p.n();"
    })
    void removedDialectIsAJavacRejection(String body) {
        var error = assertThrows(CompilerException.class,
                () -> run(null, "    static BigInteger f(PlutusData d) {\n" + body + "\n    }", List.of(NOTHING)));
        assertTrue(error.diagnostics().stream().anyMatch(d -> "JULC0085".equals(d.code())), error::getMessage);
    }

    // The equality rewrites of M1b (D13 rows "equals for == on Data"). javac rejects == between these types.

    private static EvalResult compare(JavacGateMode gate, String expression, String type, PlutusData left) {
        return run(gate, "    static boolean f(" + type + " x, byte[] y) { return " + expression + "; }",
                List.of(left, PlutusData.bytes(new byte[] {1})));
    }

    @Test
    void rewritingValueEqualityTurnsARuntimeFailureIntoAComparison() {
        var map = PlutusData.map();
        assertInstanceOf(EvalResult.Failure.class, compare(JavacGateMode.OFF, "x == y", "Value", map));
        var rewritten = (Constant.BoolConst) value(compare(null, "x.equals(y)", "Value", map));
        assertFalse(rewritten.value(), "a Value is not equal to bytes");
    }

    @Test
    void rewritingHashEqualityKeepsEveryObservation() {
        for (var left : List.of(PlutusData.bytes(new byte[] {1}), PlutusData.bytes(new byte[] {2}), PlutusData.map())) {
            var dialect = compare(JavacGateMode.OFF, "x == y", "PubKeyHash", left);
            var rewritten = compare(null, "x.equals(y)", "PubKeyHash", left);
            assertEquals(dialect.getClass(), rewritten.getClass(), left.toString());
            if (dialect instanceof EvalResult.Success) assertEquals(value(dialect), value(rewritten), left.toString());
        }
    }
}
