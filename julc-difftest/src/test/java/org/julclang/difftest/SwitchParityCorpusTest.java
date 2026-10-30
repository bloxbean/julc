package org.julclang.difftest;

import org.julclang.compiler.CompilerException;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.error.CompilerDiagnostic;
import org.julclang.core.PlutusData;
import org.julclang.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-067 Verification 3 (I9), the M1 corpus: generated switch expressions over a fixed universe, each compiled by
 * javac 21.0.3 (by explicit path, {@link Javac21}) and by JuLC. JuLC must accept exactly what javac accepts, except
 * where the ADR rejects legal Java on purpose (colon-form fall-through and grouped labels, JULC0075; parameterized
 * sealed selectors, JULC0007), and every rejection must carry one of the D4 codes. A sample of the accepted programs
 * is also run on the JVM and on JuLC at every level.
 * <p>
 * Dimensions: every sequence of up to four labels drawn from {@code A}, {@code A when <guard>}, {@code B},
 * {@code B when <guard>} and {@code default} (arrow form; colon form up to three labels); every guard kind (literal
 * and constant-variable {@code true}/{@code false}, compound constants, a non-boolean guard, a parameter, an
 * effectively final local, a reassigned loop variable, a for-each variable, a {@code Boolean} method) in three
 * shapes; colon groups that fall through, grouped labels and several patterns per label; pattern variables that
 * collide with a parameter, a local, a for-each variable or an outer pattern variable, or reuse a field's name; a
 * one-constructor sealed type; a parameterized sealed type. Record patterns join in M2a/M2b.
 */
class SwitchParityCorpusTest {

    /** JavaParser's JAVA_21 grammar rejects the source before JuLC's checks run (uncoded, like any parse error). */
    private static final String PARSE_ERROR = "parse error";

    private static final Set<String> D4_CODES = Set.of("JULC0005", "JULC0007", "JULC0071", "JULC0072", "JULC0073",
            "JULC0074", "JULC0075", "JULC0076", "JULC0082", "JULC0083");

    /** A generated program; {@code intended} is the code JuLC must reject it with although javac accepts it. */
    record Program(String name, String source, String intended, boolean run) {
    }

    /** For javac only: JuLC's class files target Java 25, which javac 21.0.3 cannot read. */
    private static final String JULC_LIST_STUB = """
            package org.julclang.core.types;

            public interface JulcList<T> extends Iterable<T> {
                @SafeVarargs
                static <T> JulcList<T> of(T... elements) {
                    throw new UnsupportedOperationException();
                }
            }
            """;

    private static final String HEADER = """
            import java.math.BigInteger;
            import org.julclang.core.types.JulcList;

            public class Probe {
                sealed interface S permits A, B {}
                record A(BigInteger v) implements S {}
                record B(BigInteger v) implements S {}
                static final boolean ON = true;
                static final boolean OFF = false;
                static BigInteger x9 = BigInteger.TEN;
                static Boolean big(BigInteger x) { return x.compareTo(BigInteger.TEN) > 0; }
            """;

    private static String method(String prelude, String switchText) {
        return HEADER + """
                    public static BigInteger m(BigInteger a, BigInteger n) {
                        S s = a.signum() > 0 ? new A(a) : new B(a);
                """ + prelude + "        return " + switchText + ";\n    }\n}\n";
    }

    private static final String PARAM_GUARD = "n.signum() > 0";

    /** One arrow or colon label: {@code A}, {@code A?}, {@code B}, {@code B?} or {@code D}. */
    private static String label(String symbol, int index, boolean colon, String guard) {
        String result = "BigInteger.valueOf(" + index + ")";
        String head = switch (symbol) {
            case "D" -> "default";
            default -> "case " + symbol.charAt(0) + " x" + index + (symbol.endsWith("?") ? " when " + guard : "");
        };
        return "            " + head + (colon ? ": yield " + result + ";\n" : " -> " + result + ";\n");
    }

    private static String switchOf(List<String> symbols, boolean colon, String guard) {
        var text = new StringBuilder("switch (s) {\n");
        for (int i = 0; i < symbols.size(); i++) text.append(label(symbols.get(i), i, colon, guard));
        return text.append("        }").toString();
    }

