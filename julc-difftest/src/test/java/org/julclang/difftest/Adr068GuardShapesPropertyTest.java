package org.julclang.difftest;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.core.PlutusData;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a guard may read, over the three families whose answer turns on what the reference can see: a list method that takes
 * a function, a variable of a {@code switch} in the guard, and an inferred parameter under each receiver. A program is
 * accepted by both frontends, with the JVM's answer and one cost and size, or rejected by both with one code, or, at a
 * simple-name receiver alone, accepted by {@code JAVAC} with the JVM's answer (D13, Accepted by decision). {@link #SEEN}
 * holds both outcomes of each family, so no family goes vacuous.
 */
class Adr068GuardShapesPropertyTest {

    private static final int TRIES = 240;
    private static final DiffRunner RUNNER = new DiffRunner(configs(), 10_000);
    private static final TagTable TAGS = new TagTable(List.of(new TagTable.Entry("Case$S$A", "Case$S", 0),
            new TagTable.Entry("Case$S$B", "Case$S", 1), new TagTable.Entry("Case$S$D", "Case$S", 2)));

    /** The one shape {@code JAVAC} accepts and the reference does not: a lambda whose parameter the guard reads, at a simple-name receiver. */
    private static final Pattern WIDER_RECEIVER = Pattern.compile("return l\\.(filter|any|all)\\(e ");

    private static final List<String> RECEIVERS = List.of("l", "(l)", "h.is()", "ids(l)", "l.tail()", "l.filter(y -> true)");
    private static final List<String> LEAVES = List.of("P.signum() > 0", "P.equals(BigInteger.ONE)", "!P.equals(BigInteger.ONE)",
            "P.compareTo(BigInteger.ONE) > 0", "P.signum() > 0 && c", "c || P.signum() > 0", "!l.any(z -> z.equals(P))", "P.longValue() > 0");
    private static final List<String> OPERATIONS = List.of("!R.any(x -> x.signum() > 0)", "R.all(x -> x.signum() > 0) && c",
            "c || R.any(x -> x.signum() > 0)", "R.any(x -> x.signum() > 0) == c", "!R.filter(x -> x.signum() > 0).isEmpty()",
            "R.filter(x -> x.signum() > 0).size() > 0", "!R.map(x -> x.add(x)).isEmpty()", "R.find(x -> x.signum() > 0).signum() > 0");
    private static final List<String> ARMS = List.of("i.v().signum() > 0", "a.v().signum() > 0", "i.v().equals(a.v())", "c", "!c",
            "t instanceof S.B", "i instanceof S.A", "!l.any(x -> x.equals(i.v()))", "!l.any(x -> x.signum() > 0)");

    private static String program(String family, String body) {
        return """
                import java.math.BigInteger;
                import org.julclang.core.types.JulcList;

                public class Case {
                    public record H(JulcList<BigInteger> is) { }
                    public sealed interface S {
                        record A(BigInteger v) implements S { }
                        record B(BigInteger v, BigInteger w) implements S { }
                        record D() implements S { }
                    }
                    static JulcList<BigInteger> ids(JulcList<BigInteger> l) { return l; }
                    public static long m(S s, S t, boolean c, JulcList<BigInteger> l, H h) { %s }
                    static final String FAMILY = "%s";
                }
                """.formatted(body, family);
    }

    private record Shape(String family, String body) {
    }

    private static List<DiffConfig> configs() {
        var configs = new ArrayList<>(DiffConfig.allLevels());
        configs.addAll(DiffConfig.allLevels(CompilerFrontend.JAVAC));
        return configs;
    }

    private static String guarded(String guard) {
        return "return switch (s) { case S.A a when " + guard + " -> 1; default -> 3; };";
    }

    private static Arbitrary<Shape> parameters() {
        return Combinators.combine(Arbitraries.of(RECEIVERS), Arbitraries.of(LEAVES), Arbitraries.of("filter", "any", "all"), Arbitraries.of(false, true))
                .as((receiver, leaf, method, viaVar) -> {
                    String name = viaVar ? "w" : "e";
                    String guard = leaf.replace("P", name);
                    String match = "switch (s) { case S.A a when " + guard + " -> true; default -> false; }";
                    String lambda = viaVar ? "e -> { var w = e; return " + match + "; }" : "e -> " + match;
                    String call = receiver + "." + method + "(" + lambda + ")";
                    return new Shape("a parameter", method.equals("filter") ? "return " + call + ".size();" : "return " + call + " ? 1 : 0;");
                });
    }

