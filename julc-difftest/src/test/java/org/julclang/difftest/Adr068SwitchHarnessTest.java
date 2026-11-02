package org.julclang.difftest;

import org.julclang.compiler.CompilerFrontend;
import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

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

    private static final TagTable TAGS = new TagTable(List.of(
            new TagTable.Entry("Case$S$A", "Case$S", 0), new TagTable.Entry("Case$S$B", "Case$S", 1), new TagTable.Entry("Case$S$D", "Case$S", 2),
            new TagTable.Entry("Case$O$P", "Case$O", 0), new TagTable.Entry("Case$X", "Case$T", 0), new TagTable.Entry("Case$Y", "Case$T", 1)));

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
        return RUNNER.run(new DiffCase("switch", program(members), "Case", "m", args, TAGS, null));
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
    }

    private static void assertAgreeOnEvery(String members) {
        for (var s : everyS()) assertAgree(members, s);
    }

    private static void assertAgreeOnEveryPair(String members) {
        for (var s : everyS()) {
            for (var t : everyS()) assertAgree(members, s, t);
        }
    }

    /** The JVM's answer on {@code JAVAC}, and on the reference at least one level differs: a D13 semantic fix. */
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

    private static void assertStopsOnJavac(String members, String code, PlutusData... args) {
        var report = run(members, List.of(args));
        report.results().forEach((config, result) -> {
            if (config.frontend() == CompilerFrontend.JAVAC) {
                assertEquals(Verdict.Kind.REJECTED, result.verdict().kind(), report::describe);
                assertTrue(result.uplc().toString().contains(code), () -> code + " expected: " + report.describe());
            }
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
        assertStopsOnJavac("public static boolean m(BigInteger n, S s) { return n.equals(switch (s) { case S.A a -> true; default -> false; }); }", "JULC0084", integer(7), a(1));
    }
}
