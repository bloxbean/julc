package org.julclang.difftest;

import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.PlutusData;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ADR-067 outcome-matrix rows with checker P: sources that javac 21.0.3 rejects (or that JuLC rejects on purpose),
 * which the {@code .diffcase} format cannot hold. Each case records javac 21.0.3's verdict, JuLC's verdict at
 * the base ({@code fd605e77}, history) and JuLC's expected verdict now; both current verdicts are checked.
 * Disagreements between javac 21.0.3 and the build JDK's {@code javac --release 21} are printed, not checked.
 */
class Javac21ParityTest {

    /** What JuLC must do: compile and return {@code value} at every level, or reject with {@code code}. */
    record Julc(BigInteger value, String code) {
        static Julc value(long value) {
            return new Julc(BigInteger.valueOf(value), null);
        }

        static Julc rejects(String code) {
            return new Julc(null, code);
        }

        @Override
        public String toString() {
            return value != null ? "accepts, " + value : "REJECT " + code;
        }
    }

    /**
     * One parity case. {@code javac21} is null when javac 21.0.3 accepts, else a fragment of its first error;
     * {@code stubs} are extra sources javac needs (JuLC's own types, which a Java 21 javac cannot read from its
     * Java 25 class files); JuLC compiles {@code source} only.
     */
    record ParityCase(String name, String row, String source, Map<String, String> stubs, List<PlutusData> args,
                      String javac21, String julcBase, Julc julc) {
        Map<String, String> javacSources() {
            var sources = new LinkedHashMap<String, String>();
            sources.put(source.contains("package org.julclang.ledger;") ? "org/julclang/ledger/Probe.java" : "Probe.java", source);
            sources.putAll(stubs);
            return sources;
        }
    }

    static final String S = """
                sealed interface S permits A, B {}
                record A(BigInteger v) implements S {}
                record B(BigInteger v) implements S {}
            """;

    static String probe(String declarations, String method) {
        return "import java.math.BigInteger;\n\npublic class Probe {\n" + declarations + "\n" + method + "\n}\n";
    }

    static String switchOverA(String arms) {
        return probe(S, """
                    public static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        return switch (s) {
                """ + arms + """
                        };
                    }
                """);
    }

    private static final List<PlutusData> FIVE = List.of(PlutusData.integer(5));

