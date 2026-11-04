package org.julclang.difftest;

import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-068 D10 on what the javac frontend covers: each program runs on the JVM and, through each frontend at every level,
 * on the VM. The JVM is the oracle, so a value here comes from neither frontend. A verdict is per frontend, and a
 * difference between the frontends is reported even where both agree with the JVM.
 */
class Adr068LoweringHarnessTest {

    private static final DiffRunner RUNNER = new DiffRunner(configs(), 10_000);

    private static List<DiffConfig> configs() {
        var configs = new ArrayList<>(DiffConfig.allLevels());
        return configs;
    }

    private static String program(String members) {
        return """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;

                public class Case {
                    %s
                }
                """.formatted(members);
    }

    private static PlutusData integer(long value) {
        return new PlutusData.IntData(BigInteger.valueOf(value));
    }

    private static DiffRunner.Report run(String members, List<PlutusData> args) {
        return RUNNER.run(new DiffCase("m3a", program(members), "Case", "m", args));
    }

    private static void assertAgree(String members, List<PlutusData> args) {
        var report = run(members, args);
        assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
        assertEquals(4, report.results().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "5", "2147483647", "0x7FFFFFFF", "0xFFFFFFFF", "0x80000000", "017", "0b101", "1_000",
            "-5", "- 5", "-1", "-2147483648", "-/*c*/5", "(-7)", "((7))", "\\u002d5"})
    void intLiterals(String literal) {
        assertAgree("public static int m() { return " + literal + "; }", List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0L", "5L", "0xFFFFFFFFFFFFFFFFL", "9223372036854775807L", "-9223372036854775808L", "0x8000000000000000L"})
    void longLiterals(String literal) {
        assertAgree("public static long m() { return " + literal + "; }", List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    void booleans(String literal) {
        assertAgree("public static boolean m() { return " + literal + "; }", List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "\" padded \"", "\"a\\tb\\n\"", "\"q\\\"\\\\\"", "\"\\101\\s\\7\"", "\"\u00e9\"", "\"\\uD83D\\uDE00\"",
            "\"\\u0041\""})
    void strings(String literal) {
        assertAgree("public static String m() { return " + literal + "; }", List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"new byte[]{}", "new byte[]{1, 2, 3}", "new byte[]{-1, 0x7F, -128, 127, -0x10, -0}"})
    void byteArrays(String literal) {
        assertAgree("public static byte[] m() { return " + literal + "; }", List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"BigInteger.ZERO", "BigInteger.ONE", "BigInteger.TWO", "BigInteger.TEN"})
    void apiConstants(String constant) {
        assertAgree("public static BigInteger m() { return " + constant + "; }", List.of());
    }

    @Test
    void parametersInOrder() {
        assertAgree("public static BigInteger m(BigInteger a, BigInteger b) { return a; }", List.of(integer(1), integer(2)));
        assertAgree("public static BigInteger m(BigInteger a, BigInteger b) { return b; }", List.of(integer(1), integer(2)));
        assertAgree("public static boolean m(boolean a) { return a; }",
                List.of(new PlutusData.ConstrData(1, List.of())));
        assertAgree("public static byte[] m(byte[] a) { return a; }",
                List.of(new PlutusData.BytesData("hi".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void staticConstants() {
        assertAgree("static final BigInteger K = BigInteger.TEN; public static BigInteger m() { return K; }", List.of());
        assertAgree("static BigInteger K = BigInteger.TWO; public static BigInteger m() { return K; }", List.of());
        assertAgree("static final int A = 3; static final int B = A; public static int m() { return B; }", List.of());
        assertAgree("static final int A = 3, B = 4; public static int m() { return B; }", List.of());
        assertAgree("static final byte[] B = new byte[]{9}; public static byte[] m() { return B; }", List.of());
    }

    /** I3 in the VM: the parameter, not the static of its name. The reference keeps its own name, this a marked one. */
    @Test
    void aParameterShadowsAStatic() {
        assertAgree("static final BigInteger K = BigInteger.ONE; public static BigInteger m(BigInteger K) { return K; }",
                List.of(integer(42)));
    }

    @Test
    void aStaticNotShadowedIsTheStatic() {
        assertAgree("static final BigInteger K = BigInteger.TEN; public static BigInteger m(BigInteger L) { return K; }",
                List.of(integer(42)));
    }

    @Test
    void aHelperDoesNotChangeTheResult() {
        assertAgree("static BigInteger unused(BigInteger x) { return x; } public static BigInteger m() { return BigInteger.ONE; }", List.of());
    }

    @Test
    void aVoidHelperIsHarmless() {
        assertAgree("static void unused() { } public static boolean m() { return true; }", List.of());
    }

    /**
     * D13's "accepted by decision": the ADR-065 I2c escape shapes M0 scheduled for acceptance, exactly as the corpus holds
     * them. The reference rejects each while reading it, so only the JVM can say what they mean, and javac's reading is the
     * program's on both sides.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "public class Case { public static String m() { return \"\\u\uFF10\uFF10\uFF14\uFF11\"; } }",
            "public class Case { public static String m() { return \"\\u\u0661\u0662\u0663\u0664\"; } }",
            "public class Case { public static String m() { return \"\\u005c\\\\\\\\\\u0030\"; } }",
            "public class Case { public static String m() { return \"\\u005c\\\\\\u006e\"; } }",
            "public class Case { public static String m() { return \"\\u005c\\\\\\u005cn\"; } }",
            "public class Case { public static String m() { return \"\\u005c\\\\\\u0030\"; } }"})
    void anEscapeShapeAcceptedByDecisionAgreesWithTheJvm(String source) {
        assertAcceptedByDecision(source, List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "import java.math.BigInteger;\npublic class Case { public static BigInteger m(BigInteger a) {\n    return a; // \\u\u0661\u0662\u0663\u0664\n} }\n",
            "import java.math.BigInteger;\npublic class Case { public static BigInteger m(BigInteger a) {\n    return a // \\u005c\\\\\\u000a .add(a)\n    ;\n} }\n"})
    void anEscapeShapeInACommentAgreesWithTheJvm(String source) {
        assertAcceptedByDecision(source, List.of(integer(3)));
    }

    /** A lone surrogate in a comment cannot reach a script, so javac's reading of the rest is all there is to compare. */
    @ParameterizedTest
    @ValueSource(strings = {
            "import java.math.BigInteger;\npublic class Case { public static BigInteger m(BigInteger a) {\n    return a; // \\uD800\n} }\n",
            "import java.math.BigInteger;\npublic class Case { public static BigInteger m(BigInteger a) {\n    return a // \\uD800\\\\\\u000a .add(a)\n    ;\n} }\n"})
    void aLoneSurrogateInACommentAgreesWithTheJvm(String source) {
        assertAcceptedByDecision(source, List.of(integer(3)));
    }

    private static void assertAcceptedByDecision(String source, List<PlutusData> args) {
        var report = RUNNER.run(new DiffCase("m3a", source, "Case", "m", args));
        report.results().forEach((config, result) -> assertEquals(
                Verdict.Kind.AGREE,
                result.verdict().kind(), report::describe));
    }

    /** A program outside the slice is rejected on JAVAC, not compiled some other way: its verdict says so (I8). */
    @Test
    void aProgramNotPortedIsRejectedHereAndAgreesThere() {
        var report = run("public static BigInteger m(BigInteger a) { return BigInteger.valueOf((short) a.longValue()); }", List.of(integer(2)));
        report.results().forEach((config, result) -> {
                assertEquals(Verdict.Kind.REJECTED, result.verdict().kind(), report.describe());
                assertEquals("JULC0124", assertInstanceOf(Observation.Rejected.class, result.uplc()).code());
            
        });
    }

}