    static List<Program> corpus() {
        var programs = new ArrayList<Program>();
        // 1. Label sequences.
        var alphabet = List.of("A", "A?", "B", "B?", "D");
        var sequences = new ArrayList<List<String>>();
        sequences.add(List.of());
        for (int length = 1; length <= 4; length++) {
            var next = new ArrayList<List<String>>();
            for (var prefix : sequences) {
                if (prefix.size() != length - 1) continue;
                for (var symbol : alphabet) {
                    var sequence = new ArrayList<>(prefix);
                    sequence.add(symbol);
                    next.add(sequence);
                }
            }
            sequences.addAll(next);
        }
        int counter = 0;
        for (var sequence : sequences) {
            if (sequence.isEmpty()) continue;
            String key = String.join(",", sequence);
            programs.add(new Program("arrow[" + key + "]", method("", switchOf(sequence, false, PARAM_GUARD)), null,
                    counter++ % 7 == 0));
            if (sequence.size() <= 3) {
                programs.add(new Program("colon[" + key + "]", method("", switchOf(sequence, true, PARAM_GUARD)), null,
                        counter % 11 == 0));
            }
        }
        // 2. Guard kinds, in three shapes.
        var guards = new LinkedHashMap<String, String[]>();
        guards.put("true", new String[]{"", "true"});
        guards.put("false", new String[]{"", "false"});
        guards.put("ON", new String[]{"", "ON"});
        guards.put("OFF", new String[]{"", "OFF"});
        guards.put("Probe.OFF", new String[]{"", "Probe.OFF"});
        guards.put("final local true", new String[]{"        final boolean on = true;\n", "on"});
        guards.put("final local false", new String[]{"        final boolean off = false;\n", "off"});
        guards.put("1 == 1", new String[]{"", "1 == 1"});
        guards.put("1 == 2", new String[]{"", "1 == 2"});
        guards.put("!(2 > 3)", new String[]{"", "!(2 > 3)"});
        guards.put("3", new String[]{"", "3"});
        guards.put("parameter", new String[]{"", PARAM_GUARD});
        guards.put("binding", new String[]{"", "x0.v().compareTo(n) > 0"});
        guards.put("effectively final local", new String[]{"        BigInteger lim = n.add(BigInteger.ONE);\n", "lim.signum() > 0"});
        guards.put("reassigned local", new String[]{"        BigInteger lim = BigInteger.ZERO;\n"
                + "        while (lim.compareTo(n) < 0) { lim = lim.add(BigInteger.ONE); }\n", "lim.signum() > 0"});
        guards.put("Boolean method", new String[]{"", "big(x0.v())"});
        // Review F54 and F29: JLS 15.29 constants, and guard types established from the source (F52).
        guards.put("(boolean) true", new String[]{"", "(boolean) true"});
        guards.put("(boolean) false", new String[]{"", "(boolean) false"});
        guards.put("(1 << 1) == 2", new String[]{"", "(1 << 1) == 2"});
        guards.put("\"a\" == \"a\"", new String[]{"", "\"a\" == \"a\""});
        guards.put("\"a\" == \"b\"", new String[]{"", "\"a\" == \"b\""});
        guards.put("promoted conditional", new String[]{"", "((true ? 2147483647 : 0L) + 1) > 0"});
        guards.put("int conditional overflow", new String[]{"", "((true ? 2147483647 : 0) + 1) > 0"});
        guards.put("(byte) cast", new String[]{"", "(byte) 200 == -56", "JULC0072"});
        guards.put("(Object) true", new String[]{"", "(Object) true"});
        guards.put("true ? true : 0", new String[]{"", "(true ? true : 0)"});
        guards.put("binding when false", new String[]{"", "!(s instanceof A a2)"});
        guards.put("binding when true", new String[]{"", "s instanceof A a2", "JULC0074"});
        guards.put("floating constant", new String[]{"", "1.5 > 1.0", "JULC0072"});
        // Review round 2 (F60-F67): the guard subset. A third element marks a deliberate over-rejection.
        guards.put("char conditional", new String[]{"", "(\"x\" + (true ? 'a' : 98)) == \"xa\"", "JULC0072"});
        guards.put("char comparison", new String[]{"", "'a' < 'b'", "JULC0072"});
        guards.put("int condition", new String[]{"", "(1 ? true : false)"});
        guards.put("== on a mixed conditional", new String[]{"", "(x0.v().signum() > 0 ? true : 0) == true"});
        guards.put("non-constant String ==", new String[]{"", "(n + \"b\") == \"3b\"", "JULC0072"});
        guards.put("constant String concatenation ==", new String[]{"", "\"a\" + \"b\" == \"ab\""});
        guards.put("reference cast", new String[]{"", "((S) (Object) new A(n)) instanceof A", "JULC0072"});
        guards.put("sibling instanceof", new String[]{"", "x0 instanceof B"});
        guards.put("same-family instanceof", new String[]{"", "s instanceof B"});
        guards.put("BigInteger relational", new String[]{"", "x0.v() < n"});
        guards.put("record ==", new String[]{"", "x0 == x0", "JULC0072"});
        // Review round 3: the verified constant subset (int and long width), and guard-binding conflicts.
        guards.put("long constant widening", new String[]{"        final long nl = 1;\n", "(nl << 32) == 1"});
        guards.put("int constant overflow", new String[]{"", "(2147483647 + 1) < 0"});
        guards.put("constant narrowing cast", new String[]{"", "((int) 2147483648L) < 0"});
        guards.put("guard binding named like a parameter", new String[]{"", "!(s instanceof B n)"});
        guards.put("guard binding twice", new String[]{"", "(s instanceof A a2) || (s instanceof A a2)"});
        guards.put("constant overflow beside a binding", new String[]{"", "(2147483647 + 1) < x0.v().signum()"});
        guards.put("constant narrowing beside a binding", new String[]{"", "((int) 2147483648L) < x0.v().signum()"});
        guards.put("int arithmetic", new String[]{"", "x0.v().signum() + 2147483647 < 0", "JULC0072"});
        guards.put("String.equals of a boolean", new String[]{"", "\"a\".equals(true)", "JULC0072"});
        var shapes = Map.of(
                "A?,B", List.of("A?", "B"),
                "A?,A,B", List.of("A?", "A", "B"),
                "A?,D", List.of("A?", "D"));
        for (var guard : guards.entrySet()) {
            for (var shape : shapes.entrySet()) {
                // A third element is the code JuLC rejects the guard with on purpose where javac accepts it.
                String intended = guard.getValue().length > 2 ? guard.getValue()[2] : null;
                programs.add(new Program("guard[" + guard.getKey() + "][" + shape.getKey() + "]",
                        method(guard.getValue()[0], switchOf(shape.getValue(), false, guard.getValue()[1])), intended, true));
            }
        }
        // A for-each variable read by a guard (effectively final), and a loop-updated one (not).
        programs.add(new Program("guard[for-each variable]", HEADER + """
                    public static BigInteger m(BigInteger a, BigInteger n) {
                        JulcList<BigInteger> xs = JulcList.of(a, n);
                        BigInteger acc = BigInteger.ZERO;
                        for (var k : xs) {
                            S s = k.signum() > 0 ? new A(k) : new B(k);
                            acc = acc.add(switch (s) { case A x0 when k.compareTo(BigInteger.TWO) > 0 -> BigInteger.ONE; default -> BigInteger.TWO; });
                        }
                        return acc;
                    }
                }
                """, null, false));
        programs.add(new Program("guard[accumulator]", HEADER + """
                    public static BigInteger m(BigInteger a, BigInteger n) {
                        JulcList<BigInteger> xs = JulcList.of(a, n);
                        BigInteger acc = BigInteger.ZERO;
                        for (var k : xs) {
                            S s = k.signum() > 0 ? new A(k) : new B(k);
                            acc = acc.add(switch (s) { case A x0 when acc.signum() > 0 -> BigInteger.ONE; default -> BigInteger.TWO; });
                        }
                        return acc;
                    }
                }
                """, null, false));
        // 3. Colon groups.
        programs.add(new Program("colon[falls through into default]", method("", """
                switch (s) {
                            case A x0: x0.v().negate();
                            default: yield BigInteger.TWO;
                        }"""), "JULC0075", true));
        programs.add(new Program("colon[falls through, guarded]", method("", """
                switch (s) {
                            case A x0 when n.signum() > 0: x0.v().negate();
                            default: yield BigInteger.TWO;
                        }"""), "JULC0075", false));
        programs.add(new Program("colon[falls through into a pattern]", method("", """
                switch (s) {
                            case A x0: x0.v().negate();
                            case B x1: yield BigInteger.TWO;
                        }"""), null, false));
        programs.add(new Program("colon[last group falls off]", method("", """
                switch (s) {
                            case A x0: yield BigInteger.ONE;
                            case B x1: x1.v();
                        }"""), null, false));
        programs.add(new Program("colon[if without else falls through]", method("", """
                switch (s) {
                            case A x0: if (x0.v().signum() > 0) { yield BigInteger.ONE; }
                            default: yield BigInteger.TWO;
                        }"""), "JULC0075", false));
        programs.add(new Program("mixed[arrow then colon]", method("", """
                switch (s) {
                            case A x0 when n.signum() > 0 -> BigInteger.ONE;
                            default: yield BigInteger.TWO;
                        }"""), null, false));
        programs.add(new Program("mixed[colon then arrow]", method("", """
                switch (s) {
                            case A x0: yield BigInteger.ONE;
                            default -> BigInteger.TWO;
                        }"""), null, false));
        programs.add(new Program("arrow[two patterns in one label]", method("", """
                switch (s) {
                            case A x0, B x1 -> BigInteger.ONE;
                        }"""), null, false));
        programs.add(new Program("colon[grouped pattern labels]", method("", """
                switch (s) {
                            case A x0: case B x1: yield BigInteger.ONE;
                        }"""), null, false));
        programs.add(new Program("colon[grouped labels without bindings]", HEADER.replace("record B(BigInteger v) implements S {}",
                "record B(BigInteger v) implements S {}\n    sealed interface X permits X0, Y0 {}\n    record X0() implements X {}\n    record Y0() implements X {}") + """
                    public static BigInteger m(BigInteger a, BigInteger n) {
                        X x = a.signum() > 0 ? new X0() : new Y0();
                        return switch (x) {
                            case X0(): case Y0(): yield BigInteger.valueOf(3);
                        };
                    }
                }
                """, "JULC0075", false));
        // 4. Pattern-variable names.
        programs.add(new Program("names[parameter]", method("", """
                switch (s) { case A n -> BigInteger.ONE; case B x1 -> BigInteger.TWO; }"""), null, false));
        programs.add(new Program("names[local]", method("        BigInteger y = a;\n", """
                switch (s) { case A y -> BigInteger.ONE; case B x1 -> BigInteger.TWO; }"""), null, false));
        programs.add(new Program("names[field]", method("", """
                switch (s) { case A x9 -> x9.v(); case B x1 -> x9; }"""), null, true));
        programs.add(new Program("names[outer pattern variable]", method("", """
                switch (s) { case A x0 -> switch (s) { case A x0 -> BigInteger.ONE; default -> BigInteger.TWO; }; case B x1 -> BigInteger.TEN; }"""),
                null, false));
        programs.add(new Program("names[sibling arms]", method("", """
                switch (s) { case A x0 when n.signum() > 0 -> x0.v(); case A x0 -> x0.v().negate(); case B x0 -> BigInteger.TEN; }"""),
                null, true));
        programs.add(new Program("names[for-each variable]", HEADER + """
                    public static BigInteger m(BigInteger a, BigInteger n) {
                        JulcList<BigInteger> xs = JulcList.of(a, n);
                        BigInteger acc = BigInteger.ZERO;
                        for (var k : xs) {
                            S s = k.signum() > 0 ? new A(k) : new B(k);
                            acc = acc.add(switch (s) { case A k -> BigInteger.ONE; default -> BigInteger.TWO; });
                        }
                        return acc;
                    }
                }
                """, null, false));
        // 5. A one-constructor sealed type and a parameterized one.
        String single = HEADER.replace("record B(BigInteger v) implements S {}",
                "record B(BigInteger v) implements S {}\n    sealed interface O permits OA {}\n    record OA(BigInteger v) implements O {}");
        programs.add(new Program("single[guarded, then unguarded]", single + """
                    public static BigInteger m(BigInteger a, BigInteger n) {
                        O o = new OA(a);
                        return switch (o) { case OA x0 when n.signum() > 0 -> BigInteger.ONE; case OA x1 -> x1.v(); };
                    }
                }
                """, null, true));
        programs.add(new Program("single[guarded only]", single + """
                    public static BigInteger m(BigInteger a, BigInteger n) {
                        O o = new OA(a);
                        return switch (o) { case OA x0 when n.signum() > 0 -> BigInteger.ONE; };
                    }
                }
                """, null, false));
        String generic = HEADER.replace("record B(BigInteger v) implements S {}",
                "record B(BigInteger v) implements S {}\n    sealed interface G<X> permits GC, GD {}\n"
                        + "    record GC(BigInteger c) implements G<String> {}\n    record GD(BigInteger d) implements G<BigInteger> {}");
        programs.add(new Program("generic[GD only]", generic + """
                    public static BigInteger m(BigInteger a, BigInteger n) {
                        G<BigInteger> g = new GD(a);
                        return switch (g) { case GD d -> d.d(); };
                    }
                }
                """, "JULC0007", false));
        programs.add(new Program("generic[GC and GD]", generic + """
                    public static BigInteger m(BigInteger a, BigInteger n) {
                        G<BigInteger> g = new GD(a);
                        return switch (g) { case GC c -> BigInteger.ONE; case GD d -> d.d(); };
                    }
                }
                """, null, false));
        return programs;
    }

