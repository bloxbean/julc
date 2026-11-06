package org.julclang.difftest;

import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-069: {@code instanceof} pattern variables beyond the plain {@code if}. Each program runs on the JVM and, at every
 * level, on the VM; the JVM is the oracle. {@code S} has variants of different layouts ({@code A(v)}, {@code B(v, w)}), a
 * nullary one ({@code D}), and one with A's layout ({@code C(v)}): a read under a failed test fails, differs, or, on a
 * {@code C}, reads a plausible value that only the comparison with the JVM catches.
 * <p>
 * M0 pins what {@code main} does before the change: the shapes the ADR adds, with the code each is rejected with today, and
 * the shapes that compile today, which must keep agreeing. The milestones turn the rejections into agreement.
 */
class Adr069PatternScopesHarnessTest {

    private static final DiffRunner RUNNER = new DiffRunner(DiffConfig.allLevels(), 10_000);

    private static String program(String members) {
        return """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcList;
                import org.julclang.stdlib.Builtins;

                public class Case {
                    static BigInteger said(String m, BigInteger v) { return Builtins.unIData(Builtins.trace(m, Builtins.iData(v))); }
                    static S echo(String m, S s) { Builtins.trace(m, Builtins.iData(BigInteger.ZERO)); return s; }
                    public sealed interface S {
                        record A(BigInteger v) implements S { }
                        record B(BigInteger v, BigInteger w) implements S { }
                        record D() implements S { }
                        record C(BigInteger v) implements S { }
                    }
                    static JulcList<S> xs() {
                        return JulcList.of(new S.A(BigInteger.TWO), new S.D(), new S.B(BigInteger.TEN, BigInteger.ONE), new S.C(BigInteger.valueOf(5)),
                                new S.A(BigInteger.valueOf(7)));
                    }
                    %s
                }
                """.formatted(members);
    }

    private static final TagTable TAGS = new TagTable(List.of(
            new TagTable.Entry("Case$S$A", "Case$S", 0), new TagTable.Entry("Case$S$B", "Case$S", 1), new TagTable.Entry("Case$S$D", "Case$S", 2),
            new TagTable.Entry("Case$S$C", "Case$S", 3)));

    private static PlutusData integer(long value) {
        return new PlutusData.IntData(BigInteger.valueOf(value));
    }

    private static PlutusData constr(int tag, PlutusData... fields) {
        return new PlutusData.ConstrData(tag, List.of(fields));
    }

    private static PlutusData a(long v) {
        return constr(0, integer(v));
    }

    private static PlutusData b(long v, long w) {
        return constr(1, integer(v), integer(w));
    }

    private static PlutusData d() {
        return constr(2);
    }

    private static PlutusData c(long v) {
        return constr(3, integer(v));
    }

    /** One of each variant, and a zero and a negative where the shapes compare. */
    private static List<PlutusData> everyS() {
        return List.of(a(7), a(0), a(-1), b(10, 4), d(), c(9));
    }

    private static DiffRunner.Report run(String members, PlutusData... args) {
        return RUNNER.run(new DiffCase("adr069", program(members), "Case", "m", List.of(args), TAGS, null));
    }

    private static void assertAgree(String members, PlutusData... args) {
        var report = run(members, args);
        assertEquals(4, report.results().size());
        assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
    }

    private static void assertRejected(String members, String code) {
        var report = run(members, a(1));
        report.results().forEach((config, result) -> {
            assertEquals(Verdict.Kind.REJECTED, result.verdict().kind(), report::describe);
            assertEquals(code, ((Observation.Rejected) result.uplc()).code(), report::describe);
        });
    }

    // --- M0: what compiles today keeps agreeing (I4) ---

    /** a01, a05, and a01 in a loop that updates nothing declared before it (bound by Ifs today). */
    @Test
    void theWholeConditionFormAgrees() {
        for (var s : everyS()) {
            assertAgree("public static long m(S s) { if (s instanceof S.A a) { return a.v().longValue(); } return 0; }", s);
            assertAgree("public static long m(S s) { if (s instanceof S.B b) { return b.w().longValue(); } "
                    + "else if (s instanceof S.A a) { return a.v().longValue() * 2; } return 0; }", s);
            assertAgree("public static long m(S s) { if (echo(\"e\", s) instanceof S.A a) { return said(\"v\", a.v()).longValue(); } return 0; }", s);
        }
        assertAgree("public static long m() { for (S x : xs()) { if (x instanceof S.A a) { said(\"v\", a.v()); } } return 1; }");
    }

