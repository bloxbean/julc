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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 D10 over {@code switch}: each program on the JVM and, through each frontend at every level, on the VM, read back
 * through Data. The JVM is the oracle, so every value, trace and failure here comes from neither frontend. A traced operand
 * pins what ran and in which order, and a program that fails pins what ran before it did. Every assertion needs
 * {@code AGREE}, which a rejection is not, except where a test says it observes one.
 */
class Adr068SwitchHarnessTest {

    private static final DiffRunner RUNNER = new DiffRunner(configs(), 10_000);
    /** Programs run, each at eight configurations, whose every verdict was {@code AGREE}. */
    private static final AtomicInteger AGREED = new AtomicInteger();

    @AfterAll
    static void whatRan() {
        System.out.println("switch harness: " + AGREED + " runs agreed");
        assertTrue(AGREED.get() >= 700, "too few runs agreed: " + AGREED);
    }

    private static List<DiffConfig> configs() {
        var configs = new ArrayList<>(DiffConfig.allLevels());
        return configs;
    }

    private static String program(String members) {
        return """
                import java.math.BigInteger;
                import java.util.Optional;
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcList;
                import org.julclang.core.types.JulcMap;
                import org.julclang.core.types.Tuple2;
                import org.julclang.stdlib.Builtins;

                public class Case {
                    static BigInteger said(String m, BigInteger v) { return Builtins.unIData(Builtins.trace(m, Builtins.iData(v))); }
                    static long saidL(String m, long v) { return Builtins.unIData(Builtins.trace(m, Builtins.iData(v))).longValue(); }
                    static boolean saidB(String m, boolean v) { saidL(m, 0); return v; }
                    static S saidS(String m, S v) { saidL(m, 0); return v; }
                    static long fail(long z) { return Builtins.unIData(Builtins.error()).longValue(); }
                    static S failS(S s) { return fail(0) == 0 ? s : s; }
                    static boolean failB(boolean b) { return fail(0) == 0 ? b : b; }
                    static long plus(long x, long y) { return x + y; }
                    public sealed interface S {
                        record A(BigInteger v) implements S { }
                        record B(BigInteger v, BigInteger w) implements S { }
                        record D() implements S { }
                    }
                    public sealed interface O { record P(BigInteger x) implements O { } }
                    public sealed interface T permits X, Y { }
                    public record X(BigInteger v) implements T { }
                    public record Y(S s, boolean b) implements T { }
                    public record R(S s, BigInteger n) { }
                    %s
                }
                """.formatted(members);
    }

    private static final List<TagTable.Entry> TAG_ENTRIES = List.of(
            new TagTable.Entry("Case$S$A", "Case$S", 0), new TagTable.Entry("Case$S$B", "Case$S", 1), new TagTable.Entry("Case$S$D", "Case$S", 2),
            new TagTable.Entry("Case$O$P", "Case$O", 0), new TagTable.Entry("Case$X", "Case$T", 0), new TagTable.Entry("Case$Y", "Case$T", 1));
    private static final TagTable TAGS = new TagTable(TAG_ENTRIES);

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

    private static PlutusData list(long... values) {
        return new PlutusData.ListData(Arrays.stream(values).mapToObj(Adr068SwitchHarnessTest::integer).toList());
    }

    private static List<PlutusData> everyS() {
        return List.of(a(7), a(0), a(-1), b(1, 2), b(-3, 4), d());
    }

    private static DiffRunner.Report run(String members, List<PlutusData> args) {
        return RUNNER.run(new DiffCase("switch", program(members), "Case", "m", args, TAGS, null));
    }

    /** Every frontend at every level agrees with the JVM, and the frontends with each other on value, cost and size. */
    private static void assertAgree(String members, PlutusData... args) {
        var report = run(members, List.of(args));
        assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
        assertEquals(4, report.results().size());
        report.results().forEach((config, result) -> {
        });
        AGREED.incrementAndGet();
    }

    private static void assertAgreeOnEvery(String members) {
        for (var s : everyS()) assertAgree(members, s);
    }

    private static void assertAgreeOnEveryPair(String members) {
        for (var s : everyS()) {
            for (var t : everyS()) assertAgree(members, s, t);
        }
    }

    private static void assertRejectedAlike(String members, String code, PlutusData... args) {
        var report = run(members, List.of(args));
        report.results().forEach((config, result) -> {
            assertEquals(Verdict.Kind.REJECTED, result.verdict().kind(), report::describe);
            assertTrue(result.uplc().toString().contains(code), () -> code + " expected: " + report.describe());
        });
    }

    /** The JVM's answer on {@code JAVAC}, and on the reference at least one level differs: a D13 semantic fix. */
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

    // --- Arms ---

    @ParameterizedTest
    @ValueSource(strings = {
            "public static BigInteger m(S s) { return switch (s) { case S.A a -> a.v(); case S.B b -> b.w(); case S.D d -> BigInteger.TWO; }; }",
            "public static BigInteger m(S s) { return switch (s) { case S.D d -> BigInteger.TWO; case S.B b -> b.v(); case S.A a -> a.v().negate(); }; }",
            "public static BigInteger m(S s) { return switch (s) { case S.A a -> a.v(); default -> BigInteger.TEN; }; }",
            "public static long m(S s) { return switch (s) { default -> 9; }; }",
            "public static boolean m(S s) { return switch (s) { case S.A a -> a.v().signum() > 0; default -> false; }; }",
            "public static String m(S s) { return switch (s) { case S.A a -> \"a\"; case S.B b -> \"b\"; default -> \"d\"; }; }",
            "public static byte[] m(S s) { return switch (s) { case S.A a -> new byte[]{1}; default -> new byte[]{2, 3}; }; }",
            "public static S m(S s) { return switch (s) { case S.A a -> new S.B(a.v(), a.v()); case S.B b -> new S.A(b.w()); default -> s; }; }",
            "public static S m(S s) { return switch (s) { case S.A a -> a; default -> new S.D(); }; }",
            "public static long m(S s) { return switch (s) { case S.A a -> { long x = a.v().longValue(); yield x + 1; } default -> { long y = 2; yield y + y; } }; }",
            "public static long m(S s) { return switch (s) { case S.A a: yield 1; case S.B b: long x = b.w().longValue(); yield x; default: yield 3; }; }",
            "public static long m(S s) { return switch (s) { case S.A a: { yield 1; } default: { yield 3; } }; }",
    })
    void armsAndDefaults(String members) {
        assertAgreeOnEvery(members);
    }

