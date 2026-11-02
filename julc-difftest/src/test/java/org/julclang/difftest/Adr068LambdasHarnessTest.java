package org.julclang.difftest;

import org.julclang.compiler.CompilerFrontend;
import org.julclang.core.PlutusData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 D10 over lambdas and the list methods that apply them: each program on the JVM and, through each frontend at every
 * level, on the VM. The JVM is the oracle, and results are read through Data, so every value, trace and failure here comes
 * from neither frontend. A rejection passes nothing: {@link #assertAgree} needs a value or a failure the JVM gives, and
 * {@link #evaluated} counts them.
 */
class Adr068LambdasHarnessTest {

    private static final DiffRunner RUNNER = new DiffRunner(configs(), 10_000);
    /** Runs on {@code frontend=JAVAC} that evaluated and agreed with the JVM. */
    private static final AtomicInteger EVALUATED = new AtomicInteger();

    private static List<DiffConfig> configs() {
        var configs = new ArrayList<>(DiffConfig.allLevels());
        configs.addAll(DiffConfig.allLevels(CompilerFrontend.JAVAC));
        return configs;
    }

    private static String program(String members) {
        return """
                import java.math.BigInteger;
                import java.util.Optional;
                import java.util.function.Function;
                import java.util.function.Predicate;
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcArray;
                import org.julclang.core.types.JulcList;
                import org.julclang.core.types.JulcMap;
                import org.julclang.core.types.Tuple2;
                import org.julclang.ledger.PubKeyHash;
                import org.julclang.stdlib.Builtins;

                public class Case {
                    static BigInteger said(String m, BigInteger v) { return Builtins.unIData(Builtins.trace(m, Builtins.iData(v))); }
                    static boolean check(String m, boolean v) { Builtins.trace(m, Builtins.iData(BigInteger.ZERO)); return v; }
                    static BigInteger fail(BigInteger z) { return Builtins.unIData(Builtins.error()); }
                    static final BigInteger K = BigInteger.TEN;
                    public record R(BigInteger a, BigInteger b) { }
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

    private static PlutusData list(PlutusData... items) {
        return new PlutusData.ListData(List.of(items));
    }

    private static PlutusData integers(long... values) {
        return new PlutusData.ListData(Arrays.stream(values).mapToObj(Adr068LambdasHarnessTest::integer).toList());
    }

    private static PlutusData text(String value) {
        return new PlutusData.BytesData(value.getBytes(StandardCharsets.UTF_8));
    }

    private static PlutusData bytes(int... values) {
        var data = new byte[values.length];
        for (int i = 0; i < values.length; i++) data[i] = (byte) values[i];
        return new PlutusData.BytesData(data);
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

    private static PlutusData r(long a, long b) {
        return constr(0, integer(a), integer(b));
    }

    private static DiffRunner.Report run(String members, List<PlutusData> args) {
        return RUNNER.run(new DiffCase("m3f", program(members), "Case", "m", args, TAGS, null));
    }

    private static void assertJavacAgrees(DiffRunner.Report report) {
        report.results().forEach((config, result) -> {
            if (config.frontend() == CompilerFrontend.JAVAC) {
                assertEquals(Verdict.Kind.AGREE, result.verdict().kind(), report::describe);
                EVALUATED.incrementAndGet();
            }
        });
    }

    /** Every frontend at every level agrees with the JVM, and the frontends with each other on value, cost and size. */
    private static void assertAgree(String members, PlutusData... args) {
        var report = run(members, List.of(args));
        assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
        assertEquals(8, report.results().size());
        assertJavacAgrees(report);
        report.results().forEach((config, result) -> {
            if (config.frontend() == CompilerFrontend.JAVAPARSER) {
                var javac = report.results().get(new DiffConfig(config.level(), "Java", CompilerFrontend.JAVAC));
                assertEquals(result.uplc(), javac.uplc(), "the frontends disagree at " + config);
                assertEquals(result.measurement(), javac.measurement(), "cost at " + config);
            }
        });
    }

    /** A difference D13 lists as a semantic fix: the reference compiles this and answers otherwise, and {@code JAVAC} is the JVM's. */
    private static void assertFixedOnJavac(String members, PlutusData... args) {
        var report = run(members, List.of(args));
        boolean referenceDiverges = false;
        for (var entry : report.results().entrySet()) {
            if (entry.getKey().frontend() == CompilerFrontend.JAVAPARSER) {
                assertTrue(entry.getValue().verdict().kind() != Verdict.Kind.REJECTED, () -> "a rejection is no fix, it is a decision: " + report.describe());
                referenceDiverges |= entry.getValue().verdict().kind() != Verdict.Kind.AGREE;
            }
        }
        assertJavacAgrees(report);
        assertTrue(referenceDiverges, () -> "the reference no longer diverges, so this is no fix: " + report.describe());
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

    private static void assertRejectedAlike(String members, String code, PlutusData... args) {
        var report = run(members, List.of(args));
        report.results().forEach((config, result) -> {
            assertEquals(Verdict.Kind.REJECTED, result.verdict().kind(), report::describe);
            assertTrue(result.uplc().toString().contains(code), () -> code + " expected: " + report.describe());
        });
    }

    private static void assertStopsOnJavac(String members, PlutusData... args) {
        var report = run(members, List.of(args));
        report.results().forEach((config, result) -> {
            if (config.frontend() == CompilerFrontend.JAVAC) {
                assertEquals(Verdict.Kind.REJECTED, result.verdict().kind(), report::describe);
                assertTrue(result.uplc().toString().contains("JULC0084"), report::describe);
            }
        });
    }

    private static final String BIG_LIST = "JulcList<BigInteger>";

    // --- A lambda runs at each element, in order ---

    @Test
    void aBodyRunsOncePerElementInOrder() {
        for (var xs : List.of(integers(), integers(1), integers(3, 1, 2), integers(2, 2, 2))) {
            assertAgree("public static " + BIG_LIST + " m(" + BIG_LIST + " xs) { return xs.map(x -> said(\"m\", x)); }", xs);
            assertAgree("public static " + BIG_LIST + " m(" + BIG_LIST + " xs) { return xs.filter(x -> said(\"f\", x).compareTo(BigInteger.ZERO) > 0); }", xs);
        }
    }

    @Test
    void aLambdaIsMadeWithoutRunningAndTheReceiverRunsFirst() {
        var xs = integers(1, 2);
        assertAgree("public static " + BIG_LIST + " m(" + BIG_LIST + " xs) { var ys = xs.filter(x -> check(\"filter\", true)); note(); return ys; } "
                + "static void note() { Builtins.trace(\"after\", Builtins.iData(BigInteger.ZERO)); }", xs);
        assertAgree("static " + BIG_LIST + " h(" + BIG_LIST + " xs) { Builtins.trace(\"receiver\", Builtins.iData(BigInteger.ZERO)); return xs; } "
                + "public static " + BIG_LIST + " m(" + BIG_LIST + " xs) { return h(xs).map(x -> said(\"body\", x)); }", xs);
    }

    @Test
    void aFailingBodyOnAnEmptyAndANonEmptyList() {
        String fails = "x -> BigInteger.TEN.divide(BigInteger.ZERO)";
        String failsTest = "x -> BigInteger.TEN.divide(BigInteger.ZERO).signum() > 0";
        for (var xs : List.of(integers(), integers(1), integers(1, 2, 3))) {
            assertAgree("public static " + BIG_LIST + " m(" + BIG_LIST + " xs) { return xs.map(" + fails + "); }", xs);
            assertAgree("public static " + BIG_LIST + " m(" + BIG_LIST + " xs) { return xs.filter(" + failsTest + "); }", xs);
            assertAgree("public static boolean m(" + BIG_LIST + " xs) { return xs.any(" + failsTest + "); }", xs);
            assertAgree("public static boolean m(" + BIG_LIST + " xs) { return xs.all(" + failsTest + "); }", xs);
            assertAgree("public static boolean m(" + BIG_LIST + " xs) { return xs.all(x -> { if (x.signum() > 0) { return fail(x).signum() > 0; } return true; }); }", xs);
        }
        assertFixedOnJavac("public static BigInteger m(" + BIG_LIST + " xs) { return xs.find(x -> x.signum() > 0); }", integers(0, 2, 3));
    }

    @Test
    void aFailureAtALaterElementStillRunsWhatCameBefore() {
        assertAgree("public static " + BIG_LIST + " m(" + BIG_LIST + " xs) { return xs.map(x -> BigInteger.TEN.divide(said(\"m\", x))); }", integers(2, 0, 5));
        assertAgree("public static " + BIG_LIST + " m(" + BIG_LIST + " xs) { return xs.filter(x -> BigInteger.TEN.divide(said(\"f\", x)).compareTo(BigInteger.ZERO) > 0); }", integers(2, 0, 5));
    }

    // --- T7: what any and all do past the answer ---

    @Test
    void anyDoesNotStopAtTheFirstMatch() {
        String any = "public static boolean m(" + BIG_LIST + " xs) { return xs.any(x -> BigInteger.TEN.divide(x).signum() > 0); }";
        assertAgree(any, integers(1, 2));
        assertAgree(any, integers(0, 1));
        assertBothDivergeAlike(any, integers(1, 0));
        assertBothDivergeAlike("public static boolean m(" + BIG_LIST + " xs) { return xs.any(x -> said(\"t\", x).compareTo(BigInteger.ZERO) > 0); }", integers(1, 2));
    }

    @Test
    void allDoesNotStopAtTheFirstRefusal() {
        String all = "public static boolean m(" + BIG_LIST + " xs) { return xs.all(x -> BigInteger.TEN.divide(x.add(BigInteger.ONE)).signum() > 0); }";
        assertAgree(all, integers(0, 1));
        assertAgree(all, integers(-1, 1));
        assertBothDivergeAlike("public static boolean m(" + BIG_LIST + " xs) { return xs.all(x -> BigInteger.TEN.divide(x).signum() < 0); }", integers(1, 0));
        assertBothDivergeAlike("public static boolean m(" + BIG_LIST + " xs) { return xs.all(x -> said(\"t\", x).compareTo(BigInteger.ZERO) < 0); }", integers(1, 2));
    }

    @Test
    void findStopsWhereJavaStops() {
        String read = "public static BigInteger m(" + BIG_LIST + " xs) { BigInteger r = xs.find(x -> BigInteger.TEN.divide(x).signum() > 0); return r.add(BigInteger.ONE); }";
        assertFixedOnJavac(read, integers(1, 0));
        assertFixedOnJavac("public static BigInteger m(" + BIG_LIST + " xs) { BigInteger r = xs.find(x -> said(\"t\", x).compareTo(BigInteger.ZERO) > 0); return r; }", integers(-1, 1, 2));
    }

    // --- Captures ---

    @Test
    void aCaptureOfEachKind() {
        var xs = integers(1, 2, 3);
        assertAgree("public static " + BIG_LIST + " m(" + BIG_LIST + " xs, BigInteger k) { return xs.map(x -> x.add(k)); }", xs, integer(5));
        assertAgree("public static " + BIG_LIST + " m(" + BIG_LIST + " xs, BigInteger k) { var j = k.add(k); return xs.map(x -> x.add(j)); }", xs, integer(5));
        assertAgree("public static " + BIG_LIST + " m(" + BIG_LIST + " xs) { return xs.map(x -> x.add(K)); }", xs);
        assertAgree("public static " + BIG_LIST + " m(" + BIG_LIST + " xs) { return xs.map(x -> { var y = x.add(x); return xs.map(z -> z.add(y)).size() > 0 ? y : x; }); }", xs);
        assertAgree("public static boolean m(" + BIG_LIST + " xs, boolean c) { return xs.any(x -> c); }", xs, bool(true));
        assertAgree("public static boolean m(JulcList<byte[]> xs, byte[] k) { return xs.any(x -> Builtins.equalsByteString(x, k)); }", list(bytes(1), bytes(2)), bytes(2));
        assertAgree("public static " + BIG_LIST + " m(" + BIG_LIST + " xs, R r) { return xs.map(x -> x.add(r.b())); }", xs, r(1, 2));
        assertAgree("public static boolean m(" + BIG_LIST + " xs, " + BIG_LIST + " ys) { return xs.any(x -> ys.contains(x)); }", xs, integers(9, 3));
    }

    @Test
    void aCaptureIsTheValueJavaBound() {
        assertAgree("public static " + BIG_LIST + " m(" + BIG_LIST + " xs, BigInteger k) { if (k.signum() > 0) { var j = k.add(k); return xs.map(x -> x.add(j)); } var j = k; return xs.map(x -> x.add(j)); }",
                integers(1), integer(-3));
        assertAgree("public static BigInteger m(" + BIG_LIST + " xs, BigInteger k) { var ys = xs.map(x -> said(\"map\", x.add(k))); var j = said(\"after\", k); return j; }",
                integers(1, 2), integer(3));
    }

    @Test
    void aPatternVariable() {
        String source = "public static " + BIG_LIST + " m(" + BIG_LIST + " xs, S s) { if (s instanceof S.A a) { return xs.map(x -> x.add(a.v())); } return xs; }";
        assertAgree(source, integers(1, 2), a(7));
        assertAgree(source, integers(1, 2), b(1, 2));
        assertAgree("public static boolean m(" + BIG_LIST + " xs, S s) { if (s instanceof S.B q) { return xs.any(x -> x.equals(q.w()) || x.equals(q.v())); } return false; }",
                integers(1, 2), b(9, 2));
        assertAgree("public static " + BIG_LIST + " m(JulcList<S> xs, " + BIG_LIST + " ys) { return xs.map(s -> { if (s instanceof S.A a) { return BigInteger.valueOf(ys.filter(y -> y.equals(a.v())).size()); } return BigInteger.ZERO; }); }",
                list(a(1), b(1, 2), a(2)), integers(1, 1, 3));
    }

    // --- Names ---

    @Test
    void aShadowedName() {
        var xs = integers(1, 2);
        assertAgree("public static " + BIG_LIST + " m(" + BIG_LIST + " xs) { return xs.map(K -> K.add(K)); }", xs);
        assertAgree("public static " + BIG_LIST + " m(" + BIG_LIST + " xs) { return xs.map(x -> { var K = x.add(x); return K; }); }", xs);
        assertAgree("static BigInteger x() { return BigInteger.ONE; } public static " + BIG_LIST + " m(" + BIG_LIST + " xs) { return xs.map(x -> x.add(x())); }", xs);
        assertAgree("public static boolean m(" + BIG_LIST + " xs) { return xs.any(y -> y.signum() > 0) && xs.all(y -> y.signum() > 0); }", xs);
        assertAgree("public static long m(" + BIG_LIST + " xs) { boolean a = xs.any(y -> y.signum() > 0); long y = 3; if (a) { return y; } return y + 1; }", xs);
        assertAgree("public static " + BIG_LIST + " m(" + BIG_LIST + " xs) { return xs.map(x -> x.add(K)); }", xs);
    }

    /** The binders the builders make carry a {@code #} name, so a parameter named like one of them is any other name. */
    @ParameterizedTest
    @ValueSource(strings = {"acc", "x_flt", "acc_map", "go", "lst", "h", "x_rev", "acc_rev"})
    void aParameterNamedLikeABuilderBinder(String name) {
        assertAgree("public static boolean m(" + BIG_LIST + " xs, " + BIG_LIST + " ys) { return xs.any(" + name + " -> ys.any(y -> y.equals(" + name + "))); }",
                integers(1, 2), integers(2));
        assertAgree("public static " + BIG_LIST + " m(" + BIG_LIST + " xs, " + BIG_LIST + " ys) { return xs.map(" + name + " -> BigInteger.valueOf(ys.filter(y -> y.equals(" + name + ")).size())); }",
                integers(1, 2), integers(2, 2));
    }

    // --- Nesting ---

    @Test
    void nestedLambdas() {
        assertAgree("public static boolean m(" + BIG_LIST + " xs, BigInteger k) { return xs.any(x -> xs.all(y -> y.compareTo(x.add(k)) < 0)); }", integers(1, 2), integer(5));
        assertAgree("public static boolean m(" + BIG_LIST + " xs) { return xs.any(a -> xs.any(b -> xs.any(c -> a.add(b).equals(c)))); }", integers(1, 2, 3));
        assertAgree("public static JulcList<JulcList<BigInteger>> m(" + BIG_LIST + " xs) { return xs.map(x -> xs.filter(y -> y.compareTo(x) <= 0)); }", integers(1, 2, 3));
        assertFixedOnJavac("public static JulcList<JulcList<BigInteger>> m(JulcList<JulcList<BigInteger>> xss, BigInteger k) { return xss.map(l -> l.map(y -> y.add(k))); }",
                list(integers(1, 2), integers(), integers(3)), integer(1));
    }

    // --- Records and sealed variants ---

    @Test
    void overRecords() {
        var rs = list(r(1, 2), r(3, 3));
        assertAgree("public static " + BIG_LIST + " m(JulcList<R> xs) { return xs.map(r -> r.a()); }", rs);
        assertAgree("public static JulcList<R> m(JulcList<R> xs) { return xs.map(r -> new R(r.b(), r.a())); }", rs);
        assertAgree("public static JulcList<R> m(JulcList<R> xs) { return xs.filter(r -> r.a().equals(r.b())); }", rs);
        assertAgree("public static JulcList<R> m(" + BIG_LIST + " xs, BigInteger k) { return xs.map(x -> new R(x, k)); }", integers(1, 2), integer(9));
        assertAgree("public static boolean m(JulcList<R> xs, R k) { return xs.any(r -> r.equals(k)); }", rs, r(3, 3));
        assertFixedOnJavac("public static BigInteger m(JulcList<R> xs) { R r = xs.find(q -> q.a().equals(q.b())); return r.a(); }", rs);
    }

    @Test
    void overSealedVariants() {
        var ss = list(a(1), b(2, 3), constr(2), a(-1));
        assertAgree("public static " + BIG_LIST + " m(JulcList<S> xs) { return xs.map(s -> { if (s instanceof S.A a) { return a.v(); } if (s instanceof S.B q) { return q.w(); } return BigInteger.ZERO; }); }", ss);
        assertAgree("public static JulcList<S> m(JulcList<S> xs) { return xs.filter(s -> { if (s instanceof S.D d) { return false; } return true; }); }", ss);
        assertAgree("public static JulcList<S> m(" + BIG_LIST + " xs) { return xs.map(x -> new S.A(x)); }", integers(1, 2));
        assertAgree("public static JulcList<S> m(" + BIG_LIST + " xs) { JulcList<S> ys = xs.map(x -> { if (x.signum() > 0) { return new S.A(x); } return new S.D(); }); return ys; }", integers(1, 0));
        assertAgree("public static boolean m(JulcList<S> xs, S k) { return xs.any(s -> s.equals(k)); }", ss, b(2, 3));
    }

    // --- An element's encoding, on the way in and out ---

    @Test
    void anElementGoesInAndComesOut() {
        assertAgree("public static " + BIG_LIST + " m(" + BIG_LIST + " xs) { return xs.map(x -> x); }", integers(-1, 0, 1L << 40));
        assertAgree("public static JulcList<Boolean> m(JulcList<Boolean> xs) { return xs.map(x -> x); }", list(bool(true), bool(false)));
        assertAgree("public static JulcList<Boolean> m(JulcList<Boolean> xs) { return xs.map(x -> !x); }", list(bool(true), bool(false)));
        assertAgree("public static JulcList<Boolean> m(JulcList<Boolean> xs) { return xs.filter(x -> x); }", list(bool(true), bool(false)));
        assertAgree("public static JulcList<String> m(JulcList<String> xs) { return xs.map(x -> x + \"!\"); }", list(text("a"), text("é")));
        assertAgree("public static JulcList<String> m(JulcList<String> xs) { return xs.filter(x -> x.equals(\"a\")); }", list(text("a"), text("b")));
        assertAgree("public static JulcList<byte[]> m(JulcList<byte[]> xs) { return xs.map(x -> Builtins.appendByteString(x, x)); }", list(bytes(1), bytes()));
        assertAgree("public static JulcList<byte[]> m(JulcList<PubKeyHash> xs) { return xs.map(p -> p.hash()); }", list(bytes(1, 2), bytes(3)));
        assertAgree("public static JulcList<PlutusData> m(JulcList<PlutusData> xs) { return xs.map(x -> x); }", list(integer(1), text("a"), list(), constr(3)));
        assertAgree("public static JulcList<Optional<BigInteger>> m(JulcList<Optional<BigInteger>> xs) { return xs.map(x -> x); }", list(constr(0, integer(1)), constr(1)));
        assertAgree("public static JulcList<Optional<BigInteger>> m(" + BIG_LIST + " xs) { return xs.map(x -> Optional.of(x)); }", integers(1));
        assertAgree("public static JulcList<Tuple2<BigInteger, BigInteger>> m(" + BIG_LIST + " xs) { return xs.map(x -> new Tuple2<>(x, x)); }", integers(1));
        assertAgree("public static JulcList<JulcList<BigInteger>> m(" + BIG_LIST + " xs) { return xs.map(x -> xs); }", integers(1, 2));
        assertAgree("public static JulcList<Boolean> m(" + BIG_LIST + " xs) { return xs.map(x -> x.signum() > 0); }", integers(1, -1));
        assertAgree("public static JulcList<String> m(" + BIG_LIST + " xs) { return xs.map(x -> \"a\"); }", integers(1));
    }

    @Test
    void aListOrAMapElementIsDecoded() {
        assertFixedOnJavac("public static JulcList<JulcList<BigInteger>> m(JulcList<JulcList<BigInteger>> xs) { return xs.map(x -> x); }", list(integers(1), integers()));
        assertFixedOnJavac("public static boolean m(JulcList<JulcList<BigInteger>> xs) { return xs.any(l -> l.isEmpty()); }", list(integers(1), integers()));
        assertFixedOnJavac("public static " + BIG_LIST + " m(JulcList<JulcList<BigInteger>> xs) { return xs.map(l -> l.head()); }", list(integers(1), integers(2)));
        var map = new PlutusData.MapData(List.of(new PlutusData.Pair(integer(1), integer(2))));
        assertFixedOnJavac("public static " + BIG_LIST + " m(JulcList<JulcMap<BigInteger, BigInteger>> xs) { return xs.map(l -> l.get(BigInteger.ONE)); }", list(map));
        assertFixedOnJavac("public static JulcList<JulcMap<BigInteger, BigInteger>> m(JulcList<JulcMap<BigInteger, BigInteger>> xs) { return xs.map(l -> l); }", list(map));
    }

    private static final String CALLBACK_HELPER = "static boolean helper(" + BIG_LIST + " xs, BigInteger k) { if (k.equals(BigInteger.ONE)) { return xs.get(0).equals(BigInteger.ZERO); } "
            + "if (k.equals(BigInteger.TWO)) { return xs.get(1).equals(BigInteger.ZERO); } return true; } "
            + "public static boolean m(JulcList<JulcList<BigInteger>> groups, BigInteger k) { return groups.any(xs -> helper(xs, k)); }";

    /** ADR-043's {@code CALLBACK_HELPER}: the parameter is a list now, at every level, on each path through the helper. */
    @ParameterizedTest
    @ValueSource(longs = {1, 2})
    void aListParameterPassedOn(long k) {
        assertFixedOnJavac(CALLBACK_HELPER, list(integers(0, 1), integers(1, 0)), integer(k));
    }

    /** The path that does not read the list agrees on both frontends, and {@code JAVAC} pays for one decode per element. */
    @Test
    void aListParameterNotRead() {
        assertJavacAgrees(run(CALLBACK_HELPER, List.of(list(integers(0, 1), integers(1, 0)), integer(0))));
        var report = run("static BigInteger pick(" + BIG_LIST + " xs, BigInteger k) { if (k.equals(BigInteger.ONE)) { return xs.get(0); } return BigInteger.ZERO; } "
                + "public static long m(JulcList<JulcList<BigInteger>> groups) { return groups.filter(xs -> pick(xs, BigInteger.ZERO).equals(BigInteger.ZERO)).size(); }",
                List.of(list(integers(1), integers(2))));
        assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
        assertJavacAgrees(report);
    }

    // --- A fold's accumulator: the builders' folds hold a boolean or a list, and no method takes one (D6) ---

    @Test
    void eachFoldOfTheBuilders() {
        for (var xs : List.of(integers(), integers(1), integers(1, 2, 3))) {
            assertAgree("public static boolean m(" + BIG_LIST + " xs) { return xs.any(x -> x.signum() > 1); }", xs);
            assertAgree("public static boolean m(" + BIG_LIST + " xs) { return xs.all(x -> x.signum() > 0); }", xs);
            assertAgree("public static " + BIG_LIST + " m(" + BIG_LIST + " xs) { return xs.map(x -> x.add(x)); }", xs);
            assertAgree("public static " + BIG_LIST + " m(" + BIG_LIST + " xs) { return xs.filter(x -> !x.equals(BigInteger.TWO)); }", xs);
        }
    }

    @Test
    void aFilterThenAMap() {
        for (var xs : List.of(integers(), integers(-1, 1), integers(2, -2, 2))) {
            assertAgree("public static " + BIG_LIST + " m(" + BIG_LIST + " xs) { return xs.filter(x -> x.signum() > 0).map(y -> y.add(y)); }", xs);
            assertAgree("public static long m(" + BIG_LIST + " xs) { return xs.filter(x -> x.signum() > 0).map(y -> y.add(y)).size(); }", xs);
            assertAgree("public static JulcList<R> m(" + BIG_LIST + " xs) { return xs.filter(x -> x.signum() > 0).map(y -> new R(y, y.add(y))); }", xs);
            assertAgree("public static boolean m(" + BIG_LIST + " xs) { JulcList<BigInteger> ys = xs.map(y -> y.add(y)); return ys.filter(x -> x.signum() > 0).any(x -> x.equals(BigInteger.TWO)); }",
                    xs);
        }
    }

    // --- F1022, F1023, F1024, F1041: what the reference's element type loses, each the JVM's on JAVAC ---

    @Test
    void aMapsResultIsReadByJavacsElementType() {
        assertFixedOnJavac("public static BigInteger m(" + BIG_LIST + " xs, BigInteger k) { return xs.map(x -> x.add(k)).head(); }", integers(1, 2), integer(1));
        assertFixedOnJavac("public static BigInteger m(" + BIG_LIST + " xs, BigInteger k) { var ys = xs.map(x -> x.add(k)); return ys.head(); }", integers(1, 2), integer(1));
        assertFixedOnJavac("public static boolean m(" + BIG_LIST + " xs) { return xs.map(x -> x.signum() > 0).any(b -> b); }", integers(1, -1));
        assertFixedOnJavac("public static boolean m() { var bs = JulcList.of(true, false); return bs.map(x -> !x).head(); }");
        assertFixedOnJavac("public static boolean m() { var bs = JulcList.of(true, false); return bs.any(x -> x); }");
    }

    @Test
    void aMemberTheReferenceCannotFindStaysRejected() {
        assertRejectedAlike("public static boolean m(" + BIG_LIST + " xs) { return xs.map(x -> x).any(y -> y.signum() > 0); }", "JULC0055", integers(1));
        assertRejectedAlike("public static boolean m(" + BIG_LIST + " xs, BigInteger k) { return xs.map(x -> x).any(y -> y.compareTo(k) > 0); }", "JULC0055", integers(1), integer(0));
        assertRejectedAlike("public static BigInteger m(" + BIG_LIST + " xs) { return xs.map(x -> x).head().add(BigInteger.ONE); }", "JULC0055", integers(1));
        assertRejectedAlike("public static BigInteger m(" + BIG_LIST + " xs) { var r = xs.find(x -> true); return r.add(BigInteger.ONE); }", "JULC0055", integers(1));
        assertRejectedAlike("public static boolean m(" + BIG_LIST + " xs, BigInteger k) { var r = xs.find(x -> x.equals(k)); return r.equals(k); }", "JULC0055", integers(1, 2), integer(2));
        assertRejectedAlike("public static boolean m(BigInteger a) { var xs = JulcList.of(a); return xs.any(x -> x.signum() > 0); }", "JULC0055", integer(1));
        assertRejectedAlike("public static boolean m(BigInteger a, BigInteger b) { var y = JulcList.of(a).head(); return y.compareTo(b) > 0; }", "JULC0055", integer(1), integer(2));
    }

    @Test
    void aParameterWithAWrittenTypeIsDecoded() {
        assertFixedOnJavac("public static boolean m(" + BIG_LIST + " xs) { return xs.any((BigInteger b) -> b.signum() > 0); }", integers(1));
        assertFixedOnJavac("public static " + BIG_LIST + " m(" + BIG_LIST + " xs) { return xs.filter((BigInteger b) -> b.signum() > 0); }", integers(1, -1));
        assertFixedOnJavac("public static " + BIG_LIST + " m(" + BIG_LIST + " xs) { return xs.map((BigInteger b) -> b.add(b)); }", integers(1));
        assertFixedOnJavac("public static boolean m(JulcList<Boolean> xs) { return xs.any((Boolean b) -> b); }", list(bool(true)));
        assertFixedOnJavac("public static JulcList<String> m(JulcList<String> xs) { return xs.map((String b) -> b + \"!\"); }", list(text("a")));
        assertFixedOnJavac("public static boolean m(JulcList<JulcList<BigInteger>> xs) { return xs.any((JulcList<BigInteger> l) -> l.isEmpty()); }", list(integers()));
    }

    @Test
    void findIsItsElement() {
        assertFixedOnJavac("public static BigInteger m(" + BIG_LIST + " xs, BigInteger k) { return xs.find(x -> x.equals(k)); }", integers(1, 2), integer(2));
        assertFixedOnJavac("public static BigInteger m(" + BIG_LIST + " xs, BigInteger k) { BigInteger r = xs.find(x -> x.equals(k)); return r.add(r); }", integers(1, 2), integer(2));
        assertFixedOnJavac("public static PlutusData m(JulcList<PlutusData> xs) { return xs.find(x -> true); }", integers(1, 2));
        assertFixedOnJavac("public static boolean m(JulcList<PlutusData> items) { PlutusData found = items.find((PlutusData x) -> Builtins.unIData(x).equals(BigInteger.valueOf(3))); return Builtins.constrTag(found) == 0; }",
                integers(1, 3));
        assertRejectedAlike("public static byte[] m(JulcList<PubKeyHash> xs) { return xs.find(p -> true).hash(); }", "JULC0055", list(bytes(1, 2)));
        assertFixedOnJavac("public static PubKeyHash m(JulcList<PubKeyHash> xs) { return xs.find(p -> true); }", list(bytes(1, 2)));
        assertFixedOnJavac("public static boolean m(JulcList<BigInteger> items) { PlutusData found = PlutusData.cast(items.find(x -> x.compareTo(BigInteger.TWO) > 0), PlutusData.class); return Builtins.constrTag(found) == 0; }",
                integers(1, 3));
    }

    /** Javac's element type encodes the result, where the term says less than it holds. */
    @Test
    void whatAMapEncodesIsJavacsType() {
        assertFixedOnJavac("public static " + BIG_LIST + " m(" + BIG_LIST + " xs, " + BIG_LIST + " ys) { return xs.map(x -> ys.find(y -> y.equals(x))); }", integers(1, 2), integers(2, 1));
    }

    /** Only the cast views {@code find}: any other call takes the element. */
    @Test
    void anotherCallTakesTheElement() {
        assertFixedOnJavac("public static BigInteger m(" + BIG_LIST + " xs) { Optional<BigInteger> o = Optional.of(xs.find(x -> true)); return o.get(); }", integers(1));
    }

    @Test
    void aFindThatFindsNothing() {
        assertJavacAgrees(run("public static BigInteger m(" + BIG_LIST + " xs) { BigInteger r = xs.find(x -> x.signum() > 5); return r.add(BigInteger.ONE); }", List.of(integers(1))));
        // What Java returns no one reads, so nothing is read of it here.
        assertAgree("public static long m(" + BIG_LIST + " xs) { xs.find(x -> x.signum() > 5); return 1; }", integers(1));
    }

    /** Java's null goes on, and the script fails at the read, which is {@code JulcMap.get}'s and fails closed. */
    @Test
    void aMissThatNothingReadsFailsInTheScript() {
        var report = run("public static long m(" + BIG_LIST + " xs) { var r = xs.find(x -> x.signum() > 5); return 1; }", List.of(integers(1)));
        assertEquals(new Observation.Value(integer(1), List.of()), report.jvm());
        report.results().forEach((config, result) -> {
            var expected = config.frontend() == CompilerFrontend.JAVAC ? Verdict.Kind.MISMATCH : Verdict.Kind.AGREE;
            assertEquals(expected, result.verdict().kind(), report::describe);
            if (config.frontend() == CompilerFrontend.JAVAC) assertTrue(result.uplc() instanceof Observation.Failure, report::describe);
        });
    }

    /** D6: a view, not a read. Java throws here, and the script holds the {@code Optional} the corpus reads it as. */
    @Test
    void aCastViewsWhatFindLeaves() {
        assertBothDivergeAlike("public static boolean m(" + BIG_LIST + " xs) { return PlutusData.cast(xs.find(x -> x.signum() > 0), Optional.class).isPresent(); }", integers(1));
        assertBothDivergeAlike("public static boolean m(" + BIG_LIST + " xs) { return PlutusData.cast(xs.find(x -> x.signum() > 5), Optional.class).isPresent(); }", integers(1));
    }

    // --- What stops ---

    @Test
    void aFunctionTheReferenceTypesAsDataStops() {
        assertStopsOnJavac("public static boolean m(" + BIG_LIST + " xs, BigInteger k) { return xs.any((x -> x.equals(k))); }", integers(1), integer(1));
        assertStopsOnJavac("public static boolean m(" + BIG_LIST + " xs, boolean c) { return xs.any(c ? x -> true : x -> false); }", integers(1), bool(true));
        assertStopsOnJavac("public static boolean m(" + BIG_LIST + " xs) { return xs.any((var x) -> x.signum() > 0); }", integers(1));
        assertStopsOnJavac("public static boolean m(JulcList xs) { return xs.any(x -> true); }", integers(1));
    }

    @Test
    void aMethodReferenceAndAFunctionalLocalAreRejectedAlike() {
        assertRejectedAlike("static boolean h(BigInteger x) { return true; } public static boolean m(" + BIG_LIST + " xs) { return xs.any(Case::h); }", "Method references", integers(1));
        assertRejectedAlike("public static BigInteger m(BigInteger a) { Function<BigInteger, BigInteger> f = x -> x; return f.apply(a); }", "Functional interface .apply()", integer(1));
        assertRejectedAlike("public static boolean m(BigInteger a) { Predicate<BigInteger> f = x -> true; return f.test(a); }", "Functional interface .test()", integer(1));
    }

    @Test
    void aValueWithNoEncodingIsRejectedAlike() {
        assertRejectedAlike("public static long m(" + BIG_LIST + " xs) { var ys = xs.map(x -> JulcArray.of(x)); return ys.size(); }",
                "JULC0091", integers(1));
    }

    /** A pass here means evaluation: this many runs of {@code JAVAC} gave what the JVM gives. */
    @AfterAll
    static void evaluated() {
        System.out.println("lambda programs evaluated on javac: " + EVALUATED.get());
        assertTrue(EVALUATED.get() >= 500, () -> "too few runs evaluated: " + EVALUATED.get());
    }
}
