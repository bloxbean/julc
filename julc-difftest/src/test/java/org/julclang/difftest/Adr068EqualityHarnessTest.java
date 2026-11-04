package org.julclang.difftest;

import org.julclang.compiler.CompilerFrontend;
import org.julclang.core.PlutusData;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comparisons between values Java keeps distinct, on {@code frontend=JAVAC} at every level, with the JVM as the oracle. Each
 * rejected row states the JVM's answer, which the run confirms, so a row is a comparison a script must not answer. Each kept
 * row must truly evaluate and agree: a rejection is no pass there. The JavaParser frontend stays as frozen with the gate off
 * (D4), so these are the required tests of {@code 068-followups.md}'s "Closed by M5" list.
 */
class Adr068EqualityHarnessTest {

    private static final DiffRunner RUNNER = new DiffRunner(DiffConfig.allLevels(), 10_000);

    private static final TagTable TAGS = new TagTable(List.of(new TagTable.Entry("Case$S$A", "Case$S", 0), new TagTable.Entry("Case$S$B", "Case$S", 1)));

    private static final PlutusData TRUE = new PlutusData.ConstrData(1, List.of());
    private static final PlutusData FALSE = new PlutusData.ConstrData(0, List.of());

    private static String program(String members) {
        return """
                import java.math.BigInteger;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcList;
                import org.julclang.core.types.JulcMap;
                import org.julclang.core.types.Tuple2;
                import org.julclang.core.types.Tuple3;
                import org.julclang.ledger.PubKeyHash;
                import org.julclang.ledger.TokenName;
                import org.julclang.stdlib.Builtins;

                public class Case {
                    public record R(BigInteger x) { }
                    public record Q(BigInteger x) { }
                    public sealed interface S { record A(BigInteger x) implements S { } record B(BigInteger x) implements S { } }
                    %s
                }
                """.formatted(members);
    }

    private static DiffRunner.Report run(String members, List<PlutusData> args) {
        return RUNNER.run(new DiffCase("equality", program(members), "Case", "m", args, TAGS, null));
    }

    private static PlutusData i(long value) {
        return new PlutusData.IntData(BigInteger.valueOf(value));
    }

    private static PlutusData s(String value) {
        return new PlutusData.BytesData(value.getBytes(StandardCharsets.UTF_8));
    }

    private static PlutusData l(PlutusData... items) {
        return new PlutusData.ListData(List.of(items));
    }

    private static PlutusData m(PlutusData key, PlutusData value) {
        return new PlutusData.MapData(List.of(new PlutusData.Pair(key, value)));
    }

    private static PlutusData c(int tag, PlutusData... fields) {
        return new PlutusData.ConstrData(tag, List.of(fields));
    }

    /** A comparison the script must not answer: {@code code} on JAVAC, and {@code jvm} on the JVM. */
    record Refused(String id, String code, String members, PlutusData jvm, List<PlutusData> args) {
        @Override
        public String toString() {
            return id;
        }
    }

    private static Refused refused(String id, String code, String members, PlutusData jvm, PlutusData... args) {
        return new Refused(id, code, members, jvm, List.of(args));
    }

    private static final String L_INT = "public static boolean m(List<BigInteger> a";
    private static final String M_INT = "public static boolean m(Map<BigInteger, BigInteger> a";

