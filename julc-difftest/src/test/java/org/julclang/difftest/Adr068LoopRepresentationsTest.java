package org.julclang.difftest;

import org.julclang.compiler.CompilerFrontend;
import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A variable holds one representation, and a tuple of accumulators carries it, on {@code frontend=JAVAC} at every level with
 * the JVM as the oracle. The first half is a product of what an element is, where it comes from and where it goes. The second
 * is a Data variable that holds a builtin's list, however the list reaches it.
 */
class Adr068LoopRepresentationsTest {

    private static final DiffRunner RUNNER = new DiffRunner(DiffConfig.allLevels(CompilerFrontend.JAVAC), 10_000);
    private static final DiffRunner BOTH = new DiffRunner(both(), 10_000);

    private static List<DiffConfig> both() {
        var configs = new ArrayList<>(DiffConfig.allLevels());
        configs.addAll(DiffConfig.allLevels(CompilerFrontend.JAVAC));
        return configs;
    }

    static final TagTable TAGS = new TagTable(List.of(new TagTable.Entry("Case$S$A", "Case$S", 0),
            new TagTable.Entry("Case$S$B", "Case$S", 1), new TagTable.Entry("Case$S$D", "Case$S", 2)));

    private static PlutusData integer(long value) {
        return new PlutusData.IntData(BigInteger.valueOf(value));
    }

    private static PlutusData bool(boolean value) {
        return new PlutusData.ConstrData(value ? 1 : 0, List.of());
    }

    private static PlutusData bytes(int... values) {
        var array = new byte[values.length];
        for (int i = 0; i < values.length; i++) array[i] = (byte) values[i];
        return new PlutusData.BytesData(array);
    }

    private static PlutusData list(PlutusData... items) {
        return new PlutusData.ListData(List.of(items));
    }

    private static PlutusData constr(int tag, PlutusData... fields) {
        return new PlutusData.ConstrData(tag, List.of(fields));
    }

    private static PlutusData map(PlutusData... keysAndValues) {
        var entries = new ArrayList<PlutusData.Pair>();
        for (int i = 0; i < keysAndValues.length; i += 2) entries.add(new PlutusData.Pair(keysAndValues[i], keysAndValues[i + 1]));
        return new PlutusData.MapData(entries);
    }

    /** One element type: how it is written, a value of it, and a list of it as Data. */
    record Element(String name, String type, String value, PlutusData items) {
    }

    static final List<Element> ELEMENTS = List.of(
            new Element("BigInteger", "BigInteger", "BigInteger.TEN", list(integer(1), integer(2))),
            new Element("bytes", "byte[]", "new byte[]{9}", list(bytes(1), bytes(2, 3))),
            new Element("String", "String", "\"z\"", list(bytes(0x61), bytes(0x62, 0x63))),
            new Element("Boolean", "Boolean", "true", list(bool(true), bool(false))),
            new Element("record", "R", "new R(BigInteger.ONE, new byte[]{1})",
                    list(constr(0, integer(1), bytes(1)), constr(0, integer(2), bytes(2)))),
            new Element("sealed", "S", "new S.D()", list(constr(0, integer(1)), constr(1, integer(2), integer(3)))),
            new Element("list", "JulcList<BigInteger>", "JulcList.of(BigInteger.TEN)", list(list(integer(1)), list(integer(2), integer(3)))),
            new Element("map", "JulcMap<BigInteger, BigInteger>", "JulcMap.empty()", list(map(integer(1), integer(2)), map())),
            new Element("Optional", "Optional<BigInteger>", "Optional.of(BigInteger.ONE)", list(constr(0, integer(1)), constr(1))),
            new Element("Tuple2", "Tuple2<BigInteger, BigInteger>", "new Tuple2<>(BigInteger.ONE, BigInteger.TWO)", list(constr(0, integer(1), integer(2)))));

    static final List<String> ITERABLES = List.of("xs", "(xs)", "xs.tail()", "JulcList.of(xs.head(), xs.head())", "xs.map(q -> q)",
            "xs.filter(q -> true)", "xs.reverse()", "c ? xs : xs", "id(xs)");

