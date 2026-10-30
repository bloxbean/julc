package org.julclang.difftest;

import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The JVM side's isolation and bounds (ADR-063 H3, H5, H6). */
class JvmExecutorTest {

    private static DiffCase probe(String body, PlutusData... args) {
        return new DiffCase("probe", "public class Probe {\n" + body + "\n}\n", "Probe", "m", List.of(args));
    }

    private static PlutusData integer(long v) {
        return new PlutusData.IntData(BigInteger.valueOf(v));
    }

    @Test
    void everyEvaluationStartsFromTheFieldInitializers() {
        var executor = new JvmExecutor(5_000);
        var counter = probe("static long K = 0; public static long m(long n) { K = K + n; return K; }", integer(2));
        assertEquals(new Observation.Value(integer(2), List.of()), executor.run(counter));
        assertEquals(new Observation.Value(integer(2), List.of()), executor.run(counter));
    }

    @Test
    void exceptionsAreFailures() {
        var observation = new JvmExecutor(5_000).run(probe("public static long m(long n) { return 10 / n; }", integer(0)));
        var failure = assertInstanceOf(Observation.Failure.class, observation);
        assertTrue(failure.detail().contains("ArithmeticException"), failure.detail());
    }

    @Test
    void nonTerminationAndStackOverflowAreInconclusive() {
        var executor = new JvmExecutor(100);
        // A timed-out evaluation cannot be stopped, only abandoned, so this loop ends by itself
        // well after the timeout instead of spinning until the test JVM exits.
        assertInstanceOf(Observation.Inconclusive.class, executor.run(probe(
                "public static long m(long n) { long s = 0; while (n < 3000000000L) { s = s ^ n; n = n + 1; } return s; }",
                integer(1))));
        assertInstanceOf(Observation.Inconclusive.class,
                executor.run(probe("public static long m(long n) { return m(n + 1) + 1; }", integer(1))));
    }

    @Test
    void sourceThatJavacRejectsIsAHarnessError() {
        assertThrows(HarnessException.class,
                () -> new JvmExecutor(5_000).run(probe("public static long m(long n) { return undefined; }", integer(1))));
    }

    @Test
    void javacsDiagnosticsAreReported() {
        var e = assertThrows(HarnessException.class,
                () -> new JvmExecutor(5_000).run(probe("public static long m(long n) { return undefined; }", integer(1))));
        assertTrue(e.getMessage().contains("javac rejected the source") && e.getMessage().contains("undefined"), e.getMessage());
    }

    /** A sealed family declared beside the entry class, in the same file, used only inside {@code m}. */
    private static String siblings(String className) {
        return """
                import java.math.BigInteger;

                public class %1$s {
                    public static BigInteger m(BigInteger a) {
                        %1$sS s = a.signum() > 0 ? new %1$sA(a) : new %1$sB();
                        return s instanceof %1$sA x ? x.v() : BigInteger.ZERO;
                    }
                }

                sealed interface %1$sS permits %1$sB, %1$sA {}
                record %1$sA(BigInteger v) implements %1$sS {}
                record %1$sB() implements %1$sS {}
                """.formatted(className);
    }

    private static TagTable siblingTags(String className) {
        return TagTable.of(new TagTable.Entry(className + "A", className + "S", 1),
                new TagTable.Entry(className + "B", className + "S", 0));
    }

    /**
     * ADR-066 D3, review F51: source validation covers every class javac generates from the source, top-level
     * siblings of the entry class included, and runs on the JVM side itself (F48), not only in {@link DiffRunner}.
     */
    @Test
    void theTagTableIsValidatedAgainstEveryClassOfTheSource() {
        var executor = new JvmExecutor(5_000);
        var untagged = new DiffCase("siblings", siblings("Sib"), "Sib", "m", List.of(integer(3)));
        var e = assertThrows(HarnessException.class, () -> executor.run(untagged));
        assertTrue(e.getMessage().contains("No tag for SibA under SibS") || e.getMessage().contains("No tag for SibB under SibS"),
                e.getMessage());
        assertThrows(HarnessException.class, () -> DiffRunner.standard().run(untagged));
        var tagged = new DiffCase("siblings", siblings("Sib"), "Sib", "m", List.of(integer(3)), siblingTags("Sib"), null);
        assertEquals(new Observation.Value(integer(3), List.of()), executor.run(tagged));
        var partial = TagTable.of(new TagTable.Entry("SibB", "SibS", 0));
        assertThrows(HarnessException.class, () -> executor.run(new DiffCase("siblings", siblings("Sib"), "Sib", "m",
                List.of(integer(3)), partial, null)));
    }

    /** Sources compiled together keep their own classes: one source's siblings are not another's. */
    @Test
    void precompiledSourcesKeepTheirOwnClasses() {
        var executor = new JvmExecutor(5_000);
        var family = new DiffCase("family", siblings("Fam"), "Fam", "m", List.of(integer(-1)), siblingTags("Fam"), null);
        var plain = probe("public static long m(long n) { return n; }", integer(4));
        executor.precompile(List.of(family, plain));
        assertEquals(new Observation.Value(integer(0), List.of()), executor.run(family));
        assertEquals(new Observation.Value(integer(4), List.of()), executor.run(plain));
        assertThrows(HarnessException.class, () -> executor.run(new DiffCase("plain with tags", plain.source(), "Probe", "m",
                plain.args(), siblingTags("Fam"), null)), "a table may not name another source's classes");
    }

    @Test
    void argumentsMustMatchTheParameters() {
        assertThrows(HarnessException.class,
                () -> new JvmExecutor(5_000).run(probe("public static long m(long n) { return n; }")));
    }

    private static DiffCase traced(String body, PlutusData... args) {
        return new DiffCase("traced", "import org.julclang.core.PlutusData;\nimport org.julclang.stdlib.Builtins;\n"
                + "public class Probe {\n" + body + "\n}\n", "Probe", "m", List.of(args));
    }

    /** Each Builtins.trace call is one event with its message unchanged (ADR-063 H2). */
    @Test
    void traceMessagesKeepTheirLineBreaksAndPrefixes() {
        var observation = new JvmExecutor(5_000).run(traced("""
                public static PlutusData m(PlutusData n) {
                    return Builtins.trace("x\\r\\ny", Builtins.trace("", Builtins.trace("a\\n[TRACE] b", n)));
                }""", integer(1)));
        assertEquals(new Observation.Value(integer(1), List.of("a\n[TRACE] b", "", "x\r\ny")), observation);
    }

    @Test
    void tracesBeforeAFailureAreKept() {
        var observation = new JvmExecutor(5_000).run(traced("""
                public static long m(long n) {
                    Builtins.trace("before", new PlutusData.IntData(java.math.BigInteger.valueOf(n)));
                    return 10 / n;
                }""", integer(0)));
        var failure = assertInstanceOf(Observation.Failure.class, observation);
        assertEquals(List.of("before"), failure.traces());
    }

    /** Only Builtins.trace makes trace events; other output, even with the prefix, is not one. */
    @Test
    void otherOutputIsNotATrace() {
        var observation = new JvmExecutor(5_000).run(traced("""
                public static long m(long n) {
                    System.out.println("[TRACE] forged");
                    System.out.print("[TRACE] also forged\\n");
                    return n;
                }""", integer(1)));
        assertEquals(new Observation.Value(integer(1), List.of()), observation);
    }
}
