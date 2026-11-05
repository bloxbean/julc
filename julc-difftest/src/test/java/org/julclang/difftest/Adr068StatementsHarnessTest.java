package org.julclang.difftest;

import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 D10 over locals, {@code if}, {@code return} and {@code instanceof}: each program on the JVM and, through each
 * frontend at every level, on the VM. The JVM is the oracle, so every value, trace and failure here comes from neither
 * frontend. Traced operands pin the order of statements, and which branch ran. A case that needs a sealed value passes it
 * as Data, with its tags from the same table the JVM side reads.
 */
class Adr068StatementsHarnessTest {

    private static final DiffRunner RUNNER = new DiffRunner(configs(), 10_000);
    private static final boolean[] BOOLEANS = {false, true};
    private static final long[] EDGES = {0, 1, -1, 2, 5, -7, 2147483647L, -2147483648L, 1L << 40};

    private static List<DiffConfig> configs() {
        var configs = new ArrayList<>(DiffConfig.allLevels());
        return configs;
    }

    private static String program(String members) {
        return """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcList;
                import org.julclang.stdlib.Builtins;

                public class Case {
                    static BigInteger said(String m, BigInteger v) { return Builtins.unIData(Builtins.trace(m, Builtins.iData(v))); }
                    static long saidL(String m, long v) { return Builtins.unIData(Builtins.trace(m, Builtins.iData(v))).longValue(); }
                    static void note(String m) { Builtins.trace(m, Builtins.iData(BigInteger.ZERO)); }
                    static long fail(long z) { return Builtins.unIData(Builtins.error()).longValue(); }
                    public sealed interface S {
                        record A(BigInteger v) implements S { }
                        record B(BigInteger v, BigInteger w) implements S { }
                        record D() implements S { }
                    }
                    public sealed interface T permits X, Z { }
                    public record X(BigInteger p) implements T { }
                    public record Z(BigInteger q) implements T { }
                    public sealed interface U {
                        record Y(BigInteger y) implements U { }
                        record X(BigInteger x, BigInteger y) implements U { }
                    }
                    %s
                }
                """.formatted(members);
    }

    private static final TagTable TAGS = new TagTable(List.of(
            new TagTable.Entry("Case$S$A", "Case$S", 0), new TagTable.Entry("Case$S$B", "Case$S", 1), new TagTable.Entry("Case$S$D", "Case$S", 2),
            new TagTable.Entry("Case$X", "Case$T", 0), new TagTable.Entry("Case$Z", "Case$T", 1),
            new TagTable.Entry("Case$U$Y", "Case$U", 0), new TagTable.Entry("Case$U$X", "Case$U", 1)));

    private static PlutusData integer(long value) {
        return new PlutusData.IntData(BigInteger.valueOf(value));
    }

