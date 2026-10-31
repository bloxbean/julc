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

    // The equality rewrites of M1b (D13 rows "equals for == on Data"). javac rejects == between these unrelated types.
    // No corpus evaluation runs these fixtures, so their loops are compared here, on the fixtures' own operand types.

    private static final PlutusData HASH = PlutusData.bytes(new byte[28]);
    private static final PlutusData NO_SIGNATORIES = PlutusData.list();
    private static final PlutusData TWO_SIGNATORIES = PlutusData.list(HASH, HASH);
    private static final PlutusData NO_OUTPUTS = PlutusData.list();
    private static final PlutusData ONE_OUTPUT = PlutusData.list(PlutusData.constr(0,
            PlutusData.constr(0, PlutusData.constr(0, HASH), PlutusData.constr(1)), PlutusData.map(),
            PlutusData.constr(0), PlutusData.constr(1)));

    /** Outcome, value, traces and failure message: everything D13 calls a semantic observation. */
    private static String observed(EvalResult result) {
        return switch (result) {
            case EvalResult.Success s -> "Success " + s.resultTerm() + " " + s.traces();
            case EvalResult.Failure f -> "Failure " + f.error().split(",")[0] + " " + f.traces();
            default -> result.toString();
        };
    }

    private static String breakOverOutputs(JavacGateMode gate, String expression, PlutusData outputs) {
        return observed(run(gate, """
                    static boolean f(JulcList<TxOut> outputs, byte[] redeemer) {
                        boolean paid = false;
                        for (var output : outputs) {
                            if (%s) {
                                paid = true;
                                break;
                            }
                        }
                        return paid;
                    }
                """.formatted(expression), List.of(outputs, PlutusData.bytes(new byte[] {1}))));
    }

    private static String twoAccumulators(JavacGateMode gate, String expression, PlutusData signatories) {
        return observed(run(gate, """
                    static boolean f(JulcList<PubKeyHash> signatories, BigInteger redeemer) {
                        boolean found = false;
                        BigInteger count = BigInteger.ZERO;
                        for (var sig : signatories) {
                            found = found || %s;
                            count = count.add(BigInteger.ONE);
                        }
                        return found;
                    }
                """.formatted(expression), List.of(signatories, PlutusData.integer(1))));
    }

    private static String twoAccumulatorsWithBreak(JavacGateMode gate, String expression, PlutusData signatories) {
        return observed(run(gate, """
                    static boolean f(JulcList<PubKeyHash> signatories, BigInteger redeemer) {
                        boolean found = false;
                        BigInteger count = BigInteger.ZERO;
                        for (var sig : signatories) {
                            found = %s;
                            count = count.add(BigInteger.ONE);
                            if (found) {
                                break;
                            }
                        }
                        return found;
                    }
                """.formatted(expression), List.of(signatories, PlutusData.integer(1))));
    }

    @Test
    void anEmptyLoopReachesNoComparison() {
        assertEquals(breakOverOutputs(JavacGateMode.OFF, "output.value() == redeemer", NO_OUTPUTS),
                breakOverOutputs(null, "output.value().equals(redeemer)", NO_OUTPUTS));
        assertEquals(twoAccumulators(JavacGateMode.OFF, "sig == redeemer", NO_SIGNATORIES),
                twoAccumulators(null, "sig.equals(redeemer)", NO_SIGNATORIES));
        assertEquals(twoAccumulatorsWithBreak(JavacGateMode.OFF, "sig == redeemer", NO_SIGNATORIES),
                twoAccumulatorsWithBreak(null, "sig.equals(redeemer)", NO_SIGNATORIES));
    }

    @Test
    void rewritingValueEqualityTurnsARuntimeFailureIntoAComparison() {
        assertTrue(breakOverOutputs(JavacGateMode.OFF, "output.value() == redeemer", ONE_OUTPUT)
                .startsWith("Failure EqualsData: expected data"));
        assertTrue(breakOverOutputs(null, "output.value().equals(redeemer)", ONE_OUTPUT).contains("value=false"));
    }

    @Test
    void rewritingHashEqualityWithAnIntegerStillFailsAtADifferentBuiltin() {
        for (var loop : List.of(twoAccumulators(JavacGateMode.OFF, "sig == redeemer", TWO_SIGNATORIES),
                twoAccumulatorsWithBreak(JavacGateMode.OFF, "sig == redeemer", TWO_SIGNATORIES))) {
            assertTrue(loop.startsWith("Failure EqualsByteString: expected bytestring"), loop);
        }
        for (var loop : List.of(twoAccumulators(null, "sig.equals(redeemer)", TWO_SIGNATORIES),
                twoAccumulatorsWithBreak(null, "sig.equals(redeemer)", TWO_SIGNATORIES))) {
            assertTrue(loop.startsWith("Failure UnBData: expected data"), loop);
        }
    }
}
