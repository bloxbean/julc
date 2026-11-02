package org.julclang.difftest;

import org.julclang.compiler.CompilerFrontend;
import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
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
                import org.julclang.core.types.Tuple2;
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
        assertCarried(members, referenceFails, TagTable.EMPTY, args);
    }

    private static void assertCarried(String members, boolean referenceFails, TagTable tags, PlutusData... args) {
        var report = BOTH.run(new DiffCase("cursors", cursors(members), "Case", "m", List.of(args), tags, null));
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
        assertCarried("public static long m(PlutusData d, PlutusData e) { PlutusData c = Builtins.unMapData(d); PlutusData f = Builtins.unListData(e); long s = 0; while (!Builtins.nullList(c)) { s = s + 1; c = Builtins.tailList(c); f = (Builtins.tailList(f)); } return s; }",
                true, MAP, LIST);
    }

    /** A parameter shows nothing, so where the reference reads no direct assignment, a list that arrives in one is carried as Data. */
    private static String walker(String step) {
        return """
                public sealed interface S { record A() implements S { } record B() implements S { } }
                static PlutusData next(PlutusData c) { return Builtins.tailList(c); }
                static PlutusData nextOf(PlutusData c, JulcList<BigInteger> xs) { var r = xs.map(x -> { return Builtins.iData(x); }); return Builtins.tailList(c); }
                static PlutusData pick(PlutusData c, boolean stay) { if (stay) { return c; } return Builtins.tailList(c); }
                static long walk(PlutusData c, JulcList<BigInteger> xs, S e) { long s = 0; while (!Builtins.nullList(c)) { %s s = s + 1; } return s; }
                public static long m(PlutusData d, JulcList<BigInteger> xs) { return walk(Builtins.unListData(d), xs, new S.A()); }
                """.formatted(step);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "c = (Builtins.tailList(c));",
            "c = s > 100 ? c : Builtins.tailList(c);",
            "c = s < 100 ? Builtins.tailList(c) : c;",
            "c = switch (e) { case S.A a -> Builtins.tailList(c); case S.B b -> c; };",
            "c = next(c);",
            "c = nextOf(c, xs);",
            "c = pick(c, s > 100);",
            "PlutusData n = Builtins.tailList(c); c = n;",
    })
    void aListInAParameterFails(String step) {
        var report = BOTH.run(new DiffCase("cursors", cursors(walker(step)), "Case", "m", List.of(LIST, list(integer(1))), TAGS2, null));
        assertTrue(report.results().values().stream().allMatch(result -> result.verdict().kind() == Verdict.Kind.MISMATCH), report::describe);
    }

    /** What {@code find} leaves is the element, so a {@code var} of it holds what was decoded, and a tuple encodes that. */
    @ParameterizedTest
    @ValueSource(strings = {"BigInteger", "byte[]", "String", "Boolean"})
    void aFoundElementInATuple(String type) {
        String find = "var y = xs.find(q -> true); var z = y; long n = 0; ";
        for (String loop : List.of("while (n < 2) { z = y; n = n + 1; } ", "for (var u : xs) { z = y; n = n + 1; } ")) {
            var items = switch (type) {
                case "BigInteger" -> list(integer(1), integer(2));
                case "Boolean" -> list(bool(true), bool(false));
                default -> list(bytes("a"), bytes("b"));
            };
            var a = type.equals("BigInteger") ? integer(1) : type.equals("Boolean") ? bool(true) : bytes("a");
            var report = RUNNER.run(new DiffCase("found", cursors("public static %s m(%s a, JulcList<%s> xs) { %s%sreturn z; }".formatted(type, type, type, find, loop)), "Case", "m", List.of(a, items)));
            assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
        }
    }

    private static PlutusData bytes(String text) {
        return new PlutusData.BytesData(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aFoundElementThatReadsAndAList() {
        var read = RUNNER.run(new DiffCase("found", cursors("public static BigInteger m(BigInteger a, JulcList<BigInteger> xs) { var y = xs.find(q -> q.equals(a)); var z = y; long n = 0; while (n < 2) { z = y; n = n + 1; } return z; }"),
                "Case", "m", List.of(integer(2), list(integer(1), integer(2)))));
        assertTrue(read.all(Verdict.Kind.AGREE), read::describe);
        var nested = RUNNER.run(new DiffCase("found", cursors("public static JulcList<BigInteger> m(JulcList<JulcList<BigInteger>> xs) { var y = xs.find(q -> true); var z = y; long n = 0; while (n < 2) { z = y; n = n + 1; } return z; }"),
                "Case", "m", List.of(list(list(integer(1), integer(2))))));
        assertTrue(nested.all(Verdict.Kind.AGREE), nested::describe);
    }

    private static final PlutusData ONE = integer(1);
    private static final PlutusData EMPTY = list();

    private static final String SIGNATURE = "PlutusData d, PlutusData e, JulcList<BigInteger> xs, long l, JulcList<PlutusData> ps, org.julclang.core.types.JulcMap<PlutusData, PlutusData> g, boolean f";
    private static final String WALK = "PlutusData c = Builtins.unMapData(d); long n = 0; while (n < l) { c = Builtins.tailList(c); n = n + 1; } ";
    private static final String BUILD = "PlutusData c = Builtins.mkNilPairData(); long n = 0; while (n < l) { c = Builtins.mkCons(Builtins.mkPairData(d, d), c); n = n + 1; } ";

    private static PlutusData pairs(int size) {
        var items = new ArrayList<PlutusData>();
        for (int i = 1; i <= size; i++) items.addAll(List.of(integer(i), integer(i + 10)));
        return map(items.toArray(PlutusData[]::new));
    }

    /** Each encoding on no, one and many iterations, with what is left of the map empty and not, against each kind of value. */
    private static void assertAMap(String returned, String members, boolean referenceFails) {
        var source = cursors("public static %s m(%s) { %s }".formatted(returned, SIGNATURE, members));
        for (int size : List.of(0, 1, 2, 4)) {
            for (int iterations = 0; iterations <= size; iterations++) {
                var left = pairs(size - iterations);
                for (var other : List.of(left, pairs(1), list(constr(0, integer(1), integer(11))), integer(1))) {
                    var items = new ArrayList<PlutusData>();
                    for (int i = 0; i < iterations; i++) items.add(integer(i));
                    var report = BOTH.run(new DiffCase("pairs", source, "Case", "m", List.of(pairs(size), other, list(items.toArray(PlutusData[]::new)),
                            integer(iterations), list(pairs(1)), pairs(1), bool(true))));
                    report.results().forEach((config, result) -> {
                        boolean mayFail = config.frontend() != CompilerFrontend.JAVAC && referenceFails;
                        if (!mayFail) assertEquals(Verdict.Kind.AGREE, result.verdict().kind(), report::describe);
                    });
                }
            }
        }
    }

    /** A pair list is a map on the JVM as on-chain, so whatever encodes one meets the JVM, on the reference's {@code while} too. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "boolean|return JulcList.of(c).head().equals(e);",
            "boolean|return e.equals(JulcList.of(c).head());",
            "boolean|return e.equals(c);",
            "PlutusData|return JulcList.of(c).head();",
            "PlutusData|var w = c; return JulcList.of(w).head();",
            "PlutusData|return JulcList.of((c)).head();",
            "PlutusData|return JulcList.of(f ? c : d).head();",
            "PlutusData|return ps.prepend(c).head();",
            "long|return ps.prepend(c).size();",
            "long|return g.insert(c, d).size();",
            "PlutusData|return g.insert(c, d).get(c);",
            "PlutusData|var w = c; return xs.prepend(BigInteger.ONE).map(q -> w).head();",
            "PlutusData|PlutusData r = d; long k = 0; while (k < 1) { r = JulcList.of(c).head(); k = k + 1; } return r;",
            "PlutusData|return Builtins.mapData(c);",
            "PlutusData|return Builtins.fstPair(Builtins.headList(Builtins.mkCons(Builtins.mkPairData(d, d), c)));",
            "boolean|return Builtins.nullList(c);",
    })
    void aWalkedPairListIsAMap(String returned, String read) {
        assertAMap(returned, WALK + read, false);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "PlutusData|return JulcList.of(c).head();",
            "boolean|return JulcList.of(c).head().equals(e);",
            "PlutusData|var w = c; return ps.prepend(w).head();",
    })
    void aBuiltPairListIsAMap(String returned, String read) {
        assertAMap(returned, BUILD + read, false);
    }

    /** The reference has no list in a for-each, and {@code contains} of a value not yet Data is D6's. */
    @Test
    void aMapTheReferenceDoesNotCarry() {
        String each = "PlutusData c = Builtins.unMapData(d); long n = 0; for (var u : xs) { c = Builtins.tailList(c); n = n + 1; } ";
        assertAMap("boolean", each + "return JulcList.of(c).head().equals(e);", true);
        assertAMap("PlutusData", each + "return JulcList.of(c).head();", true);
        assertAMap("PlutusData", "PlutusData c = Builtins.mkNilPairData(); long n = 0; for (var u : xs) { c = Builtins.mkCons(Builtins.mkPairData(d, d), c); n = n + 1; } return JulcList.of(c).head();", true);
        assertAMap("boolean", WALK + "return ps.contains(c);", true);
    }

    @Test
    void aMapInATupleAndALambda() {
        assertAMap("PlutusData", WALK + "var t = new Tuple2<>(c, d); return Builtins.mapData(t.first());", false);
        var report = RUNNER.run(new DiffCase("pairs", cursors("public static PlutusData m(JulcList<PlutusData> ds) { return ds.map(d -> Builtins.mkNilPairData()).head(); }"), "Case", "m", List.of(list(ONE))));
        assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
        var mapped = BOTH.run(new DiffCase("pairs", cursors("public static PlutusData m(JulcList<PlutusData> ds) { return ds.map(d -> Builtins.unMapData(d)).head(); }"), "Case", "m", List.of(list(pairs(1)))));
        assertTrue(mapped.all(Verdict.Kind.AGREE), mapped::describe);
    }

    /** A single pair is Data on the JVM and a native pair on-chain, and encoding one fails on both frontends. */
    @ParameterizedTest
    @ValueSource(strings = {
            "public static PlutusData m(PlutusData d) { var c = Builtins.unMapData(d); return JulcList.of(Builtins.headList(c)).head(); }",
            "public static PlutusData m(PlutusData d) { var h = Builtins.headList(Builtins.unMapData(d)); return JulcList.of(h).head(); }",
            "public static boolean m(PlutusData d) { var h = Builtins.mkPairData(d, d); return d.equals(h); }",
    })
    void aSinglePairFailsOnBoth(String members) {
        var report = BOTH.run(new DiffCase("pairs", cursors(members), "Case", "m", List.of(pairs(1))));
        assertTrue(report.results().values().stream().allMatch(result -> result.verdict().kind() == Verdict.Kind.MISMATCH), report::describe);
    }

    /** Data the analysis cannot name stays Data where a list may reach it, and no list arrives on these inputs. */
    @ParameterizedTest
    @ValueSource(strings = {
            "public static long m(PlutusData c, JulcList<BigInteger> xs, long l) { long n = 0; for (var u : xs) { c = Builtins.tailList(c); n = n + 1; } return n; }",
            "public static long m(PlutusData d, JulcList<BigInteger> xs, long l) { PlutusData c = d; long n = 0; for (var u : xs) { c = Builtins.mkNilData(); n = n + 1; } return n; }",
            "public static long m(PlutusData d, JulcList<BigInteger> xs, long l) { PlutusData c = d; long n = 0; while (n < l) { c = (Builtins.mkNilData()); n = n + 1; } return n; }",
            "public static long m(PlutusData d, JulcList<BigInteger> xs, long l) { PlutusData c = d; long n = 0; while (n < l) { c = n > 100 ? Builtins.mkNilData() : c; n = n + 1; } return n; }",
            "static PlutusData nil() { return Builtins.mkNilData(); } public static long m(PlutusData d, JulcList<BigInteger> xs, long l) { PlutusData c = d; long n = 0; while (n < l) { if (n > 100) { c = nil(); } n = n + 1; } return n; }",
            "public static long m(PlutusData d, JulcList<BigInteger> xs, long l) { PlutusData c = xs.isEmpty() ? d : d; long n = 0; for (var u : xs) { c = Builtins.mkNilData(); n = n + 1; } return n; }",
    })
    void dataNothingShowsAListStaysData(String members) {
        var report = RUNNER.run(new DiffCase("data", cursors(members), "Case", "m", List.of(ONE, EMPTY, integer(0))));
        assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
    }

    /** The same, where the variable's guard stops a list reaching it through the loop's iterations. */
    @ParameterizedTest
    @ValueSource(strings = {
            "public static long m(PlutusData d, JulcList<BigInteger> xs) { PlutusData c = d; long n = 0; for (var u : xs) { if (n > 100) { c = Builtins.mkNilData(); } n = n + 1; } return n; }",
            "public static boolean m(PlutusData d, JulcList<BigInteger> xs) { PlutusData c = d; long n = 0; for (var u : xs) { if (n > 100) { c = Builtins.mkNilData(); } n = n + 1; } return Builtins.equalsData(c, d); }",
            "static PlutusData F = Builtins.mkNilData(); public static long m(PlutusData d, JulcList<BigInteger> xs) { PlutusData c = d; long n = 0; while (n < 1) { if (n > 0) { c = F; } n = n + 1; } return n; }",
    })
    void aGuardedListStaysData(String members) {
        var report = RUNNER.run(new DiffCase("data", cursors(members), "Case", "m", List.of(ONE, list(integer(1)))));
        assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
    }

    /** What a list element and a record's component start from is nothing the analysis can name either. */
    @Test
    void aReadStartsAsData() {
        var element = RUNNER.run(new DiffCase("data", cursors("public static long m(JulcList<PlutusData> ls, JulcList<BigInteger> xs) { PlutusData c = ls.head(); long n = 0; for (var u : xs) { c = Builtins.mkNilData(); n = n + 1; } return n; }"),
                "Case", "m", List.of(list(ONE), EMPTY)));
        assertTrue(element.all(Verdict.Kind.AGREE), element::describe);
        var component = RUNNER.run(new DiffCase("data", cursors("public record Bx(PlutusData v) { } public static long m(Bx b, JulcList<BigInteger> xs) { PlutusData c = b.v(); long n = 0; for (var u : xs) { c = Builtins.mkNilData(); n = n + 1; } return n; }"),
                "Case", "m", List.of(constr(0, ONE), EMPTY)));
        assertTrue(component.all(Verdict.Kind.AGREE), component::describe);
    }

    /** What a read of the variable is plays no part: a list stays a list, and a read of it as Data fails where it runs. */
    @Test
    void aReadOfAListAsDataIsNotARule() {
        assertCarried("public static long m(PlutusData d, boolean f) { PlutusData c = Builtins.unListData(d); long n = 0; while (n < 1) { c = Builtins.tailList(c); n = n + 1; } if (f) { var w = Builtins.unListData(c); } return n; }",
                false, LIST, bool(false));
        var report = RUNNER.run(new DiffCase("data", cursors("public static long m(PlutusData d) { PlutusData c = Builtins.unListData(d); long n = 0; while (n < 1) { c = Builtins.tailList(c); n = n + 1; } return Builtins.unIData(c).longValue(); }"),
                "Case", "m", List.of(LIST)));
        assertTrue(report.results().values().stream().allMatch(result -> result.verdict().kind() != Verdict.Kind.REJECTED), report::describe);
    }

    /** Each thing the analysis reads, on a program whose list would fail if it were read otherwise. */
    @Test
    void whatShowsAList() {
        assertCarried(walkerOf("public static long m(PlutusData d, long l, S e) { PlutusData c = Builtins.unListData(d); long n = 0; while (n < l) { c = switch (e) { case S.A q -> Builtins.tailList(c); case S.B q -> Builtins.tailList(c); }; n = n + 1; } return n; }"),
                true, TAGS2, LIST, integer(1), constr(1));
        assertCarried("static PlutusData rec(PlutusData d, long n) { if (n > 0) { return rec(d, n - 1); } return Builtins.unListData(d); } public static long m(PlutusData d) { PlutusData c = rec(d, 2); long n = 0; while (n < 1) { c = (Builtins.tailList(c)); n = n + 1; } return n; }",
                true, LIST);
        assertCarried("static PlutusData hp(PlutusData d) { return Builtins.headList(Builtins.unMapData(d)); } public static long m(PlutusData d) { PlutusData m = Builtins.unMapData(d); PlutusData c = Builtins.mkCons(hp(d), m); long n = 0; while (n < 1) { c = (Builtins.tailList(c)); n = n + 1; } return Builtins.nullList(c) ? 1 : 2; }",
                true, MAP);
        assertCarried("static PlutusData hp(PlutusData d) { return Builtins.headList(Builtins.unMapData(d)); } public static long m(PlutusData d) { PlutusData c = Builtins.unMapData(d); long n = 0; while (n < 1) { c = Builtins.mkCons(hp(d), c); n = n + 1; } return n; }",
                false, MAP);
    }

    /** The reference reads a direct assignment in a {@code while}, whatever the start, and {@code JAVAC} never reads less. */
    @ParameterizedTest
    @ValueSource(strings = {
            "static long walk(PlutusData c, long n) { long s = 0; while (!Builtins.nullList(c)) { c = Builtins.tailList(c); s = s + n; } return s; } public static long m(PlutusData d) { return walk(Builtins.unListData(d), 1); }",
            "static boolean walk(PlutusData c) { while (!Builtins.nullList(c)) { c = Builtins.tailList(c); } return Builtins.nullList(c); } public static boolean m(PlutusData d) { return walk(Builtins.unListData(d)); }",
            "static long walk(PlutusData c, long n) { long s = 0; PlutusData f = Builtins.mkNilData(); while (!Builtins.nullList(c)) { c = Builtins.tailList(c); f = Builtins.mkCons(Builtins.iData(BigInteger.valueOf(n)), f); s = s + n; } return s; } public static long m(PlutusData d) { return walk(Builtins.unListData(d), 1); }",
    })
    void aListInAParameterByADirectAssignment(String members) {
        var report = BOTH.run(new DiffCase("cursors", cursors(members), "Case", "m", List.of(LIST)));
        assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "static long walk(PlutusData c) { long s = 0; while (!Builtins.nullList(c)) { var h = Builtins.headList(c); s = s + Builtins.unIData(Builtins.fstPair(h)).longValue(); c = Builtins.tailList(c); } return s; } public static long m(PlutusData d) { return walk(Builtins.unMapData(d)); }",
            "static long walk(PlutusData c) { long s = 0; while (!Builtins.nullList(c)) { var h = Builtins.headList(c); s = s + Builtins.unIData(Builtins.sndPair(h)).longValue(); c = Builtins.tailList(c); } return s; } public static long m(PlutusData d) { return walk(Builtins.unMapData(d)); }",
            "static boolean walk(PlutusData c) { while (!Builtins.nullList(c)) { c = Builtins.tailList(c); } return Builtins.nullList(c); } public static boolean m(PlutusData d) { return walk(Builtins.unMapData(d)); }",
    })
    void aPairListInAParameterByADirectAssignment(String members) {
        var report = BOTH.run(new DiffCase("cursors", cursors(members), "Case", "m", List.of(MAP)));
        assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
    }

    /** A list start keeps the reference's reading though Data is assigned on a path not taken, and fails where it is taken. */
    @Test
    void aMixKeepsTheReferencesReading() {
        String members = "public static long m(PlutusData d, long l) { PlutusData c = Builtins.unListData(d); long n = 0; while (n < l) { if (n > 0) { c = Builtins.iData(BigInteger.valueOf(n)); } else { c = Builtins.tailList(c); } n = n + 1; } return n; }";
        var runs = BOTH.run(new DiffCase("data", cursors(members), "Case", "m", List.of(LIST, integer(1))));
        assertTrue(runs.all(Verdict.Kind.AGREE), runs::describe);
        var fails = BOTH.run(new DiffCase("data", cursors(members), "Case", "m", List.of(LIST, integer(2))));
        assertTrue(fails.results().values().stream().allMatch(result -> result.verdict().kind() == Verdict.Kind.MISMATCH), fails::describe);
        var data = BOTH.run(new DiffCase("data", cursors(walkerOf("""
                public static long m(PlutusData d, long l, S e) { PlutusData c = Builtins.iData(BigInteger.ONE); long n = 0; while (n < l) { c = switch (e) { case S.A q -> Builtins.tailList(Builtins.unListData(d)); case S.B q -> c; }; n = n + 1; } return n; }
                """)), "Case", "m", List.of(LIST, integer(1), constr(1)), TAGS2, null));
        assertTrue(data.all(Verdict.Kind.AGREE), data::describe);
    }

    /** Where the reference reads Data, a list and what is not one are Data, so a list start fails on both. */
    @Test
    void aMixTheReferenceDoesNotReadIsData() {
        for (String other : List.of("Builtins.iData(BigInteger.valueOf(n))", "PlutusData.UNIT")) {
            var report = BOTH.run(new DiffCase("data", cursors("public static long m(PlutusData d, long l) { PlutusData c = Builtins.unListData(d); long n = 0; while (n < l) { if (n > 100) { c = (%s); } else { c = (Builtins.tailList(c)); } n = n + 1; } return n; }".formatted(other)),
                    "Case", "m", List.of(LIST, integer(0))));
            assertTrue(report.results().values().stream().allMatch(result -> result.verdict().kind() == Verdict.Kind.MISMATCH), report::describe);
        }
    }

    /** A direct assignment on a Data start is the reference's reading, and its failure on entry, which fails closed. */
    @ParameterizedTest
    @ValueSource(strings = {
            "public static long m(PlutusData d, long l) { PlutusData c = d; long n = 0; while (n < l) { c = Builtins.mkNilData(); n = n + 1; } return n; }",
            "public static long m(PlutusData d, long l) { PlutusData c = d; long n = 0; while (n < l) { if (n > 100) { c = Builtins.mkNilData(); } n = n + 1; } return n; }",
            "public static long m(PlutusData d, long l) { PlutusData c = Builtins.iData(BigInteger.ONE); long n = 0; while (n < l) { if (n > 100) { c = Builtins.tailList(d); } n = n + 1; } return n; }",
    })
    void aDirectAssignmentOnADataStartIsTheReferencesFailure(String members) {
        var report = BOTH.run(new DiffCase("data", cursors(members), "Case", "m", List.of(ONE, integer(0))));
        assertTrue(report.results().values().stream().allMatch(result -> result.verdict().kind() == Verdict.Kind.MISMATCH), report::describe);
    }

    /** Where the reference's reading is the other kind of list, it stands, and the start fails on entry on both. */
    @ParameterizedTest
    @ValueSource(strings = {
            "public static long m(PlutusData d, PlutusData e) { PlutusData c = Builtins.unMapData(d); PlutusData f = Builtins.unListData(e); long s = 0; while (!Builtins.nullList(c)) { s = s + 1; c = Builtins.tailList(c); f = Builtins.tailList(f); } return s; }",
            "public static long m(PlutusData d, PlutusData e) { PlutusData c = Builtins.tailList(Builtins.unMapData(d)); long s = 0; while (!Builtins.nullList(c)) { c = Builtins.tailList(c); s = s + 1; } return s; }",
            "public static long m(PlutusData d, PlutusData e) { PlutusData c = Builtins.unMapData(d); long s = 0; long i = 0; while (i < 2) { i = i + 1; while (!Builtins.nullList(c)) { c = Builtins.tailList(c); s = s + 1; } } return s; }",
    })
    void theReferencesReadingOfTheOtherKindStands(String members) {
        var report = BOTH.run(new DiffCase("cursors", cursors(members), "Case", "m", List.of(MAP, LIST)));
        assertTrue(report.results().values().stream().allMatch(result -> result.verdict().kind() == Verdict.Kind.MISMATCH), report::describe);
    }

    private static String walkerOf(String method) {
        return "public sealed interface S { record A() implements S { } record B() implements S { } } " + method;
    }

    private static final TagTable TAGS2 = new TagTable(List.of(new TagTable.Entry("Case$S$A", "Case$S", 0), new TagTable.Entry("Case$S$B", "Case$S", 1)));

    /** A cursor over a pair list and the fields of a constructor travel as what they are, and a lambda's parameter is one type too. */
    @Test
    void whatElseACursorIs() {
        assertCarried("public static long m(PlutusData d) { PlutusData c = Builtins.constrFields(d); long s = 0; while (!Builtins.nullList(c)) { c = Builtins.tailList(c); s = s + 1; } return s; }",
                false, constr(0, integer(1), integer(2)));
        assertCarried("public static long m(PlutusData d) { PlutusData c = Builtins.constrFields(d); long i = 0; while (i < 2) { i = i + 1; c = c; } return Builtins.nullList(c) ? 1 : 2; }",
                true, constr(0, integer(1), integer(2)));
        assertCarried("public static boolean m(BigInteger a) { return JulcList.of(a, a).any(x -> { var y = x; long n = 0; while (n < 2) { n = n + 1; y = x; } return n > 5; }); }",
                false, integer(1));
        assertCarried("public static boolean m(BigInteger a) { return JulcList.of(a, a).any(x -> { long n = 0; while (n < 2) { n = n + 1; x = BigInteger.TEN; } return n > 5; }); }",
                true, integer(1));
    }

    /** I2d's fail-closed direction: an element read as Data that holds a boolean has no {@code equals} row, so it is {@code JULC0055}, which is listed for M5. */
    @Test
    void equalsOnAnElementOfAParenthesizedList() {
        var report = RUNNER.run(new DiffCase("equals", cursors("public static long m(JulcList<Boolean> xs) { long s = 0; for (var x : (xs)) { if (x.equals(true)) { s = s + 1; } } return s; }"),
                "Case", "m", List.of(list(bool(true), bool(false)))));
        report.results().values().forEach(result -> {
            assertEquals(Verdict.Kind.REJECTED, result.verdict().kind(), report::describe);
            assertTrue(result.uplc().toString().contains("JULC0055"), report::describe);
        });
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
