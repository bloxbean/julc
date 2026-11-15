package org.julclang.compiler.depth;

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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Issue #327, ADR-074: what javac handles flat, the frontend's lowering must handle without one Java stack frame per
 * statement. A block lowers as one term in which each statement holds the ones after it, so its depth is its length;
 * {@code Statements}, {@code Ifs}, {@code Loops}, {@code LoopBody} and {@code Conditions} walk it with a heap stack, and
 * the whole-term checks ({@code BindingCheck}, {@code PatternBindingCheck}, {@code PreLoopReads}) do too.
 * <p>
 * Each shape is lowered at 3,000 statements on a 256 KB stack: no stack overflow may arise in the lowering. The stages
 * after it are other code (the PIR passes and UPLC generation), so an overflow whose frames are all theirs does not fail
 * this test; the whole pipeline is pinned elsewhere. On a small instance each shape is compiled and evaluated, and its
 * results are Java's.
 */
class FlatSourceLoweringDepthTest {

    private static final int DEEP = 3_000;
    private static final long SMALL_STACK = 256 * 1024;
    private static final String LOWERING = "org.julclang.compiler.frontend.javac.lower.";
    /** How many frames at the top of an overflow's stack are read for the recursion that overflowed. */
    private static final int TOP = 200;

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

    record Case(String name, IntFunction<String> source, int small, List<List<PlutusData>> arguments, List<Boolean> expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static List<PlutusData> args(PlutusData first) {
        return List.of(first, PlutusData.integer(0));
    }

    private static List<PlutusData> integer(long value) {
        return args(PlutusData.integer(value));
    }

    static Stream<Case> cases() {
        return Stream.of(
                new Case("lets", FlatSourceLoweringDepthTest::lets, 25,
                        List.of(integer(-25), integer(-24)), List.of(false, true)),
                new Case("earlyReturns", FlatSourceLoweringDepthTest::earlyReturns, 25,
                        List.of(integer(0), integer(24), integer(25), integer(-1)), List.of(true, true, false, false)),
                new Case("effects", FlatSourceLoweringDepthTest::effects, 25,
                        List.of(integer(3), integer(0)), List.of(true, false)),
                new Case("statements", FlatSourceLoweringDepthTest::statements, 25,
                        List.of(integer(7)), List.of(true)),
                new Case("guards", FlatSourceLoweringDepthTest::guards, 25,
                        List.of(args(PlutusData.constr(0, PlutusData.integer(5))), args(PlutusData.constr(0, PlutusData.integer(0))),
                                args(PlutusData.constr(1, PlutusData.integer(5)))),
                        List.of(true, false, false)),
                new Case("loops", FlatSourceLoweringDepthTest::loops, 25,
                        List.of(integer(1), integer(-1)), List.of(true, false)),
                new Case("loopBody", FlatSourceLoweringDepthTest::loopBody, 25,
                        List.of(integer(-25), integer(-24)), List.of(false, true)),
                new Case("listLiteral", FlatSourceLoweringDepthTest::listLiteral, 25,
                        List.of(integer(4), integer(-4)), List.of(true, false)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void aLongFlatShapeLowersOnASmallStack(Case shape) throws InterruptedException {
        var source = shape.source().apply(shape.name().equals("loops") ? 300 : DEEP);
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(null, () -> {
            try {
                var result = new JulcCompiler(StdlibRegistry.defaultRegistry()).compile(source);
                assertFalse(result.hasErrors(), () -> result.diagnostics().toString());
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "small-stack", SMALL_STACK);
        thread.start();
        thread.join();
        var thrown = failure.get();
        if (thrown == null) return;
        var overflow = overflowIn(thrown);
        if (overflow == null) fail("not a stack overflow", thrown);
        // The recursion that overflowed fills the top of the stack; the lowering's own frames below it, which called a
        // later stage, are not the overflow.
        var lowering = new ArrayList<String>();
        var frames = overflow.getStackTrace();
        for (int i = 0; i < Math.min(TOP, frames.length); i++) {
            if (frames[i].getClassName().startsWith(LOWERING)) lowering.add(frames[i].getClassName() + "." + frames[i].getMethodName());
        }
        assertTrue(lowering.isEmpty(), () -> "the lowering overflowed the stack: " + lowering.stream().distinct().limit(12).toList());
    }

    private static StackOverflowError overflowIn(Throwable thrown) {
        for (var cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof StackOverflowError overflow) return overflow;
        }
        return null;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void aSmallInstanceKeepsJavasResults(Case shape) {
        var result = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(shape.source().apply(shape.small()), "validate");
        assertFalse(result.hasErrors(), () -> result.diagnostics().toString());
        for (int i = 0; i < shape.arguments().size(); i++) {
            var evaluated = vm.evaluateWithArgs(result.program(), shape.arguments().get(i));
            var success = assertInstanceOf(EvalResult.Success.class, evaluated, evaluated::toString);
            var value = ((Constant.BoolConst) ((Term.Const) success.resultTerm()).value()).value();
            assertEquals(shape.expected().get(i), value, shape.name() + " on " + shape.arguments().get(i));
        }
    }
}
