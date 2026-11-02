package org.julclang.difftest;

import org.julclang.compiler.CompilerFrontend;
import org.julclang.core.PlutusData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 D10 over loops: each program on the JVM and, through each frontend at every level, on the VM, read back through
 * Data. The JVM is the oracle, so every value, trace and failure here comes from neither frontend. A traced operand pins what
 * ran and in which order, and a program that fails pins what ran before it did. Every assertion needs {@code AGREE}, which a
 * rejection is not, except where a test says it observes one.
 */
class Adr068LoopsHarnessTest {

    private static final DiffRunner RUNNER = new DiffRunner(configs(), 10_000);
    /** Programs run, each at eight configurations, whose every verdict was {@code AGREE}. */
    private static final AtomicInteger AGREED = new AtomicInteger();

    @AfterAll
    static void whatRan() {
        System.out.println("loops harness: " + AGREED + " runs agreed");
        assertTrue(AGREED.get() >= 200, "too few runs agreed: " + AGREED);
    }

    private static List<DiffConfig> configs() {
        var configs = new ArrayList<>(DiffConfig.allLevels());
        configs.addAll(DiffConfig.allLevels(CompilerFrontend.JAVAC));
        return configs;
    }

    private static String program(String members) {
        return """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcList;
                import org.julclang.core.types.Tuple2;
                import org.julclang.ledger.PubKeyHash;
                import org.julclang.stdlib.Builtins;

                public class Case {
                    static long saidL(String m, long v) { return Builtins.unIData(Builtins.trace(m, Builtins.iData(v))).longValue(); }
                    static boolean saidB(String m, boolean v) { saidL(m, 0); return v; }
                    static long fail(long z) { return Builtins.unIData(Builtins.error()).longValue(); }
                    static long plus(long x, long y) { return x + y; }
                    public sealed interface S {
                        record A(BigInteger v) implements S { }
                        record B(BigInteger v, BigInteger w) implements S { }
                        record D() implements S { }
                    }
                    %s
                }
                """.formatted(members);
    }

    private static final TagTable TAGS = new TagTable(List.of(new TagTable.Entry("Case$S$A", "Case$S", 0),
            new TagTable.Entry("Case$S$B", "Case$S", 1), new TagTable.Entry("Case$S$D", "Case$S", 2)));

    private static PlutusData integer(long value) {
        return new PlutusData.IntData(BigInteger.valueOf(value));
    }

    private static PlutusData bool(boolean value) {
        return new PlutusData.ConstrData(value ? 1 : 0, List.of());
    }

    private static PlutusData list(long... values) {
        return new PlutusData.ListData(Arrays.stream(values).mapToObj(Adr068LoopsHarnessTest::integer).toList());
    }

    private static PlutusData bytes(int... values) {
        var items = new ArrayList<PlutusData>();
        for (int value : values) items.add(new PlutusData.BytesData(new byte[]{(byte) value}));
        return new PlutusData.ListData(items);
    }

    private static final List<PlutusData> LISTS = List.of(list(), list(1), list(1, 2), list(3, 1, 2), list(-1, 0, 1, 2), list(5, 5, 5, 5, 5));

    private static DiffRunner.Report run(String members, List<PlutusData> args) {
        return RUNNER.run(new DiffCase("loops", program(members), "Case", "m", args, TAGS, null));
    }

    /** Every frontend at every level agrees with the JVM, and the frontends with each other on value, cost and size. */
    private static void assertAgree(String members, PlutusData... args) {
        var report = run(members, List.of(args));
        assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
        assertEquals(8, report.results().size());
        report.results().forEach((config, result) -> {
            if (config.frontend() == CompilerFrontend.JAVAPARSER) {
                var javac = report.results().get(new DiffConfig(config.level(), "Java", CompilerFrontend.JAVAC));
                assertEquals(result.uplc(), javac.uplc(), "the frontends disagree at " + config);
                assertEquals(result.measurement(), javac.measurement(), "cost at " + config);
            }
        });
        AGREED.incrementAndGet();
    }

