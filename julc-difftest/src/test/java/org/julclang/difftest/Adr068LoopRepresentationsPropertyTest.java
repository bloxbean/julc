package org.julclang.difftest;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.core.PlutusData;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The families of {@link Adr068LoopRepresentationsTest}, composed further than a table does: elements under a chain of
 * variables and loops, and a cursor reached by a chain of forms, in any loop, with any number of accumulators beside it. The
 * JVM is the oracle, and {@code frontend=JAVAC} must meet it at every level.
 */
class Adr068LoopRepresentationsPropertyTest {

    private static final DiffRunner RUNNER = new DiffRunner(DiffConfig.allLevels(CompilerFrontend.JAVAC), 10_000);

    private static void assertAgrees(DiffCase diffCase) {
        var report = RUNNER.run(diffCase);
        assertTrue(report.all(Verdict.Kind.AGREE), () -> diffCase.source() + "\n" + report.describe());
        assertEquals(4, report.results().size());
    }

    @Provide
    Arbitrary<List<Integer>> plans() {
        return Arbitraries.integers().between(0, 1000).list().ofSize(12);
    }

    private static final class Plan {
        private final List<Integer> choices;
        private int at;

        Plan(List<Integer> choices) {
            this.choices = choices;
        }

        <T> T pick(List<T> options) {
            return options.get(Math.floorMod(choices.get(at++ % choices.size()), options.size()));
        }
    }

    /** An element of any type and origin passes through a local, a {@code var} of a conditional and a tuple's accumulators. */
    @Property(tries = 150, seed = "20261103")
    void anElementInATuple(@ForAll("plans") List<Integer> choices) {
        var plan = new Plan(choices);
        var element = plan.pick(Adr068LoopRepresentationsTest.ELEMENTS);
        String iterable = plan.pick(Adr068LoopRepresentationsTest.ITERABLES);
        String declared = plan.pick(List.of("var", Adr068LoopRepresentationsTest.primitive(element)));
        String copy = plan.pick(List.of("var y = x;", "var y = c ? x : x;", "var w = x; var y = w;", "var y = id(xs).head();"));
        String update = plan.pick(List.of("y = x;", "y = " + element.value() + ";", "y = c ? x : y;", "if (n > 0) { y = x; }"));
        String loop = plan.pick(List.of("while (n < 2) { n = n + 1; %s }", "for (var u : xs) { n = n + 1; %s }",
                "while (n < 3) { n = n + 1; %s if (n > 1) { break; } }", "for (var u : xs) { n = n + 1; %s if (n > 0) { break; } }"));
        String after = plan.pick(List.of("", "var z = y;", "h = y; n = n + 1;"));
        String shape = "h = x; " + copy + " long n = 0; " + loop.formatted(update) + " " + after + " s = s + n;";
        assertAgrees(new DiffCase("elements", Adr068LoopRepresentationsTest.source(element, iterable, declared, shape), "Case", "m",
                List.of(element.items(), new PlutusData.ConstrData(1, List.of())), Adr068LoopRepresentationsTest.TAGS, null));
    }

    private static PlutusData integers(long... values) {
        var items = new ArrayList<PlutusData>();
        for (long value : values) items.add(new PlutusData.IntData(BigInteger.valueOf(value)));
        return new PlutusData.ListData(items);
    }

    /** A cursor starts from a list, a pair list or a built list, moves by any form, in any loop, beside other accumulators. */
    @Property(tries = 200, seed = "20261103")
    void aCursorInATuple(@ForAll("plans") List<Integer> choices) {
        var plan = new Plan(choices);
        boolean map = plan.pick(List.of(false, true));
        String start = map ? "Builtins.unMapData(d)" : plan.pick(List.of("Builtins.unListData(d)", "Builtins.tailList(Builtins.unListData(d))"));
        String sibling = plan.pick(List.of("", "PlutusData f = Builtins.unListData(e); ", "PlutusData f = e; "));
        String siblingStep = sibling.isEmpty() ? "" : sibling.contains("unListData") ? "if (!Builtins.nullList(f)) { f = Builtins.tailList(f); } " : "f = e; ";
        String step = plan.pick(List.of("c = Builtins.tailList(c);", "c = (Builtins.tailList(c));", "c = next(c);", "c = nextData(c);",
                "PlutusData n = Builtins.tailList(c); c = n;", "c = s > 100 ? c : Builtins.tailList(c);",
                "if (s > 100) { s = 0; } else { c = Builtins.tailList(c); }", "{ c = Builtins.tailList(c); }"));
        String read = plan.pick(List.of("", map ? "s = s + Builtins.unIData(Builtins.fstPair(Builtins.headList(c))).longValue();"
                : "s = s + Builtins.unIData(Builtins.headList(c)).longValue();"));
        String body = read + " " + step + " " + siblingStep + "s = s + 1;";
        String loop = plan.pick(List.of(
                "while (!Builtins.nullList(c)) { %s }",
                "long i = 0; while (i < 2) { i = i + 1; while (!Builtins.nullList(c)) { %s } }",
                "for (var x : xs) { if (Builtins.nullList(c)) { break; } %s }",
                "for (var x : xs) { while (!Builtins.nullList(c)) { %s } }",
                "for (var x : xs) { PlutusData r = c; while (!Builtins.nullList(r)) { r = Builtins.tailList(r); s = s + 1; } }"));
        String end = plan.pick(List.of("return s;", "return s + (Builtins.nullList(c) ? 1 : 2);"));
        String source = """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcList;
                import org.julclang.stdlib.Builtins;

                public class Case {
                    static PlutusData.ListData next(PlutusData c) { return Builtins.tailList(c); }
                    static PlutusData nextData(PlutusData c) { return Builtins.tailList(c); }
                    public static long m(PlutusData d, PlutusData e, JulcList<BigInteger> xs) {
                        PlutusData c = %s; %slong s = 0; %s %s
                    }
                }
                """.formatted(start, sibling, loop.formatted(body), end);
        var data = map ? Adr068LoopRepresentationsTest.MAP : Adr068LoopRepresentationsTest.LIST;
        assertAgrees(new DiffCase("cursors", source, "Case", "m", List.of(data, integers(4, 5), integers(1, 2))));
    }