    static Stream<Refused> refusedRows() {
        var rows = new ArrayList<Refused>();
        // A raw collection has no element or key type to compare with.
        String raw = "public static boolean m(JulcList<BigInteger> a) { JulcList raw = a; return ";
        String rawMap = "public static boolean m(JulcMap<BigInteger, BigInteger> a) { JulcMap raw = a; return ";
        rows.add(refused("R1", "JULC0087", raw + "raw.contains(5); }", FALSE, l(i(5))));
        rows.add(refused("B1", "JULC0087", "public static boolean m(JulcList a) { return a.contains(5); }", FALSE, l(i(5))));
        rows.add(refused("B2", "JULC0087", "public static boolean m(JulcList a) { return a.contains(5L); }", FALSE, l(i(5))));
        rows.add(refused("Y5", "JULC0087", raw + "raw.prepend(7).contains(BigInteger.valueOf(7)); }", FALSE, l()));
        rows.add(refused("R3", "JULC0087", rawMap + "raw.containsKey(5); }", FALSE, m(i(5), i(1))));
        rows.add(refused("R4", "JULC0087", rawMap + "raw.lookup(5L).isPresent(); }", FALSE, m(i(5), i(1))));
        rows.add(refused("R5", "JULC0087", "public static long m(JulcMap<BigInteger, BigInteger> a) { JulcMap raw = a; return raw.delete(5).size(); }", i(1), m(i(5), i(1))));
        rows.add(refused("R13", "JULC0087", "public static boolean m(JulcMap<String, BigInteger> a, byte[] k) { JulcMap raw = a; return raw.containsKey(k); }", FALSE, m(s("x"), i(1)), s("x")));
        rows.add(refused("R14", "JULC0087", "public static boolean m(JulcList<String> a, byte[] k) { JulcList raw = a; return raw.contains(k); }", FALSE, l(s("x")), s("x")));
        rows.add(refused("Y6", "JULC0087", "public static boolean m(JulcList<R> a, Q q) { JulcList raw = a; return raw.contains(q); }", FALSE, l(c(0, i(1))), c(0, i(1))));
        // java.util takes Object, so javac has no element type to hold an argument to.
        rows.add(refused("B5", "JULC0078", L_INT + ") { return a.contains(5L); }", FALSE, l(i(5))));
        rows.add(refused("B6", "JULC0078", L_INT + ") { return a.contains(5); }", FALSE, l(i(5))));
        rows.add(refused("B7", "JULC0078", L_INT + ") { return a.contains(\"x\"); }", FALSE, l(i(5))));
        rows.add(refused("B8", "JULC0078", M_INT + ") { return a.containsKey(5L); }", FALSE, m(i(5), i(1))));
        rows.add(refused("Q1", "JULC0078", "public static boolean m(List<PubKeyHash> a, byte[] b) { return a.contains(b); }", FALSE, l(s("x")), s("x")));
        rows.add(refused("Q2", "JULC0078", "public static boolean m(List<String> a, byte[] b) { return a.contains(b); }", FALSE, l(s("x")), s("x")));
        rows.add(refused("Q3", "JULC0078", "public static boolean m(Map<PubKeyHash, BigInteger> a, byte[] b) { return a.containsKey(b); }", FALSE, m(s("x"), i(1)), s("x")));
        rows.add(refused("Q5", "JULC0078", L_INT + ", PlutusData b) { return a.contains(b); }", FALSE, l(i(1)), i(1)));
        rows.add(refused("Q6", "JULC0078", "public static boolean m(List<PlutusData> a, BigInteger b) { return a.contains(b); }", FALSE, l(i(1)), i(1)));
        rows.add(refused("Y7", "JULC0078", "public static boolean m(List<R> a, Q q) { return a.contains(q); }", FALSE, l(c(0, i(1))), c(0, i(1))));
        rows.add(refused("Y8", "JULC0078", "public static boolean m(Map<R, BigInteger> a, Q q) { return a.containsKey(q); }", FALSE, m(c(0, i(1)), i(1)), c(0, i(1))));
        rows.add(refused("Y14", "JULC0078", "public static boolean m(List<String> a, PubKeyHash q) { return a.contains(q); }", FALSE, l(s("x")), s("x")));
        rows.add(refused("get", "JULC0078", M_INT + ") { return a.get(5L) == null; }", TRUE, m(i(5), i(1))));
        rows.add(refused("value", "JULC0078", M_INT + ") { return a.containsValue(1L); }", FALSE, m(i(5), i(1))));
        rows.add(refused("default", "JULC0078", M_INT + ") { return a.getOrDefault(5L, BigInteger.ONE).signum() > 0; }", TRUE, m(i(5), i(1))));
        rows.add(refused("cond", "JULC0012", L_INT + ", boolean c, BigInteger b) { return a.contains(c ? b : \"x\"); }", FALSE, l(i(5)), FALSE, i(5)));
        // Containers compare by their type arguments.
        // A container of unrelated arguments: two empty ones are equal in Java, so not JULC0078's constant false (M5a review 2, F3).
        rows.add(refused("B12", "JULC0087", "public static boolean m(BigInteger a) { return Optional.of(a).equals(Optional.of(1)); }", FALSE, i(1)));
        rows.add(refused("B13", "JULC0087", "public static boolean m() { return Optional.of(1).equals(Optional.of(1L)); }", FALSE));
        rows.add(refused("T44", "JULC0087", "public static boolean m(R a, Q b) { return Optional.of(a).equals(Optional.of(b)); }", FALSE, c(0, i(1)), c(0, i(1))));
        rows.add(refused("T45", "JULC0087", "public static boolean m(String a, byte[] b) { return Optional.of(a).equals(Optional.of(b)); }", FALSE, s("x"), s("x")));
        rows.add(refused("T46", "JULC0087", "public static boolean m(PubKeyHash a, TokenName b) { return Optional.of(a).equals(Optional.of(b)); }", FALSE, s("x"), s("x")));
        rows.add(refused("E12", "JULC0078", "public static boolean m(Tuple2<BigInteger, BigInteger> a, Tuple2<PlutusData, PlutusData> b) { return a.equals(b); }", FALSE, c(0, i(1), i(2)), c(0, i(1), i(2))));
        rows.add(refused("T43", "JULC0078", "public static boolean m(Tuple2<R, R> a, Tuple2<PlutusData, PlutusData> b) { return a.equals(b); }", FALSE, c(0, c(0, i(1)), c(0, i(1))), c(0, c(0, i(1)), c(0, i(1)))));
        rows.add(refused("T47", "JULC0078", "public static boolean m(Tuple2<PubKeyHash, BigInteger> a, Tuple2<TokenName, BigInteger> b) { return a.equals(b); }", FALSE, c(0, s("x"), i(1)), c(0, s("x"), i(1))));
        rows.add(refused("T48", "JULC0078", "public static boolean m(Tuple2<String, BigInteger> a, Tuple2<PubKeyHash, BigInteger> b) { return a.equals(b); }", FALSE, c(0, s("x"), i(1)), c(0, s("x"), i(1))));
        rows.add(refused("T3", "JULC0078", "public static boolean m(Tuple3<BigInteger, BigInteger, String> a, Tuple3<BigInteger, BigInteger, byte[]> b) { return a.equals(b); }", FALSE, c(0, i(1), i(2), s("x")), c(0, i(1), i(2), s("x"))));
        rows.add(refused("intersection", "JULC0087", "public static boolean m(boolean c, R r, S.A a, S.B b) { return Optional.of(r).equals(Optional.of(c ? a : b)); }", FALSE, TRUE, c(0, i(1)), c(0, i(1)), c(1, i(1))));
        rows.add(refused("nested", "JULC0087", "public static boolean m(Optional<JulcList<BigInteger>> a, Optional<JulcList<String>> b) { return a.equals(b); }", FALSE, c(0, l(i(1))), c(0, l(s("x")))));
        rows.add(refused("lists", "JULC0087", "public static boolean m(List<JulcList<String>> a, JulcList<byte[]> b) { return a.contains(b); }", FALSE, l(l(s("x"))), l(s("x"))));
        return rows.stream();
    }