    /** What a loop's variable goes through: {@code %V} is a value of the type, {@code %I} the iterable. */
    static final List<String> SHAPES = List.of(
            "long n = 0; while (n < 2) { n = n + 1; x = %V; } s = s + n;",
            "var y = x; long n = 0; while (n < 2) { n = n + 1; y = %V; } s = s + n;",
            "var y = x; long n = 0; while (n < 2) { n = n + 1; y = x; } s = s + n;",
            "long n = 0; for (var u : xs) { n = n + 1; x = u; } s = s + n;",
            "long n = 0; for (var u : %I) { n = n + 1; x = u; } s = s + n;",
            "var y = x; long n = 0; for (var u : %I) { n = n + 1; y = u; if (n > 5) { break; } } s = s + n;",
            "h = x; s = s + 1;");

    static String primitive(Element element) {
        return element.type().equals("Boolean") ? "boolean" : element.type();
    }

    /** A whole program over {@code element}: {@code declared} is the loop variable's type as written. */
    static String source(Element element, String iterable, String declared, String shape) {
        String type = element.type();
        String body = shape.replace("%V", element.value()).replace("%I", iterable);
        String before = shape.startsWith("h = ") ? primitive(element) + " h = " + element.value() + "; " : "";
        return """
                import java.math.BigInteger;
                import java.util.Optional;
                import org.julclang.core.types.JulcList;
                import org.julclang.core.types.JulcMap;
                import org.julclang.core.types.Tuple2;

                public class Case {
                    public record R(BigInteger a, byte[] b) { }
                    public sealed interface S {
                        record A(BigInteger v) implements S { }
                        record B(BigInteger v, BigInteger w) implements S { }
                        record D() implements S { }
                    }
                    static JulcList<%1$s> id(JulcList<%1$s> l) { return l; }
                    public static long m(JulcList<%1$s> xs, boolean c) { %2$slong s = 0; for (%3$s x : %4$s) { %5$s } return s; }
                }
                """.formatted(type, before, declared, iterable, body);
    }

    /** The whole product, 1,260 programs: each is the JVM's at every level. */
    @Test
    void everyElementInEveryShape() {
        var failures = new ArrayList<String>();
        int runs = 0;
        for (var element : ELEMENTS) {
            for (String iterable : ITERABLES) {
                for (String declared : List.of("var", primitive(element))) {
                    for (String shape : SHAPES) {
                        var diffCase = new DiffCase("elements", source(element, iterable, declared, shape), "Case", "m",
                                List.of(element.items(), bool(true)), TAGS, null);
                        var report = RUNNER.run(diffCase);
                        runs++;
                        if (!report.all(Verdict.Kind.AGREE)) {
                            failures.add(element.name() + " | " + iterable + " | " + declared + " | " + shape + "\n" + report.describe());
                        }
                    }
                }
            }
        }
        assertEquals(1260, runs);
        assertTrue(failures.isEmpty(), () -> failures.size() + " of 1260 disagree:\n" + String.join("\n", failures.subList(0, Math.min(6, failures.size()))));
    }

    private static String cursors(String members) {
        return """
                import java.math.BigInteger;
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcList;
                import org.julclang.stdlib.Builtins;

                public class Case {
                    %s
                }
                """.formatted(members);
    }

    static final PlutusData LIST = list(integer(1), integer(2), integer(3));
    static final PlutusData MAP = map(integer(1), integer(2), integer(3), integer(4));

    /** On {@code JAVAC} the JVM's answer at every level. Where {@code referenceFails}, the reference is shown not to reach it. */
    private static void assertCarried(String members, boolean referenceFails, PlutusData... args) {
        var report = BOTH.run(new DiffCase("cursors", cursors(members), "Case", "m", List.of(args)));
        boolean referenceAgrees = true;
        for (var entry : report.results().entrySet()) {
            if (entry.getKey().frontend() == CompilerFrontend.JAVAC) {
                assertEquals(Verdict.Kind.AGREE, entry.getValue().verdict().kind(), report::describe);
            } else {
                referenceAgrees &= entry.getValue().verdict().kind() == Verdict.Kind.AGREE;
            }
        }
        boolean agrees = referenceAgrees;
        assertEquals(!referenceFails, agrees, report::describe);
    }

