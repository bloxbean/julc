package org.julclang.compiler;

import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.core.types.JulcList;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-060, JULC0056: a loop assignment to a static field or {@code @Param} rebinds the name only
 * in the rest of the assigning method's current run; every other run and every other method sees
 * the field's initial value. It is accepted only where that is Java's result: the method is an
 * entry method that nothing calls, the loop is not in a lambda, and no other method reads the
 * field. Each accepted program is compared with the same source compiled and run by javac.
 */
class LoopFieldUpdateTest {
    private static final String IMPORTS = """
            import java.math.BigInteger;
            import org.julclang.core.types.JulcList;
            """;
    private static final String XS =
            "JulcList<BigInteger> xs = JulcList.of(BigInteger.ONE, BigInteger.TWO, BigInteger.TEN);";
    private static final List<OptimizationLevel> LEVELS = List.of(
            OptimizationLevel.NONE, OptimizationLevel.BASELINE, OptimizationLevel.PV11_SAFE);

    private static String source(String members) {
        return IMPORTS + "public class Probe {\n" + members + "\n}";
    }

    /** Compiles the class with javac and runs {@code m(3)}. */
    private static long javaResult(String members) throws Exception {
        var dir = Files.createTempDirectory("julc-loop-field-");
        var file = dir.resolve("Probe.java");
        Files.writeString(file, source(members));
        var classpath = JulcList.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        var errors = new ByteArrayOutputStream();
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, errors,
                "-classpath", classpath, "-d", dir.toString(), file.toString());
        assertEquals(0, status, errors.toString());
        try (var loader = new URLClassLoader(new URL[]{dir.toUri().toURL()}, LoopFieldUpdateTest.class.getClassLoader())) {
            return (long) loader.loadClass("Probe").getMethod("m", long.class).invoke(null, 3L);
        }
    }

    /** JuLC accepts the class and {@code m(3)} returns javac's result at every level on both VMs. */
    private static void matchesJava(String members) throws Exception {
        var expected = new Term.Const(Constant.integer(BigInteger.valueOf(javaResult(members))));
        for (var level : LEVELS) {
            var program = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setOptimizationLevel(level)).compileMethod(source(members), "m").program();
            for (var provider : List.of("Java", "Scalus")) {
                var result = CompilerTestVm.pv11(provider).evaluateWithArgs(program, List.of(PlutusData.integer(3)));
                var label = provider + "/" + level + ": " + members;
                assertEquals(expected, assertInstanceOf(EvalResult.Success.class, result, label).resultTerm(), label);
            }
        }
    }

    /** JuLC rejects the class with JULC0056 and a message containing {@code reason}. */
    private static CompilerDiagnosticAssert rejected(String members, String reason) {
        var error = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileMethod(source(members), "m"), members);
        var diagnostic = error.diagnostics().getFirst();
        assertEquals("JULC0056", diagnostic.code(), error.getMessage());
        assertTrue(diagnostic.message().contains(reason), diagnostic.message());
        return new CompilerDiagnosticAssert(diagnostic);
    }

    private record CompilerDiagnosticAssert(org.julclang.compiler.error.CompilerDiagnostic diagnostic) {
        void atLine(int line) {
            assertEquals(line, diagnostic.line(), diagnostic.message());
        }
    }

    // The entry method compiled by compileMethod is m; it runs once per evaluation.
    private static final String ENTRY_UPDATES_K = "public static long m(long n) { while (K < n) { K = K + 1; } return K + helper(n); }";

    @Test
    void shadowingVariableInAnotherMethodIsNotARead() throws Exception {
        // PR #199 review: a helper whose local, lambda parameter or for-each variable shadows the
        // field never reads it. The loop lowering rebinds pre-loop names as let K = K, which is
        // not a read either (reported on the fix of that review).
        matchesJava("static long K = 0; static long helper(long v) { long K = 7; return K; }\n" + ENTRY_UPDATES_K);
        matchesJava("static long K = 0; static long helper(long v) { " + XS
                + " return xs.filter(K -> K.compareTo(BigInteger.ONE) > 0).size(); }\n" + ENTRY_UPDATES_K);
        matchesJava("static long K = 0; static long helper(long v) { " + XS
                + " long t = 0; for (var K : xs) { t = t + 1; } return t; }\n" + ENTRY_UPDATES_K);
        matchesJava("static long K = 0; static long helper(long n) { long i = 0; while (i < n) { i = i + 1; } long K = 7; return K; }\n"
                + ENTRY_UPDATES_K);
        matchesJava("static long K = 0; static long helper(long n) { long i = 0; long j = 0;"
                + " while (i < n) { i = i + 1; j = j + 2; } long K = 7; return K + j; }\n" + ENTRY_UPDATES_K);
        matchesJava("static long K = 0; static long helper(long n) { long i = 0;"
                + " if (n > 1) { while (i < n) { i = i + 1; } } long K = 7; return K + i; }\n" + ENTRY_UPDATES_K);
    }

    @Test
    void entryMethodUpdateMatchesJava() throws Exception {
        matchesJava("static long K = 0; public static long m(long n) { " + XS + " for (var x : xs) { K += 1; } return K; }");
        matchesJava("static long K = 0; public static long m(long n) { " + XS + " for (var x : xs) { K += 1; } return xs.any(x -> K > 2) ? 1 : 0; }");
        matchesJava("static long K = 0; public static long m(long n) { " + XS + " for (var y : xs) { for (var x : xs) { K += 1; } } return K; }");
        matchesJava("static long K = 0; public static long m(long n) { " + XS + " long s = 0; for (var y : xs) { s = s + K; for (var x : xs) { K += 1; } } return s * 100 + K; }");
        matchesJava("static long K = 0; public static long m(long n) { " + XS + " if (n > 0) { for (var x : xs) { K += 1; } } return K; }");
        matchesJava("static long K = 0; public static long m(long n) { " + XS + " long r = n > 1 ? 1 : 0; if (n > 1) { for (var x : xs) { K += 2; } } return r + K; }");
        matchesJava("static long K = 0; public static long m(long n) { " + XS + " for (var x : xs) { if (K >= 2) { break; } K += 1; } return K; }");
        matchesJava("static long K = 0; public static long m(long n) { while (true) { if (K >= n) { break; } K = K + 1; } return K; }");
        matchesJava("static long K = 0; public static long m(long n) { " + XS
                + " for (var y : xs) { if (y.compareTo(BigInteger.ONE) > 0) { for (var x : xs) { K += 1; } } } return K; }");
        matchesJava("static long K = 0; public static long m(long n) { " + XS + " for (var x : xs) { K += 1; } for (var x : xs) { K += 2; } return K; }");
        matchesJava("static long K = 5; public static long m(long n) { " + XS + " long a = K; for (var x : xs) { K += 1; } return a * 100 + K; }");
        matchesJava("static long K = 1; public static long m(long n) { " + XS + " long s = 0; for (var x : xs) { s = s + K; K = K * 2; } return s * 100 + K; }");
        matchesJava("static BigInteger T = BigInteger.ZERO; public static long m(long n) { long i = 0;"
                + " while (i < n) { T = T.add(BigInteger.TWO); i = i + 1; } return T.longValue(); }");
        matchesJava("static JulcList<BigInteger> L = JulcList.of(BigInteger.TEN); public static long m(long n) { " + XS
                + " for (var x : xs) { L = L.prepend(x); } return L.size(); }");
        // A lambda evaluated inside the loop reads the current value; one evaluated before the
        // loop reads the initial value, as in Java.
        matchesJava("static long K = 0; public static long m(long n) { " + XS + " for (var x : xs) { K = K + (xs.any(y -> K > 1) ? 10 : 1); } return K; }");
        matchesJava("static long K = 0; public static long m(long n) { " + XS + " JulcList<BigInteger> ys = xs.filter(x -> K < 1); for (var x : xs) { K += 1; } return ys.size() * 100 + K; }");
        // Calling a method that neither reads the field nor calls m is fine, before or after.
        String helper = "static long helper(long n) { long i = 0; while (i < n) { i = i + 1; } return i; }\n";
        matchesJava("static long K = 0; " + helper + "public static long m(long n) { long a = helper(n); while (K < n) { K = K + 1; } return K + a; }");
        matchesJava("static long K = 0; " + helper + ENTRY_UPDATES_K);
        matchesJava("static long K = 0; static long twice(long v) { return v * 2; } public static long m(long n) { " + XS
                + " if (n > 1) { for (var x : xs) { K += 1; } } return twice(K); }");
    }

    @Test
    void readByAnotherMethodIsRejected() {
        // Java reads the field before the local is declared and after the local's block ends.
        rejected("static long K = 0; static long helper(long v) { long a = K; long K = 7; return a + K; }\n" + ENTRY_UPDATES_K,
                "'helper' reads it");
        rejected("static long K = 0; static long helper(long v) { long a = 0; if (v > 0) { long K = 7; a = K; } return a + K; }\n"
                + ENTRY_UPDATES_K, "'helper' reads it");
        // Whichever method is generated first, the error points at the loop assignment. The class
        // body starts on line 4.
        rejected("static long K = 0;\nstatic long readK() { return K; }\npublic static long m(long n) {\n"
                + "    while (K < n) { K = K + 1; }\n    return readK();\n}", "'readK' reads it").atLine(7);
        rejected("static long K = 0;\npublic static long m(long n) {\n    while (K < n) { K = K + 1; }\n"
                + "    return readK();\n}\nstatic long readK() { return K; }", "'readK' reads it").atLine(6);
    }

    @Test
    void updateInAMethodThatCanRunMoreThanOnceIsRejected() {
        // Each call used to start from the field's initial value: Java 9, 18, 3, 3, 1 and 2040608
        // where JuLC returned 6, 9, 0, 0, 0 and 2020202.
        String h = "static long K = 0; static long h(long n) { long i = 0; while (i < n) { K = K + 1; i = i + 1; } return K; }\n";
        rejected(h + "public static long m(long n) { return h(n) + h(n); }", "'h' is not an entry method");
        rejected(h + "public static long m(long n) { long s = 0; long i = 0; while (i < 3) { s = s + h(n); i = i + 1; } return s; }",
                "'h' is not an entry method");
        rejected(h + "public static long m(long n) { return h(n); }", "'h' is not an entry method");
        rejected("static long K = 0; public static long m(long n) { if (n == 0) return K;"
                + " long i = 0; while (i < n) { K = K + 1; i = i + 1; } return m(0); }", "'m' calls itself");
        rejected("static long K = 0; static long h(long n) { return m(n); } public static long m(long n) { if (n == 0) return K;"
                + " long i = 0; while (i < n) { K = K + 1; i = i + 1; } return h(0); }", "'h' calls 'm'");
        rejected("static long K = 0; public static long m(long n) { long r = n > 0 ? m(n - 1) : 0;"
                + " long i = 0; while (i < 2) { K = K + 1; i = i + 1; } return r * 100 + K; }", "'m' calls itself");
        rejected("static long K = 0; public static long m(long n) { " + XS + " return xs.any(x -> {"
                + " long i = 0; while (i < n) { K = K + 1; i = i + 1; } return K > n; }) ? 1 : 0; }", "the loop is in a lambda");
        rejected("static long K = 0; static boolean B = JulcList.of(BigInteger.ONE, BigInteger.TWO).any(x -> {"
                + " long i = 0; while (i < 2) { K = K + 1; i = i + 1; } return false; });"
                + " public static long m(long n) { return K + (B ? 100 : 0); }", "a static field initializer");
    }

    @Test
    void everyLoopFormInAHelperIsChecked() {
        String call = "\npublic static long m(long n) { return h(n); }";
        for (var body : List.of(
                XS + " for (var x : xs) { K += 1; } return K;",
                XS + " for (var x : xs) { if (K > 1) { break; } K += 1; } return K;",
                XS + " long j = 0; for (var x : xs) { K += 1; j += 2; } return K + j;",
                XS + " long j = 0; for (var x : xs) { if (K > 1) { break; } K += 1; j += 2; } return K + j;",
                "while (K < n) { K = K + 1; } return K;",
                "while (K < 10) { if (K >= n) { break; } K = K + 1; } return K;",
                "long i = 0; while (i < n) { K = K + 2; i = i + 1; } return K;",
                XS + " for (var y : xs) { for (var x : xs) { K += 1; } } return K;",
                XS + " if (n > 0) { for (var x : xs) { K += 1; } } return K;",
                XS + " for (var x : xs) { K -= 1; } return K;")) {
            rejected("static long K = 9; static long h(long n) { " + body + " }" + call, "'h' is not an entry method");
        }
    }

    @Test
    void theReportedErrorIsStable() {
        // Two conflicting fields: the first update in generation order is reported, every time.
        String members = "static long K = 0; static long L = 0;\n"
                + "static long a(long n) { while (K < n) { K = K + 1; } return K; }\n"
                + "static long b(long n) { while (L < n) { L = L + 1; } return L; }\n"
                + "public static long m(long n) { return K + L; }";
        for (int i = 0; i < 20; i++) {
            rejected(members, "Assignment to field 'K' in a loop is not supported: 'a' is not an entry method").atLine(5);
        }
    }

    // --- validator, @Param, library and multi-validator pipelines ---

    private static PlutusData ints(long... values) {
        var items = new PlutusData[values.length];
        for (int i = 0; i < values.length; i++) items[i] = PlutusData.integer(values[i]);
        return PlutusData.list(items);
    }

    private static boolean accepts(org.julclang.core.Program program, PlutusData redeemer) {
        var ctx = PlutusDataCastTest.buildScriptContext(
                PlutusDataCastTest.buildTxInfo(new PlutusData[0], PlutusDataCastTest.alwaysInterval()),
                redeemer, PlutusData.constr(0, PlutusData.bytes(new byte[28])));
        return CompilerTestVm.pv11().evaluateWithArgs(program, List.of(ctx)).isSuccess();
    }

    @Test
    void entrypointUpdatesOfFieldsAndParamsMatchJava() {
        // Java: the sum of the redeemer's integers exceeds 10, and the remaining limit is not negative.
        var field = new JulcCompiler(StdlibRegistry.defaultRegistry()).compile("""
                import java.math.BigInteger;
                import org.julclang.core.types.JulcList;
                @SpendingValidator
                class Tally {
                    static BigInteger total = BigInteger.ZERO;
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        JulcList<PlutusData> items = Builtins.unListData(redeemer);
                        for (var item : items) { total = total.add(Builtins.unIData(item)); }
                        return total.compareTo(BigInteger.TEN) > 0;
                    }
                }""").program();
        assertTrue(accepts(field, ints(5, 6)));
        assertFalse(accepts(field, ints(1, 2)));
        var param = new JulcCompiler(StdlibRegistry.defaultRegistry()).compile("""
                import java.math.BigInteger;
                import org.julclang.core.types.JulcList;
                @SpendingValidator
                class Budget {
                    @Param BigInteger limit;
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        JulcList<PlutusData> items = Builtins.unListData(redeemer);
                        for (var item : items) { limit = limit.subtract(Builtins.unIData(item)); }
                        return limit.compareTo(BigInteger.ZERO) >= 0;
                    }
                }""").program().applyParams(PlutusData.integer(10));
        assertTrue(accepts(param, ints(4, 5)));
        assertFalse(accepts(param, ints(5, 6)));
    }

    @Test
    void validatorHelperCannotUpdateAParam() {
        var error = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compile("""
                import java.math.BigInteger;
                import org.julclang.core.types.JulcList;
                @SpendingValidator
                class Budget {
                    @Param BigInteger limit;
                    static BigInteger spend(JulcList<PlutusData> items) {
                        for (var item : items) { limit = limit.subtract(Builtins.unIData(item)); }
                        return limit;
                    }
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return spend(Builtins.unListData(redeemer)).compareTo(BigInteger.ZERO) >= 0;
                    }
                }"""));
        assertEquals("JULC0056", error.diagnostics().getFirst().code());
        assertTrue(error.diagnostics().getFirst().message().contains("'spend' is not an entry method"));
    }

    @Test
    void libraryMethodCannotUpdateItsField() {
        // Each call of a library method starts from its class's field initializers.
        String library = """
                import java.math.BigInteger;
                import org.julclang.core.types.JulcList;
                @OnchainLibrary
                class Counter {
                    static BigInteger seen = BigInteger.ZERO;
                    static BigInteger count(JulcList<PlutusData> items) {
                        for (var item : items) { seen = seen.add(BigInteger.ONE); }
                        return seen;
                    }
                }""";
        String validator = """
                import java.math.BigInteger;
                @SpendingValidator
                class UsesCounter {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return Counter.count(Builtins.unListData(redeemer)).compareTo(BigInteger.ONE) > 0;
                    }
                }""";
        var error = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(validator, List.of(library)));
        assertEquals("JULC0056", error.diagnostics().getFirst().code(), error.getMessage());
        assertTrue(error.diagnostics().getFirst().message().contains("'count' is not an entry method"));
    }

    @Test
    void multiValidatorHandlersAreEntryMethods() {
        String handlers = """
                import java.math.BigInteger;
                import org.julclang.core.types.JulcList;
                import org.julclang.stdlib.annotation.MultiValidator;
                import org.julclang.stdlib.annotation.Purpose;
                @MultiValidator
                class Handlers {
                    static BigInteger total = BigInteger.ZERO;
                    @Entrypoint(purpose = Purpose.MINT)
                    static boolean mint(PlutusData redeemer, ScriptContext ctx) {
                        JulcList<PlutusData> items = Builtins.unListData(redeemer);
                        for (var item : items) { total = total.add(Builtins.unIData(item)); }
                        return total.compareTo(BigInteger.TEN) > 0;
                    }
                    @Entrypoint(purpose = Purpose.SPEND)
                    static boolean spend(PlutusData redeemer, ScriptContext ctx) {
                        return %s;
                    }
                }""";
        assertNotNull(new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(handlers.formatted("true")).program());
        var error = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compile(handlers.formatted("total.compareTo(BigInteger.ZERO) == 0")));
        assertEquals("JULC0056", error.diagnostics().getFirst().code(), error.getMessage());
        assertTrue(error.diagnostics().getFirst().message().contains("'spend' reads it"));
    }
}
