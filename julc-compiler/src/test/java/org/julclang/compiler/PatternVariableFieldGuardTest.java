package org.julclang.compiler;

import org.julclang.compiler.error.CompilerDiagnostic;
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
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JULC0059 (#207): an instanceof pattern variable is bound only when the pattern is the whole
 * condition of an if statement outside a loop (#204). Anywhere else its name resolved to a field of
 * the same name, so a loop, an {@code &&} condition, a negated guard, a ternary or a lambda
 * silently read the field (Java 3, JuLC 40). Reusing a field name is now rejected in every
 * position. Every rejected program is valid Java, and every accepted one is compared with javac.
 */
class PatternVariableFieldGuardTest {
    private static final String IMPORTS = """
            import java.math.BigInteger;
            import org.julclang.core.types.JulcList;
            """;
    private static final String SHAPE_TYPES = "sealed interface Sh permits Sq, Ci {}"
            + " record Sq(BigInteger s) implements Sh {} record Ci(BigInteger r) implements Sh {}";
    /** A field that a pattern variable {@code q} would silently read. */
    private static final String FIELD_Q = " static Sq q = new Sq(BigInteger.valueOf(40));";
    private static final String ONE_SHAPE = " Sh s = n > 2 ? new Sq(BigInteger.valueOf(n)) : new Ci(BigInteger.TEN);";
    private static final String SHAPES = " JulcList<Sh> shapes = JulcList.of(new Sq(BigInteger.TWO),"
            + " new Ci(BigInteger.TEN), new Sq(BigInteger.valueOf(7)));";
    private static final List<OptimizationLevel> LEVELS = List.of(
            OptimizationLevel.NONE, OptimizationLevel.BASELINE, OptimizationLevel.PV11_SAFE);

    private static String source(String members) {
        return IMPORTS + "public class Probe {\n" + members + "\n}";
    }

    private static String withFieldQ(String method) {
        return SHAPE_TYPES + FIELD_Q + " public static long m(long n) {" + method + " }";
    }

    /** Compiles the class with javac, failing if javac rejects it. */
    private static void javac(String members, Path dir) throws Exception {
        var file = dir.resolve("Probe.java");
        Files.writeString(file, source(members));
        var classpath = JulcList.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        var errors = new ByteArrayOutputStream();
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, errors,
                "-classpath", classpath, "-d", dir.toString(), file.toString());
        assertEquals(0, status, errors.toString());
    }

    /** Compiles the class with javac and runs {@code m(3)}. */
    private static long javaResult(String members) throws Exception {
        var dir = Files.createTempDirectory("julc-pattern-field-");
        javac(members, dir);
        try (var loader = new URLClassLoader(new URL[]{dir.toUri().toURL()},
                PatternVariableFieldGuardTest.class.getClassLoader())) {
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

    private static CompilerDiagnostic firstDiagnostic(CompilerException error) {
        assertFalse(error.diagnostics().isEmpty(), error.getMessage());
        return error.diagnostics().getFirst();
    }

    /**
     * javac accepts the class; JuLC rejects it with JULC0059 for pattern variable {@code q}, at the
     * first {@code instanceof Sq q} of the source.
     */
    private static CompilerDiagnostic rejected(String members) throws Exception {
        javac(members, Files.createTempDirectory("julc-pattern-field-"));
        var error = assertThrows(CompilerException.class, () -> new JulcCompiler(StdlibRegistry.defaultRegistry())
                .compileMethod(source(members), "m"), members);
        var diagnostic = firstDiagnostic(error);
        assertEquals("JULC0059", diagnostic.code(), error.getMessage());
        assertEquals("Pattern variable 'q' has the same name as a field of Probe", diagnostic.message());
        var lines = source(members).lines().toList();
        int line = 0;
        while (!lines.get(line).contains("instanceof Sq q")) line++;
        var text = lines.get(line);
        assertEquals(line + 1, diagnostic.line(), members);
        assertEquals(text.indexOf("Sq q", text.indexOf("instanceof Sq q")) + 1, diagnostic.column(), members);
        return diagnostic;
    }

    // --- Positions where the pattern variable is not bound: these read the field silently ---

    @Test
    void forEachBodyIsRejected() throws Exception {
        rejected(withFieldQ(SHAPES + " long t = 0;"
                + " for (var s : shapes) { if (s instanceof Sq q) { t = t + q.s().longValue(); } } return t;"));
    }

    @Test
    void whileBodyIsRejected() throws Exception {
        rejected(withFieldQ(SHAPES + " long t = 0; long i = 0;"
                + " while (i < 3) { Sh s = shapes.get(i); if (s instanceof Sq q) { t = t + q.s().longValue(); } i = i + 1; }"
                + " return t;"));
    }

    @Test
    void nestedLoopBodyIsRejected() throws Exception {
        rejected(withFieldQ(SHAPES + " long t = 0;"
                + " for (var a : shapes) { for (var b : shapes) { if (b instanceof Sq q) { t = t + q.s().longValue(); } } }"
                + " return t;"));
    }

    @Test
    void andConditionIsRejected() throws Exception {
        rejected(withFieldQ(ONE_SHAPE
                + " if (s instanceof Sq q && q.s().longValue() > 1) { return q.s().longValue(); } return 0;"));
    }

    @Test
    void negatedGuardIsRejected() throws Exception {
        rejected(withFieldQ(ONE_SHAPE + " if (!(s instanceof Sq q)) { return 0; } return q.s().longValue();"));
    }

    @Test
    void orGuardIsRejected() throws Exception {
        rejected(withFieldQ(ONE_SHAPE
                + " if (!(s instanceof Sq q) || q.s().longValue() == 0) { return 0; } return q.s().longValue();"));
    }

    @Test
    void ternaryIsRejected() throws Exception {
        rejected(withFieldQ(ONE_SHAPE + " long v = s instanceof Sq q ? q.s().longValue() : 5; return v;"));
    }

    @Test
    void lambdaIsRejected() throws Exception {
        rejected(withFieldQ(SHAPES
                + " if (shapes.any(x -> x instanceof Sq q && q.s().longValue() > 5)) { return 1; } return 0;"));
    }

    @Test
    void helperMethodIsRejected() throws Exception {
        rejected(SHAPE_TYPES + FIELD_Q
                + " static boolean big(Sh s) { return s instanceof Sq q && q.s().longValue() > 1; }"
                + " public static long m(long n) {" + ONE_SHAPE + " if (big(s)) { return 1; } return 0; }");
    }

    /** Bound correctly today, but rejected too: the rule does not depend on the position. */
    @Test
    void wholeIfConditionIsRejectedToo() throws Exception {
        rejected(withFieldQ(ONE_SHAPE + " if (s instanceof Sq q) { return q.s().longValue(); } return 0;"));
    }

    @Test
    void diagnosticPointsAtThePattern() throws Exception {
        String members = SHAPE_TYPES + "\n"
                + "static Sq q = new Sq(BigInteger.valueOf(40));\n"
                + "public static long m(long n) {\n"
                + "   " + ONE_SHAPE + "\n"
                + "    if (s instanceof Sq q && q.s().longValue() > 1) { return q.s().longValue(); }\n"
                + "    return 0;\n"
                + "}";
        var diagnostic = rejected(members);
        var lines = source(members).lines().toList();
        int line = 0;
        while (!lines.get(line).contains("instanceof Sq q")) line++;
        assertEquals(line + 1, diagnostic.line());
        assertEquals(lines.get(line).indexOf("Sq q") + 1, diagnostic.column());
        assertTrue(diagnostic.suggestion().contains("Rename the pattern variable"), diagnostic.suggestion());
    }

    @Test
    void paramFieldIsRejected() {
        String validator = """
                import java.math.BigInteger;
                @SpendingValidator
                class Guarded {
                    sealed interface Action permits Pay, Stop {}
                    record Pay(BigInteger amount) implements Action {}
                    record Stop() implements Action {}
                    @Param
                    static BigInteger limit;
                    @Entrypoint
                    static boolean validate(Action redeemer, ScriptContext ctx) {
                        if (redeemer instanceof Pay limit && limit.amount().signum() > 0) {
                            return true;
                        }
                        return false;
                    }
                }""";
        var error = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(validator));
        var diagnostic = firstDiagnostic(error);
        assertEquals("JULC0059", diagnostic.code(), error.getMessage());
        assertEquals("Pattern variable 'limit' has the same name as a field of Guarded", diagnostic.message());
        assertEquals(11, diagnostic.line());
    }

    @Test
    void librarySourceIsRejected() {
        String library = """
                package com.example.lib;
                import java.math.BigInteger;
                @OnchainLibrary
                public class Shapes {
                    public sealed interface Shape permits Box, Dot {}
                    public record Box(BigInteger side) implements Shape {}
                    public record Dot() implements Shape {}
                    static final Box unit = new Box(BigInteger.ONE);
                    public static BigInteger side(Shape shape) {
                        if (shape instanceof Box unit && unit.side().signum() > 0) { return unit.side(); }
                        return BigInteger.ZERO;
                    }
                }""";
        String validator = """
                import java.math.BigInteger;
                import com.example.lib.Shapes;
                @SpendingValidator
                class UsesShapes {
                    @Entrypoint
                    static boolean validate(PlutusData redeemer, ScriptContext ctx) {
                        return Shapes.side(new Shapes.Box(Builtins.unIData(redeemer))).signum() > 0;
                    }
                }""";
        var error = assertThrows(CompilerException.class,
                () -> new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(validator, List.of(library)));
        var diagnostic = firstDiagnostic(error);
        assertEquals("JULC0059", diagnostic.code(), error.getMessage());
        assertEquals("Pattern variable 'unit' has the same name as a field of Shapes", diagnostic.message());
    }

    // --- Names that cannot be captured stay accepted and match Java ---

    @Test
    void distinctPatternNameIsAccepted() throws Exception {
        matchesJava(withFieldQ(ONE_SHAPE + " if (s instanceof Sq sq) { return sq.s().longValue() + q.s().longValue(); } return 0;"));
    }

    /** Switch case patterns are bound in every position, so a field name is fine there. */
    @Test
    void switchCasePatternNamedLikeFieldIsAccepted() throws Exception {
        matchesJava(withFieldQ(ONE_SHAPE + " return switch (s) { case Sq q -> q.s().longValue(); case Ci c -> 0; };"));
        matchesJava(withFieldQ(SHAPES + " long t = 0;"
                + " for (var s : shapes) { long v = switch (s) { case Sq q -> q.s().longValue(); case Ci c -> 0; }; t = t + v; }"
                + " return t;"));
    }

    /** Methods have their own namespace (ADR-060), so a method name cannot capture the pattern. */
    @Test
    void patternNamedLikeMethodIsAccepted() throws Exception {
        matchesJava(SHAPE_TYPES + " static long q(long x) { return x + 100; }"
                + " public static long m(long n) {" + ONE_SHAPE
                + " if (s instanceof Sq q) { return q.s().longValue(); } return q(n); }");
    }

    /** A library's fields are bound only around its own methods, so they cannot capture a validator pattern. */
    @Test
    void patternNamedLikeLibraryFieldIsAccepted() {
        String library = """
                package com.example.lib;
                import java.math.BigInteger;
                @OnchainLibrary
                public class Fees {
                    public static final BigInteger q = BigInteger.valueOf(40);
                    public static BigInteger base() { return q; }
                }""";
        String validator = "import com.example.lib.Fees;\n" + source(SHAPE_TYPES + " public static long m(long n) {"
                + ONE_SHAPE + " if (s instanceof Sq q) { return q.s().longValue() + Fees.base().longValue(); } return 0; }");
        // Java: q is the pattern variable (Sq(3)), so 3 + 40.
        var expected = new Term.Const(Constant.integer(BigInteger.valueOf(43)));
        for (var level : LEVELS) {
            var program = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                    new CompilerOptions().setOptimizationLevel(level)).compileMethod(validator, "m", List.of(library)).program();
            for (var provider : List.of("Java", "Scalus")) {
                var result = CompilerTestVm.pv11(provider).evaluateWithArgs(program, List.of(PlutusData.integer(3)));
                var label = provider + "/" + level;
                assertEquals(expected, assertInstanceOf(EvalResult.Success.class, result, label).resultTerm(), label);
            }
        }
    }

    // --- Other earlier binders of the same name: rejected or equal to Java, never captured ---

    /**
     * JuLC rejects the class or {@code m(3)} returns javac's result. Until #204 these report
     * {@code Undefined variable}; afterwards they must compile to Java's result.
     */
    private static void rejectedOrMatchesJava(String members) throws Exception {
        long java = javaResult(members);
        CompileResult compiled;
        try {
            compiled = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source(members), "m");
        } catch (CompilerException rejected) {
            return;
        }
        var result = CompilerTestVm.pv11().evaluateWithArgs(compiled.program(), List.of(PlutusData.integer(3)));
        assertEquals(new Term.Const(Constant.integer(BigInteger.valueOf(java))),
                assertInstanceOf(EvalResult.Success.class, result, members).resultTerm(), members);
    }

    private static String method(String body) {
        return SHAPE_TYPES + " public static long m(long n) {" + body + " }";
    }

    @Test
    void earlierPatternBindingsDoNotCapture() throws Exception {
        // The first q is bound over a continuation-bearing branch, the second is in a loop.
        rejectedOrMatchesJava(method(ONE_SHAPE + SHAPES + " long t = 0;"
                + " if (s instanceof Sq q) { if (n > 5) { return 99; } }"
                + " for (var x : shapes) { if (x instanceof Sq q) { t = t + q.s().longValue(); } } return t;"));
        rejectedOrMatchesJava(method(ONE_SHAPE + " if (s instanceof Sq q) { if (n > 5) { return 99; } }"
                + " Sh u = new Sq(BigInteger.valueOf(50));"
                + " if (u instanceof Sq q && q.s().longValue() > 1) { return q.s().longValue(); } return 0;"));
        rejectedOrMatchesJava(method(ONE_SHAPE + " long t = 0; if (s instanceof Sq q) { t = q.s().longValue(); }"
                + " Sh u = new Sq(BigInteger.valueOf(50));"
                + " if (u instanceof Sq q && q.s().longValue() > 1) { return t * 1000 + q.s().longValue(); } return t;"));
    }

    @Test
    void outOfScopeLocalsDoNotCapture() throws Exception {
        rejectedOrMatchesJava(method(SHAPES + " long t = 0;"
                + " { Sq q = new Sq(BigInteger.valueOf(40)); t = q.s().longValue(); }"
                + " for (var x : shapes) { if (x instanceof Sq q) { t = t + q.s().longValue(); } } return t;"));
        rejectedOrMatchesJava(method(ONE_SHAPE + " long t = 0;"
                + " if (n > 0) { Sq q = new Sq(BigInteger.valueOf(40)); t = q.s().longValue(); }"
                + " if (s instanceof Sq q && q.s().longValue() > 1) { return t * 1000 + q.s().longValue(); } return t;"));
        rejectedOrMatchesJava(method(ONE_SHAPE + " JulcList<Sq> sqs = JulcList.of(new Sq(BigInteger.valueOf(40))); long t = 0;"
                + " for (var q : sqs) { t = t + q.s().longValue(); }"
                + " if (s instanceof Sq q && q.s().longValue() > 1) { return t * 1000 + q.s().longValue(); } return t;"));
        rejectedOrMatchesJava(method(ONE_SHAPE + SHAPES
                + " long t = switch (s) { case Sq q -> q.s().longValue(); case Ci c -> 0; };"
                + " for (var x : shapes) { if (x instanceof Sq q) { t = t + q.s().longValue(); } } return t;"));
    }
}
