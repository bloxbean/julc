package org.julclang.compiler.depth;

import org.julclang.compiler.CompileResult;
import org.julclang.compiler.CompilerTestVm;
import org.julclang.compiler.JulcCompiler;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Issue #327, ADR-074: what javac handles flat, the frontend's lowering must handle without one Java stack frame per
 * statement. A block lowers as one term in which each statement holds the ones after it, so its depth is its length;
 * {@code Statements}, {@code Ifs}, {@code Loops}, {@code LoopBody} and {@code Conditions} walk it with a heap stack, and
 * the whole-term checks ({@code BindingCheck}, {@code PatternBindingCheck}, {@code PreLoopReads}) do too.
 * <p>
 * Each shape is compiled whole, as a validator and as a method, on a 256 KB stack, at 3,000 statements or links (an
 * {@code else if} chain as long as javac itself parses on that stack), and the method is evaluated: its results are Java's.
 * A small instance of each is checked the same way on the default stack.
 */
class FlatSourceLoweringDepthTest {

    private static final int DEEP = 3_000;
    private static final long SMALL_STACK = 256 * 1024;
    /** As long an {@code else if} chain as javac parses on {@link #SMALL_STACK}, with room: javac's own limit is about 150. */
    private static final int ELSE_IFS = 120;

    private static JulcVm vm;

    @BeforeAll
    static void setUp() {
        vm = CompilerTestVm.pv11();
    }

    private static final String HEAD = """
            import java.math.BigInteger;
            import org.julclang.stdlib.annotation.Entrypoint;
            import org.julclang.stdlib.annotation.SpendingValidator;
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcList;
            import org.julclang.core.types.JulcMap;
            import org.julclang.stdlib.Builtins;

            @SpendingValidator
            class Deep {
                sealed interface Shape permits Sq, Ci {}
                record Sq(BigInteger side) implements Shape {}
                record Ci(BigInteger radius) implements Shape {}
            """;

    private static String validate(String parameters, String body) {
        return HEAD + "    @Entrypoint\n    static boolean validate(" + parameters + ") {\n" + body + "    }\n}\n";
    }

    private static String method(String body) {
        return validate("BigInteger x, PlutusData ctx", body);
    }

    /** {@code a_i = a_{i-1} + 1} for {@code n} locals: {@code x + n > 0}. */
    static String lets(int n) {
        var body = new StringBuilder("        BigInteger a0 = x;\n");
        for (int i = 1; i <= n; i++) body.append("        BigInteger a").append(i).append(" = a").append(i - 1).append(".add(BigInteger.ONE);\n");
        body.append("        return a").append(n).append(".signum() > 0;\n");
        return method(body.toString());
    }

    /** {@code n} early returns: true for {@code 0 <= x < n}. */
    static String earlyReturns(int n) {
        var body = new StringBuilder();
        for (int i = 0; i < n; i++) body.append("        if (x.equals(BigInteger.valueOf(").append(i).append("))) return x.signum() >= 0;\n");
        body.append("        return false;\n");
        return method(body.toString());
    }

    /** {@code n} ifs for their effect, a trace: {@code x > 0}. */
    static String effects(int n) {
        var body = new StringBuilder("        BigInteger y = x;\n");
        for (int i = 0; i < n; i++) body.append("        if (x.signum() > ").append(i % 5).append(") { Builtins.trace(\"t\", ctx); }\n");
        body.append("        return y.signum() > 0;\n");
        return method(body.toString());
    }

    /** {@code n} expression statements: true. */
    static String statements(int n) {
        var body = new StringBuilder();
        for (int i = 0; i < n; i++) body.append("        Builtins.trace(\"c\", ctx);\n");
        body.append("        return x.signum() >= 0 || x.signum() < 0;\n");
        return method(body.toString());
    }

    /** {@code n} guards that each introduce a pattern variable into the rest of the block: a square of positive side. */
    static String guards(int n) {
        var body = new StringBuilder();
        for (int i = 0; i < n; i++) body.append("        if (!(s instanceof Sq q").append(i).append(")) return false;\n");
        body.append("        return q").append(n - 1).append(".side().signum() > 0;\n");
        return validate("Shape s, PlutusData ctx", body.toString());
    }

    /** {@code n} for-each loops with an accumulator, one after another: {@code x * (1 + 2n) > 0}. */
    static String loops(int n) {
        var body = new StringBuilder("        BigInteger y = x;\n        JulcList<BigInteger> xs = JulcList.of(x, x);\n");
        for (int i = 0; i < n; i++) body.append("        for (BigInteger e : xs) { y = y.add(e); }\n");
        body.append("        return y.signum() > 0;\n");
        return method(body.toString());
    }

    /** A loop body of {@code n} locals and updates: {@code x + n > 0}. */
    static String loopBody(int n) {
        var body = new StringBuilder("        BigInteger y = x;\n        JulcList<BigInteger> xs = JulcList.of(x);\n");
        body.append("        for (BigInteger e : xs) {\n");
        for (int i = 0; i < n; i++) {
            body.append("            BigInteger t").append(i).append(" = y.add(BigInteger.ONE);\n");
            body.append("            y = t").append(i).append(";\n");
        }
        body.append("        }\n        return y.signum() > 0;\n");
        return method(body.toString());
    }