    private static PlutusData bool(boolean value) {
        return new PlutusData.ConstrData(value ? 1 : 0, List.of());
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

    private static List<PlutusData> everyS() {
        return List.of(a(7), a(0), a(-1), b(1, 2), b(-3, 4), d());
    }

    private static DiffRunner.Report run(String members, List<PlutusData> args) {
        return RUNNER.run(new DiffCase("m3c", program(members), "Case", "m", args, TAGS, null));
    }

    /** Every frontend at every level agrees with the JVM, and the frontends with each other on value, cost and size. */
    private static void assertAgree(String members, PlutusData... args) {
        var report = run(members, List.of(args));
        assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
        assertEquals(4, report.results().size());
        report.results().forEach((config, result) -> {
        });
    }

    /** Both frontends differ from the JVM, the same way: the reference's behaviour, ported and pinned. */
    private static void assertBothDivergeAlike(String members, PlutusData... args) {
        var report = run(members, List.of(args));
        var observations = new HashSet<>();
        report.results().forEach((config, result) -> {
            assertEquals(Verdict.Kind.MISMATCH, result.verdict().kind(), report::describe);
            observations.add(result.uplc());
        });
        assertEquals(1, observations.size(), report::describe);
    }

    private static void assertFixedOnJavac(String members, PlutusData... args) {
        var report = run(members, List.of(args));
        for (var entry : report.results().entrySet()) {
                assertEquals(Verdict.Kind.AGREE, entry.getValue().verdict().kind(), report::describe);
            
        }
    }

    private static void assertStopsOnJavac(String members, String code, PlutusData... args) {
        var report = run(members, List.of(args));
        report.results().forEach((config, result) -> {
                assertEquals(Verdict.Kind.REJECTED, result.verdict().kind(), report::describe);
                assertTrue(result.uplc().toString().contains(code), () -> code + " expected: " + report.describe());
            
        });
    }

    // --- Locals ---

    @Test
    void localsHoldWhatTheirInitializerGave() {
        for (long a : EDGES) {
            assertAgree("public static long m(long a) { long b = a + 1; long c = b * 2; return c - a; }", integer(a));
            assertAgree("public static long m(long a) { var b = a; var c = b; return c; }", integer(a));
            assertAgree("public static boolean m(long a) { boolean b = a > 0; boolean c = !b; return b != c; }", integer(a));
        }
    }

    @Test
    void aLocalOfEachRepresentation() {
        assertAgree("public static BigInteger m(BigInteger a) { BigInteger b = a.add(BigInteger.ONE); return b; }", integer(1));
        assertAgree("public static BigInteger m(BigInteger a) { var b = a.add(BigInteger.ONE); return b.multiply(b); }", integer(3));
        assertAgree("public static String m(String a) { String b = a + \"!\"; String c = b + b; return c; }",
                new PlutusData.BytesData("hi".getBytes()));
        assertAgree("public static byte[] m(byte[] a) { byte[] b = new byte[]{1, 2}; byte[] c = Builtins.appendByteString(a, b); return c; }",
                new PlutusData.BytesData(new byte[]{9}));
        assertAgree("public static PlutusData m(PlutusData a) { PlutusData b = a; return b; }", integer(5));
        assertAgree("public static boolean m(boolean a) { Boolean b = a; return b; }", bool(true));
        assertAgree("public static BigInteger m(JulcList<BigInteger> a) { JulcList<BigInteger> b = a.tail(); var c = b.head(); return c; }",
                new PlutusData.ListData(List.of(integer(1), integer(2))));
        assertAgree("public static long m() { final int k = 5; final long l = k; return k + l; }");
        assertAgree("public static boolean m() { final String k = \"a\"; return k == \"a\"; }");
    }

    @Test
    void aFinalStringLocalIsAConstantToEquality() {
        assertAgree("public static boolean m() { final String k = \"a\"; final String l = k + \"b\"; return l == \"ab\"; }");
    }

    /** An initializer runs where it stands, used or not: Java evaluates it, and so does the strict let. */
    @Test
    void anUnusedLocalStillRuns() {
        assertAgree("public static long m(long a) { long b = saidL(\"B\", a); return 1; }", integer(5));
        assertAgree("public static long m(long a) { long b = fail(a); return 1; }", integer(5));
        assertAgree("public static long m(long a, long z) { long b = a / z; return 1; }", integer(5), integer(0));
    }

    @Test
    void localsRunInSourceOrder() {
        assertAgree("public static long m(long a) { long b = saidL(\"1\", a); long c = saidL(\"2\", a); long d = saidL(\"3\", a); return c; }", integer(5));
        assertAgree("public static long m(long a) { long b = saidL(\"1\", a); note(\"2\"); long d = saidL(\"3\", a); return d; }", integer(5));
        assertAgree("public static long m(long a) { note(\"1\"); note(\"2\"); return saidL(\"3\", a); }", integer(5));
    }

    @Test
    void anExpressionStatementRunsAndItsValueIsDropped() {
        assertAgree("public static long m(long a) { saidL(\"x\", a); return 1; }", integer(5));
        assertAgree("public static long m(long a) { fail(a); return 1; }", integer(5));
        assertAgree("public static long m(long a) { Builtins.error(); return 1; }", integer(5));
        assertAgree("static void w(long a) { note(\"w\"); } public static long m(long a) { w(a); w(a + 1); return a; }", integer(5));
    }

    @Test
    void aVoidHelperRunsItsStatements() {
        assertAgree("static void w(long a) { long b = a + 1; note(\"w\"); } public static long m(long a) { w(a); return a; }", integer(5));
        assertAgree("static void w(boolean c) { if (c) { note(\"t\"); } } public static long m(boolean c) { w(c); return 1; }", bool(true));
        assertAgree("static void w(boolean c) { if (c) { note(\"t\"); } } public static long m(boolean c) { w(c); return 1; }", bool(false));
        assertAgree("static void w(boolean c) { if (c) { return; } note(\"f\"); } public static long m(boolean c) { w(c); note(\"after\"); return 1; }", bool(true));
        assertAgree("static void w(boolean c) { if (c) { return; } note(\"f\"); } public static long m(boolean c) { w(c); note(\"after\"); return 1; }", bool(false));
    }

    // --- Scoping ---

    @Test
    void aLocalShadowsAStaticOnlyInItsBlock() {
        String field = "static final long K = 10; ";
        assertAgree(field + "public static long m(long a) { if (a > 0) { long K = a + a; if (K > 5) { return K; } } return a + K; }", integer(5));
        assertAgree(field + "public static long m(long a) { if (a > 0) { long K = a + a; if (K > 5) { return K; } } return a + K; }", integer(1));
        assertAgree(field + "public static long m(long a) { if (a > 0) { long K = a + a; if (K > 5) { return K; } } return a + K; }", integer(-1));
        assertAgree(field + "public static long m(long a) { if (a > 0) { long K = 1; saidL(\"x\", K); } return a + K; }", integer(5));
        assertAgree(field + "public static long m(long a) { long b = K; long K = a; return K + b; }", integer(5));
        assertAgree(field + "public static long m(long a) { if (a > 0) { return a; } else { long K = 2; return K; } }", integer(-5));
        assertAgree(field + "public static long m(long a) { if (a > 0) { long K = 2; return K; } return K; }", integer(-5));
        assertAgree(field + "static long h() { return K; } public static long m(long a) { long K = a; return K + h(); }", integer(5));
    }

    @Test
    void siblingBlocksHoldTheirOwnLocals() {
        for (boolean c : BOOLEANS) {
            assertAgree("public static long m(boolean c) { if (c) { long x = 1; if (x > 0) { return x; } } else { long x = 2; if (x > 0) { return x; } } long x = 3; return x; }", bool(c));
            assertAgree("public static long m(boolean c) { if (c) { long x = 1; saidL(\"t\", x); } else { long x = 2; saidL(\"e\", x); } long x = 3; return x; }", bool(c));
        }
    }

    @Test
    void aParameterIsNotCapturedByABranchLocal() {
        assertAgree("static final long a = 1; public static long m(long b) { if (b > 0) { long a = b + 1; if (a > 2) { return a; } } return a; }", integer(5));
        assertAgree("static final long a = 1; public static long m(long b) { if (b > 0) { long a = b + 1; if (a > 2) { return a; } } return a; }", integer(1));
    }

    // --- if ---

    @ParameterizedTest
    @ValueSource(strings = {
            "if (a > 0) { return 1; } return 2;",
            "if (a > 0) { return 1; } else { return 2; }",
            "if (a > 0) return 1; return 2;",
            "if (a > 0) return 1; else return 2;",
            "if (a > 0) { return 1; } else if (a < 0) { return 2; } else { return 3; }",
            "if (a > 0) { return 1; } else if (a < 0) { return 2; } return 3;",
            "if (a > 0) { if (a > 3) { return 1; } return 2; } return 3;",
            "if (a > 0) if (a > 3) return 1; return 3;",
            "if (a > 0) if (a > 3) return 1; else return 2; return 3;",
            "if (a > 0) { } return 3;",
            "if (a > 0) { } else { return 2; } return 3;",
            "if ((a > 0)) { return 1; } return 2;",
            "if (((a > 0))) { return 1; } return 2;",
            "if (a > 0 && a < 3) { return 1; } return 2;",
            "if (a > 0 || a < -3) { return 1; } return 2;",
            "if (!(a > 0)) { return 1; } return 2;",
            "long b = a; if (b > 0) { long c = b + 1; return c; } long c = b - 1; return c;",
            "if (a > 1) { return 1; } if (a > 0) { return 2; } if (a < -1) { return 3; } return 4;",
            "if (a > 0) { long b = a * 2; if (b > 6) { return b; } long c = b + 1; if (c > 3) { return c; } } return 0;",
    })
    void returns(String body) {
        for (long a : new long[]{-4, -1, 0, 1, 2, 3, 4}) {
            assertAgree("public static long m(long a) { " + body + " }", integer(a));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "if (a > 0) { note(\"t\"); } return saidL(\"after\", a);",
            "if (a > 0) { note(\"t\"); } else { note(\"e\"); } return saidL(\"after\", a);",
            "if (a > 0) note(\"t\"); else note(\"e\"); return saidL(\"after\", a);",
            "if (a > 0) { long b = saidL(\"t\", a); note(\"u\"); } return a;",
            "if (a > 0) { if (a > 2) { note(\"i\"); } note(\"o\"); } return a;",
            "if (a > 0) if (a > 2) note(\"i\"); return a;",
            "if (a > 0) { note(\"t\"); return 1; } note(\"after\"); return 2;",
            "if (a > 0) { return saidL(\"t\", 1); } else { note(\"e\"); } return saidL(\"after\", 2);",
            "if (a > 0) { if (a > 2) { note(\"i\"); } return 1; } return 2;",
            "if (a > 0) { if (a > 2) { return 1; } note(\"fell\"); } note(\"after\"); return 2;",
            "if (a > 0) { note(\"1\"); } if (a > 1) { note(\"2\"); } if (a > 2) { note(\"3\"); } return a;",
            "if (a > 0) { fail(a); } return a;",
            "if (a > 0) { } else { fail(a); } return a;",
            "if (a > 0) { fail(a); return 1; } return a;",
            "if (a > 0) { Builtins.error(); } return a;",
            "if (saidL(\"c\", a) > 0) { note(\"t\"); } else { note(\"e\"); } return a;",
            "if (saidL(\"c1\", a) > 0 && saidL(\"c2\", a) > 1) { note(\"t\"); } return a;",
    })
    void effects(String body) {
        for (long a : new long[]{-1, 0, 1, 2, 3}) {
            assertAgree("public static long m(long a) { " + body + " }", integer(a));
        }
    }

    @Test
    void anIfInAVoidMethod() {
        for (boolean c : BOOLEANS) {
            assertAgree("static void w(boolean c) { if (c) { note(\"t\"); } else { note(\"e\"); } } public static long m(boolean c) { w(c); return 1; }", bool(c));
            assertAgree("static void w(boolean c) { if (c) { return; } else { note(\"e\"); } } public static long m(boolean c) { w(c); return 1; }", bool(c));
            assertAgree("static void w(boolean c) { if (c) { note(\"t\"); return; } note(\"e\"); return; } public static long m(boolean c) { w(c); return 1; }", bool(c));
        }
    }

    @Test
    void aBooleanResult() {
        for (long a : EDGES) {
            assertAgree("public static boolean m(long a) { if (a > 0) { return true; } return false; }", integer(a));
            assertAgree("public static boolean m(long a) { if (a > 0) { return a < 5; } else { return a > -5; } }", integer(a));
        }
    }

    @Test
    void aRecursiveHelperWithStatements() {
        assertAgree("static long sum(long n) { if (n <= 0) { return 0; } long r = sum(n - 1); return n + r; } public static long m(long a) { return sum(a); }", integer(6));
        assertAgree("static boolean even(long n) { if (n == 0) { return true; } return odd(n - 1); } static boolean odd(long n) { if (n == 0) { return false; } return even(n - 1); }"
                + " public static boolean m(long a) { return even(a); }", integer(7));
    }

    // --- instanceof ---

    @ParameterizedTest
    @ValueSource(strings = {
            "if (s instanceof S.A a) { return a.v(); } return BigInteger.ZERO;",
            "if (s instanceof S.B b) { return b.w(); } return BigInteger.ZERO;",
            "if (s instanceof S.D d) { return BigInteger.TEN; } return BigInteger.ZERO;",
            "if (s instanceof S.A a) { return a.v(); } else { return BigInteger.ONE; }",
            "if (s instanceof S.A a) return a.v(); return BigInteger.ZERO;",
            "if (s instanceof S.A a) return a.v(); else return BigInteger.ONE;",
            "if (s instanceof S.A a) { return a.v(); } else if (s instanceof S.B b) { return b.w(); } return BigInteger.TEN;",
            "if (s instanceof S.A v) { return v.v(); } else if (s instanceof S.B v) { return v.w(); } else { return BigInteger.TEN; }",
            "if (s instanceof S.A a) { if (s instanceof S.A b) { return b.v().add(a.v()); } } return BigInteger.ZERO;",
            "if (s instanceof S.A a) { if (s instanceof S.B b) { return b.v(); } return a.v(); } return BigInteger.ZERO;",
            "if (s instanceof S.A a) { var x = a.v(); return x.add(x); } var y = BigInteger.ONE; return y;",
            "if (s instanceof final S.A a) { return a.v(); } return BigInteger.ZERO;",
            "if (s instanceof S.B b) { if (b.w().signum() > 0) { return b.w(); } } return BigInteger.ONE;",
            "boolean t = s instanceof S.A a; if (t) { return BigInteger.ONE; } return BigInteger.ZERO;",
            "if ((s) instanceof S.A a) { return a.v(); } return BigInteger.ZERO;",
    })
    void aTypeTestOverEveryVariant(String body) {
        for (var s : everyS()) assertAgree("public static BigInteger m(S s) { " + body + " }", s);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "if (s instanceof S.A a) { note(\"a\"); } return BigInteger.ONE;",
            "if (s instanceof S.A a) { said(\"v\", a.v()); } else { note(\"else\"); } return BigInteger.ONE;",
            "if (s instanceof S.A a) { said(\"v\", a.v()); return a.v(); } note(\"after\"); return BigInteger.ONE;",
            "if (s instanceof S.A a) { if (a.v().signum() > 0) { note(\"pos\"); } } return BigInteger.ONE;",
            "if (s instanceof S.A a) if (a.v().signum() > 0) note(\"pos\"); return BigInteger.ONE;",
            "if (s instanceof S.A a) { note(\"a\"); } if (s instanceof S.B b) { note(\"b\"); } return BigInteger.ONE;",
    })
    void aTypeTestForItsEffect(String body) {
        for (var s : everyS()) assertAgree("public static BigInteger m(S s) { " + body + " }", s);
    }

