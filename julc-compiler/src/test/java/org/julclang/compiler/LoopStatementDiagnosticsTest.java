package org.julclang.compiler;

import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.compiler.error.DiagnosticCodes;
import org.julclang.compiler.error.DiagnosticInfo;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.math.BigInteger;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-062 T20 (#257): loop and statement forms outside the subset are rejected with a diagnostic
 * code, the location of the construct and a remedy. Each rejection is checked at every
 * optimization level, and each remedy the diagnostic suggests is compiled and evaluated.
 */
class LoopStatementDiagnosticsTest {
    private static final String IMPORTS = """
            import java.math.BigInteger;
            import org.julclang.core.types.JulcList;
            import org.julclang.core.types.JulcMap;
            import org.julclang.stdlib.Builtins;
            import org.julclang.stdlib.lib.ContextsLib;

            """;
    private static final List<OptimizationLevel> LEVELS = List.of(
            OptimizationLevel.NONE, OptimizationLevel.BASELINE, OptimizationLevel.PV11_SAFE);

    private static String source(String members) {
        return IMPORTS + "public class Probe {\n" + members + "}\n";
    }

    /**
     * Compiles {@code m} at every level, expects a rejection with {@code info}'s code, the given
     * message and {@code info}'s fix as the remedy, located at the first occurrence of
     * {@code at} in the source.
     */
    private static void rejected(String members, DiagnosticInfo info, String message, String at) {
        var src = source(members);
        int offset = src.indexOf(at);
        assertTrue(offset >= 0, at);
        int line = (int) src.substring(0, offset).chars().filter(c -> c == '\n').count() + 1;
        int column = offset - src.lastIndexOf('\n', offset - 1);
        for (var level : LEVELS) {
            var error = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setOptimizationLevel(level)).compileMethod(src, "m"), level + ": " + members);
            var errors = error.diagnostics().stream().filter(CompilerDiagnostic::isError).toList();
            assertEquals(1, errors.size(), error.getMessage());
            var diagnostic = errors.getFirst();
            assertEquals(info.code(), diagnostic.code(), error.getMessage());
            assertEquals(message, diagnostic.message());
            assertEquals(info.fix(), diagnostic.suggestion());
            assertEquals("Probe.java", diagnostic.fileName());
            assertEquals(line, diagnostic.line(), "line of " + at);
            assertEquals(column, diagnostic.column(), "column of " + at);
        }
    }

    /**
     * javac rejects {@code m} first (ADR-068 I2b): {@code JULC0085} with javac's {@code key}, located at the first
     * occurrence of {@code at}, at every level.
     */
    private static void javacRejected(String members, String key, String at) {
        var src = source(members);
        int offset = src.indexOf(at);
        assertTrue(offset >= 0, at);
        int line = (int) src.substring(0, offset).chars().filter(c -> c == '\n').count() + 1;
        int column = offset - src.lastIndexOf('\n', offset - 1);
        for (var level : LEVELS) {
            var error = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setOptimizationLevel(level)).compileMethod(src, "m"), level + ": " + members);
            var errors = error.diagnostics().stream().filter(CompilerDiagnostic::isError).toList();
            assertEquals(1, errors.size(), error.getMessage());
            var diagnostic = errors.getFirst();
            assertEquals("JULC0085", diagnostic.code(), error.getMessage());
            assertTrue(diagnostic.message().endsWith("(" + key + ")"), diagnostic.message());
            assertEquals(line, diagnostic.line(), "line of " + at);
            assertEquals(column, diagnostic.column(), "column of " + at);
        }
    }

    /** JuLC accepts {@code m} and returns {@code expected} at every level on both VMs. */
    private static void evaluates(String members, List<PlutusData> args, Constant expected) {
        for (var level : LEVELS) {
            var program = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setOptimizationLevel(level)).compileMethod(source(members), "m").program();
            for (var provider : List.of("Java", "Scalus")) {
                var result = CompilerTestVm.pv11(provider).evaluateWithArgs(program, args);
                var label = provider + "/" + level;
                assertEquals(new Term.Const(expected),
                        assertInstanceOf(EvalResult.Success.class, result, label).resultTerm(), label);
            }
        }
    }

    private static PlutusData ints(long... values) {
        var items = new PlutusData[values.length];
        for (int i = 0; i < values.length; i++) items[i] = PlutusData.integer(values[i]);
        return PlutusData.list(items);
    }

    private static Constant integer(long value) {
        return Constant.integer(BigInteger.valueOf(value));
    }

    // --- continue: JULC0060 ---

    @Test
    void continueInForEach() {
        rejected("""
                    public static BigInteger m(JulcList<BigInteger> xs) {
                        BigInteger total = BigInteger.ZERO;
                        for (var x : xs) {
                            if (x.signum() < 0) { continue; }
                            total = total.add(x);
                        }
                        return total;
                    }
                """, DiagnosticCodes.CONTINUE_UNSUPPORTED, "continue is not supported on-chain", "continue;");
    }

    @Test
    void continueInWhileAndInLoopWithBreak() {
        rejected("""
                    public static long m(long n) {
                        long i = 0;
                        while (i < n) {
                            i = i + 1;
                            if (i > 2) { continue; }
                        }
                        return i;
                    }
                """, DiagnosticCodes.CONTINUE_UNSUPPORTED, "continue is not supported on-chain", "continue;");
        rejected("""
                    public static long m(JulcList<BigInteger> xs) {
                        long c = 0;
                        for (var x : xs) {
                            if (x.signum() < 0) { break; }
                            if (x.signum() == 0) { continue; }
                            c = c + 1;
                        }
                        return c;
                    }
                """, DiagnosticCodes.CONTINUE_UNSUPPORTED, "continue is not supported on-chain", "continue;");
    }

    @Test
    void continueRemedy() {
        // The fix: guard the rest of the body instead. Java returns 1 + 3 = 4 for [1, -2, 3].
        evaluates("""
                    public static BigInteger m(JulcList<BigInteger> xs) {
                        BigInteger total = BigInteger.ZERO;
                        for (var x : xs) {
                            if (x.signum() >= 0) { total = total.add(x); }
                        }
                        return total;
                    }
                """, List.of(ints(1, -2, 3)), integer(4));
    }

    @Test
    void continueInValidator() {
        var src = """
                import java.math.BigInteger;
                import org.julclang.core.types.JulcList;
                import org.julclang.core.PlutusData;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;

                @SpendingValidator
                class Probe {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        JulcList<BigInteger> xs = JulcList.of(BigInteger.ONE);
                        long c = 0;
                        for (var x : xs) {
                            if (x.signum() == 0) { continue; }
                            c = c + 1;
                        }
                        return c > 0;
                    }
                }
                """;
        var error = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(src));
        assertEquals("JULC0060", error.diagnostics().getFirst().code(), error.getMessage());
    }

    @Test
    void continueInCodeTheCompilerNeverLowersIsAccepted() {
        // Review r1 F1: a static nested helper class is ordinary off-chain Java that JuLC does not
        // compile. JULC0060 is raised where a loop is lowered, so the helper keeps compiling, and
        // the script is the same as without the continue.
        String validator = """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcList;
                import org.julclang.ledger.ScriptContext;
                import org.julclang.stdlib.annotation.Entrypoint;
                import org.julclang.stdlib.annotation.SpendingValidator;

                @SpendingValidator
                class Probe {
                    static class OffChain {
                        static long count(JulcList<BigInteger> xs) {
                            long c = 0;
                            for (var x : xs) {
                                %s
                            }
                            return c;
                        }
                    }

                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return true;
                    }
                }
                """;
        for (var level : LEVELS) {
            var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setOptimizationLevel(level));
            var withContinue = compiler.compile(validator.formatted("if (x.signum() < 0) { continue; } c = c + 1;")).program();
            var guarded = compiler.compile(validator.formatted("if (x.signum() >= 0) { c = c + 1; }")).program();
            assertArrayEquals(UplcFlatEncoder.encodeProgram(guarded), UplcFlatEncoder.encodeProgram(withContinue), level.toString());
        }
    }

    // --- return inside while: JULC0003 ---

    @Test
    void returnInsideWhile() {
        rejected("""
                    public static long m(long n) {
                        long i = 0;
                        while (i < n) {
                            if (i > 3) { return i; }
                            i = i + 1;
                        }
                        return n;
                    }
                """, DiagnosticCodes.RETURN_INSIDE_WHILE, "'return' is not supported inside while loop body", "return i;");
    }

    @Test
    void returnInsideWhileElseBranchIsLocated() {
        rejected("""
                    public static long m(long n) {
                        long i = 0;
                        while (i < n) {
                            if (i > 3) { i = i + 2; } else { return i; }
                            i = i + 1;
                        }
                        return n;
                    }
                """, DiagnosticCodes.RETURN_INSIDE_WHILE, "'return' is not supported inside while loop body", "return i;");
    }

    @Test
    void returnInsideWhileRemedy() {
        // The fix: break, then return after the loop. Java: i reaches 4, the loop breaks, m(10) = 4.
        evaluates("""
                    public static long m(long n) {
                        long i = 0;
                        while (i < n) {
                            if (i > 3) { break; }
                            i = i + 1;
                        }
                        return i;
                    }
                """, List.of(PlutusData.integer(10)), integer(4));
    }

    // --- reassignment outside a loop: JULC0061 ---

    @Test
    void reassignmentOutsideLoop() {
        rejected("""
                    public static long m(long n) {
                        long x = n;
                        x = x * n;
                        return x;
                    }
                """, DiagnosticCodes.REASSIGNMENT_OUTSIDE_LOOP, "Reassigning 'x' is not supported outside a loop", "x = x * n");
    }

    @Test
    void reassignmentInBranchParameterFieldAndExpression() {
        rejected("""
                    public static long m(long n) {
                        long x = n;
                        if (n > 1) { x = 2; }
                        return x;
                    }
                """, DiagnosticCodes.REASSIGNMENT_OUTSIDE_LOOP, "Reassigning 'x' is not supported outside a loop", "x = 2");
        rejected("""
                    public static long m(long n) {
                        n = n + 1;
                        return n;
                    }
                """, DiagnosticCodes.REASSIGNMENT_OUTSIDE_LOOP, "Reassigning 'n' is not supported outside a loop", "n = n + 1");
        rejected("""
                    static long K = 0;
                    public static long m(long n) {
                        K = n;
                        return K;
                    }
                """, DiagnosticCodes.REASSIGNMENT_OUTSIDE_LOOP, "Reassigning 'K' is not supported outside a loop", "K = n");
        rejected("""
                    public static long m(long n) {
                        long x = n;
                        return (x = 2);
                    }
                """, DiagnosticCodes.REASSIGNMENT_OUTSIDE_LOOP, "Reassigning 'x' is not supported outside a loop", "x = 2");
    }

    @Test
    void reassignmentInLambdaIsReportedOnce() {
        // Review r1 F7: a lambda body is generated more than once; the diagnostic is reported once.
        javacRejected("""
                    public static long m(JulcList<BigInteger> xs) {
                        BigInteger k = BigInteger.ZERO;
                        long n = xs.map(v -> k = v).size();
                        return n;
                    }
                """, "compiler.err.cant.ref.non.effectively.final.var", "k = v");
    }

    @Test
    void assignmentInsideLoopKeepsItsOwnDiagnostic() {
        // An assignment inside an expression, in a loop with no accumulator, is not "outside a
        // loop"; it keeps the generic diagnostic (LoopBlockAssignmentTest covers it).
        var error = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileMethod(source("""
                    public static BigInteger m(JulcList<BigInteger> xs) {
                        BigInteger acc = BigInteger.ZERO;
                        for (var x : xs) {
                            ContextsLib.trace(BigInteger.ZERO.equals(acc = acc.add(x)) ? "zero" : "other");
                        }
                        return acc;
                    }
                """), "m"));
        assertTrue(error.getMessage().contains("Unsupported expression: AssignExpr"), error.getMessage());
        assertTrue(error.diagnostics().stream().noneMatch(d -> "JULC0061".equals(d.code())), error.getMessage());
    }

    @Test
    void reassignmentRemedy() {
        evaluates("""
                    public static long m(long n) {
                        long x = n;
                        long squared = x * n;
                        return squared;
                    }
                """, List.of(PlutusData.integer(3)), integer(9));
    }

    // --- JulcMap.of on-chain: JULC0062 ---

    @Test
    void julcMapOf() {
        rejected("""
                    public static boolean m(byte[] a) {
                        JulcMap<byte[], BigInteger> map = JulcMap.of(a, BigInteger.ONE);
                        return map.containsKey(a);
                    }
                """, DiagnosticCodes.JULCMAP_OF_OFF_CHAIN_ONLY,
                "JulcMap.of is off-chain only and has no on-chain implementation", "JulcMap.of(");
        rejected("""
                    public static boolean m(byte[] a, byte[] b) {
                        var map = JulcMap.of(a, BigInteger.ONE, b, BigInteger.TWO);
                        return map.containsKey(b);
                    }
                """, DiagnosticCodes.JULCMAP_OF_OFF_CHAIN_ONLY,
                "JulcMap.of is off-chain only and has no on-chain implementation", "JulcMap.of(");
    }

    @Test
    void julcMapOfIsRecognisedByItsQualifiedName() {
        rejected("""
                    public static boolean m(byte[] a) {
                        var map = org.julclang.core.types.JulcMap.of(a, BigInteger.ONE);
                        return map.containsKey(a);
                    }
                """, DiagnosticCodes.JULCMAP_OF_OFF_CHAIN_ONLY,
                "JulcMap.of is off-chain only and has no on-chain implementation", "org.julclang.core.types.JulcMap.of(");
    }

    @Test
    void julcMapOfWithoutImport() {
        // Review r2 F12: JuLC resolves an unimported JulcMap.empty() to the stdlib map, so an
        // unimported JulcMap.of(...) is diagnosed the same way.
        var error = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileMethod("""
                        import java.math.BigInteger;
                        import org.julclang.core.types.JulcMap;

                        public class Probe {
                            public static boolean m(byte[] a) {
                                var map = JulcMap.of(a, BigInteger.ONE);
                                return true;
                            }
                        }
                        """, "m"));
        assertEquals("JULC0062", error.diagnostics().getFirst().code(), error.getMessage());
    }

    @Test
    void ownClassNamedJulcMapIsNotTheStdlibMap() {
        // Review r1 F5: a class of the source named JulcMap is not org.julclang.core.types.JulcMap.
        var error = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileMethod("""
                        public class JulcMap {
                            static long of(long n) { return n; }
                            public static long m(long n) { return JulcMap.of(n); }
                        }
                        """, "m"));
        assertTrue(error.diagnostics().stream().noneMatch(d -> "JULC0062".equals(d.code())), error.getMessage());
    }

    @Test
    void julcMapOfWithTwoEntriesRemedy() {
        // insert adds at the front, so JulcMap.of(a, 1, b, 2) is empty().insert(b, 2).insert(a, 1),
        // off-chain and on-chain: both have a as the first key.
        var offChain = JulcMap.of(BigInteger.ONE, BigInteger.TEN, BigInteger.TWO, BigInteger.ZERO);
        var rebuilt = JulcMap.<BigInteger, BigInteger>empty().insert(BigInteger.TWO, BigInteger.ZERO)
                .insert(BigInteger.ONE, BigInteger.TEN);
        assertEquals(offChain.keys().head(), rebuilt.keys().head());
        assertEquals(offChain.keys().tail().head(), rebuilt.keys().tail().head());
        evaluates("""
                    public static byte[] m(byte[] a, byte[] b) {
                        JulcMap<byte[], BigInteger> map = JulcMap.<byte[], BigInteger>empty()
                                .insert(b, BigInteger.TWO).insert(a, BigInteger.ONE);
                        return map.keys().head();
                    }
                """, List.of(PlutusData.bytes(new byte[]{1}), PlutusData.bytes(new byte[]{2})),
                Constant.byteString(new byte[]{1}));
    }

    @Test
    void julcMapOfRemedy() {
        evaluates("""
                    public static boolean m(byte[] a) {
                        JulcMap<byte[], BigInteger> map = JulcMap.<byte[], BigInteger>empty().insert(a, BigInteger.ONE);
                        return map.containsKey(a);
                    }
                """, List.of(PlutusData.bytes(new byte[]{1, 2})), Constant.bool(true));
    }

    // --- break in a loop that updates nothing: JULC0063 ---

    @Test
    void breakInForEachWithoutAccumulator() {
        rejected("""
                    public static BigInteger m(BigInteger a, BigInteger b, JulcList<BigInteger> xs) {
                        BigInteger v0 = BigInteger.valueOf(-5);
                        for (var x1 : xs) {
                            if (BigInteger.valueOf(-5).compareTo(BigInteger.valueOf(-5)) < 0) { break; }
                        }
                        return BigInteger.valueOf(-5);
                    }
                """, DiagnosticCodes.BREAK_IN_LOOP_WITHOUT_ACCUMULATOR,
                "break is not supported in a loop that updates no variable declared before the loop", "break;");
    }

    @Test
    void breakInWhileWithoutAccumulator() {
        rejected("""
                    public static long m(long n) {
                        while (n > 0) {
                            if (n > 2) { break; }
                            if (n == 1) { Builtins.error(); }
                        }
                        return n;
                    }
                """, DiagnosticCodes.BREAK_IN_LOOP_WITHOUT_ACCUMULATOR,
                "break is not supported in a loop that updates no variable declared before the loop", "break;");
    }

    @Test
    void breakInLoopWhoseUpdateIsInASwitchStatement() {
        // Review r1 F2: the loop does update c, inside a switch statement, which the accumulator
        // analysis does not see. The diagnostic points at the update, not at the break.
        rejected("""
                    public static long m(JulcList<BigInteger> xs) {
                        long c = 0;
                        for (var x : xs) {
                            if (x.signum() < 0) { break; }
                            switch (x.intValue()) { case 1 -> c = c + 1; default -> c = c + 2; }
                        }
                        return c;
                    }
                """, DiagnosticCodes.LOOP_UPDATE_POSITION_UNSUPPORTED,
                "Updating 'c' inside a switch statement is not supported in this loop", "c = c + 1;");
    }

    @Test
    void breakInLoopWhoseUpdateIsInAnExpressionOrLambda() {
        rejected("""
                    public static long m(JulcList<BigInteger> xs) {
                        long c = 0;
                        for (var x : xs) {
                            if (x.signum() < 0) { break; }
                            if ((c = c + 1) > 5) { Builtins.error(); }
                        }
                        return c;
                    }
                """, DiagnosticCodes.LOOP_UPDATE_POSITION_UNSUPPORTED,
                "Updating 'c' inside an expression is not supported in this loop", "c = c + 1)");
        javacRejected("""
                    public static long m(JulcList<BigInteger> xs) {
                        BigInteger k = BigInteger.ZERO;
                        for (var x : xs) {
                            if (x.signum() < 0) { break; }
                            long n = xs.map(v -> k = v).size();
                        }
                        return 1;
                    }
                """, "compiler.err.cant.ref.non.effectively.final.var", "k = v");
    }

    @Test
    void breakInWhileWhoseUpdateIsInTheCondition() throws Exception {
        // Review r2 F10: the loop updates x in its condition, which the accumulator analysis does
        // not see. javac returns 3 for n = 6; JuLC points at the update. The remedy, the update as
        // the first statement of the body, gives javac's 3 on both VMs.
        String inCondition = """
                    public static long m(long n) {
                        long x = n;
                        while ((x = x - 1) > 0) {
                            if (x == 3) { break; }
                        }
                        return x;
                    }
                """;
        assertEquals(3L, javaLongResult(inCondition, 6));
        rejected(inCondition, DiagnosticCodes.LOOP_UPDATE_POSITION_UNSUPPORTED,
                "Updating 'x' in the loop condition is not supported in this loop", "x = x - 1");
        String remedy = """
                    public static long m(long n) {
                        long x = n;
                        while (x - 1 > 0) {
                            x = x - 1;
                            if (x == 3) { break; }
                        }
                        return x;
                    }
                """;
        assertEquals(3L, javaLongResult(remedy, 6));
        evaluates(remedy, List.of(PlutusData.integer(6)), integer(3));
    }

    @Test
    void updatePositionRemedies() {
        // The JULC0065 fixes. Java for [1, 2, -1, 5]: c is 1 + 2 = 3 with the if/else, and 2 with
        // the update as a statement; both loops break at -1.
        evaluates("""
                    public static long m(JulcList<BigInteger> xs) {
                        long c = 0;
                        for (var x : xs) {
                            if (x.signum() < 0) { break; }
                            if (x.intValue() == 1) { c = c + 1; } else { c = c + 2; }
                        }
                        return c;
                    }
                """, List.of(ints(1, 2, -1, 5)), integer(3));
        evaluates("""
                    public static long m(JulcList<BigInteger> xs) {
                        long c = 0;
                        for (var x : xs) {
                            if (x.signum() < 0) { break; }
                            c = c + 1;
                            if (c > 5) { Builtins.error(); }
                        }
                        return c;
                    }
                """, List.of(ints(1, 2, -1, 5)), integer(2));
    }

    private static final String T26 = """
                public static BigInteger m(JulcList<BigInteger> xs) {
                    BigInteger s = BigInteger.ZERO;
                    for (var x : xs) {
                        s = s.add(x);
                        for (var y : xs) {
                            if (y.compareTo(BigInteger.ONE) == 0) { break; }
                            if (y.compareTo(BigInteger.TWO) == 0) { Builtins.error(); }
                        }
                    }
                    return s;
                }
            """;

    /** Compiles the class with javac and runs {@code m(xs)}. */
    private static Object javaResult(String members, JulcList<BigInteger> xs) throws Exception {
        var dir = Files.createTempDirectory("julc-t26-");
        var file = dir.resolve("Probe.java");
        Files.writeString(file, source(members));
        var classpath = String.join(File.pathSeparator,
                JulcList.class.getProtectionDomain().getCodeSource().getLocation().getPath(),
                Builtins.class.getProtectionDomain().getCodeSource().getLocation().getPath());
        var errors = new ByteArrayOutputStream();
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, errors,
                "-classpath", classpath, "-d", dir.toString(), file.toString());
        assertEquals(0, status, errors.toString());
        try (var loader = new URLClassLoader(new URL[]{dir.toUri().toURL()}, LoopStatementDiagnosticsTest.class.getClassLoader())) {
            return loader.loadClass("Probe").getMethod("m", JulcList.class).invoke(null, xs);
        }
    }

    /** Compiles the class with javac and runs {@code m(n)}. */
    private static long javaLongResult(String members, long n) throws Exception {
        var dir = Files.createTempDirectory("julc-t20-");
        var file = dir.resolve("Probe.java");
        Files.writeString(file, source(members));
        var classpath = String.join(File.pathSeparator,
                JulcList.class.getProtectionDomain().getCodeSource().getLocation().getPath(),
                Builtins.class.getProtectionDomain().getCodeSource().getLocation().getPath());
        var errors = new ByteArrayOutputStream();
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, errors,
                "-classpath", classpath, "-d", dir.toString(), file.toString());
        assertEquals(0, status, errors.toString());
        try (var loader = new URLClassLoader(new URL[]{dir.toUri().toURL()}, LoopStatementDiagnosticsTest.class.getClassLoader())) {
            return (long) loader.loadClass("Probe").getMethod("m", long.class).invoke(null, n);
        }
    }

    @Test
    void nestedForEachWithBreakAndNoAccumulator() throws Exception {
        // ADR-062 T26 (#273): javac stops the inner loop at 1, so Builtins.error() is never reached
        // and m([1, 2]) = 3. JuLC used to accept this and ignore the inner break, so the script
        // failed. It is now rejected at the inner break, as the same loop is on its own.
        assertEquals(BigInteger.valueOf(3), javaResult(T26, JulcList.of(BigInteger.ONE, BigInteger.TWO)));
        rejected(T26, DiagnosticCodes.BREAK_IN_LOOP_WITHOUT_ACCUMULATOR,
                "break is not supported in a loop that updates no variable declared before the loop", "break;");
    }

    @Test
    void nestedWhileWithBreakAndNoAccumulatorAndOuterLoopWithTwoAccumulators() {
        rejected("""
                    public static BigInteger m(JulcList<BigInteger> xs) {
                        BigInteger s = BigInteger.ZERO;
                        for (var x : xs) {
                            s = s.add(x);
                            while (x.signum() > 0) {
                                if (x.compareTo(BigInteger.ONE) == 0) { break; }
                                Builtins.error();
                            }
                        }
                        return s;
                    }
                """, DiagnosticCodes.BREAK_IN_LOOP_WITHOUT_ACCUMULATOR,
                "break is not supported in a loop that updates no variable declared before the loop", "break;");
        rejected("""
                    public static BigInteger m(JulcList<BigInteger> xs) {
                        BigInteger s = BigInteger.ZERO;
                        BigInteger t = BigInteger.ZERO;
                        for (var x : xs) {
                            s = s.add(x);
                            t = t.add(x);
                            for (var y : xs) {
                                if (y.compareTo(BigInteger.ONE) == 0) { break; }
                            }
                        }
                        return s.add(t);
                    }
                """, DiagnosticCodes.BREAK_IN_LOOP_WITHOUT_ACCUMULATOR,
                "break is not supported in a loop that updates no variable declared before the loop", "break;");
    }

    @Test
    void nestedLoopWithoutBreakIsStillAccepted() {
        // Control: the inner loop without break is unaffected. Java: 1 + 2 = 3.
        evaluates("""
                    public static BigInteger m(JulcList<BigInteger> xs) {
                        BigInteger s = BigInteger.ZERO;
                        for (var x : xs) {
                            s = s.add(x);
                            for (var y : xs) {
                                if (y.compareTo(BigInteger.TEN) == 0) { Builtins.error(); }
                            }
                        }
                        return s;
                    }
                """, List.of(ints(1, 2)), integer(3));
    }

    @Test
    void breakWithoutAccumulatorRemedy() {
        // The fix: set a variable declared before the loop where it breaks. Java: the loop stops
        // at 1, so the error for 2 is never reached, and m([1, 2]) = 7.
        evaluates("""
                    public static long m(JulcList<BigInteger> xs) {
                        boolean stopped = false;
                        for (var x : xs) {
                            if (x.compareTo(BigInteger.ONE) == 0) { stopped = true; break; }
                            if (x.compareTo(BigInteger.TWO) == 0) { Builtins.error(); }
                        }
                        return 7;
                    }
                """, List.of(ints(1, 2)), integer(7));
    }

    // --- a statement a loop with break cannot hold: JULC0064 ---

    @Test
    void nestedWhileInLoopWithBreak() {
        rejected("""
                    public static BigInteger m(BigInteger a, BigInteger b, JulcList<BigInteger> xs) {
                        BigInteger v0 = BigInteger.valueOf(-5).add(BigInteger.valueOf(-5)).add(BigInteger.valueOf(-5));
                        for (var x1 : xs) {
                            if (BigInteger.valueOf(-5).add(BigInteger.valueOf(-2)).compareTo(a) < 0) { break; }
                            long i2 = 0;
                            while (i2 < 0) {
                                v0 = BigInteger.valueOf(-5);
                                i2 = i2 + 1;
                            }
                            v0 = BigInteger.valueOf(-5);
                        }
                        return BigInteger.valueOf(-5);
                    }
                """, DiagnosticCodes.BREAK_LOOP_STATEMENT_UNSUPPORTED,
                "A nested while loop is not supported in a loop that contains break and updates one variable declared before the loop",
                "while (i2");
    }

    @Test
    void nestedForEachInLoopWithBreak() {
        rejected("""
                    public static long m(JulcList<BigInteger> xs) {
                        long c = 0;
                        for (var x : xs) {
                            if (x.signum() < 0) { break; }
                            for (var y : xs) { c = c + 1; }
                        }
                        return c;
                    }
                """, DiagnosticCodes.BREAK_LOOP_STATEMENT_UNSUPPORTED,
                "A nested for-each loop is not supported in a loop that contains break and updates one variable declared before the loop",
                "for (var y");
    }

    @Test
    void otherStatementInLoopWithBreakIsNotJulc0064() {
        // Review r1 F3: JULC0064 and its fix are about nested loops; a stray ';' keeps its own
        // diagnostic.
        var error = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileMethod(source("""
                    public static long m(JulcList<BigInteger> xs) {
                        long c = 0;
                        for (var x : xs) {
                            if (x.signum() < 0) { break; }
                            ;
                            c = c + 1;
                        }
                        return c;
                    }
                """), "m"));
        assertTrue(error.getMessage().contains("EmptyStmt"), error.getMessage());
        assertTrue(error.diagnostics().stream().noneMatch(d -> "JULC0064".equals(d.code())), error.getMessage());
    }

    @Test
    void nestedLoopRemedy() {
        // The fix: move the nested loop into a helper. Java: for [1, 2, -1, 4] the loop adds
        // 3 * 1 and 3 * 2, then breaks at -1, so m = 9.
        evaluates("""
                    static BigInteger addTimes(BigInteger v, BigInteger x) {
                        BigInteger r = v;
                        long i = 0;
                        while (i < 3) {
                            r = r.add(x);
                            i = i + 1;
                        }
                        return r;
                    }

                    public static BigInteger m(JulcList<BigInteger> xs) {
                        BigInteger v = BigInteger.ZERO;
                        for (var x : xs) {
                            if (x.signum() < 0) { break; }
                            v = addTimes(v, x);
                        }
                        return v;
                    }
                """, List.of(ints(1, 2, -1, 4)), integer(9));
    }

    // --- labels, and a break or continue that names one: JULC0066 (#201) ---

    private static final String LABEL_MESSAGE = "Labels are not supported on-chain: '%s'";

    /** The #201 reproducer, with {@code exit} as the statement that leaves the inner loop. */
    private static String nestedCount(String exit) {
        return """
                    public static long m(JulcList<BigInteger> xs) {
                        long count = 0;
                        for (var x : xs) {
                            for (var y : xs) {
                                if (y.equals(BigInteger.TWO)) { %s }
                                count = count + 1;
                            }
                        }
                        return count;
                    }
                """.formatted(exit);
    }

    @Test
    void labelledBreakWithoutLabelIssue201() {
        // #201: javac rejects this (undefined label outer). JuLC used to compile the break as a
        // plain break of the inner loop and return 3 for [1, 2, 3].
        javacRejected(nestedCount("break outer;"), "compiler.err.undef.label", "break outer;");
    }

    @Test
    void labelledBreakInNestedWhile() {
        javacRejected("""
                    public static long m(long n) {
                        long count = 0;
                        long i = 0;
                        while (i < n) {
                            long j = 0;
                            while (j < n) {
                                if (j == 2) { break outer; }
                                count = count + 1;
                                j = j + 1;
                            }
                            i = i + 1;
                        }
                        return count;
                    }
                """, "compiler.err.undef.label", "break outer;");
    }

    @Test
    void labelledBreakWithMatchingLabel() throws Exception {
        // Valid Java: javac leaves both loops at the first 2, so m([1, 2, 3]) = 1. JuLC rejects
        // the label itself, which comes first.
        String members = """
                    public static long m(JulcList<BigInteger> xs) {
                        long count = 0;
                        outer:
                        for (var x : xs) {
                            for (var y : xs) {
                                if (y.equals(BigInteger.TWO)) { break outer; }
                                count = count + 1;
                            }
                        }
                        return count;
                    }
                """;
        assertEquals(1L, javaResult(members, JulcList.of(BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3))));
        rejected(members, DiagnosticCodes.LABEL_UNSUPPORTED, LABEL_MESSAGE.formatted("outer:"), "outer:");
    }

    @Test
    void labelledContinue() {
        // continue is not supported with or without a label; the labelled form reports the label
        // (JULC0066), and its fix says continue is JULC0060 too.
        javacRejected(nestedCount("continue outer;"), "compiler.err.undef.label", "continue outer;");
        rejected("""
                    public static long m(JulcList<BigInteger> xs) {
                        long count = 0;
                        outer:
                        for (var x : xs) {
                            for (var y : xs) {
                                if (y.equals(BigInteger.TWO)) { continue outer; }
                                count = count + 1;
                            }
                        }
                        return count;
                    }
                """, DiagnosticCodes.LABEL_UNSUPPORTED, LABEL_MESSAGE.formatted("outer:"), "outer:");
        assertTrue(DiagnosticCodes.LABEL_UNSUPPORTED.fix().contains("JULC0060"));
    }

    @Test
    void labelsAtMethodLevel() {
        // A labelled break outside any loop (reported by subset validation), a labelled block and
        // a labelled continue outside any loop (reported where the statement is lowered).
        javacRejected("""
                    public static long m(long n) {
                        if (n > 1) { break outer; }
                        return n;
                    }
                """, "compiler.err.undef.label", "break outer;");
        rejected("""
                    public static long m(long n) {
                        done: {
                            if (n > 1) { break done; }
                        }
                        return n;
                    }
                """, DiagnosticCodes.LABEL_UNSUPPORTED, LABEL_MESSAGE.formatted("break done"), "break done;");
        rejected("""
                    public static long m(long n) {
                        done: {
                            ContextsLib.trace("n");
                        }
                        return n;
                    }
                """, DiagnosticCodes.LABEL_UNSUPPORTED, LABEL_MESSAGE.formatted("done:"), "done:");
        javacRejected("""
                    public static long m(long n) {
                        if (n > 1) { continue outer; }
                        return n;
                    }
                """, "compiler.err.undef.label", "continue outer;");
    }

    @Test
    void labelledBreakInSwitchArmAndLambda() {
        javacRejected("""
                    public static long m(JulcList<BigInteger> xs) {
                        long count = 0;
                        for (var x : xs) {
                            count = count + 1;
                            switch (x.intValue()) {
                                case 2 -> { break outer; }
                                default -> { }
                            }
                        }
                        return count;
                    }
                """, "compiler.err.undef.label", "break outer;");
        javacRejected("""
                    public static long m(JulcList<BigInteger> xs) {
                        long n = xs.filter(y -> {
                            boolean found = false;
                            for (var z : xs) {
                                if (z.equals(y)) { found = true; break outer; }
                            }
                            return found;
                        }).size();
                        return n;
                    }
                """, "compiler.err.undef.label", "break outer;");
    }

    @Test
    void plainBreakIsUnaffected() throws Exception {
        // Control: the unlabelled break leaves the inner loop only. Java and JuLC: 3 for [1, 2, 3].
        String members = nestedCount("break;");
        assertEquals(3L, javaResult(members, JulcList.of(BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3))));
        evaluates(members, List.of(ints(1, 2, 3)), integer(3));
    }

    @Test
    void labelRemedy() throws Exception {
        // The fix: a variable set where the inner loop breaks, and the rest of the outer loop
        // skipped while it is set. Java and JuLC: 1 for [1, 2, 3], as `break outer` gives.
        String members = """
                    public static long m(JulcList<BigInteger> xs) {
                        long count = 0;
                        boolean stop = false;
                        for (var x : xs) {
                            if (!stop) {
                                for (var y : xs) {
                                    if (y.equals(BigInteger.TWO)) { stop = true; break; }
                                    count = count + 1;
                                }
                            }
                        }
                        return count;
                    }
                """;
        assertEquals(1L, javaResult(members, JulcList.of(BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3))));
        evaluates(members, List.of(ints(1, 2, 3)), integer(1));
        // The catalogue's example, where the flag is the only variable the loops update.
        String flagOnly = """
                    public static boolean m(JulcList<BigInteger> xs) {
                        boolean stop = false;
                        for (var x : xs) {
                            if (!stop) {
                                for (var y : xs) {
                                    if (y.equals(BigInteger.TWO)) { stop = true; break; }
                                }
                            }
                        }
                        return stop;
                    }
                """;
        assertEquals(true, javaResult(flagOnly, JulcList.of(BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3))));
        evaluates(flagOnly, List.of(ints(1, 2, 3)), Constant.bool(true));
        evaluates(flagOnly, List.of(ints(1, 3)), Constant.bool(false));
    }
}
