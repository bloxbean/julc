package org.julclang.difftest;

import org.julclang.compiler.CompilerFrontend;
import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 D10 over M3b: calls and operators on the JVM and, through each frontend at every level, on the VM. The JVM is
 * the oracle, so every expected value, trace and failure here comes from neither frontend. Traced operands pin evaluation
 * order and short-circuiting: a program that evaluated in another order, or a side it should not, reports other traces
 * than the JVM's.
 */
class Adr068CallsHarnessTest {

    private static final DiffRunner RUNNER = new DiffRunner(configs(), 10_000);
    private static final List<long[]> ORDERS = List.of(new long[]{3, 1}, new long[]{2, 2}, new long[]{1, 3});

    private static List<DiffConfig> configs() {
        var configs = new ArrayList<>(DiffConfig.allLevels());
        configs.addAll(DiffConfig.allLevels(CompilerFrontend.JAVAC));
        return configs;
    }

    private static String program(String members) {
        return """
                import java.math.BigInteger;
                import java.util.Optional;
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcList;
                import org.julclang.core.types.JulcMap;
                import org.julclang.ledger.Interval;
                import org.julclang.ledger.Value;
                import org.julclang.stdlib.Builtins;

                public class Case {
                    static BigInteger said(String m, BigInteger v) { return Builtins.unIData(Builtins.trace(m, Builtins.iData(v))); }
                    static long saidL(String m, long v) { return Builtins.unIData(Builtins.trace(m, Builtins.iData(v))).longValue(); }
                    static BigInteger fail(BigInteger z) { return Builtins.unIData(Builtins.error()); }
                    %s
                }
                """.formatted(members);
    }

    private static PlutusData integer(long value) {
        return new PlutusData.IntData(BigInteger.valueOf(value));
    }

    private static PlutusData bool(boolean value) {
        return new PlutusData.ConstrData(value ? 1 : 0, List.of());
    }

    private static PlutusData string(String value) {
        return new PlutusData.BytesData(value.getBytes(StandardCharsets.UTF_8));
    }