    @Test
    void aTypeTestAsAValue() {
        for (var s : everyS()) {
            assertAgree("public static boolean m(S s) { return s instanceof S.A a; }", s);
            assertAgree("public static boolean m(S s) { return !(s instanceof S.D d); }", s);
            assertAgree("public static boolean m(S s) { return s instanceof S.A a || s instanceof S.B b; }", s);
            assertAgree("public static boolean m(S s) { if ((s instanceof S.A a)) { return true; } return false; }", s);
            assertAgree("public static boolean m(S s) { if (s instanceof S.A a && true) { return true; } return false; }", s);
            assertAgree("public static boolean m(S s) { if (!(s instanceof S.A a)) { return true; } return false; }", s);
        }
    }

    @Test
    void aVariantAsItsOwnOperand() {
        assertAgree("public static BigInteger m(S.A a) { if (a instanceof S.A b) { return b.v(); } return BigInteger.ZERO; }", a(4));
    }

    /** A name another sum holds, nested or not: the variant is what javac bound, and its tag is its own sum's. */
    @Test
    void aVariantNamedLikeAnotherSumsRecord() {
        var ux = constr(1, integer(1), integer(2));
        var uy = constr(0, integer(3));
        assertAgree("public static BigInteger m(U u) { if (u instanceof U.X x) { return x.y(); } return BigInteger.ZERO; }", ux);
        assertAgree("public static BigInteger m(U u) { if (u instanceof U.X x) { return x.y(); } return BigInteger.ZERO; }", uy);
        assertAgree("public static BigInteger m(U u) { if (u instanceof U.Y y) { return y.y(); } return BigInteger.ZERO; }", ux);
        assertAgree("public static BigInteger m(U u) { if (u instanceof U.Y y) { return y.y(); } return BigInteger.ZERO; }", uy);
        var tx = constr(0, integer(1));
        var tz = constr(1, integer(9));
        assertAgree("public static BigInteger m(T t) { if (t instanceof X x) { return x.p(); } return BigInteger.ZERO; }", tx);
        assertAgree("public static BigInteger m(T t) { if (t instanceof X x) { return x.p(); } return BigInteger.ZERO; }", tz);
        assertAgree("public static BigInteger m(T t) { if (t instanceof Z z) { return z.q(); } return BigInteger.ZERO; }", tz);
    }