    /** A variable that is declared and never used compiles today, in every position: its test is a bare tag test. */
    @Test
    void anUnusedVariableAgrees() {
        for (var s : everyS()) {
            for (long n : new long[]{0, 1}) {
                assertAgree("public static boolean m(S s, long n) { return !(s instanceof S.A a); }", s, integer(n));
                assertAgree("public static boolean m(S s, long n) { return s instanceof S.A a && n > 0; }", s, integer(n));
                assertAgree("public static long m(S s, long n) { if (s instanceof S.A a && n > 0) { return 1; } return 0; }", s, integer(n));
                assertAgree("public static long m(S s, long n) { if ((s instanceof S.A a)) { return 1; } return 0; }", s, integer(n));
                assertAgree("public static long m(S s, long n) { return s instanceof S.A a ? 1 : 0; }", s, integer(n));
                assertAgree("public static boolean m(S s, long n) { return xs().any(s instanceof S.A a ? x -> n > 0 : x -> false); }", s, integer(n));
                assertAgree("public static long m(S s, long n) { long i = 0; while (s instanceof S.A a && i < n + 2) { i = i + 1; } return i; }",
                        s, integer(n));
            }
        }
        assertAgree("public static long m() { long t = 0; for (S x : xs()) { if (x instanceof S.A a) { t = t + 1; } } return t; }");
        for (long n : new long[]{0, 1}) {
            assertAgree("public static long m(long n) { long t = 0; for (S x : xs()) { if (x instanceof S.A a && n > 0) { t = t + 1; } } return t; }",
                    integer(n));
        }
    }

    /** n08: a switch over the sealed interface, in a loop, the rewrite that works today. */
    @Test
    void aSwitchInALoopAgrees() {
        assertAgree("public static long m() { long t = 0; for (S x : xs()) { t = t + switch (x) { case S.A a -> a.v().longValue(); "
                + "case S.B b -> b.w().longValue(); case S.C c -> 100; case S.D d -> 1; }; } return t; }");
    }

