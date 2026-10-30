package org.julclang.difftest;

import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
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
        // "cannot find symbol" appears only in javac's diagnostic, not in the source the message echoes.
        assertTrue(e.getMessage().contains("javac rejected the source") && e.getMessage().contains("cannot find symbol"), e.getMessage());
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

    /**
     * javac 21.0.3 and 25.0.2 accept this program and emit a class the JVM's verifier rejects (a pattern switch used as
     * an {@code if} test, then a pattern switch passed as an argument): an oracle fault, not an observation (ADR-063
     * H6, ADR-066 M3). G1 keeps switches out of tests. If a later javac emits a valid class, this case runs, and the
     * test needs another class the JVM rejects.
     */
    @Test
    void aClassTheJvmRejectsIsAHarnessError() {
        String source = """
                import java.math.BigInteger;

                public class Probe {
                    sealed interface S0 permits C0, B0, A0 {}
                    record C0(BigInteger w) implements S0 {}
                    record B0() implements S0 {}
                    record A0(boolean x) implements S0 {}
                    static final S0 x = new C0(BigInteger.ONE);

                    public static boolean m(boolean c) {
                        if (switch (x) { case A0 f1 -> f1.x(); default -> c; }) { return c; }
                        return x.equals(switch (x) { case A0 p -> new B0(); default -> new B0(); });
                    }
                }
                """;
        var tags = TagTable.of(new TagTable.Entry("Probe$C0", "Probe$S0", 0), new TagTable.Entry("Probe$B0", "Probe$S0", 1),
                new TagTable.Entry("Probe$A0", "Probe$S0", 2));
        var e = assertThrows(HarnessException.class, () -> new JvmExecutor(5_000).run(new DiffCase("verify", source, "Probe",
                "m", List.of(new PlutusData.ConstrData(0, List.of())), tags, null)));
        assertTrue(e.getMessage().contains("the JVM rejects javac's class") && e.getMessage().contains("VerifyError"),
                e.getMessage());
        // Raised while linking, before any initializer or the method runs.
        assertTrue(Arrays.stream(e.getCause().getStackTrace()).anyMatch(frame -> frame.getMethodName().equals("link")
                && frame.getClassName().equals(JvmExecutor.class.getName())), "raised while linking");
    }

    private static final String INITIALIZERS = """
            import java.math.BigInteger;
            import org.julclang.stdlib.Builtins;

            public class Probe {
                static final BigInteger T = Builtins.unIData(Builtins.trace("init", Builtins.iData(BigInteger.ONE)));
                static final BigInteger F = %s;

                public static BigInteger m(BigInteger a) {
                    return a.add(F);
                }
            }
            """;

    /**
     * Initialization is part of the run: a static initializer that throws is the program's failure, with the traces
     * before it, not a class the JVM rejects (review of M3 part 1, F74, F95).
     */
    @Test
    void aFailingStaticInitializerIsAFailure() {
        for (String init : List.of("Builtins.unIData(Builtins.error())", "BigInteger.ONE.divide(BigInteger.ZERO)")) {
            var diffCase = new DiffCase("initializer", INITIALIZERS.formatted(init), "Probe", "m", List.of(integer(1)));
            var failure = assertInstanceOf(Observation.Failure.class, new JvmExecutor(5_000).run(diffCase), init);
            assertEquals(List.of("init"), failure.traces(), init);
            assertTrue(!init.contains("divide") || failure.detail().contains("ArithmeticException"), failure.detail());
            var report = DiffRunner.standard().run(diffCase);
            assertTrue(report.all(Verdict.Kind.AGREE), report.describe());
        }
    }

    /** So is a member class's failing initializer, met when the method runs, and a later use of that class. */
    @Test
    void aMemberClassWhoseInitializerFailsIsAFailure() {
        var diffCase = new DiffCase("member initializer", """
                import java.math.BigInteger;

                public class Probe {
                    record R(BigInteger v) {
                        static final BigInteger Z = BigInteger.ONE.divide(BigInteger.ZERO);
                    }

                    public static BigInteger m(BigInteger a) {
                        try {
                            return R.Z;
                        } catch (ExceptionInInitializerError e) {
                            return R.Z.add(a);
                        }
                    }
                }
                """, "Probe", "m", List.of(integer(1)));
        var failure = assertInstanceOf(Observation.Failure.class, new JvmExecutor(5_000).run(diffCase));
        assertTrue(failure.detail().contains("ArithmeticException"), failure.detail());
    }

    private static DiffCase thrown(String members, String body) {
        return new DiffCase("thrown", """
                import java.math.BigInteger;
                import org.julclang.stdlib.Builtins;

                public class Probe {
                    static final BigInteger T = Builtins.unIData(Builtins.trace("init", Builtins.iData(BigInteger.ONE)));
                %s

                    public static BigInteger m(BigInteger a) {
                        %s
                    }
                }
                """.formatted(members, body), "Probe", "m", List.of(integer(1)));
    }

    /**
     * Whatever the program throws while it runs is its failure, with its traces, also an error the JVM uses for
     * linkage (review of M3 part 1 round 2, F95): the phase decides, not the throwable's class.
     */
    @Test
    void linkageErrorsThrownWhileRunningAreFailures() {
        var executor = new JvmExecutor(5_000);
        var cases = List.of(
                // An initializer that throws NoClassDefFoundError or LinkageError directly: errors are not wrapped.
                thrown("    static final BigInteger F = gone();\n    static BigInteger gone() { throw new NoClassDefFoundError(\"Gone\"); }",
                        "return a.add(F);"),
                thrown("    static final BigInteger F = gone();\n    static BigInteger gone() { throw new LinkageError(\"init\"); }",
                        "return a.add(F);"),
                // The method, after a trace of its own.
                thrown("", "Builtins.trace(\"m\", Builtins.iData(a));\n        throw new LinkageError(\"m\");"),
                thrown("", "Builtins.trace(\"m\", Builtins.iData(a));\n        throw new NoClassDefFoundError(\"Gone\");"),
                thrown("", "Builtins.trace(\"m\", Builtins.iData(a));\n        throw new VerifyError(\"not about this case\");"));
        var traces = List.of(List.of("init"), List.of("init"), List.of("init", "m"), List.of("init", "m"), List.of("init", "m"));
        for (int i = 0; i < cases.size(); i++) {
            var failure = assertInstanceOf(Observation.Failure.class, executor.run(cases.get(i)), cases.get(i).source());
            assertEquals(traces.get(i), failure.traces(), cases.get(i).source());
        }
    }

    /**
     * A class of the case that the JVM rejects when it loads lazily, here a local class with javac's unverifiable
     * bytecode, is still an oracle fault: the error names the class.
     */
    @Test
    void aLocalClassTheJvmRejectsIsAHarnessError() {
        String source = """
                import java.math.BigInteger;

                public class Probe {
                    sealed interface S0 permits C0, B0, A0 {}
                    record C0(BigInteger w) implements S0 {}
                    record B0() implements S0 {}
                    record A0(boolean x) implements S0 {}
                    static final S0 x = new C0(BigInteger.ONE);

                    public static boolean m(boolean c) {
                        class Local {
                            boolean f(boolean c) {
                                if (switch (x) { case A0 f1 -> f1.x(); default -> c; }) { return c; }
                                return x.equals(switch (x) { case A0 p -> new B0(); default -> new B0(); });
                            }
                        }
                        return new Local().f(c);
                    }
                }
                """;
        var tags = TagTable.of(new TagTable.Entry("Probe$C0", "Probe$S0", 0), new TagTable.Entry("Probe$B0", "Probe$S0", 1),
                new TagTable.Entry("Probe$A0", "Probe$S0", 2));
        var e = assertThrows(HarnessException.class, () -> new JvmExecutor(5_000).run(new DiffCase("lazy", source, "Probe",
                "m", List.of(new PlutusData.ConstrData(0, List.of())), tags, null)));
        assertTrue(e.getMessage().contains("the JVM rejects javac's class") && e.getMessage().contains("VerifyError")
                && e.getMessage().contains("Probe$1Local"), e.getMessage());
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