    private static Arbitrary<Shape> operations() {
        return Combinators.combine(Arbitraries.of(RECEIVERS), Arbitraries.of(OPERATIONS))
                .as((receiver, operation) -> new Shape("an operation", guarded(operation.replace("R", receiver))));
    }

    private static Arbitrary<Shape> arms() {
        return Arbitraries.of(ARMS).map(arm -> new Shape("an arm", guarded("(switch (t) { case S.A i -> " + arm + "; default -> false; })")));
    }

    @Provide
    Arbitrary<Shape> shapes() {
        return Arbitraries.oneOf(parameters(), operations(), arms());
    }

    @Provide
    Arbitrary<List<Integer>> inputs() {
        return Arbitraries.integers().between(-2, 2).list().ofSize(4);
    }

    private static final Map<String, Integer> SEEN = new TreeMap<>();

    @BeforeProperty
    void clear() {
        SEEN.clear();
    }

    @AfterProperty
    void bothOutcomesOfEachFamilyRan() {
        System.out.println("guard shapes " + SEEN);
        if (SEEN.values().stream().mapToInt(Integer::intValue).sum() < TRIES) return; // A try failed, and that is the report.
        for (String family : List.of("a parameter", "an operation", "an arm")) {
            assertTrue(SEEN.getOrDefault(family + " accepted", 0) >= 5, () -> family + " accepted too rarely: " + SEEN);
            assertTrue(SEEN.getOrDefault(family + " rejected", 0) >= 5, () -> family + " rejected too rarely: " + SEEN);
        }
        assertTrue(SEEN.getOrDefault("a parameter wider", 0) >= 5, () -> "a parameter wider too rarely: " + SEEN);
    }

    private static PlutusData integer(long value) {
        return new PlutusData.IntData(BigInteger.valueOf(value));
    }

    private static PlutusData variant(long v) {
        return new PlutusData.ConstrData(v == 0 ? 2 : 0, v == 0 ? List.of() : List.of(integer(v)));
    }

    @Property(tries = TRIES, seed = "20261102")
    void bothFrontendsAnswerAlike(@ForAll("shapes") Shape shape, @ForAll("inputs") List<Integer> inputs) {
        var values = new PlutusData.ListData(inputs.stream().map(v -> integer(v)).toList());
        var arguments = List.of(variant(inputs.get(0)), variant(inputs.get(1)), new PlutusData.ConstrData(inputs.get(2) > 0 ? 1 : 0, List.of()), values,
                new PlutusData.ConstrData(0, List.of(values)));
        var report = RUNNER.run(new DiffCase("guards", program(shape.family(), shape.body()), "Case", "m", arguments, TAGS, null));
        boolean rejected = report.results().values().stream().anyMatch(result -> result.verdict().kind() == Verdict.Kind.REJECTED);
        boolean wider = rejected && report.results().entrySet().stream().noneMatch(result -> result.getKey().frontend() == CompilerFrontend.JAVAC
                && result.getValue().verdict().kind() == Verdict.Kind.REJECTED);
        SEEN.merge(shape.family() + (wider ? " wider" : rejected ? " rejected" : " accepted"), 1, Integer::sum);
        report.results().forEach((config, result) -> {
            var javac = report.results().get(new DiffConfig(config.level(), "Java", CompilerFrontend.JAVAC));
            if (wider) {
                assertTrue(WIDER_RECEIVER.matcher(shape.body()).lookingAt(), report::describe);
                assertEquals(config.frontend() == CompilerFrontend.JAVAC ? Verdict.Kind.AGREE : Verdict.Kind.REJECTED, result.verdict().kind(), report::describe);
                return;
            }
            if (rejected) {
                assertEquals(Verdict.Kind.REJECTED, result.verdict().kind(), report::describe);
                var code = assertInstanceOf(Observation.Rejected.class, result.uplc(), report::describe).code();
                assertEquals(code, assertInstanceOf(Observation.Rejected.class, javac.uplc(), report::describe).code(), report::describe);
            } else {
                assertEquals(Verdict.Kind.AGREE, result.verdict().kind(), report::describe);
                assertEquals(result.measurement(), javac.measurement(), () -> config + "\n" + report.describe());
            }
        });
    }
}