    // --- M0: the shapes the ADR adds, as rejected today (each flips in M1-M3) ---

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiterString = " ~ ", textBlock = """
            a02 &&                  ~ JULC0011 ~ public static long m(S s) { if (s instanceof S.A a && a.v().signum() > 0) { return 1; } return 0; }
            a03 early return        ~ JULC0011 ~ public static long m(S s) { if (!(s instanceof S.A a)) { return 0; } return a.v().longValue(); }
            a04 negated or          ~ JULC0011 ~ public static long m(S s) { if (!(s instanceof S.A a) || a.v().signum() == 0) { return 0; } return a.v().longValue(); }
            a06 ternary value       ~ JULC0011 ~ public static long m(S s) { long v = s instanceof S.A a ? a.v().longValue() : 5; return v; }
            a07 ternary condition   ~ JULC0011 ~ public static long m(S s) { return s instanceof S.A a && a.v().signum() > 0 ? 1 : 0; }
            a07b boolean local      ~ JULC0011 ~ public static long m(S s) { boolean c = s instanceof S.A a && a.v().signum() > 0; return c ? 1 : 0; }
            a08 lambda              ~ JULC0011 ~ public static long m(S s) { return xs().any(x -> x instanceof S.A a && a.v().signum() > 5) ? 1 : 0; }
            a09 lambda block guard  ~ JULC0011 ~ public static long m(S s) { return xs().any(x -> { if (!(x instanceof S.A a)) { return false; } return a.v().signum() > 0; }) ? 1 : 0; }
            a10 use after if-else   ~ JULC0011 ~ public static long m(S s) { if (s instanceof S.A a) { } else { return 0; } return a.v().longValue(); }
            a11 while condition     ~ JULC0011 ~ public static long m(S s) { long t = 0; while (s instanceof S.A a && a.v().longValue() > t) { t = t + 1; } return t; }
            b01 for                 ~ JULC0011 ~ public static long m(S s) { long t = 0; for (S x : xs()) { if (x instanceof S.A a) { t = t + a.v().longValue(); } } return t; }
            b02 for &&              ~ JULC0011 ~ public static long m(S s) { long t = 0; for (S x : xs()) { if (x instanceof S.A a && a.v().signum() > 0) { t = t + a.v().longValue(); } } return t; }
            b03 for else-if         ~ JULC0011 ~ public static long m(S s) { long t = 0; for (S x : xs()) { if (x instanceof S.B b) { t = t + b.w().longValue(); } else if (x instanceof S.A a) { t = t + a.v().longValue(); } } return t; }
            b04 while               ~ JULC0011 ~ public static long m(S s) { long t = 0; long i = 0; while (i < 4) { S x = xs().get(i); if (x instanceof S.A a) { t = t + a.v().longValue(); } i = i + 1; } return t; }
            b05 nested for          ~ JULC0011 ~ public static long m(S s) { long t = 0; for (S y : xs()) { for (S x : xs()) { if (x instanceof S.A a) { t = t + a.v().longValue(); } } } return t; }
            b06 for break           ~ JULC0011 ~ public static long m(S s) { long t = 0; for (S x : xs()) { if (x instanceof S.B b) { t = t + b.w().longValue(); break; } t = t + 1; } return t; }
            b07 break guard         ~ JULC0011 ~ public static long m(S s) { long t = 0; for (S x : xs()) { if (!(x instanceof S.A a)) { break; } t = t + a.v().longValue(); } return t; }
            b08 for ternary         ~ JULC0011 ~ public static long m(S s) { long t = 0; for (S x : xs()) { t = t + (x instanceof S.A a ? a.v().longValue() : 1); } return t; }
            c01 after while         ~ JULC0011 ~ public static long m(S s) { S x = s; while (!(x instanceof S.A a)) { x = new S.A(BigInteger.ONE); } return a.v().longValue(); }
            n01 bare test in a loop ~ JULC0077 ~ public static long m(S s) { long t = 0; for (S x : xs()) { if (x instanceof S.A) { t = t + 1; } } return t; }
            n02 bare test           ~ JULC0077 ~ public static long m(S s) { if (s instanceof S.A) { return 1; } return 0; }
            n03 bare test negated   ~ JULC0077 ~ public static long m(S s) { return !(s instanceof S.A) ? 1 : 0; }
            n04 bare test value     ~ JULC0077 ~ public static long m(S s) { boolean c = s instanceof S.A; return c ? 1 : 0; }
            n05 bare else-if, loop  ~ JULC0077 ~ public static long m(S s) { long t = 0; for (S x : xs()) { if (x instanceof S.B) { t = t + 10; } else if (x instanceof S.A) { t = t + 1; } } return t; }
            n06 bare negated, loop  ~ JULC0077 ~ public static long m(S s) { long t = 0; for (S x : xs()) { if (!(x instanceof S.A)) { t = t + 1; } } return t; }
            n07 bare test, lambda   ~ JULC0077 ~ public static long m(S s) { return xs().any(x -> x instanceof S.C) ? 1 : 0; }
            """)
    void whatTheAdrAddsIsRejectedToday(String shape, String code, String members) {
        assertRejected(members, code);
    }

    /** Assignments to a pattern variable (D8). Each compiles or is rejected today as pinned here. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiterString = " ~ ", textBlock = """
            outside a loop          ~ JULC0061 ~ public static long m(S s) { if (s instanceof S.A a) { a = new S.A(BigInteger.ONE); return a.v().longValue(); } return 0; }
            in an accumulator loop  ~ JULC0116 ~ public static long m(S s) { long t = 0; for (S x : xs()) { if (x instanceof S.A a) { if (t > 0) { a = new S.A(BigInteger.ONE); } t = t + 1; } } return t; }
            under an if, accumulator loop ~ JULC0114 ~ public static long m(S s) { long r = 0; for (S y : xs()) { if (y instanceof S.A a) { if (r > 0) { for (S x : xs()) { a = new S.A(a.v().add(BigInteger.ONE)); } } r = r + a.v().longValue(); } } return r; }
            """)
    void anAssignmentToAPatternVariableToday(String shape, String code, String members) {
        assertRejected(members, code);
    }

    /** a01's form, whose then-branch loop updates the variable: it compiles today and keeps its result (D8 rule 2, Q5). */
    @Test
    void anA01VariableUpdatedByALoopAgrees() {
        for (var s : everyS()) {
            assertAgree("public static long m(S s) { if (s instanceof S.A a) { for (S x : xs()) { a = new S.A(a.v().add(BigInteger.ONE)); } "
                    + "return a.v().longValue(); } return 0; }", s);
            for (long n : new long[]{0, 1}) {
                // Review r3, F13: the outer loop updates nothing, so Ifs binds a, and ADR-050's join carries the inner loop's update.
                assertAgree("public static long m(S s, long n) { for (S y : xs()) { if (y instanceof S.A a) { if (n > 0) { "
                        + "for (S x : xs()) { a = new S.A(a.v().add(BigInteger.ONE)); } } said(\"r\", a.v()); } } return 1; }", s, integer(n));
            }
        }
    }

    // --- R1 (out of scope): an unchecked cast to a variant reads the other variant's fields ---

    /**
     * {@code (S.A) s} keeps the operand: on a {@code B(10, 4)} the JVM throws {@code ClassCastException} where the VM reads
     * B's first field as A's. Pinned as it is today; a fix of the cast turns this into agreement.
     */
    @Test
    void r1AnUncheckedCastReadsAnotherVariant() {
        var report = run("public static long m(S s) { S.A q = (S.A) s; return q.v().longValue(); }", b(10, 4));
        assertInstanceOf(Observation.Failure.class, report.jvm(), report::describe);
        assertTrue(((Observation.Failure) report.jvm()).detail().contains("ClassCastException"), report::describe);
        report.results().forEach((config, result) -> {
            assertEquals(Verdict.Kind.MISMATCH, result.verdict().kind(), report::describe);
            assertEquals(new PlutusData.IntData(BigInteger.TEN), ((Observation.Value) result.uplc()).data(), report::describe);
        });
    }
}
