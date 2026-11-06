package org.julclang.difftest;

import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigInteger;
import java.util.Arrays;
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
                    static S make(BigInteger v) { return v.signum() > 0 ? new S.C(v) : new S.D(); }
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

    // --- M2: &&, ||, !, parentheses and ?:, in conditions and values (D3) ---

    /** Each over every variant, and both values of {@code n} where it appears. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiterString = " ~ ", textBlock = """
            a02 &&                  ~ public static long m(S s, long n) { if (s instanceof S.A a && a.v().signum() > 0) { return 1; } return 0; }
            a06 ternary value       ~ public static long m(S s, long n) { long v = s instanceof S.A a ? a.v().longValue() : 5; return v; }
            a07 ternary condition   ~ public static long m(S s, long n) { return s instanceof S.A a && a.v().signum() > 0 ? 1 : 0; }
            a07b boolean local      ~ public static long m(S s, long n) { boolean c = s instanceof S.A a && a.v().signum() > 0; return c ? 1 : 0; }
            a08 lambda              ~ public static long m(S s, long n) { return ys(s).any(x -> x instanceof S.A a && a.v().signum() > 5) ? 1 : 0; }
            a11 while condition     ~ public static long m(S s, long n) { long t = 0; while (s instanceof S.A a && a.v().longValue() > t) { t = t + 1; } return t; }
            a12 while, two accs     ~ public static long m(S s, long n) { long t = 0; long u = 0; while (s instanceof S.A a && a.v().longValue() > t) { t = t + 1; u = u + 2; } return t * 10 + u; }
            b02 for &&              ~ public static long m(S s, long n) { long t = 0; for (S x : ys(s)) { if (x instanceof S.A a && a.v().signum() > 0) { t = t + a.v().longValue(); } } return t; }
            b08 for ternary         ~ public static long m(S s, long n) { long t = 0; for (S x : ys(s)) { t = t + (x instanceof S.A a ? a.v().longValue() : 1); } return t; }
            c02 negated or, else    ~ public static long m(S s, long n) { if (!(s instanceof S.A a) || a.v().signum() == 0) { return 0; } else { return a.v().longValue(); } }
            c03 join under binder   ~ public static long m(S s, long n) { if (s instanceof S.A a && (a.v().signum() > 0 || a.v().signum() < 0)) { return a.v().longValue(); } return 7; }
            c04 double negation     ~ public static long m(S s, long n) { return !(!(s instanceof S.A a) || a.v().signum() == 0) && a.v().signum() > 0 ? 1 : 0; }
            c05 boolean ?: as test  ~ public static long m(S s, long n) { if (n > 0 ? s instanceof S.A a && a.v().signum() > 0 : s instanceof S.B b && b.w().signum() > 0) { return 1; } return 2; }
            c06 test of a variable  ~ public static long m(S s, long n) { if (s instanceof S.A a && make(a.v()) instanceof S.C c && c.v().compareTo(a.v()) == 0) { return c.v().longValue(); } return 0; }
            c07 traced shortcircuit ~ public static long m(S s, long n) { return echo("l", s) instanceof S.A a && said("r", a.v()).signum() > 0 || echo("o", s) instanceof S.B b && said("w", b.w()).signum() > 0 ? 1 : 0; }
            c08 lambda captures     ~ public static long m(S s, long n) { return ys(s).any(x -> s instanceof S.A a && x instanceof S.A b && b.v().compareTo(a.v()) < 0) ? 1 : 0; }
            c09 shared value join   ~ public static long m(S s, long n) { long v = (s instanceof S.A a && a.v().signum() > 0) ? a.v().longValue() * 3 : said("e", BigInteger.TEN).longValue() + n; return v; }
            c10 record ?: value     ~ public static long m(S s, long n) { S r = s instanceof S.A a && a.v().signum() > 0 ? new S.C(a.v()) : s; return r instanceof S.C c ? c.v().longValue() : -1; }
            c11 || in a loop        ~ public static long m(S s, long n) { long t = 0; for (S x : ys(s)) { if (!(x instanceof S.C c) || c.v().signum() > n) { t = t + 1; } else { t = t + c.v().longValue(); } } return t; }
            c12 ! in a break loop   ~ public static long m(S s, long n) { long t = 0; for (S x : ys(s)) { if (!(x instanceof S.A a)) { t = t + 1; } else { t = t + a.v().longValue(); break; } } return t; }
            c13 nested ternaries    ~ public static long m(S s, long n) { return s instanceof S.A a ? (a.v().signum() > 0 ? a.v().longValue() : -a.v().longValue()) : s instanceof S.B b ? b.w().longValue() : 9; }
            c14 parenthesized &&    ~ public static long m(S s, long n) { return ((s instanceof S.A a) && (a.v().signum() >= n)) ? 1 : 0; }
            c15 two call operands   ~ public static long m(S s, long n) { return echo("l", s) instanceof S.A a && echo("r", make(a.v().add(BigInteger.TEN))) instanceof S.C c && c.v().subtract(a.v()).longValue() == 10 ? 1 : 0; }
            c16 the same, as an if  ~ public static long m(S s, long n) { if (echo("l", s) instanceof S.A a && echo("r", make(a.v().add(BigInteger.TEN))) instanceof S.C c && c.v().subtract(a.v()).longValue() == 10) { return c.v().longValue(); } return 0; }
            c17 || then a loop      ~ public static long m(S s, long n) { if (!(s instanceof S.A a) || a.v().signum() > n) { long q = 0; for (S y : ys(s)) { q = q + 1; } return q; } return 5; }
            c18 two accs, && and || ~ public static long m(S s, long n) { long t = 0; long u = 0; for (S x : ys(s)) { if (x instanceof S.A a && a.v().signum() > 0 || x instanceof S.C c && c.v().signum() > n) { t = t + 1; } else { u = u + 1; } } return t * 10 + u; }
            c19 joined, &&          ~ public static long m(S s, long n) { long t = 0; for (S x : ys(s)) { if (x instanceof S.A a && a.v().signum() > n) { t = t + a.v().longValue(); } else { break; } t = t + 1; } return t; }
            c20 joined, ||          ~ public static long m(S s, long n) { long t = 0; for (S x : ys(s)) { if (!(x instanceof S.C c) || c.v().signum() > n) { t = t + 1; } else { t = t + c.v().longValue(); break; } t = t + 2; } return t; }
            c21 lambda choice, &&   ~ public static long m(S s, long n) { return xs().any(s instanceof S.A a && a.v().signum() > n ? x -> a.v().signum() > 0 : x -> false) ? 1 : 0; }
            c22 boolean ?: value    ~ public static long m(S s, long n) { boolean c = n > 0 ? s instanceof S.A a && a.v().signum() > 0 : s instanceof S.C d && d.v().signum() > 0; return c ? 1 : 0; }
            c23 join in a lambda    ~ public static long m(S s, long n) { return ys(s).any(x -> (s instanceof S.A a && x instanceof S.C c && c.v().compareTo(a.v()) < 0) ? c.v().add(BigInteger.ONE).signum() > 0 : BigInteger.valueOf(n).add(BigInteger.TEN).signum() > 10) ? 1 : 0; }
            """)
    void aConditionAgrees(String shape, String members) {
        for (var s : everyS()) {
            for (long n : new long[]{0, 1}) assertAgree(members, s, integer(n));
        }
    }

    /** A value of another representation under a binding condition: native bytes, and Data views behind a negation. */
    @Test
    void aConditionalOfOtherRepresentationsAgrees() {
        for (var s : everyS()) {
            assertAgree("public static byte[] m(S s) { return !(s instanceof S.A a) || a.v().signum() == 0 ? new byte[]{1} : new byte[]{2, 3}; }", s);
            for (long n : new long[]{0, 7}) {
                assertAgree("public static PlutusData m(S s, PlutusData d) { return !(s instanceof S.A a) ? d : (PlutusData) Builtins.iData(a.v()); }", s, integer(n));
            }
        }
    }

    /**
     * I6: a condition that nests &&, || and ! over pattern tests, with continuations that are no constant, grows linearly in
     * its depth: each level adds the same few bytes, and every program agrees with the JVM.
     */
    @Test
    void aNestOfConditionsIsLinear() {
        var sizes = new int[3];
        int[] depths = {6, 12, 24};
        for (int k = 0; k < depths.length; k++) {
            var condition = "s instanceof S.A a0 && a0.v().signum() >= 0";
            for (int i = 1; i <= depths[k]; i++) {
                condition = switch (i % 3) {
                    case 1 -> "(!(s instanceof S.B b" + i + ") || b" + i + ".w().signum() > " + i + ") && (" + condition + ")";
                    case 2 -> "s instanceof S.A a" + i + " && (a" + i + ".v().signum() > " + i + " || " + condition + ")";
                    default -> "(n > " + i + " ? s instanceof S.C c" + i + " && c" + i + ".v().signum() > 0 : (" + condition + "))";
                };
            }
            var members = "public static long m(S s, long n) { if (" + condition + ") { return said(\"t\", BigInteger.ONE).longValue(); } "
                    + "return said(\"f\", BigInteger.TWO).longValue(); }";
            for (var s : everyS()) {
                for (long n : new long[]{0, 30}) assertAgree(members, s, integer(n));
            }
            sizes[k] = run(members, a(1), integer(0)).results().values().iterator().next().measurement().scriptBytes();
        }
        int first = sizes[1] - sizes[0];
        int second = sizes[2] - sizes[1];
        assertTrue(second <= 2 * first + 16, () -> "sizes " + Arrays.toString(sizes));
    }

    // --- M3: an if that introduces its variable into the rest of the block (D4) ---

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiterString = " ~ ", textBlock = """
            a03 early return        ~ public static long m(S s, long n) { if (!(s instanceof S.A a)) { return 0; } return a.v().longValue(); }
            a04 negated or          ~ public static long m(S s, long n) { if (!(s instanceof S.A a) || a.v().signum() == 0) { return 0; } return a.v().longValue(); }
            a09 lambda block guard  ~ public static long m(S s, long n) { return ys(s).any(x -> { if (!(x instanceof S.A a)) { return false; } return a.v().signum() > 0; }) ? 1 : 0; }
            a10 use after if-else   ~ public static long m(S s, long n) { if (s instanceof S.A a) { } else { return 0; } return a.v().longValue(); }
            d01 then falls, else exits ~ public static long m(S s, long n) { if (s instanceof S.C c) { said("c", c.v()); } else { return -1; } return n + c.v().longValue(); }
            d02 two guards in a row ~ public static long m(S s, long n) { if (!(s instanceof S.A a)) { return 0; } if (!(make(a.v()) instanceof S.C c)) { return 1; } return c.v().longValue() + a.v().longValue(); }
            d03 guard with &&       ~ public static long m(S s, long n) { if (!(s instanceof S.A a && a.v().signum() > n)) { return -2; } return a.v().longValue(); }
            d04 traced guard        ~ public static long m(S s, long n) { if (!(echo("g", s) instanceof S.A a)) { return said("x", BigInteger.ZERO).longValue(); } return said("a", a.v()).longValue(); }
            d05 exit on both inner  ~ public static long m(S s, long n) { if (!(s instanceof S.A a)) { if (n > 0) { return 1; } else { return 2; } } return a.v().longValue(); }
            d06 several endings     ~ public static long m(S s, long n) { if (s instanceof S.A a) { if (n > 0) { said("n", a.v()); } else { if (a.v().signum() > 0) { return 9; } } } else { return -1; } return n + a.v().longValue(); }
            d07 loop in falling     ~ public static long m(S s, long n) { long t = 0; if (s instanceof S.A a) { for (S y : ys(s)) { t = t + 1; } } else { return -1; } return t * 100 + a.v().longValue(); }
            d08 loop in exiting     ~ public static long m(S s, long n) { if (!(s instanceof S.A a)) { long q = 0; for (S y : ys(s)) { q = q + 1; } return q; } return a.v().longValue(); }
            d09 guard in an else    ~ public static long m(S s, long n) { if (s instanceof S.B b) { return b.w().longValue(); } else { if (!(s instanceof S.A a)) { return 3; } return a.v().longValue(); } }
            d10 nested if blocks    ~ public static long m(S s, long n) { if (n > 0) { if (!(s instanceof S.A a)) { return 4; } return a.v().longValue() * 2; } return 6; }
            d11 rest local name     ~ public static long m(S s, long n) { if (!(s instanceof S.A a)) { return 0; } else { long z = 1; } long z = a.v().longValue(); return z; }
            d12 switch arm yield    ~ public static long m(S s, long n) { return switch (s) { case S.B b -> { if (!(make(b.w()) instanceof S.C c)) { yield 0L; } yield c.v().longValue(); } default -> 7L; }; }
            d13 break guard         ~ public static long m(S s, long n) { long t = 0; for (S x : ys(s)) { if (!(x instanceof S.A a)) { break; } t = t + a.v().longValue(); } return t; }
            d14 break in else       ~ public static long m(S s, long n) { long t = 0; for (S x : ys(s)) { if (x instanceof S.C c) { t = t + 1; } else { break; } t = t + c.v().longValue(); } return t; }
            d15 break, else falls   ~ public static long m(S s, long n) { long t = 0; for (S x : ys(s)) { if (!(x instanceof S.A a)) { break; } else { t = t + n; } t = t + a.v().longValue(); } return t; }
            d16 break, two accs     ~ public static long m(S s, long n) { long t = 0; long u = 0; for (S x : ys(s)) { if (!(x instanceof S.A a)) { break; } t = t + a.v().longValue(); u = u + 1; } return t * 10 + u; }
            d18 fold before break   ~ public static long m(S s, long n) { long t = 0; for (S x : ys(s)) { if (!(x instanceof S.A a)) { if (n > 0) { t = t + 100; } break; } t = t + a.v().longValue(); } return t; }
            d19 fold, two accs      ~ public static long m(S s, long n) { long t = 0; long u = 0; for (S x : ys(s)) { if (!(x instanceof S.A a)) { if (n > 0) { t = t + 100; u = u + 1; } break; } t = t + a.v().longValue(); } return t * 10 + u; }
            d20 fold if-else, break ~ public static long m(S s, long n) { long t = 0; for (S x : ys(s)) { if (!(x instanceof S.A a)) { if (n > 0) { t = t + 100; } else { t = t + 1; } break; } t = t + a.v().longValue(); } return t; }
            d21 dead updates, break ~ public static long m(S s, long n) { long t = 0; for (S x : ys(s)) { if (!(x instanceof S.A a)) { t = t + 5; break; } t = t + a.v().longValue(); } return t; }
            d22 || dead then-side   ~ public static long m(S s, long n) { if (n > 5 || !(s instanceof S.A a)) { return said("d", BigInteger.ONE).longValue() + n; } return a.v().longValue(); }
            d17 break, nested if    ~ public static long m(S s, long n) { long t = 0; for (S x : ys(s)) { if (!(x instanceof S.A a)) { if (t > n) { break; } else { break; } } t = t + a.v().longValue(); } return t; }
            """)
    void anIfThatIntroducesItsVariableAgrees(String shape, String members) {
        for (var s : everyS()) {
            for (long n : new long[]{0, 1}) assertAgree(members, s, integer(n));
        }
    }

    /**
     * A branch that ends in a loop that never ends cannot complete normally, so it introduces the variable too. Only inputs
     * that take the other branch run: the JVM would not stop on the rest. The branch that loops ends in Error, never reached.
     */
    @Test
    void aBranchThatNeverEndsIntroducesTheVariable() {
        String members = "public static long m(S s) { if (!(s instanceof S.A a)) { long i = 0; while (true) { i = i + 1; } } return a.v().longValue(); }";
        for (var s : List.of(a(7), a(0), a(-1))) assertAgree(members, s);
    }

    /** Review M3-S4: a lambda block whose unreachable branch is the if's then-position, typed by the branch that falls through. */
    @Test
    void aLambdaBlockGuardThenPositionAgrees() {
        String members = "public static long m(S s, long n) { return ys(s).map(x -> { if (n > 5 || !(x instanceof S.A a)) { return x; } "
                + "return (S) new S.C(a.v()); }).size(); }";
        for (var s : everyS()) {
            for (long n : new long[]{0, 1}) assertAgree(members, s, integer(n));
        }
        // The unreachable end itself, after a loop that never ends, in a Data-returning lambda: every element takes the
        // branch that falls through, so the loop never runs.
        String looping = "public static long m(S s, long n) { return JulcList.of(new S.A(BigInteger.ONE), new S.A(BigInteger.TWO)).map(x -> { "
                + "if (n > 5 || !(x instanceof S.A a)) { while (true) { } } return Builtins.iData(a.v()); }).size(); }";
        for (long n : new long[]{0, 1}) assertAgree(looping, a(1), integer(n));
        // The same in a switch arm's block, and in a fold loop whose falling side holds a nested loop updating the sum.
        String arm = "public static long m(S s, long n) { return switch (s) { case S.A a -> { if (n > 5 || !(make(a.v()) instanceof S.C c)) { "
                + "while (true) { } } yield c.v().longValue(); } default -> 3L; }; }";
        for (long n : new long[]{0, 1}) assertAgree(arm, a(4), integer(n));
        String fold = "public static long m(S s, long n) { long t = 0; for (S x : JulcList.of(s, s)) { if (!(x instanceof S.A a)) { while (true) { } } "
                + "for (S y : ys(s)) { t = t + a.v().longValue(); } } return t; }";
        for (long n : new long[]{0, 1}) assertAgree(fold, a(3), integer(n));
    }

    /**
     * Review F16: in a loop with no break, a branch that cannot complete (it ends in a loop that never ends) holds an if
     * followed by more statements. That if's value is mid-body, so the trace after it runs and then error() fails, as on
     * the JVM. Inputs of every variant: the non-A ones fail before reaching the loop.
     */
    @Test
    void aFoldBeforeAnUnreachableEndRunsWhatFollowsIt() {
        String one = "public static long m(S s, long n) { long t = 0; for (S x : JulcList.of(s)) { if (!(x instanceof S.A a)) { "
                + "if (n > 0) { t = t + 1; } said(\"after\", BigInteger.valueOf(t)); Builtins.error(); while (true) { } } t = t + a.v().longValue(); } return t; }";
        String two = "public static long m(S s, long n) { long t = 0; long u = 0; for (S x : JulcList.of(s)) { if (!(x instanceof S.A a)) { "
                + "if (n > 0) { t = t + 1; u = u + 2; } said(\"after\", BigInteger.valueOf(t + u)); Builtins.error(); while (true) { } } "
                + "t = t + a.v().longValue(); u = u + 1; } return t * 10 + u; }";
        for (var s : everyS()) {
            for (long n : new long[]{0, 1}) {
                assertAgree(one, s, integer(n));
                assertAgree(two, s, integer(n));
            }
        }
    }

    /**
     * Review F19: in a loop with a break, an unreachable branch ends in an if with no break and nothing after it; both of its
     * branches trace, fail and never end. The if still runs, so its trace comes before the failure, as on the JVM.
     */
    @Test
    void aFoldAtAnUnreachableEndStillRuns() {
        String members = "public static long m(S s, long n) { long t = 0; for (S x : JulcList.of(s)) { if (!(x instanceof S.A a)) { "
                + "if (n > 0) { said(\"then\", BigInteger.ZERO); Builtins.error(); while (true) { } } "
                + "else { said(\"else\", BigInteger.ZERO); Builtins.error(); while (true) { } } } "
                + "t = t + a.v().longValue(); break; } return t; }";
        for (var s : everyS()) {
            for (long n : new long[]{0, 1}) assertAgree(members, s, integer(n));
        }
    }

    /** D8 on a variable D4 introduced: an update under a nested if in a break loop would be lost where the if rejoins. */
    @Test
    void anUpdateOfAnIntroducedVariableIsRejected() {
        assertRejected("public static long m(S s) { long t = 0; for (S x : ys(s)) { if (!(x instanceof S.A a)) { break; } "
                + "if (t > 0) { a = new S.A(BigInteger.ONE); } t = t + a.v().longValue(); } return t; }", "JULC0140");
    }

    // --- What stays rejected: JULC0139 ---

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiterString = " ~ ", textBlock = """
            c01 after while         ~ JULC0139 ~ public static long m(S s) { S x = s; while (!(x instanceof S.A a)) { x = new S.A(BigInteger.ONE); } return a.v().longValue(); }
            c02 while body          ~ JULC0139 ~ public static long m(S s) { long t = 0; S x = s; while (x instanceof S.A a) { t = t + a.v().longValue(); x = new S.D(); } return t; }
            g01 guard variable      ~ JULC0074 ~ public static long m(S s) { return switch (s) { case S.A a when s instanceof S.A b && b.v().signum() > 0 -> 1L; default -> 0L; }; }
            """)
    void whatStaysRejected(String shape, String code, String members) {
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