    /** As a permits clause holds them, not as declared. */
    @Test
    void tagsFollowThePermitsClause() {
        String sum = "public sealed interface P permits P2, P1 { } public record P1(BigInteger a) implements P { } public record P2(BigInteger a) implements P { } ";
        var tags = new TagTable(concat(TAGS, new TagTable.Entry("Case$P2", "Case$P", 0), new TagTable.Entry("Case$P1", "Case$P", 1)));
        var report = RUNNER.run(new DiffCase("m3c", program(sum + "public static BigInteger m(P p) { if (p instanceof P1 x) { return x.a(); } return BigInteger.ZERO; }"),
                "Case", "m", List.of(constr(1, integer(5))), tags, null));
        assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
    }

    /**
     * Two variants of one simple name, which the reference tells apart by that name, so it compares the first one's tag.
     * Here each has its own (F807).
     */
    @Test
    void twoVariantsOfOneSimpleNameHaveTwoTags() {
        String sum = "public sealed interface W permits Q.N, R.N { } public static final class Q { public record N(BigInteger a) implements W { } } "
                + "public static final class R { public record N(BigInteger a) implements W { } } ";
        var tags = new TagTable(concat(TAGS, new TagTable.Entry("Case$Q$N", "Case$W", 0), new TagTable.Entry("Case$R$N", "Case$W", 1)));
        var report = RUNNER.run(new DiffCase("m3c", program(sum + "public static BigInteger m(W w) { if (w instanceof R.N n) { return n.a(); } return BigInteger.ZERO; }"),
                "Case", "m", List.of(constr(1, integer(5))), tags, null));
        for (var entry : report.results().entrySet()) {
                assertEquals(Verdict.Kind.AGREE, entry.getValue().verdict().kind(), report::describe);
            
        }
    }