    /** A list literal of {@code n} elements: {@code x > 0}. */
    static String listLiteral(int n) {
        var body = new StringBuilder("        JulcList<BigInteger> xs = JulcList.of(x");
        for (int i = 1; i < n; i++) body.append(", x");
        body.append(");\n        return xs.head().signum() > 0;\n");
        return method(body.toString());
    }

    /** An {@code else if} chain of {@code n} returns: true for {@code 0 <= x < n}. */
    static String elseIfReturns(int n) {
        var body = new StringBuilder("        ");
        for (int i = 0; i < n; i++) body.append(i == 0 ? "if" : " else if").append(" (x.equals(BigInteger.valueOf(").append(i).append("))) { return true; }");
        body.append(" else { return false; }\n");
        return method(body.toString());
    }

    /** An {@code else if} chain of {@code n} effects: {@code x > 0}. */
    static String elseIfEffects(int n) {
        var body = new StringBuilder("        ");
        for (int i = 0; i < n; i++) body.append(i == 0 ? "if" : " else if").append(" (x.equals(BigInteger.valueOf(").append(i).append("))) { Builtins.trace(\"t\", ctx); }");
        body.append(" else { Builtins.trace(\"e\", ctx); }\n        return x.signum() > 0;\n");
        return method(body.toString());
    }

    /** A cast to a map through {@code n} {@code var}s, each initialized by the one before: a map of two entries. */
    static String castAliases(int n) {
        var body = new StringBuilder("        var v0 = ctx;\n");
        for (int i = 1; i <= n; i++) body.append("        var v").append(i).append(" = v").append(i - 1).append(";\n");
        body.append("        var m = PlutusData.cast(v").append(n).append(", JulcMap.class);\n        return m.size() == 2;\n");
        return method(body.toString());
    }

    /** A {@code switch} arm of {@code n} locals before its {@code yield}: a square of side greater than {@code -n}. */
    static String switchArm(int n) {
        var body = new StringBuilder("        boolean r = switch (s) {\n            case Sq q -> {\n                BigInteger a0 = q.side();\n");
        for (int i = 1; i <= n; i++) body.append("                BigInteger a").append(i).append(" = a").append(i - 1).append(".add(BigInteger.ONE);\n");
        body.append("                yield a").append(n).append(".signum() > 0;\n            }\n            case Ci c -> false;\n        };\n        return r;\n");
        return validate("Shape s, PlutusData ctx", body.toString());
    }

    /**
     * A {@code PlutusData} accumulator beside a numeric one, read through {@code n} helpers each returning the next one's
     * result, which is what the tuple's type is read from: {@code x > 0}.
     */
    static String helperChain(int n) {
        var helpers = new StringBuilder();
        for (int i = 0; i < n; i++) helpers.append("    static PlutusData h").append(i).append("(PlutusData d) { return h").append(i + 1).append("(d); }\n");
        helpers.append("    static PlutusData h").append(n).append("(PlutusData d) { return d; }\n");
        var body = "        PlutusData acc = h0(ctx);\n        BigInteger n = BigInteger.ZERO;\n        JulcList<BigInteger> xs = JulcList.of(x, x);\n"
                + "        for (BigInteger e : xs) { acc = h0(acc); n = n.add(e); }\n        return n.signum() > 0;\n";
        return HEAD + helpers + "    @Entrypoint\n    static boolean validate(BigInteger x, PlutusData ctx) {\n" + body + "    }\n}\n";
    }

    /** A string {@code ==} of a constant read through {@code n} {@code final}s, each initialized by the one before: {@code x > 0}. */
    static String constantStrings(int n) {
        var body = new StringBuilder("        final String s0 = \"a\";\n");
        for (int i = 1; i <= n; i++) body.append("        final String s").append(i).append(" = s").append(i - 1).append(";\n");
        body.append("        return s").append(n).append(" == \"a\" && x.signum() > 0;\n");
        return method(body.toString());
    }

    /** A {@code switch} guard read through {@code n} {@code var}s, each initialized by the one before: a square, and {@code ctx > 0}. */
    static String guardAliases(int n) {
        var body = new StringBuilder("        var b0 = Builtins.unIData(ctx).signum() > 0;\n");
        for (int i = 1; i <= n; i++) body.append("        var b").append(i).append(" = b").append(i - 1).append(";\n");
        body.append("        return switch (s) {\n            case Sq q when b").append(n).append(" -> true;\n")
                .append("            case Sq q -> false;\n            case Ci c -> false;\n        };\n");
        return validate("Shape s, PlutusData ctx", body.toString());
    }

    /** Arguments, and the result Java gives for them. */
    record Check(List<PlutusData> arguments, boolean expected) {
    }

    /** A shape, how long it is made deep and small, and the checks of an instance of length {@code n}. */
    record Case(String name, IntFunction<String> source, int deep, int small, IntFunction<List<Check>> checks) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static List<PlutusData> args(PlutusData first) {
        return List.of(first, PlutusData.integer(0));
    }

