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
 * <p>
 * M1 moved G6, G7, G8, H1, F13a, F13b and F13c to their final codes, and F14b to {@code JULC0007} (the ADR places it in
 * M2b; it shares M1's selector check). The {@code Pair} shape is {@code JULC0076} in M1 (record patterns are rejected
 * until M2a, review F35); M2b gives it {@code JULC0005} for the JLS 21 reason. The rows after R5 come from the M1
 * review (F28, F51, F52, F54, F55, F56, F57).
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
                """), Map.of(), FIVE, "does not cover all possible input values", "accepts, 1", Julc.rejects("JULC0005")));
        cases.add(new ParityCase("G7 guarded arm after the same unguarded pattern", "G7", switchOverA("""
                            case A x -> BigInteger.ONE;
                            case A x when x.v().signum() > 0 -> BigInteger.TWO;
                            case B y -> BigInteger.ZERO;
                """), Map.of(), FIVE, "dominated by a preceding case label", "accepts, 1", Julc.rejects("JULC0071")));
        cases.add(new ParityCase("G8 when false", "G8", switchOverA("""
                            case A x when false -> BigInteger.ONE;
                            case A x -> BigInteger.TWO;
                            case B y -> BigInteger.ZERO;
                """), Map.of(), FIVE, "constant expression with value 'false'", "accepts, 1", Julc.rejects("JULC0072")));
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
                """), Map.of(), FIVE, "must be final or effectively final", "accepts, 1", Julc.rejects("JULC0073")));
        cases.add(new ParityCase("F13a when 3", "F13a", switchOverA("""
                            case A x when 3 -> BigInteger.ONE;
                            case A x -> BigInteger.TWO;
                            case B y -> BigInteger.ZERO;
                """), Map.of(), FIVE, "incompatible types: int cannot be converted to boolean", "accepts, 1", Julc.rejects("JULC0072")));
        cases.add(new ParityCase("F13b two default labels", "F13b", probe(S, """
                    public static BigInteger m(BigInteger a) {
                        S s = new B(a);
                        return switch (s) {
                            case A x -> BigInteger.ONE;
                            default -> BigInteger.TWO;
                            default -> BigInteger.TEN;
                        };
                    }
                """), Map.of(), FIVE, "duplicate default label", "accepts, 10", Julc.rejects("JULC0082")));
        cases.add(new ParityCase("F13c binding named like a parameter", "F13c", switchOverA("""
                            case A a -> BigInteger.ONE;
                            case B y -> BigInteger.ZERO;
                """), Map.of(), FIVE, "variable a is already defined", "accepts, 1", Julc.rejects("JULC0083")));
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
                """), Map.of(), FIVE, "incompatible types", "accepts, 5", Julc.rejects("JULC0007")));
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
                """), Map.of(), FIVE, "does not cover all possible input values", "REJECT none", Julc.rejects("JULC0076")));
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
        // ---- M1 review rows
        var plutusStubs = Map.of(
                "org/julclang/core/PlutusData.java", "package org.julclang.core;\npublic interface PlutusData {}\n",
                "org/julclang/stdlib/Builtins.java", """
                package org.julclang.stdlib;
                public final class Builtins {
                    public static org.julclang.core.PlutusData iData(java.math.BigInteger v) { return null; }
                }
                """);
        String plutus = probe(S, """
                    public static BigInteger m(BigInteger a, org.julclang.core.PlutusData d) {
                        S s = new A(a);
                        return switch (s) { case A x when d -> BigInteger.ONE; default -> BigInteger.TWO; };
                    }
                """);
        cases.add(new ParityCase("F28 a PlutusData guard", "F28", plutus, plutusStubs,
                List.of(PlutusData.integer(5), PlutusData.constr(1)), "incompatible types", "added in the M1 review", Julc.rejects("JULC0072")));
        cases.add(new ParityCase("F28 a Builtins.iData guard", "F28b", switchOverA("""
                            case A x when Builtins.iData(x.v()) -> BigInteger.ONE;
                            default -> BigInteger.TWO;
                """).replace("import java.math.BigInteger;", "import java.math.BigInteger;\nimport org.julclang.stdlib.Builtins;"), plutusStubs, FIVE, "incompatible types", "added in the M1 review", Julc.rejects("JULC0072")));
        cases.add(new ParityCase("F52 (Object) true", "F52a", switchOverA("""
                            case A x when (Object) true -> BigInteger.ONE;
                            default -> BigInteger.TWO;
                """), Map.of(), FIVE, "incompatible types", "added in the M1 review", Julc.rejects("JULC0072")));
        cases.add(new ParityCase("F52 true ? true : 0", "F52b", switchOverA("""
                            case A x when (true ? true : 0) -> BigInteger.ONE;
                            default -> BigInteger.TWO;
                """), Map.of(), FIVE, "incompatible types", "added in the M1 review", Julc.rejects("JULC0072")));
        cases.add(new ParityCase("F52 x.v() > 0 ? true : 0", "F52c", switchOverA("""
                            case A x when (x.v().signum() > 0 ? true : 0) -> BigInteger.ONE;
                            default -> BigInteger.TWO;
                """), Map.of(), FIVE, "incompatible types", "added in the M1 review", Julc.rejects("JULC0072")));
        cases.add(new ParityCase("F54 (boolean) false", "F54a", switchOverA("""
                            case A x when (boolean) false -> BigInteger.ONE;
                            case A x -> BigInteger.TWO;
                            case B y -> BigInteger.ZERO;
                """), Map.of(), FIVE, "constant expression with value 'false'", "added in the M1 review", Julc.rejects("JULC0072")));
        cases.add(new ParityCase("F54 \"a\" == \"b\"", "F54b", switchOverA("""
                            case A x when "a" == "b" -> BigInteger.ONE;
                            case A x -> BigInteger.TWO;
                            case B y -> BigInteger.ZERO;
                """), Map.of(), FIVE, "constant expression with value 'false'", "added in the M1 review", Julc.rejects("JULC0072")));
        cases.add(new ParityCase("F54 promoted conditional, constant true", "F54c", switchOverA("""
                            case A x when ((true ? 2147483647 : 0L) + 1) > 0 -> BigInteger.ONE;
                            case B y -> BigInteger.ZERO;
                """), Map.of(), FIVE, null, "added in the M1 review", Julc.value(1)));
        String families = """
                    sealed interface T permits C, D {}
                    record C(BigInteger w) implements T {}
                    record D(BigInteger w) implements T {}
                """;
        cases.add(new ParityCase("F51 guard instanceof across families, uncast", "F51", probe(S + families, """
                    public static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        return switch (s) { case A x when x instanceof C -> BigInteger.ONE; default -> BigInteger.TWO; };
                    }
                """), Map.of(), FIVE, "incompatible types", "added in the M1 review", Julc.rejects("JULC0072")));
        cases.add(new ParityCase("F55 nested pattern variable named like a colon-group local", "F55a", switchOverA("""
                            case A x:
                                BigInteger y = x.v();
                                S t = new B(y);
                                yield switch (t) { case A p -> BigInteger.ONE; case B y -> BigInteger.TWO; };
                            default: yield BigInteger.ZERO;
                """), Map.of(), FIVE, "already defined", "added in the M1 review", Julc.rejects("JULC0083")));
        cases.add(new ParityCase("F55 guard reads a reassigned colon-group local", "F55b", switchOverA("""
                            case A x:
                                BigInteger lim = BigInteger.ZERO;
                                while (lim.compareTo(BigInteger.TEN) < 0) { lim = lim.add(BigInteger.ONE); }
                                S t = new A(lim);
                                yield switch (t) { case A p when p.v().compareTo(lim) > 0 -> BigInteger.ONE; default -> BigInteger.TWO; };
                            default: yield BigInteger.ZERO;
                """), Map.of(), FIVE, "effectively final", "added in the M1 review", Julc.rejects("JULC0073")));
        cases.add(new ParityCase("F56 a qualified pattern type of another declaration", "F56", probe(S + """
                    static final class Other {
                        sealed interface U permits A {}
                        record A(BigInteger v) implements U {}
                    }
                """, """
                    public static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        return switch (s) { case Other.A x when x.v().signum() > 0 -> BigInteger.ONE; default -> BigInteger.TWO; };
                    }
                """), Map.of(), FIVE, "incompatible types", "added in the M1 review", Julc.rejects("JULC0076")));
        cases.add(new ParityCase("F57 arrow rule then colon group", "F57a", switchOverA("""
                            case A x when x.v().signum() > 0 -> BigInteger.ONE;
                            default: yield BigInteger.TWO;
                """), Map.of(), FIVE, "different case kinds", "added in the M1 review", Julc.rejects("JULC0075")));
        cases.add(new ParityCase("F57 colon group then arrow rule", "F57b", switchOverA("""
                            case A x: yield BigInteger.ONE;
                            default -> BigInteger.TWO;
                """), Map.of(), FIVE, "different case kinds", "added in the M1 review", Julc.rejects("JULC0075")));
        // ---- M1 review round 2 rows (javac rejects each; JuLC rejects with a code)
        String round2 = "added in M1 review round 2";
        cases.add(new ParityCase("F60 a conditional whose condition is an int", "F60a", switchOverA("""
                            case A x when (1 ? true : false) -> BigInteger.ONE;
                            default -> BigInteger.TWO;
                """), Map.of(), FIVE, "incompatible types", round2, Julc.rejects("JULC0072")));
        cases.add(new ParityCase("F60 == on a conditional of boolean and int", "F60b", switchOverA("""
                            case A x when (x.v().signum() > 0 ? true : 0) == true -> BigInteger.ONE;
                            default -> BigInteger.TWO;
                """), Map.of(), FIVE, "bad operand types", round2, Julc.rejects("JULC0072")));
        cases.add(new ParityCase("F62 instanceof a sibling variant", "F62a", switchOverA("""
                            case A x when x instanceof B -> BigInteger.ONE;
                            default -> BigInteger.TWO;
                """), Map.of(), FIVE, "incompatible types", round2, Julc.rejects("JULC0072")));
        cases.add(new ParityCase("F62 instanceof a same-named declaration", "F62b", probe(S + """
                    static final class Other {
                        record A(BigInteger v) {}
                    }
                """, """
                    public static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        Other.A o = new Other.A(a);
                        return switch (s) { case A x when o instanceof A -> BigInteger.ONE; default -> BigInteger.TWO; };
                    }
                """), Map.of(), FIVE, "incompatible types", round2, Julc.rejects("JULC0072")));
        cases.add(new ParityCase("F63 a char conditional, constant false", "F63", switchOverA("""
                            case A x when ("x" + (true ? 'a' : 98)) == "x97" -> BigInteger.ONE;
                            case A x -> BigInteger.TWO;
                            case B y -> BigInteger.ZERO;
                """), Map.of(), FIVE, "constant expression with value 'false'", round2, Julc.rejects("JULC0072")));
        cases.add(new ParityCase("F64 an unqualified pattern type naming another A", "F64", probe("""
                    static final class Other {
                        sealed interface S permits A {}
                        record A(BigInteger v) implements S {}
                    }
                    record A(BigInteger v) {}
                """, """
                    public static BigInteger m(BigInteger a) {
                        Other.S s = new Other.A(a);
                        return switch (s) { case A x -> BigInteger.ONE; };
                    }
                """), Map.of(), FIVE, "incompatible types", round2, Julc.rejects("JULC0076")));
        cases.add(new ParityCase("F65 a cast to a user type named Boolean", "F65", probe(S + "    static final class Boolean {}\n", """
                    public static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        return switch (s) { case A x when (Boolean) true -> BigInteger.ONE; default -> BigInteger.TWO; };
                    }
                """), Map.of(), FIVE, "incompatible types", round2, Julc.rejects("JULC0072")));
        cases.add(new ParityCase("F66 a narrowing constant initializer", "F66a", probe(S + "    static final int N = 4294967296L;\n", """
                    public static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        return switch (s) { case A x when N > 0 -> BigInteger.ONE; case B y -> BigInteger.ZERO; };
                    }
                """), Map.of(), FIVE, "possible lossy conversion from long to int", round2, Julc.rejects("JULC0072")));
        cases.add(new ParityCase("F66 an instance field read from a static method", "F66b", probe(S + "    final boolean ON = true;\n", """
                    public static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        return switch (s) { case A x when ON -> BigInteger.ONE; case B y -> BigInteger.ZERO; };
                    }
                """), Map.of(), FIVE, "non-static variable ON cannot be referenced from a static context", round2, Julc.rejects("JULC0072")));
        cases.add(new ParityCase("F39 a Data-typed library call inside &&", "F39", switchOverA("""
                            case A x when Builtins.iData(x.v()) && x.v().signum() > 0 -> BigInteger.ONE;
                            default -> BigInteger.TWO;
                """).replace("import java.math.BigInteger;", "import java.math.BigInteger;\nimport org.julclang.stdlib.Builtins;"),
                plutusStubs, FIVE, "bad operand types", round2, Julc.rejects("JULC0072")));
        // ---- M1 review round 3 rows
        String round3 = "added in M1 review round 3";
        cases.add(new ParityCase("F70 a long constant shifted past int width, constant false", "F70", probe(S + "    static final long N = 1;\n", """
                    public static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        return switch (s) { case A x when (N << 32) == 1 -> BigInteger.ONE; case A x -> BigInteger.TWO; case B y -> BigInteger.ZERO; };
                    }
                """), Map.of(), FIVE, "constant expression with value 'false'", round3, Julc.rejects("JULC0072")));
        cases.add(new ParityCase("F71 a narrowing declaration read by a non-constant guard", "F71", probe(S + "    static final int N = 4294967296L;\n", """
                    public static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        return switch (s) { case A x when N > x.v().signum() -> BigInteger.ONE; default -> BigInteger.ZERO; };
                    }
                """), Map.of(), FIVE, "possible lossy conversion", round3, Julc.rejects("JULC0072")));
        cases.add(new ParityCase("F71 a qualified narrowing declaration read by a non-constant guard", "F71b", probe(S + "    static final int N = 4294967296L;\n", """
                    public static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        return switch (s) { case A x when Probe.N > x.v().signum() -> BigInteger.ONE; default -> BigInteger.ZERO; };
                    }
                """), Map.of(), FIVE, "possible lossy conversion", round3, Julc.rejects("JULC0072")));
        cases.add(new ParityCase("F71 a narrowing declaration read through another constant", "F71c",
                probe(S + "    static final int N = 4294967296L;\n    static final int M = N;\n", """
                    public static BigInteger m(BigInteger a) {
                        S s = new A(a);
                        return switch (s) { case A x when M > x.v().signum() -> BigInteger.ONE; default -> BigInteger.ZERO; };
                    }
                """), Map.of(), FIVE, "possible lossy conversion", round3, Julc.rejects("JULC0072")));
        cases.add(new ParityCase("F74 a guard binding named like a local", "F74a", probe(S, """
                    public static BigInteger m(BigInteger a) {
                        BigInteger v = a;
                        S s = new A(a);
                        return switch (s) { case A x when !(s instanceof B v) -> BigInteger.ONE; default -> BigInteger.TWO; };
                    }
                """), Map.of(), FIVE, "already defined", round3, Julc.rejects("JULC0083")));
        cases.add(new ParityCase("F74 the same binding twice in one guard", "F74b", switchOverA("""
                            case A x when (s instanceof A a2) || (s instanceof A a2) -> BigInteger.ONE;
                            default -> BigInteger.TWO;
                """), Map.of(), FIVE, "illegal attempt to redefine an existing match binding", round3, Julc.rejects("JULC0083")));
        // ---- M1 review round 4 rows
        String round4 = "added in M1 review round 4";
        // F82a-d: since T25 (ADR-062) the literal itself is rejected first, JULC0070, before the guard checker's JULC0072.
        cases.add(new ParityCase("F82 an out-of-range int literal in a constant field", "F82a", probe(S + "    static final int N = 2147483648;\n", """
                    public static BigInteger m(BigInteger a) {
                        
                        S s = new A(a);
                        return switch (s) { case A x when N > x.v().signum() -> BigInteger.ONE; default -> BigInteger.ZERO; };
                    }
                """), Map.of(), FIVE, "integer number too large", round4, Julc.rejects("JULC0070")));
        cases.add(new ParityCase("F82 an out-of-range long literal in a constant field", "F82b", probe(S + "    static final long L = 9223372036854775808L;\n", """
                    public static BigInteger m(BigInteger a) {
                        
                        S s = new A(a);
                        return switch (s) { case A x when L > x.v().signum() -> BigInteger.ONE; default -> BigInteger.ZERO; };
                    }
                """), Map.of(), FIVE, "integer number too large", round4, Julc.rejects("JULC0070")));
        cases.add(new ParityCase("F82 an out-of-range literal in a final local", "F82c", probe(S + "", """
                    public static BigInteger m(BigInteger a) {
                        final int n = 2147483648;
                        S s = new A(a);
                        return switch (s) { case A x when n > x.v().signum() -> BigInteger.ONE; default -> BigInteger.ZERO; };
                    }
                """), Map.of(), FIVE, "integer number too large", round4, Julc.rejects("JULC0070")));
        cases.add(new ParityCase("F82 an out-of-range literal read through another constant", "F82d", probe(S + "    static final int N = 2147483648;\n    static final int M = N;\n", """
                    public static BigInteger m(BigInteger a) {
                        
                        S s = new A(a);
                        return switch (s) { case A x when M > x.v().signum() -> BigInteger.ONE; default -> BigInteger.ZERO; };
                    }
                """), Map.of(), FIVE, "integer number too large", round4, Julc.rejects("JULC0070")));
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
