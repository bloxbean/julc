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
