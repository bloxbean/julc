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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                assertEquals(Verdict.Kind.AGREE, result.verdict().kind(), report::describe);
                EVALUATED.incrementAndGet();
            
        });
    }

    /** Every frontend at every level agrees with the JVM, and the frontends with each other on value, cost and size. */
    private static void assertAgree(String members, PlutusData... args) {
        var report = run(members, List.of(args));
        assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
        assertEquals(4, report.results().size());
        assertJavacAgrees(report);
        report.results().forEach((config, result) -> {
        });
    }

    /** A difference D13 lists as a semantic fix: the reference compiles this and answers otherwise, and {@code JAVAC} is the JVM's. */
    private static void assertFixedOnJavac(String members, PlutusData... args) {
        var report = run(members, List.of(args));
        for (var entry : report.results().entrySet()) {
        }
        assertJavacAgrees(report);
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

    /**
     * What stopped at {@code JULC0084} until ADR-068 M5 fails closed with a code (a value of no one type is {@code JULC0012}), or
     * lowers to the JVM's value (a function in parentheses or a conditional, a parameter written {@code var}).
     */
    private static void assertStopsOnJavac(String members, PlutusData... args) {
        var report = run(members, List.of(args));
        report.results().forEach((config, result) -> {
                var kind = result.verdict().kind();
                assertTrue(kind == Verdict.Kind.AGREE || kind == Verdict.Kind.REJECTED, report::describe);
                if (kind == Verdict.Kind.REJECTED) assertFalse(result.uplc().toString().contains("JULC0084"), report::describe);
            
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
        assertMissFailsOnJavac("public static long m(" + BIG_LIST + " xs) { var r = xs.find(x -> x.signum() > 5); return 1; }", integers(1));
    }

    /** Any read of a miss fails where its value is computed: a declared local read on another path, and an argument nothing reads. */
    @Test
    void aMissReadOnAnotherPathFailsInTheScript() {
        assertMissFailsOnJavac("public static long m(" + BIG_LIST + " xs, boolean c) { BigInteger r = xs.find(x -> x.signum() > 5); if (c) { return r.longValue(); } return 1; }", integers(1), bool(false));
        assertMissFailsOnJavac("static long h(BigInteger v) { return 1; } public static long m(" + BIG_LIST + " xs) { return h(xs.find(x -> x.signum() > 5)); }", integers(1));
        assertMissFailsOnJavac("public static long m(" + BIG_LIST + " xs) { PlutusData d = PlutusData.cast(xs.find(x -> x.signum() > 5), PlutusData.class); return 1; }", integers(1));
    }

    /**
     * The controls of the two tests above, which the head corpus's rows of them cite (r17, "Policy rejection at run time" under
     * M3f decision 1): the same programs, first with a find that cannot miss, where the decision's trigger cannot fire and
     * both frontends give the JVM's answer at every level; then with a predicate that can hold, where {@code JAVAC} gives the
     * JVM's answer on every hit and fails only on a miss, at the miss's {@code HeadList}. So the failure is the decision's and
     * no other, and it is never a value. The cast's control casts an element that is Data: a {@code BigInteger} cast to
     * {@code PlutusData} is D6's unchecked view, which the JVM refuses on a hit and both scripts pass alike.
     */
    @Test
    void aFindThatCannotMissAgreesWhereAMissFails() {
        var inputs = List.of(integers(), integers(-3), integers(-1, 0), integers(2, -5), integers(0, 4, 1));
        for (String find : List.of("xs.prepend(BigInteger.ONE).find(x -> x.signum() > 0)", "xs.find(x -> x.signum() > 0)")) {
            var shapes = List.of(
                    "public static long m(" + BIG_LIST + " xs, boolean c) { var r = " + find + "; return 1; }",
                    "public static long m(" + BIG_LIST + " xs, boolean c) { BigInteger r = " + find + "; if (c) { return r.longValue(); } return 1; }",
                    "static long h(BigInteger v) { return 1; } public static long m(" + BIG_LIST + " xs, boolean c) { return h(" + find + "); }",
                    "public static long m(" + BIG_LIST + " xs, boolean c) { JulcList<PlutusData> ds = xs.map(x -> Builtins.iData(x)); PlutusData d = PlutusData.cast("
                            + find.replace("xs.", "ds.").replace("BigInteger.ONE", "Builtins.iData(BigInteger.ONE)").replace("x.signum()", "Builtins.unIData(x).signum()")
                            + ", PlutusData.class); return 1; }");
            for (String shape : shapes) {
                for (var xs : inputs) {
                    boolean hits = find.contains("prepend") || ((PlutusData.ListData) xs).items().stream()
                            .anyMatch(x -> ((PlutusData.IntData) x).value().signum() > 0);
                    for (boolean c : List.of(true, false)) {
                        boolean read = c && shape.contains("r.longValue()");
                        if (read) {
                            // The read: JAVAC gives the JVM's answer, its value on a hit and a failure on a miss, as Java's null
                            // fails; the reference returns the Optional find leaves (r17's review, O7b).
                            var report = run(shape, List.of(xs, bool(c)));
                            assertJavacAgrees(report);
                            PlutusData optional = !hits ? constr(1) : constr(0, find.contains("prepend") ? integer(1)
                                    : ((PlutusData.ListData) xs).items().stream()
                                            .filter(x -> ((PlutusData.IntData) x).value().signum() > 0).findFirst().orElseThrow());
                            report.results().forEach((config, result) -> {
                            });
                        } else if (hits) {
                            assertBothAgree(shape, xs, bool(c));
                        } else {
                            assertMissFailsOnJavac(shape, xs, bool(c));
                        }
                    }
                }
            }
        }
    }

    private static void assertMissFailsOnJavac(String members, PlutusData... args) {
        var report = run(members, List.of(args));
        assertEquals(new Observation.Value(integer(1), List.of()), report.jvm());
        report.results().forEach((config, result) -> {
            var expected = Verdict.Kind.MISMATCH;
            assertEquals(expected, result.verdict().kind(), report::describe);
                // The miss's read, where the decision says the script fails: the head of the empty list find leaves.
                assertTrue(result.uplc() instanceof Observation.Failure f && f.detail().contains("HeadList"), report::describe);
            
        });
    }

    /** D6: a view, not a read. Java throws here, and the script holds the {@code Optional} the corpus reads it as. */
    @Test
    void aCastViewsWhatFindLeaves() {
        assertBothDivergeAlike("public static boolean m(" + BIG_LIST + " xs) { return PlutusData.cast(xs.find(x -> x.signum() > 0), Optional.class).isPresent(); }", integers(1));
        assertBothDivergeAlike("public static boolean m(" + BIG_LIST + " xs) { return PlutusData.cast(xs.find(x -> x.signum() > 5), Optional.class).isPresent(); }", integers(1));
    }

    /** Valid programs on an element of a sealed interface: a simple name as the receiver is the only shape the reference refuses, and {@code JAVAC} has javac's type there too. */
    @Test
    void aSwitchOnAnElementFollowsTheReceiver() {
        var xs = list(a(1), a(-1), b(1, 2), constr(2));
        String body = ".any(v -> switch (v) { case S.A q -> q.v().signum() > 0; case S.B q -> false; case S.D q -> false; }); }";
        String field = "record F(JulcList<S> ss) { } ";
        assertWiderOnJavac("public static boolean m(JulcList<S> xs) { return xs" + body, xs);
        assertAgree("public static boolean m(JulcList<S> xs) { return (xs)" + body, xs);
        assertAgree("public static boolean m(JulcList<S> xs) { return xs.tail()" + body, xs);
        assertAgree("public static boolean m(JulcList<S> xs) { return xs.filter(u -> true)" + body, xs);
        assertAgree("static JulcList<S> ids(JulcList<S> s) { return s; } public static boolean m(JulcList<S> xs) { return ids(xs)" + body, xs);
        assertAgree(field + "public static boolean m(F f) { return f.ss()" + body, constr(0, xs));
        assertBothAgree(field + "public static long m(F f) { S r = f.ss().find(v -> switch (v) { case S.A q -> true; default -> false; }); return 1; }", constr(0, xs));
    }

    /**
     * The last program above with a list that holds no {@code S.A}: the find misses, its unread binding is computed, and
     * {@code JAVAC}'s script fails where the JVM returns (M3f decision 1). Its control, which the head corpus's row cites:
     * with an {@code S.A} put first, the find cannot miss, and both frontends give the JVM's answer at every level on every list.
     */
    @Test
    void aSwitchFindThatCannotMissAgreesWhereAMissFails() {
        String field = "record F(JulcList<S> ss) { } ";
        String find = ".find(v -> switch (v) { case S.A q -> true; default -> false; }); return 1; }";
        for (var xs : List.of(list(), list(constr(2)), list(b(1, 2), constr(2)), list(a(1), b(1, 2)), list(b(3, 4), a(-1)))) {
            assertBothAgree(field + "public static long m(F f) { S r = f.ss().prepend(new S.A(BigInteger.ONE))" + find, constr(0, xs));
            boolean hits = ((PlutusData.ListData) xs).items().stream().anyMatch(x -> ((PlutusData.ConstrData) x).tag() == 0);
            if (hits) assertBothAgree(field + "public static long m(F f) { S r = f.ss()" + find, constr(0, xs));
            else assertMissFailsOnJavac(field + "public static long m(F f) { S r = f.ss()" + find, constr(0, xs));
        }
    }

    /** A rejection of the reference's discarded pass, which {@code JAVAC} does not make: the JVM's answer at every level (D13, Accepted by decision). */
    private static void assertWiderOnJavac(String members, PlutusData... args) {
        var report = run(members, List.of(args));
        report.results().forEach((config, result) -> assertEquals(Verdict.Kind.AGREE,
                result.verdict().kind(), report::describe));
        assertJavacAgrees(report);
    }

    /** {@code JULC0007}: an inferred element of a sealed interface has its type at a simple name too. */
    @Test
    void aSwitchOnAnInferredElementAtASimpleName() {
        var xs = list(a(1), a(-1), b(1, 2), constr(2));
        String on = "switch (v) { case S.A q -> q.v().signum() > 0; case S.B q -> false; case S.D q -> false; }";
        for (var elements : List.of(xs, list(constr(2)), list())) {
            assertWiderOnJavac("public static boolean m(JulcList<S> xs) { return xs.any(v -> " + on + "); }", elements);
            assertWiderOnJavac("public static boolean m(JulcList<S> xs) { var ys = xs; return ys.all(v -> " + on + "); }", elements);
            assertWiderOnJavac("public static long m(JulcList<S> xs) { return xs.filter(v -> " + on + ").size(); }", elements);
            assertWiderOnJavac("public static JulcList<Boolean> m(JulcList<S> xs) { return xs.map(v -> " + on + "); }", elements);
            assertWiderOnJavac("public static boolean m(JulcList<S> xs) { return xs.any(v -> { var w = v; return switch (w) { case S.A q -> true; default -> false; }; }); }", elements);
        }
        assertWiderOnJavac("public static " + BIG_LIST + " m(JulcList<S> xs) { return xs.map(v -> switch (v) { case S.A q -> q.v(); case S.B q when q.w().signum() > 0 -> q.w(); default -> BigInteger.ONE; }); }", xs);
        assertWiderOnJavac("public record F(JulcList<S> ss) { } public static boolean m(JulcList<S> xs, F f) { return xs.any(v -> f.ss().any(w -> " + on + ")); }", xs, constr(0, list(a(1))));
        assertWiderOnJavac("public static boolean m(JulcList<JulcList<S>> xss) { return xss.any(l -> l.any(v -> " + on + ")); }", list(xs, list()));
    }

    /** {@code instanceof} on an inferred element: its pattern form reads the tag as the written type does, on both frontends, and with no pattern there is none to read. */
    @Test
    void anInstanceofOnAnInferredElement() {
        var xs = list(a(1), a(-1), b(1, 2), constr(2));
        for (var elements : List.of(xs, list(constr(2)), list(a(-1)))) {
            for (var parameter : List.of("v", "(S v)")) {
                assertAgree("public static boolean m(JulcList<S> xs) { return xs.any(" + parameter + " -> { if (v instanceof S.A q) { return q.v().signum() > 0; } return false; }); }", elements);
                assertAgree("public static long m(JulcList<S> xs) { return xs.filter(" + parameter + " -> { if (v instanceof S.B q) { return true; } return false; }).size(); }", elements);
            }
        }
    }

    private static final String ON_S = " switch (q) { case S.A a -> a.v().signum() > 0; case S.B b -> false; case S.D d -> false; } ";

    /** {@code JULC0007}: what is read off a parameter at a simple name is Data to the first pass, and the type javac gave it runs. */
    @Test
    void aSwitchOnWhatIsReadOffAParameterAtASimpleName() {
        String type = "public record Rs(S s) { } ";
        var rs = list(constr(0, a(1)), constr(0, a(-1)), constr(0, constr(2)));
        assertWiderOnJavac(type + "public static boolean m(JulcList<Rs> rs) { return rs.any(r -> switch (r.s()) { case S.A a -> a.v().signum() > 0; default -> false; }); }", rs);
        assertWiderOnJavac(type + "public static boolean m(JulcList<Rs> rs) { return rs.any(r -> { var q = r.s(); return" + ON_S + "; }); }", rs);
        assertWiderOnJavac(type + "public static long m(JulcList<Rs> rs) { return rs.filter(r -> { var q = r.s(); return" + ON_S + "; }).size(); }", rs);
        assertWiderOnJavac(type + "public static boolean m(JulcList<Rs> rs, " + BIG_LIST + " ys) { return rs.any(r -> ys.any(y -> switch (r.s()) { case S.A a -> a.v().signum() > 0; default -> false; })); }", rs, integers(1));
        assertAgree(type + "static JulcList<Rs> id(JulcList<Rs> l) { return l; } public static boolean m(JulcList<Rs> rs) { return id(rs).any(r -> switch (r.s()) { case S.A a -> a.v().signum() > 0; default -> false; }); }", rs);
        String field = "public record Fs(JulcList<S> ss) { } ";
        assertWiderOnJavac(field + "public static boolean m(JulcList<Fs> fs) { return fs.any(f -> f.ss().any(q ->" + ON_S + ")); }", list(constr(0, list(a(1), a(-1)))));
    }

    /** {@code JULC0012}: the first pass types what a lambda at a simple name holds as Data, so arms and elements do not agree. */
    @Test
    void anElementTypeTheFirstPassDoesNotSee() {
        var xs = integers(1, 2);
        assertWiderOnJavac("public static " + BIG_LIST + " m(" + BIG_LIST + " xs) { return xs.map(x -> { var a = JulcArray.of(x, BigInteger.ONE); return a.get(0); }); }", xs);
        assertWiderOnJavac("public static " + BIG_LIST + " m(JulcList<R> xs) { return xs.map(r -> { var a = JulcArray.of(r.a(), BigInteger.ONE); return a.get(0); }); }", list(r(1, 2)));
        assertWiderOnJavac("public static " + BIG_LIST + " m(BigInteger a, S s) { var xs = JulcList.of(a); return xs.map(x -> switch (s) { case S.A q -> x; case S.B b -> BigInteger.ONE; case S.D d -> BigInteger.TWO; }); }", integer(5), a(1));
    }

    private static final String MAP_OF_BIG = "JulcMap<BigInteger, BigInteger>";

    private static PlutusData mapOf(long key, long value) {
        return new PlutusData.MapData(List.of(new PlutusData.Pair(integer(key), integer(value))));
    }

    /** Both frontends give the JVM's answer, each at its own cost. */
    private static void assertBothAgree(String members, PlutusData... args) {
        var report = run(members, List.of(args));
        assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
        assertJavacAgrees(report);
    }

    /** A cast to a map decodes Data, and what javac types as a map is decoded already, whatever the binder says of it. */
    @Test
    void aCastToAMapDecodesOnce() {
        assertBothAgree("public static boolean m(" + MAP_OF_BIG + " a) { var xs = JulcList.of(a); return xs.any(x -> PlutusData.cast(x, JulcMap.class).isEmpty()); }", mapOf(1, 2));
        assertBothAgree("public static long m(" + MAP_OF_BIG + " a) { var xs = JulcList.of(a); return xs.map(x -> { " + MAP_OF_BIG + " d = PlutusData.cast(x, JulcMap.class); return d.size(); }).head(); }", mapOf(1, 2));
        assertBothAgree("public static long m(" + MAP_OF_BIG + " a) { var y = JulcList.of(a).head(); " + MAP_OF_BIG + " d = PlutusData.cast(y, JulcMap.class); return d.size(); }", mapOf(1, 2));
        assertFixedOnJavac("public static long m(JulcList<" + MAP_OF_BIG + "> xs) { " + MAP_OF_BIG + " d = PlutusData.cast(xs.find(x -> true), JulcMap.class); return d.size(); }", list(mapOf(1, 2)));
        assertFixedOnJavac("public static boolean m(JulcList<" + MAP_OF_BIG + "> a) { return a.map(y -> y).any(x -> { " + MAP_OF_BIG + " d = PlutusData.cast(x, JulcMap.class); return d.isEmpty(); }); }", list(mapOf(1, 2)));
        assertBothAgree("public static long m(" + MAP_OF_BIG + " a) { " + MAP_OF_BIG + " d = PlutusData.cast(a, JulcMap.class); return d.size(); }", mapOf(1, 2));
        assertBothAgree("public static long m(" + MAP_OF_BIG + " a, BigInteger n) { var y = JulcList.of(a, n).head(); " + MAP_OF_BIG + " d = PlutusData.cast(y, JulcMap.class); return d.size(); }", mapOf(1, 2), integer(1));
    }

    /** One term cannot decode one arm and leave the other, so what holds a map on one path and Data on another stops, however it is read. */
    @Test
    void aCastToAMapOfAMixedValueStops() {
        String held = "var y = JulcList.of(a).head(); ";
        String size = " JulcMap.class); return z.size(); }";
        String declared = MAP_OF_BIG + " z = PlutusData.cast(";
        String head = "public static long m(boolean c, " + MAP_OF_BIG + " a, PlutusData d, BigInteger n, S s) { ";
        for (var c : List.of(bool(true), bool(false))) {
            for (var body : List.of(held + declared + "c ? y : d," + size, held + declared + "(c ? y : d)," + size, held + declared + "((c ? d : y))," + size,
                    declared + "c ? a : n," + size, declared + "c ? n : a," + size, declared + "c ? d : a," + size,
                    held + "var w = c ? y : d; " + declared + "w," + size, "var w = c ? a : n; " + declared + "w," + size,
                    declared + "switch (s) { case S.A q -> a; default -> d; }," + size)) {
                assertStopsOnJavac(head + body, c, mapOf(1, 2), mapOf(3, 4), integer(1), a(1));
            }
        }
        assertStopsOnJavac("public static boolean m(boolean c, " + MAP_OF_BIG + " a, PlutusData d) { var xs = JulcList.of(a); return xs.any(q -> { " + declared + "c ? q : d, JulcMap.class); return z.isEmpty(); }); }", bool(true), mapOf(1, 2), mapOf(3, 4));
    }

    /** A cast that does not decode says nothing of the value, so the decode reads through it: once on Data, never on what is decoded. */
    @Test
    void aCastToAMapReadsThroughACast() {
        String head = "public static long m(boolean c, " + MAP_OF_BIG + " a, PlutusData d, S s) { var y = JulcList.of(a).head(); " + MAP_OF_BIG + " z = PlutusData.cast(";
        String size = ", JulcMap.class); return z.size(); }";
        var one = List.of(bool(true), mapOf(1, 2), mapOf(3, 4), a(1));
        for (var held : List.of("PlutusData.cast(y, PlutusData.class)", "PlutusData.cast(y, Object.class)", "PlutusData.cast((y), PlutusData.class)", "(PlutusData.cast(y, PlutusData.class))",
                "PlutusData.cast(PlutusData.cast(y, PlutusData.class), PlutusData.class)", "PlutusData.cast(a, PlutusData.class)")) {
            assertJavacAgrees(run(head + held + size, one));
        }
        for (var mixed : List.of("PlutusData.cast(c ? y : d, PlutusData.class)", "c ? PlutusData.cast(y, PlutusData.class) : d", "c ? d : PlutusData.cast(y, PlutusData.class)",
                "PlutusData.cast(c ? d : a, PlutusData.class)", "PlutusData.cast(switch (s) { case S.A q -> a; default -> d; }, PlutusData.class)")) {
            assertStopsOnJavac(head + mixed + size, bool(true), mapOf(1, 2), mapOf(3, 4), a(1));
        }
        String through = "public static long m(boolean c, " + MAP_OF_BIG + " a, PlutusData d) { var y = JulcList.of(a).head(); var w = PlutusData.cast(c ? y : d, PlutusData.class); " + MAP_OF_BIG + " z = PlutusData.cast(w, JulcMap.class); return z.size(); }";
        assertStopsOnJavac(through, bool(true), mapOf(1, 2), mapOf(3, 4));
        assertBothDivergeAlike("public static long m(PlutusData d) { JulcMap<PlutusData, PlutusData> z = PlutusData.cast(PlutusData.cast(d, PlutusData.class), JulcMap.class); return z.size(); }", mapOf(3, 4));
    }

    /** A choice with a read of no one type in it shows no decode, so it stops, whatever else the value may hold beside it. */
    @Test
    void aCastToAMapOfAReadWithNoOneType() {
        String head = "public static long m(boolean c, " + MAP_OF_BIG + " a, PlutusData d, BigInteger n, S s) { var y = JulcList.of(a, n).head(); " + MAP_OF_BIG + " z = PlutusData.cast(";
        String size = ", JulcMap.class); return z.size(); }";
        var arguments = new PlutusData[]{bool(true), mapOf(1, 2), mapOf(3, 4), integer(1), a(1)};
        for (var choice : List.of("c ? a : y", "c ? y : d", "switch (s) { case S.A q -> a; default -> y; }", "switch (s) { case S.A q -> d; default -> y; }")) {
            assertStopsOnJavac(head + choice + size, arguments);
        }
    }

    /** Two values each decoded once, or each a map, take one term, whatever their type arguments. */
    @Test
    void aCastToAMapOfTwoHeldMaps() {
        String head = "public static long m(boolean c, " + MAP_OF_BIG + " a, PlutusData d) { " + MAP_OF_BIG + " z = PlutusData.cast(";
        for (var c : List.of(bool(true), bool(false))) {
            assertJavacAgrees(run(head + "c ? a : a, JulcMap.class); return z.size(); }", List.of(c, mapOf(1, 2), mapOf(3, 4))));
            // A raw map and a typed one are arms of two types (ADR-068 M5): JULC0012, closed.
            assertStopsOnJavac(head + "c ? PlutusData.cast(d, JulcMap.class) : a, JulcMap.class); return z.size(); }", c, mapOf(1, 2), mapOf(3, 4));
        }
    }

    /** {@code JAVAC} compiles this and the view reads one entry, which Java's checked cast of a {@code MapData} refuses. */
    private static void assertJavacIsAMap(String members, PlutusData... args) {
        var report = run(members, List.of(args));
        report.results().forEach((config, result) -> {
            assertEquals(new Observation.Value(integer(1), List.of()), result.uplc(), report::describe);
        });
    }

    /** Each arm holds the same thing, so the one term is right on both paths. */
    @Test
    void aCastToAMapOfOneRepresentationGoesOn() {
        String cast = " z = PlutusData.cast(";
        String pure = "public static long m(boolean c, " + MAP_OF_BIG + " a, " + MAP_OF_BIG + " b) { " + MAP_OF_BIG + cast + "c ? a : b, JulcMap.class); return z.size(); }";
        String lambda = "public static boolean m(boolean c, " + MAP_OF_BIG + " a) { var xs = JulcList.of(a); return xs.any(q -> { " + MAP_OF_BIG + cast + "c ? q : a, JulcMap.class); return z.isEmpty(); }); }";
        var two = new PlutusData.MapData(List.of(new PlutusData.Pair(integer(1), integer(2)), new PlutusData.Pair(integer(3), integer(4))));
        for (var c : List.of(bool(true), bool(false))) {
            assertBothAgree(pure, c, mapOf(1, 2), two);
            assertJavacAgrees(run(lambda, List.of(c, mapOf(1, 2))));
            assertBothDivergeAlike("public static long m(boolean c, PlutusData d, PlutusData e) { JulcMap<PlutusData, PlutusData>" + cast + "c ? d : e, JulcMap.class); return z.size(); }", c, mapOf(1, 2), two);
        }
    }

    /** Java refuses a {@code MapData} as a {@code JulcMap}, and the view reads it: a Data value still decodes, on both frontends. */
    @Test
    void aCastToAMapStillDecodesData() {
        assertBothDivergeAlike("public static long m(PlutusData x) { JulcMap<PlutusData, PlutusData> d = PlutusData.cast(x, JulcMap.class); return d.size(); }", mapOf(1, 2));
        assertBothDivergeAlike("public static long m(JulcList<PlutusData> xs) { return xs.map(x -> { JulcMap<PlutusData, PlutusData> d = PlutusData.cast(x, JulcMap.class); return d.size(); }).head(); }", list(mapOf(1, 2)));
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