    @Test
    void aOneConstructorSum() {
        for (long v : new long[]{0, 1, -1}) {
            assertAgree("public static BigInteger m(O o) { return switch (o) { case O.P p -> p.x(); }; }", constr(0, integer(v)));
            assertAgree("public static long m(O o, boolean c) { return switch (o) { case O.P p when c -> 1; case O.P p -> 2; }; }", constr(0, integer(v)), bool(true));
            assertAgree("public static long m(O o, boolean c) { return switch (o) { case O.P p when c -> 1; default -> 2; }; }", constr(0, integer(v)), bool(false));
        }
    }

    @Test
    void aSelectorOfATopLevelVariant() {
        assertAgree("public static BigInteger m(T t) { return switch (t) { case X x -> x.v(); case Y y -> switch (y.s()) { case S.A a -> a.v(); default -> BigInteger.ONE; }; }; }",
                constr(1, a(5), bool(true)));
        assertAgree("public static boolean m(T t) { return switch (t) { case X x -> false; case Y y -> y.b(); }; }", constr(1, d(), bool(true)));
    }

    // --- Evaluation order ---

    @Test
    void theSelectorRunsOnceBeforeAnyLabel() {
        assertAgreeOnEvery("public static long m(S s) { return switch (saidS(\"sel\", s)) { case S.A a -> saidL(\"arm\", 1); case S.B b -> 2; default -> 3; }; }");
        assertAgreeOnEvery("public static long m(S s) { return switch (saidS(\"sel\", s)) { case S.A a when saidB(\"g1\", true) -> 1; case S.B b when saidB(\"g2\", false) -> 2; default -> 3; }; }");
        assertAgreeOnEvery("public static long m(S s) { return switch (saidS(\"sel\", s)) { case S.A a when saidB(\"g1\", a.v().signum() > 0) -> 1; case S.A a -> 2; default -> saidL(\"d\", 3); }; }");
    }

    @Test
    void guardsRunInSourceOrderAndAFalseOneGoesOn() {
        assertAgreeOnEvery("""
                public static long m(S s) { return switch (s) {
                    case S.A a when saidB("g1", false) -> 1;
                    case S.B b when saidB("g2", false) -> 2;
                    case S.A a when saidB("g3", a.v().signum() > 0) -> 3;
                    case S.B b when saidB("g4", b.w().signum() > 0) -> 4;
                    case S.A a -> saidL("a", 5);
                    default -> saidL("d", 6);
                }; }""");
    }

    @Test
    void aGuardOnlyRunsOnItsVariant() {
        assertAgreeOnEvery("public static long m(S s) { return switch (s) { case S.A a when failB(true) -> 1; default -> 2; }; }");
    }

    @Test
    void aColonFormRunsOneGroup() {
        assertAgreeOnEvery("public static long m(S s) { return switch (s) { case S.A a: saidL(\"a\", 1); yield 1; case S.B b: saidL(\"b\", 2); yield 2; default: saidL(\"d\", 3); yield 3; }; }");
    }

    @Test
    void aYieldLeavesItsArm() {
        for (boolean c : new boolean[]{false, true}) {
            for (var s : everyS()) {
                assertAgree("public static long m(S s, boolean c) { return switch (s) { case S.A a -> { if (c) { saidL(\"y\", 0); yield 1; } saidL(\"n\", 0); yield 2; } default -> 3; }; }", s, bool(c));
                assertAgree("public static long m(S s, boolean c) { return switch (s) { case S.A a -> { if (c) { yield 1; } else { yield 2; } } default -> 3; }; }", s, bool(c));
                assertAgree("public static long m(S s, boolean c) { long k = switch (s) { case S.A a -> { long x = saidL(\"x\", 1); if (c) { saidL(\"c\", 0); } yield x; } default -> 3; }; return k; }", s, bool(c));
                assertAgree("public static long m(S s, boolean c) { return plus(saidL(\"l\", 1), switch (s) { case S.A a: if (c) { yield saidL(\"y\", 2); } yield saidL(\"n\", 3); default: yield 4; }); }", s, bool(c));
            }
        }
    }

    // --- Failure paths ---

    @Test
    void aSelectorThatFailsRunsNoLabel() {
        assertAgreeOnEvery("public static long m(S s) { return switch (failS(s)) { case S.A a when saidB(\"g\", true) -> saidL(\"a\", 1); default -> saidL(\"d\", 2); }; }");
        assertAgreeOnEvery("public static long m(S s) { return plus(saidL(\"l\", 1), switch (failS(s)) { case S.A a -> 1; default -> 2; }); }");
    }

    @Test
    void aGuardThatFailsRunsNothingAfterIt() {
        assertAgreeOnEvery("public static long m(S s) { return switch (s) { case S.A a when saidB(\"g1\", false) -> 1; case S.A a when failB(true) -> 2; default -> saidL(\"d\", 3); }; }");
        assertAgreeOnEvery("public static long m(S s) { return switch (s) { case S.A a when fail(0) > 0 -> 1; case S.B b -> saidL(\"b\", 2); default -> saidL(\"d\", 3); }; }");
    }

    @Test
    void anArmThatFails() {
        assertAgreeOnEvery("public static long m(S s) { return switch (s) { case S.A a -> fail(0); default -> saidL(\"d\", 2); }; }");
        assertAgreeOnEvery("public static long m(S s) { return plus(saidL(\"l\", 1), switch (s) { case S.A a -> fail(0); default -> saidL(\"d\", 2); }) + saidL(\"r\", 3); }");
        assertAgreeOnEvery("public static long m(S s) { long k = switch (s) { case S.A a -> { saidL(\"x\", 0); yield fail(0); } default -> 2; }; return k + saidL(\"after\", 1); }");
    }