    private static PlutusData bytes(int... values) {
        var bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) bytes[i] = (byte) values[i];
        return new PlutusData.BytesData(bytes);
    }

    private static PlutusData list(PlutusData... items) {
        return new PlutusData.ListData(List.of(items));
    }

    private static DiffRunner.Report run(String members, List<PlutusData> args) {
        return RUNNER.run(new DiffCase("m3b", program(members), "Case", "m", args));
    }

    /** Every frontend at every level agrees with the JVM, and the frontends with each other on value, cost and size. */
    private static void assertAgree(String members, PlutusData... args) {
        var report = run(members, List.of(args));
        assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
        assertEquals(8, report.results().size());
        var byLevel = report.results().entrySet().stream()
                .filter(e -> e.getKey().frontend() == CompilerFrontend.JAVAPARSER).toList();
        for (var entry : byLevel) {
            var javac = report.results().get(new DiffConfig(entry.getKey().level(), "Java", CompilerFrontend.JAVAC));
            assertEquals(entry.getValue().uplc(), javac.uplc(), "the frontends disagree at " + entry.getKey());
            assertEquals(entry.getValue().measurement(), javac.measurement(), "cost at " + entry.getKey());
        }
    }

    private static void assertTwoLongs(String body, long a, long b) {
        assertAgree("public static boolean m(long a, long b) { return " + body + "; }", integer(a), integer(b));
    }

    // --- Operators ---

    @ParameterizedTest
    @ValueSource(strings = {"a + b", "a - b", "a * b", "a / b", "a % b", "-a", "-(a + b)", "- -a", "a * b + a / b - a % b"})
    void arithmetic(String body) {
        for (long[] input : new long[][]{{7, 3}, {-7, 3}, {7, -3}, {-7, -3}, {0, 5}, {1L << 40, 3}}) {
            assertAgree("public static long m(long a, long b) { return " + body + "; }", integer(input[0]), integer(input[1]));
        }
    }

    /** Literals at the edges of their types: each negation here the JVM and the VM agree on. */
    @ParameterizedTest
    @ValueSource(strings = {"-2147483648", "-9223372036854775808L", "-0x7FFFFFFF", "-0x80000000L", "-(1)", "-(2147483647)"})
    void negatedLiterals(String body) {
        assertAgree("public static long m() { return " + body + "; }");
    }

    /** Where they do not agree the program stops, on javac: the reference's positive value is not the JVM's wrapped one. */
    @ParameterizedTest
    @ValueSource(strings = {"-0x80000000", "-(-2147483648)", "-0x8000000000000000L"})
    void theNegationOfAMinimumValueStops(String body) {
        assertStopsOnJavac("public static long m() { return " + body + "; }", "JULC0084");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a / b", "a % b"})
    void divisionByZeroFailsOnBothSides(String body) {
        assertAgree("public static long m(long a, long b) { return " + body + "; }", integer(7), integer(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a < b", "a <= b", "a > b", "a >= b", "a == b", "a != b", "a + 1 > b - 1", "(a > b) == (b > a)"})
    void comparisons(String body) {
        for (long[] input : ORDERS) assertTwoLongs(body, input[0], input[1]);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a == b", "a != b", "a && b", "a || b", "!a", "!a && !b", "a && b || !a", "a ? b : !b", "a == !b"})
    void booleans(String body) {
        for (boolean a : List.of(true, false)) {
            for (boolean b : List.of(true, false)) {
                assertAgree("public static boolean m(boolean a, boolean b) { return " + body + "; }", bool(a), bool(b));
            }
        }
    }

    @Test
    void stringConcatenation() {
        assertAgree("public static String m(String a, String b) { return a + b; }", string("ab"), string("cd"));
        assertAgree("public static String m(String a) { return a + \"-\"; }", string("x"));
        assertAgree("public static String m(String a) { return \"-\" + a; }", string("x"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"a\" == \"a\"", "\"a\" == \"b\"", "\"a\" != \"a\"", "\"a\" + \"b\" == \"ab\"", "\"ab\" != \"a\" + \"b\"",
            "A == \"x\"", "A + \"y\" == B", "B == A + \"y\""})
    void plainConstantStringsCompareAsJavaDoes(String body) {
        assertAgree("static final String A = \"x\"; static final String B = A + \"y\"; "
                + "public static boolean m() { return " + body + "; }");
    }

    @Test
    void conditional() {
        for (boolean c : List.of(true, false)) {
            assertAgree("public static long m(boolean c) { return c ? 1 : 2; }", bool(c));
            assertAgree("public static String m(boolean c) { return c ? \"t\" : \"f\"; }", bool(c));
            assertAgree("public static PlutusData m(boolean c, PlutusData d) { return c ? d : PlutusData.UNIT; }", bool(c), integer(5));
        }
    }

    // --- Evaluation order ---

    /** Each operator's left operand runs first: its trace comes first, and the JVM says so. */
    @ParameterizedTest
    @ValueSource(strings = {"+", "-", "*", "/", "%"})
    void arithmeticEvaluatesLeftThenRight(String op) {
        assertAgree("public static long m(long a, long b) { return saidL(\"L\", a) " + op + " saidL(\"R\", b); }", integer(7), integer(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<", "<=", ">", ">=", "==", "!="})
    void comparisonsEvaluateLeftThenRight(String op) {
        for (long[] input : ORDERS) {
            assertAgree("public static boolean m(long a, long b) { return saidL(\"L\", a) " + op + " saidL(\"R\", b); }",
                    integer(input[0]), integer(input[1]));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"<", "<=", ">", ">=", "==", "!="})
    void aFailingOperandFailsInOrder(String op) {
        assertAgree("public static boolean m(BigInteger a, BigInteger b) { return a.compareTo(fail(b)) " + op + " 0; }", integer(1), integer(2));
        assertAgree("public static boolean m(BigInteger a, BigInteger b) { return said(\"L\", a).compareTo(fail(b)) " + op + " 0; }", integer(1), integer(2));
        assertAgree("public static boolean m(BigInteger a, BigInteger b) { return fail(a).compareTo(said(\"R\", b)) " + op + " 0; }", integer(1), integer(2));
    }

    @Test
    void aFailingOperandOfAnOperatorFailsInOrder() {
        assertAgree("public static boolean m(long a, long b) { return saidL(\"L\", a) > fail(BigInteger.ONE).longValue(); }", integer(1), integer(2));
    }

    /** D6: {@code a.compareTo(b) OP 0} evaluates {@code a}, then {@code b}, once each, in all twelve forms. */
    @ParameterizedTest
    @ValueSource(strings = {"<", "<=", ">", ">=", "==", "!="})
    void compareToForms(String op) {
        for (long[] input : ORDERS) {
            for (String body : List.of("a.compareTo(b) " + op + " 0", "0 " + op + " a.compareTo(b)",
                    "said(\"L\", a).compareTo(said(\"R\", b)) " + op + " 0", "0 " + op + " said(\"L\", a).compareTo(said(\"R\", b))")) {
                assertAgree("public static boolean m(BigInteger a, BigInteger b) { return " + body + "; }", integer(input[0]), integer(input[1]));
            }
        }
    }

    /** Outside the rule the value is right, and the reference's lowering stays: the operands run more than once (T13). */
    @ParameterizedTest
    @ValueSource(strings = {"(a.compareTo(b)) > 0", "a.compareTo(b) > (0)", "a.compareTo(b) > 0L", "a.compareTo(b) > -0", "a.compareTo(b) == 1"})
    void formsOutsideTheRule(String body) {
        for (long[] input : ORDERS) {
            assertAgree("public static boolean m(BigInteger a, BigInteger b) { return " + body + "; }", integer(input[0]), integer(input[1]));
        }
    }

    /** Where a handler applies its receiver first, as Java does: the traces say so. */
    @ParameterizedTest
    @ValueSource(strings = {"add", "subtract", "multiply", "divide", "remainder", "mod", "equals"})
    void aReceiverRunsBeforeItsArgument(String method) {
        String result = method.equals("equals") ? "boolean" : "BigInteger";
        assertAgree("public static " + result + " m(BigInteger a, BigInteger b) { return said(\"R\", a)." + method + "(said(\"A\", b)); }",
                integer(7), integer(2));
    }

    @Test
    void aStaticCallsArgumentsRunLeftToRight() {
        assertAgree("public static boolean m(PlutusData a, PlutusData b) { return Builtins.equalsData(Builtins.trace(\"L\", a), Builtins.trace(\"R\", b)); }",
                integer(1), integer(2));
        assertAgree("public static BigInteger m(BigInteger a, BigInteger b) { return said(\"X\", a).add(said(\"Y\", b)).multiply(said(\"Z\", a)); }",
                integer(1), integer(2));
    }

    /** The frontends agree with each other and not with the JVM: the reference's shape, ported, and reported. */
    private static void assertBothDivergeAlike(String members, PlutusData... args) {
        var report = run(members, List.of(args));
        var observations = new HashSet<>();
        report.results().forEach((config, result) -> {
            assertEquals(Verdict.Kind.MISMATCH, result.verdict().kind(), report::describe);
            observations.add(result.uplc());
        });
        assertEquals(1, observations.size(), report::describe);
    }

    /** These apply the argument first, so its trace comes first. The result is Java's, since both run. */
    @Test
    void handlersThatRunTheirArgumentFirst() {
        String receiver = "(saidL(\"R\", 1) > 0 ? a : a)";
        assertBothDivergeAlike("public static boolean m(JulcList<BigInteger> a, BigInteger b) { return " + receiver + ".prepend(said(\"A\", b)).isEmpty(); }",
                list(), integer(1));
        assertBothDivergeAlike("public static boolean m(JulcList<BigInteger> a, BigInteger b) { return " + receiver + ".contains(said(\"A\", b)); }",
                list(), integer(1));
        assertBothDivergeAlike("public static boolean m(JulcMap<BigInteger, BigInteger> a, BigInteger b) { return "
                + receiver + ".insert(said(\"A\", b), b).isEmpty(); }", new PlutusData.MapData(List.of()), integer(1));
    }

    /** These run an operand twice on the branch that returns it, so its trace comes twice: T13's family. */
    @Test
    void handlersThatRunAnOperandTwice() {
        String method = "public static BigInteger m(BigInteger a, BigInteger b) { return ";
        assertBothDivergeAlike(method + "said(\"T\", a).abs(); }", integer(3), integer(1));
        assertBothDivergeAlike(method + "said(\"T\", a).max(b); }", integer(3), integer(1));
        assertBothDivergeAlike(method + "said(\"T\", a).min(b); }", integer(1), integer(3));
        assertBothDivergeAlike("public static int m(BigInteger a) { return said(\"T\", a).signum(); }", integer(3));
    }

    /**
     * The JVM and the reference disagree on these inputs, and the frontends agree with each other. Only a value at run time
     * shows it, so they are ported, not stopped; a fix goes in the shared handler. Each is reported in the M3b evidence.
     */
    @Test
    void handlersThatDisagreeWithTheJvmOnSomeInputs() {
        String method = "public static BigInteger m(BigInteger a, BigInteger b) { return a.mod(b); }";
        assertBothDivergeAlike(method, integer(7), integer(-3));
        assertBothDivergeAlike("public static long m(JulcList<BigInteger> a) { return a.take(-1).size(); }", list(integer(1), integer(2)));
        assertBothDivergeAlike("public static long m(JulcList<BigInteger> a) { return a.drop(-1).size(); }", list(integer(1), integer(2)));
        String lovelace = "public static BigInteger m(PlutusData a) { return Value.fromPlutusData(a).lovelaceOf(); }";
        var noAda = new PlutusData.MapData(List.of(new PlutusData.Pair(bytes(1),
                new PlutusData.MapData(List.of(new PlutusData.Pair(bytes(2), integer(7)))))));
        assertBothDivergeAlike(lovelace, noAda);
        assertBothDivergeAlike(lovelace, new PlutusData.MapData(List.of()));
    }

    // --- Short-circuiting ---

    @Test
    void theRightOfAndRunsOnlyWhenTheLeftIsTrue() {
        for (long a : List.of(-1L, 1L)) {
            assertAgree("public static boolean m(long a) { return saidL(\"L\", a) > 0 && saidL(\"R\", a) > 0; }", integer(a));
            assertAgree("public static boolean m(long a, BigInteger z) { return a > 0 && fail(z).signum() > 0; }", integer(-1), integer(a));
        }
    }

    @Test
    void theRightOfOrRunsOnlyWhenTheLeftIsFalse() {
        for (long a : List.of(-1L, 1L)) {
            assertAgree("public static boolean m(long a) { return saidL(\"L\", a) > 0 || saidL(\"R\", a) > 0; }", integer(a));
            assertAgree("public static boolean m(long a, BigInteger z) { return a > 0 || fail(z).signum() > 0; }", integer(1), integer(a));
        }
    }

    @Test
    void onlyTheTakenBranchOfAConditionalRuns() {
        for (long a : List.of(-1L, 1L)) {
            assertAgree("public static long m(long a) { return saidL(\"C\", a) > 0 ? saidL(\"T\", a) : saidL(\"F\", a); }", integer(a));
        }
        assertAgree("public static long m(long a, BigInteger z) { return a > 0 ? a : fail(z).longValue(); }", integer(1), integer(0));
        assertAgree("public static long m(long a, BigInteger z) { return a < 0 ? fail(z).longValue() : a; }", integer(1), integer(0));
    }

    /** {@code >} binds its left operand and no branch of it runs early. */
    @Test
    void shortCircuitSurvivesDsBinding() {
        assertAgree("public static boolean m(long a) { return a > 0 && saidL(\"L\", a) > saidL(\"R\", a); }", integer(-1));
        assertAgree("public static boolean m(long a) { return a > 0 && saidL(\"L\", a) > saidL(\"R\", a); }", integer(1));
    }

    // --- Calls ---

    @ParameterizedTest
    @ValueSource(strings = {"a.add(b)", "a.subtract(b)", "a.multiply(b)", "a.divide(b)", "a.remainder(b)", "a.abs()", "a.negate()",
            "a.max(b)", "a.min(b)", "a.add(b).multiply(b).abs()", "BigInteger.valueOf(a.longValue() + 1)"})
    void integerMethods(String body) {
        for (long[] input : new long[][]{{7, 3}, {-7, 3}, {7, -3}, {0, 5}}) {
            assertAgree("public static BigInteger m(BigInteger a, BigInteger b) { return " + body + "; }", integer(input[0]), integer(input[1]));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.signum()", "a.compareTo(b)", "a.intValue()"})
    void integerMethodsReturningInt(String body) {
        for (long[] input : ORDERS) {
            assertAgree("public static int m(BigInteger a, BigInteger b) { return " + body + "; }", integer(input[0]), integer(input[1]));
        }
    }

    @Test
    void equalsIsTheReceiversAcrossDeclaringClasses() {
        assertAgree("public static boolean m(BigInteger a, BigInteger b) { return a.equals(b); }", integer(1), integer(1));
        assertAgree("public static boolean m(BigInteger a, BigInteger b) { return a.equals(b); }", integer(1), integer(2));
        assertAgree("public static boolean m(String a, String b) { return a.equals(b); }", string("x"), string("x"));
        assertAgree("public static boolean m(String a, String b) { return a.equals(b); }", string("x"), string("y"));
        assertAgree("public static boolean m(PlutusData a, PlutusData b) { return a.equals(b); }", integer(1), integer(1));
        assertAgree("public static boolean m(PlutusData a, PlutusData b) { return a.equals(b); }", integer(1), bytes(1));
        assertAgree("public record R(BigInteger a) { } public static boolean m(R a, R b) { return a.equals(b); }",
                new PlutusData.ConstrData(0, List.of(integer(1))), new PlutusData.ConstrData(0, List.of(integer(1))));
        assertAgree("public record R(BigInteger a) { } public static boolean m(R a, R b) { return a.equals(b); }",
                new PlutusData.ConstrData(0, List.of(integer(1))), new PlutusData.ConstrData(0, List.of(integer(2))));
    }

    /** A stdlib call's receiver dispatches on what its term says, Data here, which is what makes this {@code EqualsData}. */
    @Test
    void equalsOnAStdlibCallIsData() {
        for (long b : List.of(1L, 2L)) {
            assertAgree("public static boolean m(BigInteger a, BigInteger b) { return Optional.of(a).equals(Optional.of(b)); }", integer(1), integer(b));
        }
    }

    @Test
    void equalsOnASealedInterfaceIsObjectEqualsAndValueEquality() {
        String members = "public sealed interface S { record A(BigInteger x) implements S { } record B() implements S { } }"
                + " public static boolean m(S a, S b) { return a.equals(b); }";
        var a = new PlutusData.ConstrData(0, List.of(integer(1)));
        var tags = new TagTable(List.of(new TagTable.Entry("Case$S$A", "Case$S", 0), new TagTable.Entry("Case$S$B", "Case$S", 1)));
        for (var other : List.of(a, new PlutusData.ConstrData(1, List.of()))) {
            var report = RUNNER.run(new DiffCase("m3b", program(members), "Case", "m", List.of(a, other), tags, null));
            assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
        }
    }

    @Test
    void listMethods() {
        var values = list(integer(4), integer(5), integer(6));
        String type = "JulcList<BigInteger>";
        for (String body : List.of("a.size()", "a.tail().size()", "a.take(2).size()", "a.drop(2).size()", "a.reverse().size()", "a.concat(a).size()")) {
            assertAgree("public static long m(" + type + " a) { return " + body + "; }", values);
        }
        for (String body : List.of("a.head()", "a.get(1)", "a.tail().head()", "a.reverse().head()", "a.prepend(BigInteger.ONE).head()",
                "a.drop(1).head()", "a.concat(a).get(4)")) {
            assertAgree("public static BigInteger m(" + type + " a) { return " + body + "; }", values);
        }
        for (String body : List.of("a.isEmpty()", "a.contains(BigInteger.valueOf(5))", "a.contains(BigInteger.TEN)", "a.tail().tail().tail().isEmpty()")) {
            assertAgree("public static boolean m(" + type + " a) { return " + body + "; }", values);
        }
    }

    @Test
    void listOfBytesAndOfData() {
        assertAgree("public static boolean m(JulcList<byte[]> a, byte[] x) { return a.contains(x); }", list(bytes(1), bytes(2)), bytes(2));
        assertAgree("public static boolean m(JulcList<byte[]> a, byte[] x) { return a.contains(x); }", list(bytes(1), bytes(2)), bytes(3));
        assertAgree("public static boolean m(JulcList<PlutusData> a, PlutusData x) { return a.contains(x); }", list(integer(1)), integer(1));
    }

    @Test
    void mapMethods() {
        var entries = new PlutusData.MapData(List.of(new PlutusData.Pair(integer(1), integer(10)), new PlutusData.Pair(integer(2), integer(20))));
        String type = "JulcMap<BigInteger, BigInteger>";
        assertAgree("public static long m(" + type + " a) { return a.size(); }", entries);
        assertAgree("public static boolean m(" + type + " a) { return a.isEmpty(); }", entries);
        assertAgree("public static BigInteger m(" + type + " a) { return a.get(BigInteger.TWO); }", entries);
        assertAgree("public static boolean m(" + type + " a) { return a.containsKey(BigInteger.TWO); }", entries);
        assertAgree("public static boolean m(" + type + " a) { return a.containsKey(BigInteger.TEN); }", entries);
        assertAgree("public static boolean m(" + type + " a) { return a.lookup(BigInteger.TEN).isPresent(); }", entries);
        assertAgree("public static BigInteger m(" + type + " a) { return a.lookup(BigInteger.ONE).get(); }", entries);
        assertAgree("public static long m(" + type + " a) { return a.delete(BigInteger.ONE).size(); }", entries);
        assertAgree("public static BigInteger m(" + type + " a) { return a.keys().head(); }", entries);
        assertAgree("public static BigInteger m(" + type + " a) { return a.values().tail().head(); }", entries);
        assertAgree("public static BigInteger m(" + type + " a) { return a.insert(BigInteger.TEN, BigInteger.ONE).get(BigInteger.TEN); }", entries);
    }

    @Test
    void optionalMethods() {
        var present = new PlutusData.ConstrData(0, List.of(integer(5)));
        var absent = new PlutusData.ConstrData(1, List.of());
        String type = "Optional<BigInteger>";
        for (var value : List.of(present, absent)) {
            assertAgree("public static boolean m(" + type + " a) { return a.isPresent(); }", value);
            assertAgree("public static boolean m(" + type + " a) { return a.isEmpty(); }", value);
        }
        assertAgree("public static BigInteger m(" + type + " a) { return a.get(); }", present);
    }

    @Test
    void stdlibCalls() {
        assertAgree("public static long m(long a) { return Math.abs(a); }", integer(-5));
        assertAgree("public static long m(long a, long b) { return Math.max(a, b); }", integer(-5), integer(2));
        assertAgree("public static long m(long a, long b) { return Math.min(a, b); }", integer(-5), integer(2));
        assertAgree("public static long m(long a, long b) { return Math.floorDiv(a, b); }", integer(-7), integer(2));
        assertAgree("public static long m(long a, long b) { return Math.floorMod(a, b); }", integer(-7), integer(2));
        assertAgree("public static BigInteger m(PlutusData a) { return Builtins.unIData(a); }", integer(5));
        assertAgree("public static PlutusData m(BigInteger a) { return Builtins.iData(a); }", integer(5));
        assertAgree("public static byte[] m(byte[] a, byte[] b) { return Builtins.concat(a, b, a); }", bytes(1), bytes(2));
        assertAgree("public static boolean m(PlutusData a, PlutusData b) { return Builtins.equalsData(a, b); }", integer(1), integer(1));
        assertAgree("public static boolean m(BigInteger a) { return JulcList.of(a).isEmpty(); }", integer(1));
        assertAgree("public static boolean m() { return JulcList.empty().isEmpty(); }");
        assertAgree("public static Optional<BigInteger> m(BigInteger a) { return Optional.of(a); }", integer(1));
        assertAgree("public static Optional<BigInteger> m() { return Optional.empty(); }");
    }

    /** I2: the reference does not know a stdlib method's return type, so javac's knowing it widens nothing. */
    @Test
    void aMemberOfAStdlibCallStaysRejected() {
        for (var report : List.of(run("public static boolean m(BigInteger a) { return Optional.of(a).isPresent(); }", List.of(integer(1))),
                run("public static boolean m() { return Interval.always().from().isInclusive(); }", List.of()))) {
            report.results().forEach((config, result) -> {
                assertEquals(Verdict.Kind.REJECTED, result.verdict().kind(), report::describe);
                assertTrue(result.uplc().toString().contains("JULC0055"), report::describe);
            });
        }
    }

    /**
     * javac says Data, and the term is a list or a map on the stack, which only a re-encoding makes Data again: what
     * {@code Typing} reads from the term, as the reference does. Without it {@code MkCons} meets a list.
     */
    @Test
    void aDataTypeDoesNotSayWhatABuiltinLeft() {
        String method = "public static PlutusData m(JulcList<PlutusData> l, PlutusData d) { return ";
        assertAgree(method + "l.prepend(Builtins.unListData(d)).head(); }", list(), list(integer(1)));
        assertAgree(method + "l.prepend(Builtins.unMapData(d)).head(); }", list(),
                new PlutusData.MapData(List.of(new PlutusData.Pair(integer(1), integer(2)))));
    }

    @Test
    void aFailureFromBuiltinsFails() {
        assertAgree("public static PlutusData m() { return Builtins.error(); }");
    }

    @Test
    void localCalls() {
        assertAgree("static long twice(long a) { return a * 2; } public static long m(long a) { return twice(twice(a)) + 1; }", integer(3));
        assertAgree("static long second(long a, long b) { return b; } public static long m(long a, long b) { return second(a, b); }", integer(1), integer(2));
        assertAgree("static long sum(long n) { return n <= 0 ? 0 : n + sum(n - 1); } public static long m(long a) { return sum(a); }", integer(4));
        assertAgree("static boolean even(long n) { return n == 0 || odd(n - 1); } static boolean odd(long n) { return n != 0 && even(n - 1); }"
                + " public static boolean m(long a) { return even(a); }", integer(4));
        assertAgree("static long k() { return 3; } public static long m() { return k() + k(); }");
        assertAgree("static long second(long a, long b) { return b; } public static long m(long a, long b) { return second(saidL(\"L\", a), saidL(\"R\", b)); }",
                integer(1), integer(2));
    }

    /**
     * A helper with no parameter is a {@code let}, bound in the assembly both frontends share, so its body runs once and
     * first: traced twice in Java it traces once, and it runs whether the branch that calls it does or not.
     */
    @Test
    void aHelperWithNoParameterRunsOnceAndFirst() {
        assertBothDivergeAlike("static long k() { return saidL(\"K\", 1); } public static long m() { return k() + k(); }");
        assertBothDivergeAlike("static long k() { return saidL(\"K\", 1); } public static long m(boolean c) { return c ? k() : 2; }", bool(false));
        assertBothDivergeAlike("static long k() { return fail(BigInteger.ONE).longValue(); } public static long m(boolean c) { return c ? k() : 2; }", bool(false));
    }

    /** Overloads share a binder and must be interchangeable; javac's choice among them cannot change the result. */
    @Test
    void sameLoweringOverloads() {
        assertAgree("static long same(long a) { return a; } static long same(int a) { return a; } public static long m(long a) { return same(a); }", integer(3));
    }

    @Test
    void recordAccessors() {
        String members = "public record Inner(BigInteger x, String s) { } public record Outer(Inner i, boolean f, byte[] b, JulcList<BigInteger> l) { }";
        var inner = new PlutusData.ConstrData(0, List.of(integer(5), string("t")));
        var outer = new PlutusData.ConstrData(0, List.of(inner, bool(true), bytes(9), list(integer(1), integer(2))));
        assertAgree(members + " public static BigInteger m(Outer o) { return o.i().x(); }", outer);
        assertAgree(members + " public static String m(Outer o) { return o.i().s(); }", outer);
        assertAgree(members + " public static boolean m(Outer o) { return o.f(); }", outer);
        assertAgree(members + " public static byte[] m(Outer o) { return o.b(); }", outer);
        assertAgree(members + " public static BigInteger m(Outer o) { return o.l().tail().head().add(o.i().x()); }", outer);
    }

    // --- Where javac's types are right and the reference is not (D13, semantic fix) ---

    private static void assertFixedOnJavac(String members, PlutusData... args) {
        var report = run(members, List.of(args));
        boolean referenceDiverges = false;
        for (var entry : report.results().entrySet()) {
            if (entry.getKey().frontend() == CompilerFrontend.JAVAC) {
                assertEquals(Verdict.Kind.AGREE, entry.getValue().verdict().kind(), report::describe);
            } else {
                referenceDiverges |= entry.getValue().verdict().kind() != Verdict.Kind.AGREE;
            }
        }
        assertTrue(referenceDiverges, "the reference no longer diverges, so this is no fix: " + report.describe());
    }

    /** #297 (D6): the target is encoded by its static type. */
    @Test
    void nestedCollectionContains() {
        var inner = list(integer(1), integer(2));
        assertFixedOnJavac("public static boolean m(JulcList<JulcList<BigInteger>> a, JulcList<BigInteger> x) { return a.contains(x); }", list(inner), inner);
        assertFixedOnJavac("public static boolean m(JulcList<JulcList<BigInteger>> a, JulcList<BigInteger> x) { return a.contains(x); }", list(inner), list(integer(3)));
        var bytes = list(bytes(1), bytes(2));
        assertFixedOnJavac("public static boolean m(JulcList<JulcList<byte[]>> a, JulcList<byte[]> x) { return a.contains(x); }", list(bytes), bytes);
        var map = new PlutusData.MapData(List.of(new PlutusData.Pair(integer(1), integer(2))));
        assertFixedOnJavac("public static boolean m(JulcList<JulcMap<BigInteger, BigInteger>> a, JulcMap<BigInteger, BigInteger> x) { return a.contains(x); }", list(map), map);
    }

    @Test
    void containsOnStrings() {
        assertFixedOnJavac("public static boolean m(JulcList<String> a, String x) { return a.contains(x); }", list(string("a")), string("a"));
        assertFixedOnJavac("public static boolean m(JulcList<String> a, String x) { return a.contains(x); }", list(string("a")), string("b"));
    }

    @Test
    void aListLiteralIsTypedByItsElements() {
        assertFixedOnJavac("public static BigInteger m() { return JulcList.of(BigInteger.ONE, BigInteger.TWO).head(); }");
        assertFixedOnJavac("public static boolean m() { return JulcList.of(BigInteger.ONE, BigInteger.TWO).contains(BigInteger.TWO); }");
        assertFixedOnJavac("public static BigInteger m() { return JulcList.of(BigInteger.ONE).head().add(BigInteger.ONE); }");
        assertFixedOnJavac("public static long m() { return JulcList.of(1, 2).head() + 1; }");
        assertFixedOnJavac("public static boolean m() { return JulcList.of(1, 2).contains(2); }");
    }

    // --- What stops rather than diverges ---

    private static void assertStopsOnJavac(String members, String code, PlutusData... args) {
        var report = run(members, List.of(args));
        report.results().forEach((config, result) -> {
            if (config.frontend() == CompilerFrontend.JAVAC) {
                assertEquals(Verdict.Kind.REJECTED, result.verdict().kind(), report::describe);
                assertTrue(result.uplc().toString().contains(code), () -> code + " expected: " + report.describe());
            }
        });
    }

    @Test
    void referenceEqualityIsRejectedWhereTheReferenceSaysTrue() {
        assertStopsOnJavac("public static boolean m(BigInteger a, BigInteger b) { return a == b; }", "JULC0086", integer(1), integer(1));
        assertStopsOnJavac("public static boolean m(byte[] a, byte[] b) { return a.equals(b); }", "JULC0087", bytes(1), bytes(1));
        assertStopsOnJavac("public static String m(String a) { return a + 1; }", "JULC0090", string("x"));
        assertStopsOnJavac("public static boolean m(BigInteger a, PlutusData d) { return a.equals(d); }", "JULC0084", integer(1), integer(1));
    }

    @Test
    void aDeclaredAccessorIsNotAComponent() {
        assertStopsOnJavac("public record R(BigInteger a) { public BigInteger a() { return BigInteger.ONE; } } public static BigInteger m(R r) { return r.a(); }",
                "JULC0084", new PlutusData.ConstrData(0, List.of(integer(7))));
    }
}
