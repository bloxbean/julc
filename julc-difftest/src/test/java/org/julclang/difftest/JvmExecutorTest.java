package org.julclang.difftest;

import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        assertTrue(Arrays.stream(e.getCause().getStackTrace()).anyMatch(frame -> frame.getMethodName().equals("linked")
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
     * linkage, whatever its message names (review of M3 part 1 round 2, F95, F105): the phase decides, not the
     * throwable's class or text.
     */
    @Test
    void linkageErrorsThrownWhileRunningAreFailures() {
        var executor = new JvmExecutor(5_000);
        var errors = List.of("new NoClassDefFoundError(\"Gone\")", "new LinkageError(\"m\")", "new VerifyError(\"Probe\")",
                "new ClassFormatError(\"Probe\")", "new VerifyError(\"NotProbeClass\")");
        for (String error : errors) {
            // From an initializer, which throws the error directly (errors are not wrapped), and from the method.
            var initializer = thrown("    static final BigInteger F = gone();\n    static BigInteger gone() { throw " + error + "; }",
                    "return a.add(F);");
            var method = thrown("", "Builtins.trace(\"m\", Builtins.iData(a));\n        throw " + error + ";");
            var failure = assertInstanceOf(Observation.Failure.class, executor.run(initializer), initializer.source());
            assertEquals(List.of("init"), failure.traces(), initializer.source());
            failure = assertInstanceOf(Observation.Failure.class, executor.run(method), method.source());
            assertEquals(List.of("init", "m"), failure.traces(), method.source());
        }
    }

    /**
     * Every class javac emitted for the case is verified before the run, so a local class with javac's unverifiable
     * bytecode, which would load lazily, is an oracle fault found while linking.
     */
    private static final String LOCAL_UNVERIFIABLE = """
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

    private static DiffCase localUnverifiable() {
        var tags = TagTable.of(new TagTable.Entry("Probe$C0", "Probe$S0", 0), new TagTable.Entry("Probe$B0", "Probe$S0", 1),
                new TagTable.Entry("Probe$A0", "Probe$S0", 2));
        return new DiffCase("lazy", LOCAL_UNVERIFIABLE, "Probe", "m", List.of(new PlutusData.ConstrData(0, List.of())), tags, null);
    }

    @Test
    void aLocalClassTheJvmRejectsIsAHarnessError() {
        var e = assertThrows(HarnessException.class, () -> new JvmExecutor(5_000).run(localUnverifiable()));
        assertTrue(e.getMessage().contains("the JVM rejects javac's class") && e.getMessage().contains("VerifyError")
                && e.getMessage().contains("Probe$1Local"), e.getMessage());
        assertTrue(Arrays.stream(e.getCause().getStackTrace()).anyMatch(frame -> frame.getMethodName().equals("linked")
                && frame.getClassName().equals(JvmExecutor.class.getName())), "raised while linking");
    }

    /** A program whose entry class has javac's unverifiable bytecode, which the JVM rejects while linking. */
    private static String unverifiable(String className) {
        return """
                import java.math.BigInteger;

                public class %s {
                    sealed interface S0 permits C0, B0 {}
                    record C0(BigInteger w) implements S0 {}
                    record B0(boolean x) implements S0 {}
                    static final S0 x = new C0(BigInteger.ONE);

                    public static boolean m(boolean c) {
                        if (switch (x) { case B0 f1 -> f1.x(); default -> c; }) { return c; }
                        return x.equals(switch (x) { case B0 p -> new C0(BigInteger.ONE); default -> new C0(BigInteger.ONE); });
                    }
                }
                """.formatted(className);
    }

    /**
     * Compiled in one batch, a case owns only the class files javac wrote for its own source, also when another
     * source's top-level class has a name that extends it with {@code $} (review of M3 part 1 round 3, F110).
     */
    @Test
    void aBatchKeepsEachSourcesClassesApart() {
        for (var names : List.of(List.of("Probe", "Probe$Other"), List.of("Probe$", "Probe$$Lambda"))) {
            var executor = new JvmExecutor(5_000);
            var sound = new DiffCase("sound", "public class " + names.get(0) + " {\n    public static long m(long n) { return n + 1; }\n}\n",
                    names.get(0), "m", List.of(integer(1)));
            var rejected = new DiffCase("rejected", unverifiable(names.get(1)), names.get(1), "m", List.of(new PlutusData.ConstrData(1, List.of())));
            executor.precompile(List.of(sound, rejected));
            assertEquals(new Observation.Value(integer(2), List.of()), executor.run(sound), names.toString());
            var e = assertThrows(HarnessException.class, () -> executor.run(rejected), names.toString());
            assertTrue(e.getMessage().contains("VerifyError"), e.getMessage());
        }
    }

    /**
     * The class files are owned by the compilation units javac reports, not by how their paths are spelled, so a
     * compile directory under a path with {@code ..} still verifies every class (review of M3 part 1 round 4, F115).
     */
    @Test
    void aWorkDirectoryWithDotSegmentsStillVerifiesEveryClass(@TempDir Path temp) throws IOException {
        Files.createDirectories(temp.resolve("a"));
        Files.createDirectories(temp.resolve("b"));
        var executor = new JvmExecutor(5_000, temp.resolve("a").resolve("..").resolve("b"));
        var local = assertThrows(HarnessException.class, () -> executor.run(localUnverifiable()));
        assertTrue(local.getMessage().contains("VerifyError") && local.getMessage().contains("Probe$1Local"), local.getMessage());
        var entry = new DiffCase("entry", unverifiable("Entry"), "Entry", "m", List.of(new PlutusData.ConstrData(1, List.of())));
        assertTrue(assertThrows(HarnessException.class, () -> executor.run(entry)).getMessage().contains("VerifyError"));
        assertEquals(new Observation.Value(integer(2), List.of()),
                executor.run(probe("public static long m(long n) { return n + 1; }", integer(1))));
    }

    /**
     * javac's URIs drop {@code ..} lexically, which after a symlinked directory names another file: the sources are
     * matched by the paths javac reads, so such a work directory still compiles and verifies every class (review of
     * M3 part 1 round 5, F120).
     */
    @Test
    void aWorkDirectoryThroughASymlinkAndDotDotStillVerifiesEveryClass(@TempDir Path temp) throws IOException {
        Files.createDirectories(temp.resolve("real").resolve("b"));
        try {
            Files.createSymbolicLink(temp.resolve("link"), temp.resolve("real").resolve("x"));
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links are unavailable here: " + e);
        }
        Files.createDirectories(temp.resolve("real").resolve("x"));
        var executor = new JvmExecutor(5_000, temp.resolve("link").resolve("..").resolve("b"));
        assertEquals(new Observation.Value(integer(2), List.of()),
                executor.run(probe("public static long m(long n) { return n + 1; }", integer(1))));
        var entry = new DiffCase("entry", unverifiable("Entry"), "Entry", "m", List.of(new PlutusData.ConstrData(1, List.of())));
        assertTrue(assertThrows(HarnessException.class, () -> executor.run(entry)).getMessage().contains("VerifyError"));
    }

    /** A class file javac writes for no source of the batch, or without a source, fails the compilation closed. */
    @Test
    void anUnownedClassFileIsAHarnessError() {
        var source = URI.create("file:///cases/Probe.java");
        var outputs = new JvmExecutor.Outputs(List.of(source));
        outputs.record("Probe", new SimpleJavaFileObject(source, JavaFileObject.Kind.SOURCE) { });
        outputs.check("owned");
        assertEquals(List.of("Probe"), outputs.of(source));
        outputs.record("Stray", new SimpleJavaFileObject(URI.create("file:///elsewhere/Stray.java"), JavaFileObject.Kind.SOURCE) { });
        assertTrue(assertThrows(HarnessException.class, () -> outputs.check("foreign")).getMessage().contains("Stray"));
        var orphan = new JvmExecutor.Outputs(List.of(source));
        orphan.record("Orphan", null);
        assertTrue(assertThrows(HarnessException.class, () -> orphan.check("orphan")).getMessage().contains("Orphan"));
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