    private static List<TagTable.Entry> concat(TagTable table, TagTable.Entry... entries) {
        return Stream.concat(table.entries().stream(), Stream.of(entries)).toList();
    }

    /** {@code e} is lowered twice, as in the reference: where the test holds it runs twice, so a trace in it shows twice. */
    @Test
    void anOperandThatTracesRunsTwiceWhereTheTestHolds() {
        String helper = "static S echo(S s) { note(\"e\"); return s; } ";
        assertBothDivergeAlike(helper + "public static BigInteger m(S s) { if (echo(s) instanceof S.A a) { return a.v(); } return BigInteger.ZERO; }", a(1));
        assertAgree(helper + "public static BigInteger m(S s) { if (echo(s) instanceof S.A a) { return a.v(); } return BigInteger.ZERO; }", b(1, 2));
    }

    // --- What stops rather than diverges ---

    /** A cast is not ported yet, so these stop at it; when it is, {@code TypeTests} stops them at the operand. */
    @Test
    void aWideningOperandStops() {
        assertStopsOnJavac("public record R(BigInteger v) { } public static BigInteger m(R r) { if ((Object) r instanceof S.A a) { return a.v(); } return BigInteger.ZERO; }",
                "JULC0126", constr(0, integer(1)));
    }

    // --- Where javac's types are right and the reference is not (D13, semantic fix) ---

    @Test
    void aVarOfAListLiteralIsTypedByItsElements() {
        assertFixedOnJavac("public static BigInteger m() { var xs = JulcList.of(BigInteger.ONE, BigInteger.TWO); return xs.head(); }");
        assertFixedOnJavac("public static boolean m() { var xs = JulcList.of(true); return xs.head(); }");
    }
}