    /** {@code JAVAC} agrees with the JVM where the reference does not agree. */
    private static void assertFixedOnJavac(String members, PlutusData... args) {
        var report = run(members, List.of(args));
        boolean referenceDiverges = false;
        for (var entry : report.results().entrySet()) {
            if (entry.getKey().frontend() == CompilerFrontend.JAVAC) {
                assertEquals(Verdict.Kind.AGREE, entry.getValue().verdict().kind(), report::describe);
            } else {
                assertTrue(entry.getValue().verdict().kind() != Verdict.Kind.REJECTED, () -> "a rejection is no fix, it is a decision: " + report.describe());
                referenceDiverges |= entry.getValue().verdict().kind() != Verdict.Kind.AGREE;
            }
        }
        assertTrue(referenceDiverges, () -> "the reference no longer diverges, so this is no fix: " + report.describe());
    }

    private static void assertAgreeOnEveryList(String members) {
        for (var xs : LISTS) assertAgree(members, xs);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "public static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { s = s + x.longValue(); } return s; }",
            "public static BigInteger m(JulcList<BigInteger> xs) { BigInteger s = BigInteger.ZERO; for (BigInteger x : xs) { s = s.add(x); } return s; }",
            "public static long m(JulcList<BigInteger> xs) { long s = 0; long t = 1; for (var x : xs) { s = s + x.longValue(); t = t + s; } return s - t; }",
            "public static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { long d = x.longValue() + 1; s = s + d * d; } return s; }",
            "public static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { if (x.signum() > 0) { s = s + 1; } else { s = s - 1; } s = s * 2; } return s; }",
            "public static long m(JulcList<BigInteger> xs) { long s = 0; long t = 0; for (var x : xs) { if (x.signum() > 0) { s = s + 1; } else { t = t - 1; } s = s * 2; } return s + t; }",
            "public static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { { long d = x.longValue(); s = s + d; } { long d = 2; s = s * d; } } return s; }",
            "public static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { saidL(\"item\", x.longValue()); s = saidL(\"sum\", s + 1); } return saidL(\"end\", s); }",
            "public static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { for (var y : xs) { s = s + x.longValue() * y.longValue(); } s = s + 1; } return s; }",
            "public static long m(JulcList<BigInteger> xs) { long s = 0; long t = 0; for (var x : xs) { long i = 0; while (i < 2) { i = i + 1; s = s + i; t = t + x.longValue(); } } return s * t; }",
            // Break
            "public static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { if (x.longValue() == 2) { break; } s = s + x.longValue(); } return s; }",
            "public static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { s = s + 1; if (x.longValue() == 2) { s = s * 10; break; } } return s; }",
            "public static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { if (x.longValue() == 2) { s = 100; break; } else { s = s + 1; } s = s + 2; } return s; }",
            "public static long m(JulcList<BigInteger> xs) { long s = 0; long t = 0; for (var x : xs) { if (x.longValue() == 2) { s = 100; break; } else { t = t + 1; } t = t + 2; } return s + t; }",
            "public static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { if (x.longValue() == 1) { s = s + 1; } else if (x.longValue() == 2) { break; } else { s = s + 3; } s = s * 2; } return s; }",
            "public static long m(JulcList<BigInteger> xs) { long s = 0; long t = 0; for (var x : xs) { if (x.longValue() == 1) { s = s + 1; } else if (x.longValue() == 2) { t = 7; break; } else { s = s + 3; } s = s * 2; } return s + t; }",
            "public static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { if (x.longValue() == 2) { saidL(\"broke\", 1); break; } saidL(\"went on\", s); s = s + 1; } saidL(\"after\", s); return s; }",
            "public static long m(JulcList<BigInteger> xs) { long s = 0; long t = 0; for (var x : xs) { s = s + 1; for (var y : xs) { t = t + 1; if (y.longValue() == 1) { break; } } if (t > 3) { break; } } return s * 100 + t; }",
            // After a loop
            "public static long m(JulcList<BigInteger> xs) { long k = 7; long s = 0; for (var x : xs) { s = s + k; } long t = s + k; return t; }",
            "public static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { s = s + 1; } long t = 0; for (var x : xs) { t = t + s; } return t; }",
            "public static long m(JulcList<BigInteger> xs) { for (var x : xs) { saidL(\"item\", x.longValue()); } return 1; }",
            "public static long m(JulcList<BigInteger> xs) { for (var x : xs) { if (x.longValue() == 2) { saidL(\"two\", 2); } } return 1; }",
    })
    void overLists(String members) {
        assertAgreeOnEveryList(members);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "public static long m(long n) { long i = 0; while (i < n) { i = i + 1; } return i; }",
            "public static long m(long n) { long i = 0; long s = 0; while (i < n) { i = i + 1; s = s + i; } return s; }",
            "public static long m(long n) { long i = 0; while (saidL(\"test\", i) < n) { i = saidL(\"body\", i + 1); } return i; }",
            "public static long m(long n) { long i = 0; long s = 0; while (saidL(\"test\", i) < n) { s = s + 1; i = saidL(\"body\", i + 1); } return s; }",
            "public static long m(long n) { long i = 0; while (saidL(\"test\", i) < n) { i = i + 1; if (i == 2) { break; } } return i; }",
            "public static long m(long n) { long i = 0; long s = 0; while (saidL(\"test\", i) < n) { i = i + 1; if (i == 2) { s = 9; break; } } return i + s; }",
            "public static long m(long n) { long i = 0; while (true) { i = i + 1; if (i > n) { break; } } return i; }",
            "public static long m(long n) { long i = 0; while (i < n) { i += 1; } return i; }",
            "public static long m(long n) { long i = n; while (i > 1) { i /= 2; } return i; }",
            "public static long m(long n) { long i = n; while (i > 1) { i -= 2; } return i; }",
            "public static long m(long n) { long i = n; long s = 1; while (i > 0) { s *= 2; i -= 1; } return s; }",
            "public static long m(long n) { long i = n; while (i > 3) { i %= 3; } return i; }",
            "public static long m(long n) { long i = 0; while (i < n) { i = i + 1; if (i == 3) { i = 10; } else { i = i + 1; } } return i; }",
            "public static long m(long n) { long i = 0; while (i < n) { if (i == 3) { i = i + 3; } else { if (i == 2) { break; } i = i + 1; } i = i + 1; } return i; }",
            "public static long m(long n) { long i = 0; long s = 0; while (i < n) { long j = 0; while (j < i) { j = j + 1; s = s + j; } i = i + 1; } return s; }",
            "public static long m(long n) { long i = 0; long s = 0; long t = 0; while (i < n) { i = i + 1; long j = 0; while (j < 5) { j = j + 1; s = s + 1; if (j == i) { break; } } t = t + s; if (t > 12) { break; } } return t * 100 + s; }",
            "public static long m(long n) { long i = 0; long s = 0; while (i < n) { i = i + 1; if (i > 1) { long j = 0; while (j < 2) { j = j + 1; s = s + i; } } s = s + 1; } return s; }",
    })
    void overCounts(String members) {
        for (long n : List.of(-1L, 0L, 1L, 2L, 3L, 5L)) assertAgree(members, integer(n));
    }

    /** The statements after an {@code if} around a loop run once, with what the loop left (ADR-050). */
    @ParameterizedTest
    @ValueSource(strings = {
            "public static long m(JulcList<BigInteger> xs, boolean c) { long s = 1; if (c) { for (var x : xs) { s = s + x.longValue(); } } return s * 2; }",
            "public static long m(JulcList<BigInteger> xs, boolean c) { long s = 1; long t = 2; if (c) { for (var x : xs) { s = s + 1; t = t + 1; } } else { for (var x : xs) { t = t * 2; } } return s * t; }",
            "public static long m(JulcList<BigInteger> xs, boolean c) { long s = 1; if (c) { for (var x : xs) { s = s + 1; } return s; } return saidL(\"after\", s * 3); }",
            "public static long m(JulcList<BigInteger> xs, boolean c) { long s = 1; if (c) { for (var x : xs) { s = s + 1; } saidL(\"in\", s); } else { saidL(\"else\", s); } return saidL(\"after\", s); }",
            "public static long m(JulcList<BigInteger> xs, boolean c) { long s = 1; if (c) { long i = 0; while (i < 3) { i = i + 1; s = s + i; } } if (!c) { for (var x : xs) { s = s + 2; } } return s; }",
            "public static long m(JulcList<BigInteger> xs, boolean c) { if (c) { for (var x : xs) { saidL(\"i\", 1); } } return saidL(\"after\", 2); }",
            "public static long m(JulcList<BigInteger> xs, boolean c) { long s = 1; if (c) for (var x : xs) s = s + 1; return s; }",
    })
    void aLoopInAnIf(String members) {
        for (var xs : LISTS) {
            assertAgree(members, xs, bool(true));
            assertAgree(members, xs, bool(false));
        }
    }

    /** A failure in a loop ends the program where Java's does, with what ran before it. */
    @ParameterizedTest
    @ValueSource(strings = {
            "public static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { saidL(\"item\", s); if (x.longValue() == 2) { fail(0); } s = s + 1; } return s; }",
            "public static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { s = s + 1; if (x.longValue() == 2) { s = fail(0); break; } } return s; }",
            "public static long m(JulcList<BigInteger> xs) { long s = 0; long t = 0; for (var x : xs) { t = 1 / (x.longValue() - 2); s = s + 1; } return s; }",
            "public static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { if (x.longValue() == 2) { break; } s = s + 1; } long t = 1 / s; return t; }",
    })
    void aFailureEndsTheLoop(String members) {
        assertAgreeOnEveryList(members);
    }

    /** Each representation travels in a tuple of accumulators and comes back. */
    @ParameterizedTest
    @ValueSource(strings = {
            "public static String m(JulcList<BigInteger> xs) { long n = 0; String s = \"\"; for (var x : xs) { n = n + 1; s = s + \"a\"; } return s; }",
            "public static boolean m(JulcList<BigInteger> xs) { long n = 0; boolean f = false; for (var x : xs) { n = n + 1; f = !f; } return f; }",
            "public static byte[] m(JulcList<BigInteger> xs) { long n = 0; byte[] b = new byte[]{1}; for (var x : xs) { n = n + 1; b = Builtins.appendByteString(b, new byte[]{2}); } return b; }",
            "public static PlutusData m(JulcList<BigInteger> xs) { long n = 0; PlutusData d = Builtins.iData(0); for (var x : xs) { n = n + 1; d = Builtins.iData(x); } return d; }",
            "public static JulcList<BigInteger> m(JulcList<BigInteger> xs) { long n = 0; JulcList<BigInteger> r = xs; for (var x : xs) { n = n + 1; if (r.isEmpty()) { break; } r = r.tail(); } return r; }",
            "public static S m(JulcList<BigInteger> xs) { long n = 0; S s = new S.D(); for (var x : xs) { n = n + 1; s = new S.A(x); } return s; }",
            "public static Tuple2<BigInteger, BigInteger> m(JulcList<BigInteger> xs) { long n = 0; Tuple2<BigInteger, BigInteger> t = new Tuple2<>(BigInteger.ZERO, BigInteger.ONE); for (var x : xs) { n = n + 1; t = new Tuple2<>(x, t.first()); } return t; }",
            "public static BigInteger m(JulcList<BigInteger> xs) { Tuple2<BigInteger, BigInteger> t = new Tuple2<>(BigInteger.ZERO, BigInteger.ONE); for (var x : xs) { t = new Tuple2<>(x, t.first()); } return t.second(); }",
            "public static PlutusData m(JulcList<BigInteger> xs) { PlutusData.ListData c = Builtins.tailList(Builtins.mkCons(Builtins.iData(0), Builtins.mkNilData())); PlutusData.ListData r = Builtins.mkNilData(); long n = 0; while (n < 2) { n = n + 1; r = Builtins.mkCons(Builtins.iData(n), r); } return Builtins.headList(r); }",
    })
    void aTupleCarriesEveryRepresentation(String members) {
        for (var xs : List.of(list(1, 2), list(4, 5, 6))) assertAgree(members, xs);
    }

    /** A {@code switch} in a body, and a loop in an arm, each keep their own exits. */
    @ParameterizedTest
    @ValueSource(strings = {
            "public static long m(JulcList<BigInteger> xs, S s) { long t = 0; for (var x : xs) { t = t + switch (s) { case S.A a -> a.v().longValue(); case S.B b -> b.w().longValue(); case S.D d -> 1; }; } return t; }",
            "public static long m(JulcList<BigInteger> xs, S s) { long t = 0; for (var x : xs) { long d = switch (s) { case S.A a -> { if (a.v().signum() > 0) { yield 5; } yield 6; } default -> 7; }; if (d == 6) { break; } t = t + d; } return t; }",
            "public static long m(JulcList<BigInteger> xs, S s) { return switch (s) { case S.A a -> { long t = 0; for (var x : xs) { t = t + a.v().longValue(); } yield t; } case S.B b -> { long i = 0; while (i < 2) { i = i + 1; if (i == b.v().longValue()) { break; } } yield i; } default -> 0; }; }",
            "public static long m(JulcList<BigInteger> xs, S s) { return switch (s) { case S.A a when a.v().signum() > 0 -> { long t = 1; for (var x : xs) { t = t * 2; } saidL(\"arm\", t); yield t + 1; } default -> 0; }; }",
            "public static long m(JulcList<BigInteger> xs, S s) { long t = 0; for (var x : xs) { if (x.longValue() == 2) { t = switch (s) { case S.A a -> t + 1; default -> t + 2; }; break; } t = t + 3; } return t; }",
            "public static long m(JulcList<BigInteger> xs, S s) { if (s instanceof S.A a) { long t = 0; for (var x : xs) { t = t + a.v().longValue(); } return t; } return 9; }",
            "public static long m(JulcList<BigInteger> xs, S s) { long t = 0; if (s instanceof S.A a) { for (var x : xs) { t = t + a.v().longValue(); } } return t * 2; }",
    })
    void aSwitchAndALoop(String members) {
        var a = new PlutusData.ConstrData(0, List.of(integer(2)));
        var negative = new PlutusData.ConstrData(0, List.of(integer(-1)));
        var b = new PlutusData.ConstrData(1, List.of(integer(1), integer(2)));
        var d = new PlutusData.ConstrData(2, List.of());
        for (var s : List.of(a, negative, b, d)) {
            for (var xs : List.of(list(), list(1, 2, 3))) assertAgree(members, xs, s);
        }
    }

    /** A loop in a lambda's body is that body's. A variable of a loop is captured at each iteration's value. */
    @ParameterizedTest
    @ValueSource(strings = {
            "public static boolean m(JulcList<BigInteger> xs) { return xs.any(x -> { long s = 0; long i = 0; while (i < 3) { i = i + 1; s = s + x.longValue(); } return s == 6; }); }",
            "public static boolean m(JulcList<BigInteger> xs) { return xs.any(x -> { long s = 0; for (var y : xs) { if (y.equals(x)) { s = s + 1; break; } } return s > 0; }); }",
            "public static JulcList<BigInteger> m(JulcList<BigInteger> xs) { return xs.map(x -> { BigInteger s = x; for (var y : xs) { s = s.add(y); } return s; }); }",
            "public static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { if (xs.any(y -> y.equals(x.add(BigInteger.ONE)))) { s = s + 1; } } return s; }",
            "public static long m(JulcList<BigInteger> xs) { long s = 0; long i = 0; while (i < 3) { i = i + 1; final long k = i; if (xs.any(y -> y.longValue() == k)) { s = s + k; } } return s; }",
            "public static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { s = s + xs.filter(y -> y.compareTo(x) > 0).size(); } return s; }",
            "public static JulcList<BigInteger> m(JulcList<BigInteger> xs) { JulcList<BigInteger> r = xs; long i = 0; while (i < 2) { i = i + 1; final long k = i; r = r.map(y -> y.add(BigInteger.valueOf(k))); } return r; }",
    })
    void aLambdaAndALoop(String members) {
        assertAgreeOnEveryList(members);
    }

    /** A {@code @Param} and a static field update as Java does where {@code JULC0056} lets them. */
    @Test
    void aFieldUpdatedByAnEntryMethod() {
        assertAgree("static long K = 3; public static long m(JulcList<BigInteger> xs) { for (var x : xs) { K = K + 1; } return K; }", list(1, 2));
        assertAgree("static long K = 3; public static long m(JulcList<BigInteger> xs) { long K = 5; for (var x : xs) { K = K + 1; } return K; }", list(1, 2));
        assertAgree("static long K = 3; public static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { long K = s; s = K + 1; } return s + K; }", list(1, 2));
        assertAgree("static final long K = 3; public static long m(long n) { long i = 0; while (i < n) { { long K = i + 1; i = K; } i = i + K; } return i * K; }", integer(4));
    }

    /** {@code hash()} of a for-each's variable is the bytes the element decoded to: the reference knows that of this variable alone. */
    @Test
    void aHashReadFromALoopVariable() {
        assertAgree("public static byte[] m(JulcList<PubKeyHash> xs) { byte[] r = new byte[]{}; for (var x : xs) { r = Builtins.appendByteString(r, x.hash()); } return r; }", bytes(1, 2));
        assertAgree("public static byte[] m(JulcList<PubKeyHash> xs) { byte[] r = new byte[]{}; for (PubKeyHash x : xs) { r = x.hash(); } return r; }", bytes(1, 2));
    }

    /**
     * An element decodes by the type javac gave it, where the reference takes it for Data and fails on-chain (F1022, the
     * follow-ups' row of an inline list). What is read off the variable stays what the reference sees of it (I2d).
     */
    @Test
    void anElementTheReferenceTakesForData() {
        assertFixedOnJavac("public static BigInteger m(BigInteger a) { BigInteger s = BigInteger.ONE; for (var x : JulcList.of(a, a)) { s = s.subtract(x); } return s; }", integer(3));
        assertFixedOnJavac("public static BigInteger m(JulcList<BigInteger> xs) { BigInteger s = BigInteger.ONE; for (var x : xs.map(y -> y.add(BigInteger.ONE))) { s = s.add(x); } return s; }", list(1, 2));
        assertFixedOnJavac("public static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs.map(y -> y.signum() > 0)) { if (x) { s = s + 1; } } return s; }", list(1, -2));
        assertAgree("public static long m(JulcList<BigInteger> xs) { long s = 0; for (var x : xs.filter(y -> y.signum() > 0)) { s = s + x.longValue(); } return s; }", list(1, -2));
        var seen = run("public static long m(BigInteger a) { long s = 0; for (var x : JulcList.of(a, a)) { s = s + x.longValue(); } return s; }", List.of(integer(3)));
        seen.results().forEach((config, result) -> {
            assertEquals(Verdict.Kind.REJECTED, result.verdict().kind(), seen::describe);
            assertTrue(result.uplc().toString().contains("JULC0055"), seen::describe);
        });
    }
}
