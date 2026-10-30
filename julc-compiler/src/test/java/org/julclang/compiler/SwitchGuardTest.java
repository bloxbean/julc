package org.julclang.compiler;

import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Term;
import org.julclang.core.flat.UplcFlatEncoder;
import org.julclang.core.types.JulcList;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.math.BigInteger;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-067 M1: switch guards on type patterns (D1, D2). Each program is compiled and run by javac and the JVM, and
 * JuLC must give the same value, or fail where Java throws, with the same traces in the same order, at every
 * optimization level on the Java and Scalus VMs (I1-I3, I10, I11).
 */
class SwitchGuardTest {

    static final String S = """
                sealed interface S permits A, B {}
                record A(BigInteger v) implements S {}
                record B(BigInteger v) implements S {}
            """;

    static String probe(String members) {
        return """
                import java.math.BigInteger;
                import org.julclang.core.types.JulcList;
                import org.julclang.stdlib.Builtins;

                public class Probe {
                """ + members + "\n}\n";
    }

    /** What one side produced: a value or a failure, with the traces in order. */
    record Outcome(BigInteger value, boolean failed, List<String> traces) {
        @Override
        public String toString() {
            return (failed ? "failure" : "value " + value) + " " + traces;
        }
    }

    /**
     * javac and the JVM: {@code m} with its arguments, traces captured from {@code Builtins.trace}. An argument is a
     * {@code Long} (a BigInteger parameter) or a {@code long[]} (a {@code JulcList<BigInteger>} parameter).
     */
    static Outcome java(String source, Object... args) throws Exception {
        var dir = Files.createTempDirectory("julc-switch-guard-");
        var file = dir.resolve("Probe.java");
        Files.writeString(file, source);
        var errors = new ByteArrayOutputStream();
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, errors,
                "-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", dir.toString(), file.toString());
        assertEquals(0, status, errors.toString());
        var traces = new ArrayList<String>();
        var out = new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8) {
            @Override
            public void println(String line) {
                if (line != null && line.startsWith("[TRACE] ")) traces.add(line.substring(8));
            }
        };
        var original = System.out;
        try (var loader = new URLClassLoader(new URL[]{dir.toUri().toURL()}, SwitchGuardTest.class.getClassLoader())) {
            var method = Arrays.stream(loader.loadClass("Probe").getDeclaredMethods())
                    .filter(m -> m.getName().equals("m")).findFirst().orElseThrow();
            method.setAccessible(true);
            Object[] values = Arrays.stream(args).map(SwitchGuardTest::javaValue).toArray();
            System.setOut(out);
            try {
                Object result = method.invoke(null, values);
                return new Outcome(result instanceof Boolean b ? (b ? BigInteger.ONE : BigInteger.ZERO) : (BigInteger) result,
                        false, List.copyOf(traces));
            } catch (InvocationTargetException e) {
                return new Outcome(null, true, List.copyOf(traces));
            } finally {
                System.setOut(original);
            }
        }
    }

    private static Object javaValue(Object arg) {
        if (arg instanceof long[] list) return JulcList.of(Arrays.stream(list).mapToObj(BigInteger::valueOf).toArray(BigInteger[]::new));
        return BigInteger.valueOf((Long) arg);
    }

    private static PlutusData dataValue(Object arg) {
        if (arg instanceof long[] list) return PlutusData.list(Arrays.stream(list).mapToObj(PlutusData::integer).toArray(PlutusData[]::new));
        return PlutusData.integer((Long) arg);
    }

    /** JuLC: compiles {@code m} at {@code level} and evaluates it on {@code vm}. */
    static Outcome julc(String source, OptimizationLevel level, String vm, Object... args) {
        var program = new JulcCompiler(StdlibRegistry.defaultRegistry(),
                new CompilerOptions().setOptimizationLevel(level)).compileMethod(source, "m").program();
        var data = Arrays.stream(args).map(SwitchGuardTest::dataValue).toList();
        var result = CompilerTestVm.pv11(vm).evaluateWithArgs(program, data);
        return switch (result) {
            case EvalResult.Success s -> new Outcome(integer(s.resultTerm()), false, s.traces());
            case EvalResult.Failure f -> new Outcome(null, true, f.traces());
            default -> fail("unexpected " + result);
        };
    }

    private static BigInteger integer(Term term) {
        if (term instanceof Term.Const c && c.value() instanceof Constant.IntegerConst i) return i.value();
        if (term instanceof Term.Const c && c.value() instanceof Constant.BoolConst b) return b.value() ? BigInteger.ONE : BigInteger.ZERO;
        return fail("not an integer: " + term);
    }

    /** JuLC agrees with the JVM on every input, at every level, on both VMs. */
    static void matchesJava(String members, Object[]... inputs) throws Exception {
        String source = probe(members);
        for (Object[] args : inputs) {
            var expected = java(source, args);
            for (var level : OptimizationLevel.values()) {
                for (var vm : List.of("Java", "Scalus")) {
                    var actual = julc(source, level, vm, args);
                    assertEquals(expected, actual, vm + "/" + level + " " + Arrays.toString(args) + "\n" + source);
                }
            }
        }
    }

    static Object[] in(Object... args) {
        return Arrays.stream(args).map(a -> a instanceof Integer i ? (Object) i.longValue() : a).toArray();
    }

    // ---- the #275 shapes

    @Test
    void guardReadsItsBinding() throws Exception {
        matchesJava(S + """
                    static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        return switch (s) {
                            case A x when x.v().compareTo(BigInteger.TEN) > 0 -> BigInteger.ONE;
                            case A x -> BigInteger.TWO;
                            case B y -> BigInteger.ZERO;
                        };
                    }
                """, in(-3), in(10), in(20));
    }

    @Test
    void guardPassesItsWholeBindingToAHelper() throws Exception {
        // The binding itself, not only its decoded fields; 'best' is also a static field the binding shadows.
        matchesJava(S + """
                    static A best = new A(BigInteger.valueOf(100));
                    static boolean positive(A a) { return a.v().signum() > 0; }
                    static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        return switch (s) {
                            case A best when positive(best) -> best.v();
                            case A x when x.equals(best) -> BigInteger.TEN;
                            case A x -> BigInteger.ONE;
                            case B y -> BigInteger.ZERO;
                        };
                    }
                """, in(5), in(-5), in(100));
    }

    @Test
    void guardReadsParameterAndStaticField() throws Exception {
        matchesJava(S + """
                    static BigInteger LIMIT = BigInteger.valueOf(7);
                    static BigInteger m(BigInteger a, BigInteger b) {
                        S s = b.signum() > 0 ? new A(a) : new B(a);
                        return switch (s) {
                            case A x when b.compareTo(LIMIT) > 0 -> BigInteger.ONE;
                            case A x when x.v().compareTo(LIMIT) < 0 -> BigInteger.TWO;
                            case A x -> x.v();
                            case B y when y.v().signum() == 0 -> BigInteger.TEN;
                            default -> BigInteger.valueOf(-1);
                        };
                    }
                """, in(3, 8), in(3, 2), in(9, 2), in(0, -1), in(4, -1));
    }

    @Test
    void severalGuardedArmsWithDifferentBindingNames() throws Exception {
        matchesJava(S + """
                    static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        return switch (s) {
                            case A x when x.v().signum() > 0 -> BigInteger.ONE;
                            case A y when y.v().compareTo(BigInteger.valueOf(-10)) < 0 -> y.v().negate();
                            case A z -> z.v().add(BigInteger.valueOf(100));
                            case B w -> BigInteger.ZERO;
                        };
                    }
                """, in(5), in(-30), in(-3), in(0));
    }

    @Test
    void guardedArmThenDefault() throws Exception {
        matchesJava(S + """
                    static BigInteger m(BigInteger a) {
                        S s = a.signum() >= 0 ? new A(a) : new B(a);
                        return switch (s) {
                            case A x when x.v().compareTo(BigInteger.TEN) > 0 -> BigInteger.ONE;
                            default -> BigInteger.TWO;
                        };
                    }
                """, in(-3), in(3), in(30));
    }

    @Test
    void failingGuardFailsTheSwitch() throws Exception {
        matchesJava(S + """
                    static BigInteger m(BigInteger a, BigInteger b) {
                        S s = new A(a);
                        return switch (s) {
                            case A x when x.v().divide(b).signum() > 0 -> BigInteger.ONE;
                            case A x -> BigInteger.TWO;
                            case B y -> BigInteger.ZERO;
                        };
                    }
                """, in(3, 0), in(3, 1), in(-3, 1));
    }

    @Test
    void guardsAndBodiesTraceInJavaOrder() throws Exception {
        matchesJava(S + """
                    static BigInteger said(String message, BigInteger value) {
                        return Builtins.unIData(Builtins.trace(message, Builtins.iData(value)));
                    }
                    static boolean check(String message, boolean value) {
                        // equals, not signum: signum evaluates its receiver twice (a pre-existing issue).
                        return said(message, BigInteger.ONE).equals(BigInteger.ONE) && value;
                    }
                    static BigInteger m(BigInteger a) {
                        BigInteger b = said("selector", a);
                        S s = b.signum() >= 0 ? new A(b) : new B(b);
                        return switch (s) {
                            case A x when check("guard 1", x.v().compareTo(BigInteger.TEN) > 0) -> said("body 1", BigInteger.ONE);
                            case A x when check("guard 2", x.v().signum() > 0) -> said("body 2", BigInteger.TWO);
                            case B y when check("guard 3", y.v().compareTo(BigInteger.valueOf(-10)) < 0) -> said("body 3", BigInteger.TEN);
                            default -> said("default", BigInteger.ZERO);
                        };
                    }
                """, in(20), in(5), in(0), in(-20), in(-5));
    }

    // ---- constant guards (JLS 15.29)

    @Test
    void constantTrueGuardsAreUnguarded() throws Exception {
        matchesJava(S + """
                    static final boolean ON = true;
                    static BigInteger m(BigInteger a) {
                        S s = a.signum() > 0 ? new A(a) : new B(a);
                        BigInteger first = switch (s) {
                            case A x when true -> BigInteger.ONE;
                            case B y -> BigInteger.ZERO;
                        };
                        BigInteger second = switch (s) {
                            case A x when ON -> BigInteger.TEN;
                            case B y when 1 == 1 -> BigInteger.TWO;
                        };
                        return first.add(second);
                    }
                """, in(4), in(-4));
    }

    // ---- forms

    @Test
    void colonFormThatYieldsInEveryGroup() throws Exception {
        matchesJava(S + """
                    static BigInteger m(BigInteger a) {
                        S s = a.signum() > 0 ? new A(a) : new B(a);
                        return switch (s) {
                            case A x when x.v().compareTo(BigInteger.TEN) > 0:
                                yield BigInteger.ONE;
                            case A x:
                                BigInteger doubled = x.v().add(x.v());
                                yield doubled;
                            default:
                                yield BigInteger.ZERO;
                        };
                    }
                """, in(20), in(3), in(-1));
    }

    @Test
    void nestedSwitchInGuardedArmAndGuard() throws Exception {
        matchesJava(S + """
                    sealed interface T permits C, D {}
                    record C(BigInteger w) implements T {}
                    record D(BigInteger w) implements T {}
                    static BigInteger m(BigInteger a, BigInteger b) {
                        S s = new A(a);
                        T t = b.signum() > 0 ? new C(b) : new D(b);
                        return switch (s) {
                            case A x when (switch (t) { case C c when c.w().compareTo(x.v()) > 0 -> true; default -> false; }) ->
                                switch (t) {
                                    case C c when c.w().compareTo(BigInteger.TEN) > 0 -> c.w();
                                    case C c -> x.v();
                                    case D d -> BigInteger.valueOf(-7);
                                };
                            case A x -> x.v().negate();
                            case B y -> BigInteger.ZERO;
                        };
                    }
                """, in(1, 5), in(1, 50), in(5, 1), in(5, -1));
    }

    @Test
    void guardInstanceofIsATypeTest() throws Exception {
        matchesJava(S + """
                    sealed interface T permits C, D {}
                    record C(BigInteger w) implements T {}
                    record D(BigInteger w) implements T {}
                    sealed interface U permits P, Q {}
                    record P(T t) implements U {}
                    record Q(BigInteger z) implements U {}
                    static BigInteger m(BigInteger a) {
                        U u = a.signum() > 0 ? new P(new C(a)) : a.signum() < 0 ? new P(new D(a)) : new Q(a);
                        return switch (u) {
                            case P p when p.t() instanceof C -> BigInteger.ONE;
                            case P p when !(p.t() instanceof C) && p.t() instanceof D -> BigInteger.TWO;
                            case P p -> BigInteger.TEN;
                            case Q q -> q.z();
                        };
                    }
                """, in(3), in(-3), in(0));
    }

    @Test
    void booleanGuard() throws Exception {
        matchesJava(S + """
                    static Boolean big(BigInteger x) { return x.compareTo(BigInteger.TEN) > 0; }
                    static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        return switch (s) {
                            case A x when big(x.v()) -> BigInteger.ONE;
                            case A x -> BigInteger.TWO;
                            case B y -> BigInteger.ZERO;
                        };
                    }
                """, in(-3), in(30));
    }

    @Test
    void singleConstructorSumType() throws Exception {
        matchesJava("""
                    sealed interface O permits OA {}
                    record OA(BigInteger v) implements O {}
                    static BigInteger m(BigInteger a) {
                        O o = new OA(a);
                        return switch (o) {
                            case OA x when x.v().signum() < 0 -> BigInteger.ONE;
                            case OA x -> x.v();
                        };
                    }
                """, in(-3), in(3));
    }

    // ---- loops (ADR-048, I11)

    @Test
    void guardsInsideForEachAndWhile() throws Exception {
        matchesJava(S + """
                    static BigInteger m(JulcList<BigInteger> xs) {
                        BigInteger acc = BigInteger.ZERO;
                        for (var k : xs) {
                            S s = k.signum() >= 0 ? new A(k) : new B(k);
                            acc = acc.add(switch (s) {
                                case A x when k.compareTo(BigInteger.TWO) > 0 -> BigInteger.valueOf(50);
                                case A x -> x.v();
                                case B y when y.v().signum() < 0 -> BigInteger.valueOf(1000);
                                default -> BigInteger.ZERO;
                            });
                        }
                        BigInteger i = BigInteger.ZERO;
                        while (i.compareTo(BigInteger.valueOf(4)) < 0) {
                            final BigInteger j = i;
                            S s = new A(j);
                            acc = acc.add(switch (s) {
                                case A x when x.v().compareTo(BigInteger.TWO) >= 0 -> BigInteger.valueOf(100000);
                                default -> BigInteger.ONE;
                            });
                            i = i.add(BigInteger.ONE);
                        }
                        return acc;
                    }
                """, in(new long[]{1, 2, -2}), in(new long[]{5, 6, -2}), in(new long[]{}));
    }

    // ---- scope (F9, I10)

    @Test
    void failedArmBindingNeverShadowsLaterArmOrDefault() throws Exception {
        matchesJava(S + """
                    static BigInteger limit = BigInteger.valueOf(99);
                    static BigInteger m(BigInteger a) {
                        S s = a.signum() > 0 ? new A(a) : a.signum() < 0 ? new B(a) : new A(BigInteger.valueOf(-1));
                        BigInteger later = switch (s) {
                            case A limit when limit.v().signum() > 0 -> BigInteger.ONE;
                            case A q -> limit;
                            case B q -> BigInteger.ZERO;
                        };
                        BigInteger viaDefault = switch (s) {
                            case A limit when limit.v().signum() > 0 -> BigInteger.ONE;
                            default -> limit;
                        };
                        BigInteger viaGuard = switch (s) {
                            case B limit when limit.v().compareTo(BigInteger.valueOf(-100)) < 0 -> BigInteger.ONE;
                            case B q when limit.signum() > 0 -> limit.add(BigInteger.ONE);
                            default -> BigInteger.ZERO;
                        };
                        return later.multiply(BigInteger.valueOf(10000)).add(viaDefault.multiply(BigInteger.valueOf(100))).add(viaGuard);
                    }
                """, in(5), in(0), in(-5));
    }

    // ---- size (I6)

    private static int size(String source) {
        var program = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source, "m").program();
        return UplcFlatEncoder.encodeProgram(program).length;
    }

    @Test
    void defaultBodyIsEmittedOnce() {
        // default serves three places here: the end of A's chain, the end of B's chain and C, which has no arm.
        String big = "a.multiply(a).add(a.multiply(BigInteger.valueOf(17))).subtract(a.multiply(a).multiply(a))"
                + ".add(BigInteger.valueOf(123456789)).multiply(a.add(BigInteger.valueOf(987654321)))";
        String declarations = """
                    sealed interface S3 permits A3, B3, C3 {}
                    record A3(BigInteger v) implements S3 {}
                    record B3(BigInteger v) implements S3 {}
                    record C3(BigInteger v) implements S3 {}
                """;
        java.util.function.Function<String, String> guarded = body -> probe(declarations + """
                    static BigInteger m(BigInteger a) {
                        S3 s = new A3(a);
                        return switch (s) {
                            case A3 x when x.v().signum() > 0 -> BigInteger.ONE;
                            case B3 y when y.v().signum() > 0 -> BigInteger.TWO;
                            default -> %s;
                        };
                    }
                """.formatted(body));
        java.util.function.Function<String, String> plain = body -> probe("""
                    static BigInteger m(BigInteger a) {
                        return %s;
                    }
                """.formatted(body));
        int once = size(plain.apply(big)) - size(plain.apply("BigInteger.ZERO"));
        int inSwitch = size(guarded.apply(big)) - size(guarded.apply("BigInteger.ZERO"));
        assertTrue(inSwitch < once + once / 2, "default body costs " + inSwitch + " bytes in the switch, " + once + " alone");
    }

    @Test
    void sizeIsLinearInGuardedArms() {
        var sizes = new ArrayList<Integer>();
        for (int n = 1; n <= 12; n++) {
            var arms = new StringBuilder();
            for (int i = 0; i < n; i++) {
                // Every arm reads the field 'limit' that the arm's own binding shadows, so each
                // continuation is a join, not inlined: the worst case for size.
                arms.append("            case A limit when limit.v().compareTo(BigInteger.valueOf(").append(i)
                        .append(")) == 0 -> limit.v().add(BigInteger.valueOf(").append(i).append("));\n");
                arms.append("            case B q when q.v().compareTo(BigInteger.valueOf(").append(i)
                        .append(")) == 0 -> limit;\n");
            }
            var source = probe(S + "    static BigInteger limit = BigInteger.valueOf(99);\n"
                    + "    static BigInteger m(BigInteger a) {\n        S s = new A(a);\n        return switch (s) {\n"
                    + arms + "            default -> limit;\n        };\n    }\n");
            var program = new JulcCompiler(StdlibRegistry.defaultRegistry()).compileMethod(source, "m").program();
            sizes.add(UplcFlatEncoder.encodeProgram(program).length);
        }
        // Linear: every added pair of arms costs about the same; exponential growth would at least double it.
        var steps = new ArrayList<Integer>();
        for (int i = 1; i < sizes.size(); i++) steps.add(sizes.get(i) - sizes.get(i - 1));
        int first = steps.getFirst();
        for (int step : steps) {
            assertTrue(step <= first + 8 && step >= first - 8, "sizes " + sizes + ", steps " + steps);
        }
    }
}