    /** A variable that starts from Data the analysis cannot name, and that a list may reach by any form on a path not taken. */
    @Property(tries = 200, seed = "20261103")
    void aStartFromData(@ForAll("plans") List<Integer> choices) {
        var plan = new Plan(choices);
        String start = plan.pick(List.of("d", "e", "ds.head()", "b.v()", "id(d)", "xs.isEmpty() ? d : e", "(d)", "Builtins.iData(BigInteger.ONE)",
                "Builtins.bData(new byte[]{1})", "F"));
        String list = plan.pick(List.of("Builtins.mkNilData()", "(Builtins.mkNilData())", "Builtins.mkNilPairData()", "Builtins.tailList(Builtins.unListData(l))",
                "nil()", "G", "Builtins.unMapData(m)", "Builtins.mkCons(d, Builtins.mkNilData())", "Builtins.tailList(c)"));
        String update = plan.pick(List.of("if (s > 100) { c = %s; }", "c = s > 100 ? %s : c;", "if (s > 100) { PlutusData r = %s; c = r; }",
                "c = switch (e2) { case S.B q -> %s; default -> c; };", "if (s > 100) { c = h(%s); }"));
        String sibling = plan.pick(List.of("", "PlutusData f = Builtins.unListData(l); ", "PlutusData f = d; "));
        String siblingStep = sibling.isEmpty() ? "" : sibling.contains("unListData") ? "if (!Builtins.nullList(f)) { f = Builtins.tailList(f); } " : "f = e; ";
        String body = update.formatted(list) + " " + siblingStep + "s = s + 1;";
        String loop = plan.pick(List.of(
                "while (s < 2) { %s }",
                "for (var x : xs) { %s }",
                "for (var x : xs) { %s if (s > 0) { break; } }",
                "long i = 0; while (i < 2) { i = i + 1; while (s < 2) { %s } }",
                "for (var x : xs) { long i = 0; while (i < 2) { i = i + 1; %s } }"));
        String end = plan.pick(List.of("return s;", "return s + (Builtins.equalsData(c, c) ? 1 : 2);", "var w = c; return s + (w.equals(c) ? 1 : 2);", "return s + JulcList.of(c).size();"));
        String source = """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcList;
                import org.julclang.stdlib.Builtins;

                public class Case {
                    public sealed interface S { record A() implements S { } record B() implements S { } }
                    public record Bx(PlutusData v) { }
                    static PlutusData F = Builtins.iData(BigInteger.TEN);
                    static PlutusData G = Builtins.mkNilData();
                    static PlutusData nil() { return Builtins.mkNilData(); }
                    static PlutusData id(PlutusData v) { return v; }
                    static PlutusData h(PlutusData v) { return v; }
                    public static long m(PlutusData d, PlutusData e, PlutusData l, PlutusData m, JulcList<PlutusData> ds, Bx b, JulcList<BigInteger> xs, S e2) {
                        PlutusData c = %s; %slong s = 0; %s %s
                    }
                }
                """.formatted(start, sibling, loop.formatted(body), end);
        var one = new PlutusData.IntData(BigInteger.ONE);
        var tags = new TagTable(List.of(new TagTable.Entry("Case$S$A", "Case$S", 0), new TagTable.Entry("Case$S$B", "Case$S", 1)));
        assertAgrees(new DiffCase("data", source, "Case", "m", List.of(one, new PlutusData.IntData(BigInteger.TWO), integers(1, 2), Adr068LoopRepresentationsTest.MAP,
                new PlutusData.ListData(List.of(one)), new PlutusData.ConstrData(0, List.of(one)), integers(1, 2), new PlutusData.ConstrData(0, List.of())), tags, null));
    }
}
