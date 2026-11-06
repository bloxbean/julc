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
                    static JulcList<S> ys(S s) {
                        return JulcList.of(new S.C(BigInteger.valueOf(3)), s, new S.B(BigInteger.ONE, BigInteger.TWO), s, new S.A(BigInteger.valueOf(4)));
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

    // --- M1: a single pattern test, wherever its if stands, and a bare type test (D1, D2) ---

    /** Loop bodies with an accumulator (LoopBody: foldIf, breakingIf, joined), over a list that holds each variant. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiterString = " ~ ", textBlock = """
            b01 for                 ~ public static long m(S s) { long t = 0; for (S x : ys(s)) { if (x instanceof S.A a) { t = t + a.v().longValue(); } } return t; }
            b03 for else-if         ~ public static long m(S s) { long t = 0; for (S x : ys(s)) { if (x instanceof S.B b) { t = t + b.w().longValue(); } else if (x instanceof S.A a) { t = t + a.v().longValue(); } } return t; }
            b04 while               ~ public static long m(S s) { long t = 0; long i = 0; while (i < 5) { S x = ys(s).get(i); if (x instanceof S.A a) { t = t + a.v().longValue(); } i = i + 1; } return t; }
            b05 nested for          ~ public static long m(S s) { long t = 0; for (S y : ys(s)) { for (S x : ys(y)) { if (x instanceof S.A a) { t = t + a.v().longValue(); } } } return t; }
            b06 for break           ~ public static long m(S s) { long t = 0; for (S x : ys(s)) { if (x instanceof S.A a) { t = t + a.v().longValue(); break; } t = t + 1; } return t; }
            b06b break else         ~ public static long m(S s) { long t = 0; for (S x : ys(s)) { if (x instanceof S.A a) { t = t + a.v().longValue(); } else { break; } t = t + 1; } return t; }
            b06c fold in break loop ~ public static long m(S s) { long t = 0; for (S x : ys(s)) { if (x instanceof S.A a) { t = t + a.v().longValue(); } if (t > 6) { break; } } return t; }
            b09 two accumulators    ~ public static long m(S s) { long t = 0; long u = 0; for (S x : ys(s)) { if (x instanceof S.A a) { t = t + a.v().longValue(); u = u + 1; } } return t * 10 + u; }
            b10 two, with break     ~ public static long m(S s) { long t = 0; long u = 0; for (S x : ys(s)) { if (x instanceof S.C c) { t = t + c.v().longValue(); u = u + 1; } if (u > 1) { break; } } return t * 10 + u; }
            b11 traced operand      ~ public static long m(S s) { long t = 0; for (S x : ys(s)) { if (echo("e", x) instanceof S.A a) { t = t + said("v", a.v()).longValue(); } } return t; }
            b12 parenthesized       ~ public static long m(S s) { long t = 0; for (S x : ys(s)) { if ((x instanceof S.C c)) { t = t + c.v().longValue(); } } return t; }
            b13 outer in inner loop ~ public static long m(S s) { long t = 0; for (S y : ys(s)) { if (y instanceof S.A a) { for (S x : ys(s)) { t = t + a.v().longValue(); } } } return t; }
            n01 bare test in a loop ~ public static long m(S s) { long t = 0; for (S x : ys(s)) { if (x instanceof S.A) { t = t + 1; } } return t; }
            n05 bare else-if, loop  ~ public static long m(S s) { long t = 0; for (S x : ys(s)) { if (x instanceof S.B) { t = t + 10; } else if (x instanceof S.A) { t = t + 1; } } return t; }
            n06 bare negated, loop  ~ public static long m(S s) { long t = 0; for (S x : ys(s)) { if (!(x instanceof S.A)) { t = t + 1; } } return t; }
            """)
    void aPatternTestInALoopBodyAgrees(String shape, String members) {
        for (var s : everyS()) assertAgree(members, s);
    }

    /** Outside loop bodies: a second pair of parentheses, a conditional lambda, and bare tests in every position. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiterString = " ~ ", textBlock = """
            p01 parenthesized       ~ public static long m(S s) { if ((s instanceof S.A a)) { return a.v().longValue(); } return 0; }
            p02 traced, parens      ~ public static long m(S s) { if (((echo("e", s) instanceof S.C c))) { return said("v", c.v()).longValue(); } return 0; }
            p03 conditional lambda  ~ public static long m(S s) { return xs().any(s instanceof S.A a ? x -> a.v().signum() > 0 : x -> false) ? 1 : 0; }
            p04 lambda, own test    ~ public static long m(S s) { return ys(s).any(x -> { if ((x instanceof S.C c)) { return c.v().signum() > 3; } return false; }) ? 1 : 0; }
            n02 bare test           ~ public static long m(S s) { if (s instanceof S.A) { return 1; } return 0; }
            n03 bare test negated   ~ public static long m(S s) { return !(s instanceof S.A) ? 1 : 0; }
            n04 bare test value     ~ public static long m(S s) { boolean c = s instanceof S.A; return c ? 1 : 0; }
            n07 bare test, lambda   ~ public static long m(S s) { return ys(s).any(x -> x instanceof S.D) ? 1 : 0; }
            n09 bare test, traced   ~ public static long m(S s) { return echo("e", s) instanceof S.B ? 1 : 0; }
            """)
    void aPatternOrTypeTestAgrees(String shape, String members) {
        for (var s : everyS()) assertAgree(members, s);
    }

    // --- The shapes a later milestone adds: each is JULC0139 until then ---

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiterString = " ~ ", textBlock = """
            a02 &&                  ~ JULC0139 ~ public static long m(S s) { if (s instanceof S.A a && a.v().signum() > 0) { return 1; } return 0; }
            a03 early return        ~ JULC0139 ~ public static long m(S s) { if (!(s instanceof S.A a)) { return 0; } return a.v().longValue(); }
            a04 negated or          ~ JULC0139 ~ public static long m(S s) { if (!(s instanceof S.A a) || a.v().signum() == 0) { return 0; } return a.v().longValue(); }
            a06 ternary value       ~ JULC0139 ~ public static long m(S s) { long v = s instanceof S.A a ? a.v().longValue() : 5; return v; }
            a07 ternary condition   ~ JULC0139 ~ public static long m(S s) { return s instanceof S.A a && a.v().signum() > 0 ? 1 : 0; }
            a07b boolean local      ~ JULC0139 ~ public static long m(S s) { boolean c = s instanceof S.A a && a.v().signum() > 0; return c ? 1 : 0; }
            a08 lambda              ~ JULC0139 ~ public static long m(S s) { return xs().any(x -> x instanceof S.A a && a.v().signum() > 5) ? 1 : 0; }
            a09 lambda block guard  ~ JULC0139 ~ public static long m(S s) { return xs().any(x -> { if (!(x instanceof S.A a)) { return false; } return a.v().signum() > 0; }) ? 1 : 0; }
            a10 use after if-else   ~ JULC0139 ~ public static long m(S s) { if (s instanceof S.A a) { } else { return 0; } return a.v().longValue(); }
            a11 while condition     ~ JULC0139 ~ public static long m(S s) { long t = 0; while (s instanceof S.A a && a.v().longValue() > t) { t = t + 1; } return t; }
            b02 for &&              ~ JULC0139 ~ public static long m(S s) { long t = 0; for (S x : xs()) { if (x instanceof S.A a && a.v().signum() > 0) { t = t + a.v().longValue(); } } return t; }
            b07 break guard         ~ JULC0139 ~ public static long m(S s) { long t = 0; for (S x : xs()) { if (!(x instanceof S.A a)) { break; } t = t + a.v().longValue(); } return t; }
            b08 for ternary         ~ JULC0139 ~ public static long m(S s) { long t = 0; for (S x : xs()) { t = t + (x instanceof S.A a ? a.v().longValue() : 1); } return t; }
            c01 after while         ~ JULC0139 ~ public static long m(S s) { S x = s; while (!(x instanceof S.A a)) { x = new S.A(BigInteger.ONE); } return a.v().longValue(); }
            """)
    void whatALaterMilestoneAddsIsRejected(String shape, String code, String members) {
        assertRejected(members, code);
    }

    /** Assignments to a pattern variable (D8): each is JULC0140, whichever rule meets it first. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiterString = " ~ ", textBlock = """
            outside a loop          ~ JULC0140 ~ public static long m(S s) { if (s instanceof S.A a) { a = new S.A(BigInteger.ONE); return a.v().longValue(); } return 0; }
            in an accumulator loop  ~ JULC0140 ~ public static long m(S s) { long t = 0; for (S x : xs()) { if (x instanceof S.A a) { if (t > 0) { a = new S.A(BigInteger.ONE); } t = t + 1; } } return t; }
            under an if, accumulator loop ~ JULC0140 ~ public static long m(S s) { long r = 0; for (S y : xs()) { if (y instanceof S.A a) { if (r > 0) { for (S x : xs()) { a = new S.A(a.v().add(BigInteger.ONE)); } } r = r + a.v().longValue(); } } return r; }
            in a switch arm         ~ JULC0140 ~ public static long m(S s) { return switch (s) { case S.B b -> { if (s instanceof S.B q && b.w().signum() > 0) { q = new S.B(BigInteger.ONE, BigInteger.ONE); } yield 1L; } default -> 0L; }; }
            nested-loop accumulator ~ JULC0140 ~ public static long m(S s) { long r = 0; for (S y : xs()) { if (y instanceof S.A a) { for (S x : xs()) { a = new S.A(a.v().add(BigInteger.ONE)); } r = r + a.v().longValue(); } } return r; }
            """)
    void anAssignmentToAPatternVariable(String shape, String code, String members) {
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