    @Test
    void julcAcceptsExactlyWhatJavac21Accepts() {
        var programs = corpus();
        var sources = new LinkedHashMap<String, Map<String, String>>();
        programs.forEach(p -> sources.put(p.name(), javacSources(p)));
        var javac = Javac21.compile(sources);
        var problems = new ArrayList<String>();
        var release21Differences = new ArrayList<String>();
        int accepted = 0, rejected = 0, intended = 0, ran = 0, parseErrors = 0;
        var runner = DiffRunner.standard();
        for (var program : programs) {
            var verdict = javac.get(program.name());
            var release21 = Javac21.javacRelease21(javacSources(program));
            if (release21.accepted() != verdict.accepted()) {
                release21Differences.add(program.name() + ": javac 21.0.3 " + verdict + "; --release 21 " + release21);
            }
            String code = julcRejection(program.source());
            boolean julcAccepts = code == null;
            if (PARSE_ERROR.equals(code)) parseErrors++;
            if (code != null && !D4_CODES.contains(code) && !code.equals(PARSE_ERROR)) {
                problems.add(program.name() + ": JuLC rejects with " + code + ", not a D4 code");
            }
            if (program.intended() != null && verdict.accepted()) {
                intended++;
                if (!program.intended().equals(code)) problems.add(program.name() + ": expected " + program.intended() + ", got " + code);
                continue;
            }
            if (julcAccepts != verdict.accepted()) {
                problems.add(program.name() + ": javac 21.0.3 " + verdict + ", JuLC " + (julcAccepts ? "accepts" : "rejects " + code));
                continue;
            }
            if (julcAccepts) {
                accepted++;
                if (program.run()) {
                    ran++;
                    for (long a : new long[]{5, -5}) {
                        for (long n : new long[]{3, -3}) {
                            var report = runner.run(new DiffCase(program.name(), program.source(), "Probe", "m",
                                    List.of(PlutusData.integer(a), PlutusData.integer(n))));
                            report.results().forEach((config, result) -> {
                                if (result.verdict().kind() != Verdict.Kind.AGREE) {
                                    problems.add(program.name() + " (" + a + ", " + n + ") " + config + ": " + result.verdict());
                                }
                            });
                        }
                    }
                }
            } else {
                rejected++;
            }
        }
        System.out.println("[ADR-067 parity corpus] " + programs.size() + " programs: " + accepted + " accepted by both ("
                + ran + " run both ways), " + rejected + " rejected by both (" + parseErrors + " by JuLC's parser), "
                + intended + " rejected by JuLC on purpose");
        release21Differences.forEach(d -> System.out.println("[javac 21.0.3 vs --release 21] " + d));
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    private static Map<String, String> javacSources(Program program) {
        return Map.of("Probe.java", program.source(), "org/julclang/core/types/JulcList.java", JULC_LIST_STUB);
    }

    /** The code JuLC rejects the program with ({@code "none"} when uncoded), or null when it compiles. */
    private static String julcRejection(String source) {
        try {
            new JulcCompiler(StdlibRegistry.defaultRegistry(), new CompilerOptions().setOptimizationLevel(OptimizationLevel.NONE))
                    .compileMethod(source, "m");
            return null;
        } catch (CompilerException e) {
            if (e.getMessage() != null && e.getMessage().contains("Parse error")) return PARSE_ERROR;
            return e.diagnostics().stream().filter(d -> d.level() == CompilerDiagnostic.Level.ERROR).findFirst()
                    .map(d -> d.code() == null ? "none" : d.code()).orElse("none");
        }
    }
}