    private static PlutusData square(long side) {
        return PlutusData.constr(0, PlutusData.integer(side));
    }

    private static PlutusData circle(long radius) {
        return PlutusData.constr(1, PlutusData.integer(radius));
    }

    private static PlutusData entries(int count) {
        var pairs = new PlutusData.Pair[count];
        for (int i = 0; i < count; i++) pairs[i] = new PlutusData.Pair(PlutusData.integer(i), PlutusData.integer(i * 10L));
        return PlutusData.map(pairs);
    }

    private static Check check(PlutusData first, boolean expected) {
        return new Check(args(first), expected);
    }

    private static Check check(long x, boolean expected) {
        return check(PlutusData.integer(x), expected);
    }

    static Stream<Case> cases() {
        return Stream.of(
                new Case("lets", FlatSourceLoweringDepthTest::lets, DEEP, 25, n -> List.of(check(-n, false), check(-n + 1, true))),
                new Case("earlyReturns", FlatSourceLoweringDepthTest::earlyReturns, DEEP, 25,
                        n -> List.of(check(0, true), check(n - 1, true), check(n, false), check(-1, false))),
                new Case("effects", FlatSourceLoweringDepthTest::effects, DEEP, 25, n -> List.of(check(3, true), check(0, false))),
                new Case("statements", FlatSourceLoweringDepthTest::statements, DEEP, 25, n -> List.of(check(7, true))),
                new Case("guards", FlatSourceLoweringDepthTest::guards, DEEP, 25,
                        n -> List.of(check(square(5), true), check(square(0), false), check(circle(5), false))),
                new Case("loops", FlatSourceLoweringDepthTest::loops, 300, 25, n -> List.of(check(1, true), check(-1, false))),
                new Case("loopBody", FlatSourceLoweringDepthTest::loopBody, DEEP, 25, n -> List.of(check(-n, false), check(-n + 1, true))),
                new Case("listLiteral", FlatSourceLoweringDepthTest::listLiteral, DEEP, 25, n -> List.of(check(4, true), check(-4, false))),
                new Case("elseIfReturns", FlatSourceLoweringDepthTest::elseIfReturns, ELSE_IFS, 25,
                        n -> List.of(check(0, true), check(n - 1, true), check(n, false), check(-1, false))),
                new Case("elseIfEffects", FlatSourceLoweringDepthTest::elseIfEffects, ELSE_IFS, 25,
                        n -> List.of(check(3, true), check(0, false), check(n + 5, true))),
                new Case("castAliases", FlatSourceLoweringDepthTest::castAliases, DEEP, 25,
                        n -> List.of(new Check(List.of(PlutusData.integer(0), entries(2)), true),
                                new Check(List.of(PlutusData.integer(0), entries(3)), false))),
                new Case("switchArm", FlatSourceLoweringDepthTest::switchArm, DEEP, 25,
                        n -> List.of(check(square(-n), false), check(square(-n + 1), true), check(circle(5), false))),
                new Case("helperChain", FlatSourceLoweringDepthTest::helperChain, DEEP, 25, n -> List.of(check(1, true), check(-1, false))),
                new Case("constantStrings", FlatSourceLoweringDepthTest::constantStrings, DEEP, 25,
                        n -> List.of(check(1, true), check(-1, false))),
                new Case("guardAliases", FlatSourceLoweringDepthTest::guardAliases, DEEP, 25,
                        n -> List.of(new Check(List.of(square(1), PlutusData.integer(1)), true),
                                new Check(List.of(square(1), PlutusData.integer(-1)), false),
                                new Check(List.of(circle(1), PlutusData.integer(1)), false))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void aLongFlatShapeCompilesOnASmallStackAndKeepsJavasResults(Case shape) throws InterruptedException {
        var source = shape.source().apply(shape.deep());
        var method = new AtomicReference<CompileResult>();
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(null, () -> {
            try {
                var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry());
                var validator = compiler.compile(source);
                assertFalse(validator.hasErrors(), () -> validator.diagnostics().toString());
                method.set(compiler.compileMethod(source, "validate"));
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "small-stack", SMALL_STACK);
        thread.start();
        thread.join();
        if (failure.get() != null) fail(shape.name() + " did not compile on a " + SMALL_STACK / 1024 + " KB stack", failure.get());
        assertResults(shape, shape.deep(), method.get());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void aSmallInstanceKeepsJavasResults(Case shape) {
        assertResults(shape, shape.small(),
                new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(shape.source().apply(shape.small()), "validate"));
    }

    private static void assertResults(Case shape, int length, CompileResult result) {
        assertFalse(result.hasErrors(), () -> result.diagnostics().toString());
        for (var check : shape.checks().apply(length)) {
            var evaluated = vm.evaluateWithArgs(result.program(), check.arguments());
            var success = assertInstanceOf(EvalResult.Success.class, evaluated, evaluated::toString);
            var value = ((Constant.BoolConst) ((Term.Const) success.resultTerm()).value()).value();
            assertEquals(check.expected(), value, shape.name() + " of length " + length + " on " + check.arguments());
        }
    }
}
