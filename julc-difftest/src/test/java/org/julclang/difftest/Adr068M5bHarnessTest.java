package org.julclang.difftest;

import org.julclang.core.PlutusData;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * ADR-068 M5, part 2 ({@code adr/evidence/068-m5a.md}): the shapes that stopped at {@code JULC0084} and now lower on
 * {@code frontend=JAVAC} give the JVM's value at NONE, BASELINE, PV11_SAFE and PV11_COSTED, for every input below. The reference
 * is not asserted: where it misreads the shape (a hash read as Data, a negated minimum value, a lambda's parameter as Data) it is
 * on the follow-ups' "Closed by M5".
 */
class Adr068M5bHarnessTest {

    private static final DiffRunner JAVAC = new DiffRunner(DiffConfig.allLevels(), 20_000);

    private static final String HEADER = """
            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcList;
            import org.julclang.ledger.PubKeyHash;
            import org.julclang.ledger.TokenName;
            import org.julclang.stdlib.annotation.NewType;

            public class Case {
            """;

    private static PlutusData integer(long v) {
        return new PlutusData.IntData(BigInteger.valueOf(v));
    }

    private static PlutusData bool(boolean b) {
        return new PlutusData.ConstrData(b ? 1 : 0, List.of());
    }

    private static PlutusData bytes(int... values) {
        var array = new byte[values.length];
        for (int i = 0; i < values.length; i++) array[i] = (byte) values[i];
        return new PlutusData.BytesData(array);
    }

    private static PlutusData list(long... values) {
        var items = new java.util.ArrayList<PlutusData>();
        for (long v : values) items.add(integer(v));
        return new PlutusData.ListData(items);
    }

    private static void javacIsTheJvm(String members, List<List<PlutusData>> inputs) {
        for (var args : inputs) {
            var report = JAVAC.run(new DiffCase("m5b", HEADER + members + "\n}\n", "Case", "m", args));
            assertInstanceOf(Observation.Value.class, report.jvm(), report::describe);
            report.results().values().forEach(result -> assertEquals(Verdict.Kind.AGREE, result.verdict().kind(), report::describe));
        }
    }

    /** A wrapper read from a variable, a field or a call is its bytes: the reference applies UnBData to them and fails. */
    @ParameterizedTest
    @ValueSource(strings = {
            "public static byte[] m(PubKeyHash p) { return p.hash(); }",
            "public record R(PubKeyHash p) { } public static byte[] m(R r) { return (r.p()).hash(); }",
            "static PubKeyHash h(PubKeyHash p) { return p; } public static byte[] m(PubKeyHash p) { return h(p).hash(); }",
            "public static byte[] m(byte[] a) { return new PubKeyHash(a).hash(); }"})
    void aWrapperReadIsItsBytes(String members) {
        var argument = members.contains("record R(") ? new PlutusData.ConstrData(0, List.of(bytes(1, 2, 3))) : bytes(1, 2, 3);
        javacIsTheJvm(members, List.of(List.of(argument)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0x80000000", "-(-2147483648)", "- -2147483648", "-0x8000000000000000L"})
    void theNegationOfAMinimumValueWraps(String body) {
        String type = body.endsWith("L") ? "long" : "int";
        javacIsTheJvm("public static " + type + " m(BigInteger a) { return " + body + "; }", List.of(List.of(integer(0))));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "public static boolean m(JulcList<BigInteger> xs, boolean c) { return xs.any((x -> x.signum() > 0)); }",
            "public static boolean m(JulcList<BigInteger> xs, boolean c) { return xs.any(c ? x -> x.signum() > 0 : x -> x.signum() < 0); }",
            "public static boolean m(JulcList<BigInteger> xs, boolean c) { return xs.any(c ? (x -> true) : (x -> x.equals(BigInteger.TWO))); }",
            "public static long m(JulcList<BigInteger> xs, boolean c) { return xs.filter((var x) -> x.signum() > 0).size(); }",
            "public static JulcList<BigInteger> m(JulcList<BigInteger> xs, boolean c) { return xs.map(c ? x -> x.add(BigInteger.ONE) : x -> x); }",
            "public static long m(JulcList xs, boolean c) { return xs.filter(x -> c).size(); }"})
    void aFunctionInParenthesesOrAConditionalOrWrittenVar(String members) {
        javacIsTheJvm(members, List.of(List.of(list(-1, 2, 3), bool(true)), List.of(list(-1, 2, 3), bool(false)), List.of(list(), bool(true))));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "public static long m(JulcList<BigInteger> xs) { long s = 0; for (Object o : xs) { s = s + 1; } return s; }",
            "public static long m(JulcList xs) { long s = 0; for (Object o : xs) { s = s + 1; } return s; }",
            "public static long m(JulcList<BigInteger> xs) { long s = 0; for (Long x : JulcList.of(5L, 6L)) { s = s + x; } return s; }"})
    void aWiderForEachVariableHoldsTheElement(String members) {
        javacIsTheJvm(members, List.of(List.of(list(1, 2, 3)), List.of(list())));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static final boolean J = false; public static boolean m(BigInteger a) { boolean J = (true || J); return J; }"})
    void aLocalReadInItsOwnInitializerNeverRuns(String members) {
        javacIsTheJvm(members, List.of(List.of(integer(1)), List.of(integer(-1))));
    }
}