    /** The accumulator's value reaches it some way the reference's reading of the assignment does not see. */
    @ParameterizedTest
    @ValueSource(strings = {
            "public static long m(PlutusData d) { PlutusData c = Builtins.unListData(d); long s = 0; long i = 0; while (i < 2) { i = i + 1; while (!Builtins.nullList(c)) { c = Builtins.tailList(c); s = s + 1; } } return s; }",
            "static PlutusData.ListData next(PlutusData c) { return Builtins.tailList(c); } public static long m(PlutusData d) { PlutusData c = Builtins.unListData(d); long s = 0; while (!Builtins.nullList(c)) { c = next(c); s = s + 1; } return s; }",
            "static PlutusData next(PlutusData c) { return Builtins.tailList(c); } public static long m(PlutusData d) { PlutusData c = Builtins.unListData(d); long s = 0; while (!Builtins.nullList(c)) { c = next(c); s = s + 1; } return s; }",
            "public static long m(PlutusData d) { PlutusData c = Builtins.unListData(d); long s = 0; while (!Builtins.nullList(c)) { PlutusData n = Builtins.tailList(c); c = n; s = s + 1; } return s; }",
            "public static long m(PlutusData d) { PlutusData c = Builtins.unListData(d); long s = 0; while (!Builtins.nullList(c)) { c = (Builtins.tailList(c)); s = s + 1; } return s; }",
            "public static long m(PlutusData d) { PlutusData c = Builtins.unListData(d); long s = 0; while (!Builtins.nullList(c)) { c = s > 100 ? c : Builtins.tailList(c); s = s + 1; } return s; }",
    })
    void aListTheAssignmentShows(String members) {
        assertCarried(members, true, LIST);
    }

    @Test
    void aListThroughALoopAndASibling() {
        assertCarried("public static long m(PlutusData d, JulcList<BigInteger> xs) { PlutusData c = Builtins.unListData(d); long s = 0; for (var x : xs) { c = Builtins.tailList(c); s = s + 1; } return s; }",
                true, LIST, list(integer(1), integer(2)));
        assertCarried("public static long m(PlutusData d, JulcList<BigInteger> xs) { long s = 0; for (var x : xs) { PlutusData cur = Builtins.unListData(d); long i = 0; while (!Builtins.nullList(cur)) { i = i + 1; cur = Builtins.tailList(cur); } s = s + i + (Builtins.nullList(cur) ? 1 : 2); } return s; }",
                true, LIST, list(integer(1), integer(2)));
        assertCarried("public static long m(PlutusData d, PlutusData e) { PlutusData c = Builtins.unMapData(d); PlutusData f = Builtins.unListData(e); long s = 0; while (!Builtins.nullList(c)) { s = s + 1; c = Builtins.tailList(c); f = Builtins.tailList(f); } return s; }",
                true, MAP, LIST);
    }

    /** What already worked stays so: a map cursor, a built list, and a cursor with no sign of a list, which holds Data. */
    @ParameterizedTest
    @ValueSource(strings = {
            "public static long m(PlutusData d) { PlutusData c = Builtins.unListData(d); long s = 0; while (!Builtins.nullList(c)) { s = s + Builtins.unIData(Builtins.headList(c)).longValue(); c = Builtins.tailList(c); } return s; }",
            "public static PlutusData m(PlutusData d) { PlutusData cur = d; long i = 0; while (i < 1) { i = i + 1; cur = Builtins.iData(i); } return cur; }",
    })
    void whatTheReferenceAnswers(String members) {
        assertCarried(members, false, LIST);
    }

    @Test
    void whatTheReferenceAnswersOfAMap() {
        assertCarried("public static long m(PlutusData d) { PlutusData c = Builtins.unMapData(d); long s = 0; while (!Builtins.nullList(c)) { PlutusData p = Builtins.headList(c); s = s + Builtins.unIData(Builtins.fstPair(p)).longValue(); c = Builtins.tailList(c); } return s; }",
                false, MAP);
        assertCarried("public static long m(PlutusData d) { PlutusData c = Builtins.unMapData(d); long s = 0; while (s < 1) { s = s + 1; if (!Builtins.nullList(c)) { c = Builtins.tailList(c); } } return Builtins.nullList(c) ? 5 : 6; }",
                false, MAP);
    }
}