    @ParameterizedTest
    @MethodSource("refusedRows")
    void aComparisonJavaKeepsDistinctIsRefused(Refused row) {
        var report = run(row.members(), row.args());
        assertEquals(row.jvm(), assertInstanceOf(Observation.Value.class, report.jvm(), report::describe).data(), report::describe);
        assertEquals(4, report.results().size());
        report.results().forEach((config, result) -> {
            assertEquals(Verdict.Kind.REJECTED, result.verdict().kind(), report::describe);
            assertTrue(result.uplc().toString().contains(row.code()), () -> row.code() + " expected: " + report.describe());
        });
    }

    /** A comparison that stays: it evaluates at every level and gives the JVM's answer. */
    record Kept(String id, String members, PlutusData jvm, List<PlutusData> args) {
        @Override
        public String toString() {
            return id;
        }
    }

    private static Kept kept(String id, String members, PlutusData jvm, PlutusData... args) {
        return new Kept(id, members, jvm, List.of(args));
    }

    static Stream<Kept> keptRows() {
        var rows = new ArrayList<Kept>();
        rows.add(kept("Y9", "public static boolean m(List<S> a, S.A q) { return a.contains(q); }", TRUE, l(c(0, i(1))), c(0, i(1))));
        rows.add(kept("Y9b", "public static boolean m(List<S> a, S.A q) { return a.contains(q); }", FALSE, l(c(1, i(1))), c(0, i(1))));
        rows.add(kept("Y9c", "public static boolean m(JulcList<S> a, S.A q) { return a.contains(q); }", TRUE, l(c(0, i(1))), c(0, i(1))));
        String optional = "public static boolean m(BigInteger a, BigInteger b) { return Optional.of(a).equals(Optional.of(b)); }";
        rows.add(kept("one type, equal", optional, TRUE, i(1), i(1)));
        rows.add(kept("one type, different", optional, FALSE, i(1), i(2)));
        rows.add(kept("sum and variant", "public static boolean m(S a, BigInteger b) { return Optional.of(a).equals(Optional.of(new S.A(b))); }", TRUE, c(0, i(1)), i(1)));
        rows.add(kept("empty", "public static boolean m(BigInteger a) { return Optional.of(a).equals(Optional.empty()); }", FALSE, i(1)));
        rows.add(kept("tuple", "public static boolean m(Tuple2<BigInteger, String> a, Tuple2<BigInteger, String> b) { return a.equals(b); }", TRUE, c(0, i(1), s("x")), c(0, i(1), s("x"))));
        rows.add(kept("tuple3", "public static boolean m(Tuple3<BigInteger, String, Boolean> a, Tuple3<BigInteger, String, Boolean> b) { return a.equals(b); }", FALSE, c(0, i(1), s("x"), TRUE), c(0, i(1), s("x"), FALSE)));
        rows.add(kept("tuple of a sum", "public static boolean m(Tuple2<S, S> a, Tuple2<S.A, S.B> b) { return a.equals(b); }", TRUE, c(0, c(0, i(1)), c(1, i(2))), c(0, c(0, i(1)), c(1, i(2)))));
        rows.add(kept("list", L_INT + ", BigInteger b) { return a.contains(b); }", TRUE, l(i(5)), i(5)));
        rows.add(kept("list, missing", L_INT + ", BigInteger b) { return a.contains(b); }", FALSE, l(i(5)), i(6)));
        rows.add(kept("map", M_INT + ", BigInteger b) { return a.containsKey(b); }", TRUE, m(i(5), i(1)), i(5)));
        rows.add(kept("signatories", "public static boolean m(JulcList<PubKeyHash> a, PubKeyHash b) { return a.contains(b); }", TRUE, l(s("x")), s("x")));
        rows.add(kept("signatories in java.util", "public static boolean m(List<PubKeyHash> a, PubKeyHash b) { return a.contains(b); }", TRUE, l(s("x")), s("x")));
        rows.add(kept("a hash from its bytes", "public static boolean m(List<PubKeyHash> a, byte[] b) { return a.contains(new PubKeyHash(b)); }", TRUE, l(s("x")), s("x")));
        rows.add(kept("Data", "public static boolean m(List<PlutusData> a, PlutusData b) { return a.contains(b); }", TRUE, l(i(1)), i(1)));
        rows.add(kept("Data subtype", "public static boolean m(JulcList<PlutusData> a, BigInteger b) { return a.contains(Builtins.iData(b)); }", TRUE, l(i(1)), i(1)));
        // A read through a list literal is seen as Data, and holds what javac's element type decoded it to.
        rows.add(kept("a decoded read", "public static boolean m(BigInteger a) { return JulcList.of(a).head().equals(a); }", TRUE, i(1)));
        rows.add(kept("a var of a decoded read", "public static boolean m(BigInteger a) { var h = JulcList.of(a).head(); return h.equals(a); }", TRUE, i(1)));
        rows.add(kept("a decoded text", "public static boolean m(String a) { var h = JulcList.of(a).head(); return h.equals(a); }", TRUE, s("x")));
        // A conditional is typed by its operands, whatever its target is.
        String conditional = "public static boolean m(boolean c, BigInteger a, BigInteger b) { return a.equals(c ? b : a); }";
        rows.add(kept("Z2", conditional, TRUE, TRUE, i(1), i(1)));
        rows.add(kept("Z2, the other branch", conditional, FALSE, TRUE, i(1), i(2)));
        rows.add(kept("Z2, parenthesized", "public static boolean m(boolean c, BigInteger a, BigInteger b) { return a.equals((c ? b : (a))); }", TRUE, FALSE, i(1), i(2)));
        rows.add(kept("Z3", "public static boolean m(boolean c, String a, String b) { return a.equals(c ? b : a); }", TRUE, TRUE, s("x"), s("x")));
        rows.add(kept("in a list", L_INT + ", boolean c, BigInteger b) { return a.contains(c ? b : BigInteger.ONE); }", TRUE, l(i(1)), FALSE, i(9)));
        return rows.stream();
    }

    @ParameterizedTest
    @MethodSource("keptRows")
    void aComparisonOfOneTypeEvaluates(Kept row) {
        var report = run(row.members(), row.args());
        assertEquals(row.jvm(), assertInstanceOf(Observation.Value.class, report.jvm(), report::describe).data(), report::describe);
        assertEquals(4, report.results().size());
        report.results().forEach((config, result) -> {
            assertEquals(Verdict.Kind.AGREE, result.verdict().kind(), report::describe);
            assertInstanceOf(Observation.Value.class, result.uplc(), report::describe);
        });
    }
}