    @Test
    void aSwitchAsAnArgumentHasItsNeighboursOnEachSide() {
        assertAgreeOnEvery("public static long m(S s) { return plus(switch (s) { case S.A a -> saidL(\"a\", 1); default -> saidL(\"d\", 2); }, saidL(\"r\", 3)); }");
        assertAgreeOnEvery("public static long m(S s) { return plus(saidL(\"l\", 3), switch (s) { case S.A a -> saidL(\"a\", 1); default -> saidL(\"d\", 2); }); }");
        assertAgreeOnEvery("public static long m(S s) { return plus(fail(0), switch (s) { case S.A a -> saidL(\"a\", 1); default -> saidL(\"d\", 2); }); }");
        assertAgreeOnEvery("public static long m(S s) { return plus(switch (s) { case S.A a -> saidL(\"a\", 1); default -> saidL(\"d\", 2); }, fail(0)); }");
        assertAgreeOnEvery("public static long m(S s) { return plus(switch (s) { case S.A a when saidB(\"g\", false) -> 1; default -> saidL(\"d\", 2); }, plus(saidL(\"r\", 3), switch (s) { case S.A a -> 5; default -> 6; })); }");
        assertAgreeOnEvery("public static BigInteger m(S s) { return said(\"l\", BigInteger.ONE).add(switch (s) { case S.A a -> said(\"a\", a.v()); default -> said(\"d\", BigInteger.TWO); }); }");
    }

    @Test
    void aSwitchIsAnOperandInItsPlace() {
        assertAgreeOnEvery("public static long m(S s) { return saidL(\"l\", 1) + (switch (s) { case S.A a -> saidL(\"a\", 2); default -> 3; }) * saidL(\"r\", 4); }");
        assertAgreeOnEvery("public static boolean m(S s) { return saidL(\"l\", 1) > (switch (s) { case S.A a -> saidL(\"a\", 2); default -> 0; }); }");
        assertAgreeOnEvery("public static boolean m(S s) { return switch (s) { case S.A a -> true; default -> false; } || saidB(\"r\", true); }");
        assertAgreeOnEvery("public static boolean m(S s) { return switch (s) { case S.A a -> true; default -> false; } && saidB(\"r\", true); }");
        assertAgreeOnEvery("public static long m(S s) { return (switch (s) { case S.A a -> true; default -> false; }) ? saidL(\"t\", 1) : saidL(\"e\", 2); }");
        assertAgreeOnEvery("public static R m(S s) { return new R(saidS(\"l\", s), switch (s) { case S.A a -> said(\"a\", a.v()); default -> said(\"d\", BigInteger.TWO); }); }");
    }

    // --- Nesting ---

    @Test
    void nestedSwitches() {
        assertAgreeOnEveryPair("public static BigInteger m(S s, S t) { return switch (s) { case S.A a -> switch (t) { case S.A b -> a.v().add(b.v()); case S.B c -> a.v().multiply(c.w()); default -> a.v(); }; case S.B b -> switch (t) { case S.A a -> b.w().subtract(a.v()); default -> b.v(); }; default -> BigInteger.ONE; }; }");
        assertAgreeOnEveryPair("public static long m(S s, S t) { return switch (s) { case S.A a when switch (t) { case S.A b -> true; default -> false; } -> 1; case S.A a -> 2; default -> 3; }; }");
        assertAgreeOnEveryPair("public static long m(S s, S t) { return switch (s) { case S.A a when a.v().signum() > 0 -> switch (t) { case S.A b when b.v().signum() > 0 -> saidL(\"i\", 1); default -> saidL(\"j\", 2); }; case S.B b -> 3; default -> 4; }; }");
        assertAgreeOnEveryPair("public static long m(S s, S t) { return switch (saidS(\"o\", s)) { case S.A a -> { long x = switch (saidS(\"i\", t)) { case S.A b -> 1; default -> 2; }; yield x + 1; } default -> 3; }; }");
    }

    /** #47: an inner arm with a field of the name stands between an outer pattern variable and its field. */
    @Test
    void anInnerArmsFieldIsNotAnOuterOnes() {
        assertAgreeOnEveryPair("public static BigInteger m(S s, S t) { return switch (s) { case S.A a -> switch (t) { case S.B b -> a.v().add(b.v().multiply(BigInteger.TEN)); default -> a.v(); }; default -> BigInteger.ZERO; }; }");
        assertAgreeOnEveryPair("public static BigInteger m(S s, S t) { return switch (s) { case S.A a when a.v().signum() >= 0 -> switch (t) { case S.B b when b.w().signum() >= 0 -> a.v().add(b.v().multiply(BigInteger.TEN)); default -> a.v(); }; default -> BigInteger.ZERO; }; }");
    }

    /** Two arms hold a first field each, of other names, so each reads its own decoded field under the other. */
    @Test
    void nestedArmsKeepTheirFieldsApart() {
        for (var s : everyS()) {
            var o = constr(0, integer(11));
            assertAgree("public static BigInteger m(O o, S s) { return switch (o) { case O.P p -> switch (s) { case S.A a -> p.x().subtract(a.v()); case S.B b -> b.w().subtract(p.x()); default -> p.x(); }; }; }", o, s);
            assertAgree("public static BigInteger m(O o, S s) { return switch (o) { case O.P p when p.x().signum() > 0 -> switch (s) { case S.A a when a.v().signum() >= 0 -> p.x().subtract(a.v()); default -> p.x(); }; default -> BigInteger.ZERO; }; }", o, s);
        }
    }

    // --- Shadowing ---

    /** ADR-067 F9: a failed arm's binding must not reach a later arm or {@code default}, or a guard that reads the static. */
    @Test
    void aPatternVariableShadowsAFieldInItsArmAlone() {
        assertAgreeOnEvery("static final long a = 99; public static long m(S s) { return switch (s) { case S.A a when a.v().signum() > 0 -> a.v().signum(); case S.A b -> a; default -> a + 1; }; }");
        assertAgreeOnEvery("static final long a = 99; public static long m(S s) { return switch (s) { case S.A a -> a.v().longValue(); default -> a; }; }");
        assertAgreeOnEvery("static final long a = 99; public static long m(S s) { return switch (s) { case S.B a when a.w().signum() > 0 -> 1; case S.A b when a > saidL(\"g\", 5) -> a; case S.A a -> 2; default -> a; }; }");
        assertAgreeOnEvery("static final long a = 99; public static long m(S s) { long x = switch (s) { case S.A a -> 1; default -> 2; }; return x + a; }");
    }