    /** The hand-written list: every P row of the outcome matrix (M0), with its current expectation. */
    static List<ParityCase> cases() {
        var cases = new ArrayList<ParityCase>();
        cases.add(new ParityCase("G6 only a guarded arm for A, no default", "G6", switchOverA("""
                            case A x when x.v().signum() > 0 -> BigInteger.ONE;
                            case B y -> BigInteger.ZERO;
                """), Map.of(), FIVE, "does not cover all possible input values", "accepts, 1", Julc.value(1)));
        cases.add(new ParityCase("G7 guarded arm after the same unguarded pattern", "G7", switchOverA("""
                            case A x -> BigInteger.ONE;
                            case A x when x.v().signum() > 0 -> BigInteger.TWO;
                            case B y -> BigInteger.ZERO;
                """), Map.of(), FIVE, "dominated by a preceding case label", "accepts, 1", Julc.value(1)));
        cases.add(new ParityCase("G8 when false", "G8", switchOverA("""
                            case A x when false -> BigInteger.ONE;
                            case A x -> BigInteger.TWO;
                            case B y -> BigInteger.ZERO;
                """), Map.of(), FIVE, "constant expression with value 'false'", "accepts, 1", Julc.value(1)));
        cases.add(new ParityCase("H1 guard reads a loop-updated local", "H1", probe(S, """
                    public static BigInteger m(BigInteger a) {
                        BigInteger lim = BigInteger.ZERO;
                        while (lim.compareTo(BigInteger.TEN) < 0) {
                            lim = lim.add(BigInteger.ONE);
                        }
                        S s = new A(a);
                        return switch (s) {
                            case A x when x.v().compareTo(lim) > 0 -> BigInteger.ONE;
                            case A x -> BigInteger.TWO;
                            case B y -> BigInteger.ZERO;
                        };
                    }
                """), Map.of(), FIVE, "must be final or effectively final", "accepts, 1", Julc.value(1)));
        cases.add(new ParityCase("F13a when 3", "F13a", switchOverA("""
                            case A x when 3 -> BigInteger.ONE;
                            case A x -> BigInteger.TWO;
                            case B y -> BigInteger.ZERO;
                """), Map.of(), FIVE, "incompatible types: int cannot be converted to boolean", "accepts, 1", Julc.value(1)));
        cases.add(new ParityCase("F13b two default labels", "F13b", probe(S, """
                    public static BigInteger m(BigInteger a) {
                        S s = new B(a);
                        return switch (s) {
                            case A x -> BigInteger.ONE;
                            default -> BigInteger.TWO;
                            default -> BigInteger.TEN;
                        };
                    }
                """), Map.of(), FIVE, "duplicate default label", "accepts, 10", Julc.value(10)));
        cases.add(new ParityCase("F13c binding named like a parameter", "F13c", switchOverA("""
                            case A a -> BigInteger.ONE;
                            case B y -> BigInteger.ZERO;
                """), Map.of(), FIVE, "variable a is already defined", "accepts, 1", Julc.value(1)));
        cases.add(new ParityCase("F14b parameterized sealed selector, javac-invalid", "F14b", probe("""
                    sealed interface G<X> permits GC, GD {}
                    record GC(BigInteger c) implements G<String> {}
                    record GD(BigInteger d) implements G<BigInteger> {}
                """, """
                    public static BigInteger m(BigInteger a) {
                        G<BigInteger> g = new GD(a);
                        return switch (g) {
                            case GC c -> BigInteger.ONE;
                            case GD d -> d.d();
                        };
                    }
                """), Map.of(), FIVE, "incompatible types", "accepts, 5", Julc.value(5)));
        cases.add(new ParityCase("javac 21/25 Pair shape", "Pair", probe("""
                    sealed interface T permits C, D {}
                    record C(BigInteger w) implements T {}
                    record D(BigInteger w) implements T {}
                    sealed interface PP permits Pair {}
                    record Pair(T l, T r) implements PP {}
                """, """
                    public static BigInteger m(BigInteger a) {
                        PP pp = new Pair(new D(a), new D(a));
                        return switch (pp) {
                            case Pair(C c, var t) -> BigInteger.ONE;
                            case Pair(var t, C c) -> BigInteger.TWO;
                            case Pair(D d, D d2) -> d.w();
                        };
                    }
                """), Map.of(), FIVE, "does not cover all possible input values", "REJECT none", Julc.rejects("none")));
        cases.add(new ParityCase("a user source declaring a bundled ledger FQCN", "FQCN", """
                package org.julclang.ledger;

                import java.math.BigInteger;

                record Value(BigInteger x) {}

                public class Probe {
                    public static BigInteger m(BigInteger a) {
                        Value v = new Value(a);
                        return v.x();
                    }
                }
                """, Map.of(), FIVE, null, "REJECT JULC0055", Julc.rejects("JULC0055")));
        cases.add(new ParityCase("F23 a ledger Rational assigned to a user Rational (#287)", "F23", """
                import java.math.BigInteger;

                public class Probe {
                    record Rational(BigInteger denominator, BigInteger numerator) {}

                    public static BigInteger m(BigInteger a, BigInteger b) {
                        Rational q = new org.julclang.ledger.Rational(a, b);
                        return q.numerator();
                    }
                }
                """, Map.of("org/julclang/ledger/Rational.java", """
                package org.julclang.ledger;

                import java.math.BigInteger;

                public record Rational(BigInteger numerator, BigInteger denominator) {}
                """), List.of(PlutusData.integer(5), PlutusData.integer(7)), "incompatible types",
                "accepts, 7", Julc.value(7)));
        cases.add(new ParityCase("R5 unnamed pattern variable", "R5", switchOverA("""
                            case A(var _) -> BigInteger.ONE;
                            case B y -> BigInteger.ZERO;
                """), Map.of(), FIVE, "unnamed variables are a preview feature", "REJECT none", Julc.rejects("none")));
        return cases;
    }

    @TestFactory
    Stream<DynamicTest> parity() {
        var cases = cases();
        var sources = new LinkedHashMap<String, Map<String, String>>();
        cases.forEach(c -> sources.put(c.name(), c.javacSources()));
        var verdicts = Javac21.compile(sources);
        var uplc = new UplcExecutor();
        return cases.stream().map(c -> DynamicTest.dynamicTest(c.row() + ": " + c.name(), () -> {
            var javac = verdicts.get(c.name());
            var release21 = Javac21.javacRelease21(c.javacSources());
            if (release21.accepted() != javac.accepted()) {
                System.out.println("[javac 21.0.3 vs javac " + Runtime.version().feature() + " --release 21] " + c.name()
                        + ": " + javac + " / " + release21);
            }
            if (c.javac21() == null) {
                assertTrue(javac.accepted(), c.name() + ": javac 21.0.3 should accept, " + javac);
            } else {
                assertTrue(!javac.accepted() && javac.error().contains(c.javac21()),
                        c.name() + ": javac 21.0.3 should reject with '" + c.javac21() + "', " + javac);
            }
            checkJulc(uplc, c);
        }));
    }

    static void checkJulc(UplcExecutor uplc, ParityCase c) {
        var diffCase = new DiffCase(c.name(), c.source(), "Probe", "m", c.args());
        for (var level : OptimizationLevel.values()) {
            var observation = uplc.run(diffCase, new DiffConfig(level, "Java")).observation();
            String label = c.name() + " at " + level + ": " + ObservationText.format(observation);
            if (c.julc().code() != null) {
                if (!(observation instanceof Observation.Rejected r)) {
                    fail(label + ", expected " + c.julc());
                    return;
                }
                assertEquals(c.julc().code(), r.code() == null ? "none" : r.code(), label + " " + r.message());
            } else if (observation instanceof Observation.Value v && v.data() instanceof PlutusData.IntData i) {
                assertEquals(c.julc().value(), i.value(), label);
            } else {
                fail(label + ", expected " + c.julc());
            }
        }
    }
}