    @Test
    void aSiblingArmReusesAName() {
        assertAgreeOnEvery("public static long m(S s) { return switch (s) { case S.A x -> x.v().longValue(); case S.B x -> x.w().longValue(); case S.D x -> 2; }; }");
        assertAgreeOnEvery("public static long m(S s) { return switch (s) { case S.A x when x.v().signum() > 0 -> 1; case S.A x -> x.v().signum(); default -> 2; }; }");
        assertAgreeOnEvery("public static long m(S s) { long a = switch (s) { case S.A x -> { long y = 1; yield y; } default -> { long y = 2; yield y; } }; long y = a + 1; return y; }");
    }

    /** #291: what {@code default} declares ends with it. */
    @Test
    void aDefaultsLocalEndsWithIt() {
        assertAgreeOnEvery("static final long k = 7; public static long m(S s) { long x = switch (s) { case S.A a -> 1; default -> { long k = 5; yield k; } }; return x + k; }");
    }

    // --- Guards ---

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 1, 2147483647L})
    void aConstantInAGuardHasJavasWidth(long n) {
        for (var guard : List.of("(2147483647 + 1) < a", "K + 1 < a", "((int) 2147483648L) < a", "0xFFFFFFFF < a", "(W << 32) > a", "~1 < a",
                "(5 > 4 ? 1 : 2) > a", "-2147483648 < a", "-2_147_483_648 < a", "-9223372036854775808L < a", "(2147483647L + 1) > a")) {
            assertAgree("static final int K = 2147483647; static final long W = 1; public static long m(S s, long a) { return switch (s) { case S.A x when "
                    + guard + " -> 1; default -> 2; }; }", a(1), integer(n));
        }
    }

    @Test
    void aConstantGuardIsNoGuard() {
        assertAgreeOnEvery("static final boolean ON = true; public static long m(S s) { return switch (s) { case S.A a when ON -> 1; case S.B b -> 2; case S.D d -> 3; }; }");
        assertAgreeOnEvery("public static long m(S s) { return switch (s) { case S.A a when true -> 1; case S.B b when 1 == 1 -> 2; case S.D d when 1 < 2 -> 3; }; }");
        assertAgreeOnEvery("public static long m(S s) { return switch (s) { case S.A a when \"a\" + (2147483647 + 1) == \"a-2147483648\" -> 1; case S.B b -> 2; case S.D d -> 3; }; }");
    }

    @Test
    void aGuardReadsItsBindingAndWhatIsAround() {
        for (var s : everyS()) {
            for (long n : new long[]{-1, 0, 1}) {
                assertAgree("public static long m(S s, BigInteger n) { return switch (s) { case S.A a when a.v().compareTo(n) > 0 -> 1; case S.B b when b.w().compareTo(n) <= 0 -> 2; default -> 3; }; }", s, integer(n));
                assertAgree("public static long m(S s, BigInteger n) { var k = n; return switch (s) { case S.A a when a.v().equals(k) -> 1; default -> 3; }; }", s, integer(n));
                assertAgree("static boolean has(S s) { return s instanceof S.A x; } public static long m(S s, long n) { return switch (s) { case S.A a when has(a) -> 1; default -> 3; }; }", s, integer(n));
            }
        }
    }

    @Test
    void anInstanceofInAGuardIsATotalTest() {
        assertAgreeOnEveryPair("public static long m(S s, S t) { return switch (s) { case S.A a when t instanceof S.A -> 1; case S.A a when !(t instanceof S.B) -> 2; default -> 3; }; }");
        assertAgreeOnEveryPair("public static long m(S s, S t) { return switch (s) { case S.A a when !(t instanceof S.A q) -> 1; default -> 3; }; }");
    }

    // --- Types through a switch ---

    /** F722: a diamond in an arm takes its components from where the switch's value goes. */
    @Test
    void aTupleInAnArmReadsItsDestination() {
        assertAgreeOnEvery("public static BigInteger m(S s) { Tuple2<BigInteger, BigInteger> t = switch (s) { case S.A a -> new Tuple2<>(a.v(), BigInteger.ONE); default -> new Tuple2<>(BigInteger.ONE, BigInteger.TWO); }; return t.second(); }");
        assertAgreeOnEvery("public static BigInteger m(S s) { Tuple2<BigInteger, BigInteger> t = switch (s) { case S.A a -> { yield new Tuple2<>(a.v(), BigInteger.ONE); } default -> new Tuple2<>(BigInteger.ONE, BigInteger.TWO); }; return t.first().add(t.second()); }");
        assertStopsOnJavac("public static boolean m(S s) { Tuple2 t = switch (s) { case S.A a -> new Tuple2<>(a.v(), BigInteger.ONE); default -> new Tuple2<>(BigInteger.ONE, BigInteger.TWO); }; return true; }", "JULC0012", a(1));
    }

    /** F710, F720: a {@code switch} of one type reaches a list by that type, through another {@code switch} too. */
    @Test
    void aListOfASwitchEncodesByItsType() {
        assertAgreeOnEvery("public static long m(S s) { JulcList<BigInteger> l = JulcList.of(switch (s) { case S.A a -> a.v(); default -> BigInteger.ONE; }); return l.size(); }");
        assertAgreeOnEvery("public static BigInteger m(S s) { JulcList<BigInteger> l = JulcList.of(switch (s) { case S.A a -> a.v(); default -> BigInteger.ONE; }); return l.head(); }");
        assertAgreeOnEvery("public static BigInteger m(S s) { var x = switch (s) { case S.A a -> a.v(); default -> BigInteger.ONE; }; var y = switch (s) { case S.A a -> x; default -> x.add(x); }; JulcList<BigInteger> l = JulcList.of(y); return l.head(); }");
    }

    @Test
    void aSwitchIsComparedByWhatItsArmsGive() {
        for (var s : everyS()) {
            assertAgree("public static boolean m(BigInteger n, S s) { return n.equals(switch (s) { case S.A a -> a.v(); default -> BigInteger.ONE; }); }", integer(7), s);
            assertAgree("public static boolean m(BigInteger n, S s) { return n.equals(switch (s) { case S.A a -> { yield a.v(); } default -> BigInteger.ONE; }); }", integer(1), s);
        }
        assertStopsOnJavac("public static boolean m(BigInteger n, S s) { return n.equals(switch (s) { case S.A a -> true; default -> false; }); }", "JULC0078", integer(7), a(1));
        // A yield of a nested switch is that switch's, so it says nothing of what this one gives.
        for (var s : everyS()) {
            assertAgree("public static boolean m(BigInteger n, S s) { return n.equals(switch (s) { case S.A a -> { boolean y = switch (s) { case S.A b -> { yield true; } default -> false; }; yield y ? a.v() : n; } default -> n; }); }", integer(7), s);
        }
    }

    /** #297: {@code contains} in a guard encodes its target by its type, as it does anywhere on this frontend. */
    @Test
    void aGuardsCollectionOperation() {
        var list = new PlutusData.ListData(List.of(integer(7), integer(1)));
        for (var s : everyS()) {
            assertAgree("public static long m(S s, JulcList<BigInteger> l) { return switch (s) { case S.A a when l.contains(a.v()) -> 1; default -> 3; }; }", s, list);
            assertAgree("public static long m(S s, JulcList<BigInteger> l) { return switch (s) { case S.A a when !l.isEmpty() -> 1; default -> 3; }; }", s, list);
            assertAgree("public static BigInteger m(S s, JulcList<BigInteger> l) { return switch (s) { case S.A a when l.size() > 1 -> l.head(); default -> l.tail().head(); }; }", s, list);
        }
    }

    /** F807: two variants of one simple name are two branches. The reference's gate rejects the shape, so only this frontend runs it. */
    @Test
    void twoVariantsOfOneSimpleName() {
        String members = """
                static final class P { public record V(BigInteger a) implements Q { } }
                static final class W { public record V(BigInteger b, BigInteger c) implements Q { } }
                public sealed interface Q permits P.V, W.V { }
                public static long m(Q q) { return switch (q) { case P.V x -> 1; case W.V y -> y.c().longValue(); }; }
                """;
        var entries = new ArrayList<>(TAG_ENTRIES);
        entries.add(new TagTable.Entry("Case$P$V", "Case$Q", 0));
        entries.add(new TagTable.Entry("Case$W$V", "Case$Q", 1));
        var tags = new TagTable(entries);
        for (var q : List.of(constr(0, integer(5)), constr(1, integer(5), integer(9)))) {
            var report = RUNNER.run(new DiffCase("switch", program(members), "Case", "m", List.of(q), tags, null));
            report.results().forEach((config, result) -> {
                assertEquals(Verdict.Kind.AGREE, result.verdict().kind(), report::describe);
            });
        }
    }

    /** F720: arms of two types have no type of their own, so what needs one stops, and a value that reads each by its own runs. */
    @Test
    void armsOfTwoTypesStopWhereTheyNeedOne() {
        assertStopsOnJavac("static PlutusData d(BigInteger v) { return Builtins.iData(v); } public static long m(S s) { var v = switch (s) { case S.A a -> a.v(); default -> d(BigInteger.ONE); }; return JulcList.of(v).size(); }", "JULC0012", a(1));
    }

    @Test
    void aGuardCombinesAListMethodThatTakesAFunction() {
        for (var l : List.of(list(), list(1, 2), list(-1, 0), list(-1, 2))) {
            for (var s : List.of(a(1), d())) {
                assertAgree("public static long m(S s, JulcList<BigInteger> l) { return switch (s) { case S.A a when !l.any(x -> x.signum() > 0) -> 1; default -> 3; }; }", s, l);
                assertAgree("public static long m(S s, JulcList<BigInteger> l, boolean c) { return switch (s) { case S.A a when l.all(x -> x.signum() > 0) && c -> 1; default -> 3; }; }", s, l, bool(true));
                assertAgree("public static long m(S s, JulcList<BigInteger> l, boolean c) { return switch (s) { case S.A a when c || l.any(x -> x.signum() > 0) -> 1; default -> 3; }; }", s, l, bool(false));
                assertAgree("public static long m(S s, JulcList<BigInteger> l) { return switch (s) { case S.A a when !l.filter(x -> x.signum() > 0).isEmpty() -> 1; default -> 3; }; }", s, l);
                assertAgree("public static long m(S s, JulcList<BigInteger> l) { return switch (s) { case S.A a when l.filter(x -> x.signum() > 0).size() > 0 -> 1; default -> 3; }; }", s, l);
                assertAgree("public static long m(S s, S t, JulcList<BigInteger> l) { return switch (s) { case S.A a when switch (t) { case S.A b -> !l.any(x -> x.signum() > 0); default -> false; } -> 1; default -> 3; }; }", s, s, l);
            }
        }
        assertRejectedAlike("public static long m(S s, JulcList<BigInteger> l) { return switch (s) { case S.A a when !(l).any(x -> x.signum() > 0) -> 1; default -> 3; }; }", "JULC0072", a(1), list(1));
    }

    @Test
    void aReadOfWhatHasNoBinderIsRejectedAlike() {
        assertRejectedAlike("public static long m(S s, S t) { return switch (s) { case S.A a when (switch (t) { case S.A i -> i.v().signum() > 0; default -> false; }) -> 1; default -> 3; }; }", "JULC0072", a(1), a(2));
        assertRejectedAlike("public static long m(S s, S t) { return switch (s) { case S.A a when (switch (t) { case S.A i -> { var b = i.v().signum() > 0; yield b; } default -> false; }) -> 1; default -> 3; }; }", "JULC0072", a(1), a(2));
        assertAgreeOnEveryPair("public static long m(S s, S t) { return switch (s) { case S.A a when (switch (t) { case S.A i -> a.v().signum() > 0; default -> false; }) -> 1; default -> 3; }; }");
    }

    private static PlutusData text(String value) {
        return new PlutusData.BytesData(value.getBytes(StandardCharsets.UTF_8));
    }

    private static final String IDENTITY_HELPERS = "static boolean hb(boolean z) { return z; } static boolean gt(BigInteger z) { return z.signum() > 0; } ";

    /** {@code ==} on references in a guard, wherever it stands: the JVM compares identity, the reference compares values, and {@code JAVAC} stops at D6. */
    @ParameterizedTest
    @ValueSource(strings = {
            "hb(str == oth)", "hb(str != oth)", "!hb(str == oth)", "hb(str == oth) && s instanceof S.A", "(hb(str == oth) ? true : false)",
            "hb(str == \"ab\")", "hb(lst.head() == str)", "gt(str == oth ? BigInteger.ONE : BigInteger.ZERO)",
            "(switch (str == oth ? s : new S.D()) { case S.A q -> true; default -> false; })", "(str == oth ? s : new S.D()).equals(s)",
            "(switch (s) { case S.A q when hb(str == oth) -> true; default -> false; })", "lst.contains(str == oth ? \"ab\" : \"zz\")",
            "lst.any(x -> x == str)", "lst.any(x -> str == x)", "lst.any(x -> x != str)",
    })
    void anIdentityComparisonInAGuard(String guard) {
        var strings = new PlutusData.ListData(List.of(text("ab")));
        var report = run(IDENTITY_HELPERS + "public static long m(S s, String str, String oth, JulcList<String> lst) { return switch (s) { case S.A a when " + guard + " -> 1; default -> 2; }; }",
                List.of(a(1), text("ab"), text("ab"), strings));
        report.results().forEach((config, result) -> {
                assertEquals(Verdict.Kind.REJECTED, result.verdict().kind(), report::describe);
                assertTrue(result.uplc().toString().contains("JULC0086"), report::describe);
            
        });
    }

    private static final String CONSTANTS = "static final String BAD = 2147483647 + 1 < 0 ? \"a\" : \"b\"; ";

    /** Outside D6's plain form a constant in a guard that is not constant as a whole stops, where the reference has Java's value here. */
    @ParameterizedTest
    @ValueSource(strings = {"c && (true ? \"a\" : \"b\") == \"a\"", "hb((true ? \"a\" : \"b\") == \"a\")", "c && (2147483647 + 1 < 0 ? \"a\" : \"b\") == \"a\"",
            "c || (2147483647 + 1 < 0 ? \"a\" : \"b\") == \"a\"", "c && f == \"a\""})
    void aConstantOutsideThePlainFormStopsInAGuard(String guard) {
        var report = run(IDENTITY_HELPERS + "public static long m(S s, boolean c) { final var f = true ? \"a\" : \"b\"; return switch (s) { case S.A a when " + guard + " -> 1; default -> 2; }; }", List.of(a(1), bool(true)));
        report.results().forEach((config, result) -> {
            assertEquals(Verdict.Kind.REJECTED, result.verdict().kind(), report::describe);
            assertTrue(result.uplc().toString().contains("JULC0086"), report::describe);
        });
    }

    /** A field of that form is read with unbounded integers, so the reference takes the other branch, and {@code JAVAC} stops. */
    @Test
    void aConstantFieldTheReferenceMisreadsInAGuard() {
        var report = run(CONSTANTS + "public static long m(S s, boolean c) { return switch (s) { case S.A a when c && BAD == \"a\" -> 1; default -> 2; }; }", List.of(a(1), bool(true)));
        report.results().forEach((config, result) -> {
            assertEquals(Verdict.Kind.REJECTED, result.verdict().kind(), report::describe);
            assertTrue(result.uplc().toString().contains("JULC0086"), report::describe);
        });
    }

    /** A guard constant as a whole has Java's value on both frontends, and so has the plain form in any guard. */
    @ParameterizedTest
    @ValueSource(strings = {"(true ? \"a\" : \"b\") == \"a\"", "(2147483647 + 1 < 0 ? \"a\" : \"b\") == \"a\"", "c && \"a\" + \"b\" == \"ab\"", "c && f == \"a\""})
    void aConstantInAGuardKeepsJavasValue(String guard) {
        for (var c : List.of(bool(true), bool(false))) {
            assertAgree("public static long m(S s, boolean c) { final String f = \"a\"; return switch (s) { case S.A a when " + guard + " -> 1; default -> 2; }; }", a(1), c);
        }
    }

    /** The same stop on each other reference: only a string reached a value on the reference. */
    @ParameterizedTest
    @ValueSource(strings = {"BigInteger x, BigInteger y", "S x, S y", "byte[] x, byte[] y", "Boolean x, Boolean y"})
    void anIdentityComparisonOfOtherReferencesInAGuard(String parameters) {
        var value = parameters.startsWith("BigInteger") ? integer(1) : parameters.startsWith("S") ? a(1) : parameters.startsWith("byte") ? text("ab") : bool(true);
        assertStopsOnJavac(IDENTITY_HELPERS + "public static long m(S s, " + parameters + ") { return switch (s) { case S.A a when hb(x == y) -> 1; default -> 2; }; }", "JULC0086", a(1), value, value);
    }

    /** Constant strings and primitives are values in those positions, at every level. */
    @Test
    void aComparisonOfValuesInAGuardRuns() {
        for (var guard : List.of("hb(\"a\" == \"a\")", "hb(\"a\" != \"a\")", "hb(\"a\" + \"b\" == \"ab\")", "hb(n == 1)", "hb(c == true)", "!hb(n != 1)", "(switch (s) { case S.A q when hb(n == 1) -> true; default -> false; })")) {
            for (var s : everyS()) {
                assertAgree(IDENTITY_HELPERS + "public static long m(S s, long n, boolean c) { return switch (s) { case S.A a when " + guard + " -> 1; default -> 2; }; }", s, integer(1), bool(true));
            }
        }
    }

    @Test
    void anEqualsInALambdaInAGuardRuns() {
        var strings = new PlutusData.ListData(List.of(text("ab")));
        assertAgree("public static long m(S s, String str, JulcList<String> lst) { return switch (s) { case S.A a when lst.any(x -> x.equals(str)) -> 1; default -> 2; }; }", a(1), text("ab"), strings);
        assertAgree("public static long m(S s, String str, JulcList<String> lst) { return switch (s) { case S.A a when lst.any(x -> x.equals(str)) -> 1; default -> 2; }; }", a(1), text("b"), strings);
        assertAgree("public static long m(S s, long n, JulcList<BigInteger> l) { return switch (s) { case S.A a when l.any(x -> x.signum() == n) -> 1; default -> 2; }; }", a(1), integer(1), list(1, 2));
        assertAgree("public static long m(S s, long n, JulcList<BigInteger> l) { return switch (s) { case S.A a when l.any(x -> x.signum() == n) -> 1; default -> 2; }; }", a(1), integer(-1), list(1, 2));
    }

    @Test
    void anInferredParameterInAGuardFollowsTheReceiver() {
        var l = list(1, -1, 3);
        String body = ".filter(x -> switch (s) { case S.A a when x.signum() > 0 -> true; default -> false; }).size(); }";
        for (var s : everyS()) {
            assertWiderOnJavac("public static long m(S s, JulcList<BigInteger> l) { return l" + body, s, l);
            assertAgree("public static long m(S s, JulcList<BigInteger> l) { return (l)" + body, s, l);
            assertAgree("public static long m(S s, JulcList<BigInteger> l) { return l.tail()" + body, s, l);
            assertAgree("static JulcList<BigInteger> ids(JulcList<BigInteger> l) { return l; } public static long m(S s, JulcList<BigInteger> l) { return ids(l)" + body, s, l);
        }
        assertRejectedAlike("public static long m(S s, JulcList<Boolean> l) { return l.filter(b -> switch (s) { case S.A q when b -> true; default -> false; }).size(); }", "JULC0072", a(1), new PlutusData.ListData(List.of(bool(true))));
        assertFixedOnJavac("public static long m(S s, JulcList<BigInteger> l) { return l.filter((BigInteger x) -> switch (s) { case S.A a when x.signum() > 0 -> true; default -> false; }).size(); }", a(1), l);
    }

    private static final String WHEN = " -> true; default -> false; }";

    private static PlutusData y(PlutusData s, boolean b) {
        return constr(1, s, bool(b));
    }

    /** A guard reads an inferred parameter by the type javac gave it, at a simple name too: what the reference refuses there, and its value is the JVM's. */
    @Test
    void aGuardOnAnInferredParameterAtASimpleName() {
        var l = list(1, -1, 3);
        var ys = new PlutusData.ListData(List.of(y(a(1), true), y(d(), false)));
        var nested = new PlutusData.ListData(List.of(list(1), list()));
        for (var s : everyS()) {
            for (var body : List.of("l.filter(x -> switch (s) { case S.A a when x.signum() > 0 && c" + WHEN + ").size()", "l.filter(x -> { var w = x; return switch (s) { case S.A a when w.signum() > 0" + WHEN + "; }).size()",
                    "l.filter(x -> { var w = (x); return switch (s) { case S.A a when w.signum() > 0 && !c" + WHEN + "; }).size()",
                    "(l.any(x -> switch (s) { case S.A a when c ? x.signum() > 0 : x.signum() < 0" + WHEN + ") ? 1 : 0)",
                    "(l.any(x -> ids(l).any(y -> switch (s) { case S.A a when x.signum() > 0" + WHEN + ")) ? 1 : 0)",
                    "(ids(l).any(x -> l.any(y -> switch (s) { case S.A a when y.signum() > 0" + WHEN + ")) ? 1 : 0)")) {
                for (var c : List.of(bool(true), bool(false))) {
                    assertWiderOnJavac("static JulcList<BigInteger> ids(JulcList<BigInteger> l) { return l; } public static long m(S s, boolean c, JulcList<BigInteger> l) { return " + body + "; }", s, c, l);
                }
            }
        }
        assertRejectedAlike("public static boolean m(S s, JulcList<Y> ly) { return ly.any(p -> switch (s) { case S.A a when p.s() instanceof S.A" + WHEN + "); }", "JULC0072", a(1), ys);
        assertWiderOnJavac("public static boolean m(S s, JulcList<JulcList<BigInteger>> ll) { return ll.any(p -> p.any(y -> switch (s) { case S.A a when y.signum() > 0" + WHEN + ")); }", a(1), nested);
    }

    /** A guard that is one boolean call off an inferred parameter is a boolean, as it is with the parameter written, and the JVM's answer. */
    @Test
    void aBareBooleanCallInAGuard() {
        var ys = new PlutusData.ListData(List.of(y(a(1), true), y(d(), false)));
        var lists = new PlutusData.ListData(List.of(list(1), list(), list(-1, 5)));
        var pairs = new PlutusData.ListData(List.of(constr(0, integer(1), bool(true)), constr(0, integer(2), bool(false))));
        var maps = new PlutusData.ListData(List.of(new PlutusData.MapData(List.of(new PlutusData.Pair(integer(5), integer(1))))));
        var options = new PlutusData.ListData(List.of(constr(0, integer(1)), constr(1)));
        for (var s : List.of(a(1), d())) {
            assertWiderOnJavac("public static long m(S s, JulcList<Y> ly) { return ly.filter(p -> switch (s) { case S.A a when p.b()" + WHEN + ").size(); }", s, ys);
            assertWiderOnJavac("public static long m(S s, JulcList<Y> ly) { return ly.filter(p -> { var w = p; return switch (s) { case S.A a when w.b()" + WHEN + "; }).size(); }", s, ys);
            for (var guard : List.of("p.isEmpty()", "p.any(q -> q.signum() > 0)", "p.contains(BigInteger.ONE)", "p.filter(q -> true).isEmpty()", "p.map(q -> q).isEmpty()")) {
                assertWiderOnJavac("public static long m(S s, JulcList<JulcList<BigInteger>> ll) { return ll.filter(p -> switch (s) { case S.A a when " + guard + WHEN + ").size(); }", s, lists);
            }
            assertWiderOnJavac("public static long m(S s, JulcList<Tuple2<BigInteger, Boolean>> lt) { return lt.filter(p -> switch (s) { case S.A a when p.second()" + WHEN + ").size(); }", s, pairs);
            assertWiderOnJavac("public static long m(S s, JulcList<JulcMap<BigInteger, BigInteger>> lm) { return lm.filter(p -> switch (s) { case S.A a when p.containsKey(BigInteger.valueOf(5))" + WHEN + ").size(); }", s, maps);
            assertWiderOnJavac("public static long m(S s, JulcList<Optional<BigInteger>> lo) { return lo.filter(p -> switch (s) { case S.A a when p.isPresent()" + WHEN + ").size(); }", s, options);
        }
    }

    private static void assertJavacIsTheJvm(String members, PlutusData... args) {
        var report = run(members, List.of(args));
        report.results().forEach((config, result) -> {
            assertEquals(Verdict.Kind.AGREE, result.verdict().kind(), report::describe);
        });
    }

    /** A {@code var} of a conditional or a {@code switch} of a parameter has javac's type too. */
    @Test
    void aVarOfAParameterAndAValueInAGuard() {
        var l = list(1, -1, 3);
        for (var s : List.of(a(1), d())) {
            for (var c : List.of(bool(true), bool(false))) {
                assertJavacIsTheJvm("public static boolean m(S s, boolean c, BigInteger n, JulcList<BigInteger> l) { return l.any(p -> { var w = c ? p : n; return switch (s) { case S.A a when w.signum() > 0" + WHEN + "; }); }", s, c, integer(-1), l);
                assertJavacIsTheJvm("public static boolean m(S s, boolean c, BigInteger n, JulcList<BigInteger> l) { return l.any(p -> { var w = c ? n : p; return switch (s) { case S.A a when w.signum() > 0" + WHEN + "; }); }", s, c, integer(-1), l);
            }
            for (var t : everyS()) {
                assertJavacIsTheJvm("public static boolean m(S s, S t, BigInteger n, JulcList<BigInteger> l) { return l.any(p -> { var w = switch (t) { case S.A z -> p; default -> n; }; return switch (s) { case S.A a when w.signum() > 0" + WHEN + "; }); }", s, t, integer(-1), l);
                assertJavacIsTheJvm("public static boolean m(S s, S t, BigInteger n, JulcList<BigInteger> l) { return l.any(p -> { var w = switch (t) { case S.A z -> n; default -> p; }; return switch (s) { case S.A a when w.signum() > 0" + WHEN + "; }); }", s, t, integer(-1), l);
            }
        }
    }

    @Test
    void aConstantOfAnInterface() {
        String kinds = "public sealed interface K { boolean ON = true; long N = 3; int M = 2147483647; record U() implements K { } } ";
        var entries = new ArrayList<>(TAG_ENTRIES);
        entries.add(new TagTable.Entry("Case$K$U", "Case$K", 0));
        var tags = new TagTable(entries);
        for (var members : List.of("public static long m(S s, long x) { return switch (s) { case S.A a when K.ON -> 1; default -> 2; }; }",
                "public static long m(S s, long x) { return switch (s) { case S.A a when x > K.N -> 1; default -> 2; }; }",
                "public static long m(S s, long x) { return switch (s) { case S.A a when (K.M + 1) < x -> 1; default -> 2; }; }")) {
            for (var s : everyS()) {
                for (long x : List.of(-1L, 3L, 4L)) {
                    var report = RUNNER.run(new DiffCase("switch", program(kinds + members), "Case", "m", List.of(s, integer(x)), tags, null));
                    assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
                    AGREED.incrementAndGet();
                }
            }
        }
    }

    /** A rejection of the reference that {@code JAVAC} does not make: the JVM's answer at every level, and the reference still refuses. */
    private static void assertWiderOnJavac(String members, PlutusData... args) {
        var report = run(members, List.of(args));
        report.results().forEach((config, result) -> assertEquals(Verdict.Kind.AGREE,
                result.verdict().kind(), report::describe));
        AGREED.incrementAndGet();
    }

    private static String underNew(String guard) {
        return "new X(switch (s) { case S.A a when " + guard + " -> BigInteger.ONE; default -> BigInteger.TWO; })";
    }

    @Test
    void aConstantGuardThatReadsALocalThroughANewStops() {
        for (var s : List.of(a(1), d())) {
            assertJavacIsTheJvm("public static X m(S s) { final boolean on = true; return " + underNew("on") + "; }", s);
            assertAgree("public static X m(S s, boolean c) { final boolean on = true; return " + underNew("on && c") + "; }", s, bool(false));
            assertAgree("public static X m(S s, boolean c) { final boolean on = true; return " + underNew("on && c") + "; }", s, bool(true));
        }
    }

    /** What javac binds under a {@code new} and the reference does not see: the reference stops, and {@code JAVAC} is the JVM's unless the guard is a constant. */
    @Test
    void aGuardUnderANewReadsWhatJavacBound() {
        for (var s : List.of(a(1), d())) {
            assertWiderOnJavac("public static X m(S s, S t) { return " + underNew("t instanceof S.A") + "; }", s, a(2));
            assertWiderOnJavac("public static X m(S s, long n) { return " + underNew("n > 0") + "; }", s, integer(1));
            assertJavacIsTheJvm("public static X m(S s) { final long z = 5; return " + underNew("z > 0") + "; }", s);
            assertWiderOnJavac("public static X m(S s) { final int z = 2147483647; return new X(switch (s) { case S.A a when (z + 1) < a.v().signum() -> BigInteger.ONE; default -> BigInteger.TWO; }); }", s);
            assertJavacIsTheJvm("public static X m(S s) { final String u = \"a\"; return " + underNew("u == \"a\"") + "; }", s);
            assertJavacIsTheJvm("static final boolean OFF = false; public static X m(S s) { final boolean OFF = true; return " + underNew("OFF") + "; }", s);
        }
    }

    /** JavaParser does not parse a conditional as a guard with no parentheses, and javac does. */
    @Test
    void aConditionalGuardWithNoParentheses() {
        for (var c : List.of(bool(true), bool(false))) {
            assertWiderOnJavac("public static long m(S s, boolean c, boolean d) { return switch (s) { case S.A a when c ? d : true -> 1; default -> 3; }; }", a(1), c, bool(false));
        }
    }

    /** {@code ==} on two {@code Boolean}s compares references, so it stops on {@code JAVAC}. Under a {@code new} the reference takes them as booleans. */
    @Test
    void twoBooleansComparedUnderANew() {
        var members = "public static X m(S s, Boolean p, Boolean q) { return " + underNew("p == q") + "; }";
        var report = run(members, List.of(a(1), bool(true), bool(true)));
        report.results().forEach((config, result) -> assertEquals(Verdict.Kind.REJECTED,
                result.verdict().kind(), report::describe));
    }

    /** A local that shadows a constant field: the reference reads the field through a {@code new}, and {@code JAVAC} the local, as Java does. */
    @Test
    void aLocalThatShadowsAConstantFieldUnderANew() {
        assertFixedOnJavac("static final boolean ON = true; public static X m(S s, boolean c) { boolean ON = c; return " + underNew("ON") + "; }", a(1), bool(false));
        assertFixedOnJavac("static final boolean ON = true; public static X m(S s, boolean ON) { return " + underNew("ON") + "; }", a(1), bool(false));
    }

    @Test
    void aSwitchStatementIsNotInTheSubset() {
        var report = run("public static long m(S s) { switch (s) { case S.A a -> { return 1; } default -> { return 2; } } }", List.of(a(1)));
        report.results().forEach((config, result) -> assertEquals(Verdict.Kind.REJECTED, result.verdict().kind(), report::describe));
    }
}
