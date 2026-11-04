package org.julclang.difftest;

import com.sun.source.util.JavacTask;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterProperty;
import org.julclang.compiler.CompilerFrontend;
import org.julclang.compiler.frontend.javac.ArrayIdentityEquality;
import org.julclang.compiler.frontend.javac.EqualityRelation;
import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;
import org.julclang.core.types.Tuple2;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.TokenName;

import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EqualityRelation} against a plain Java model: pairs of static types, each with real values, and Java's own
 * {@code equals} on them. The model's Data is what a script compares, written here apart from the compiler. Two claims:
 * <ul>
 *   <li>related types: where two values have one Data, Java says they are equal, so a script cannot say true where Java says
 *       false;</li>
 *   <li>unrelated types: Java says false on every pair, but for containers that hold nothing, which is the rule's stated
 *       cost.</li>
 * </ul>
 * A second property compiles the comparison on {@code frontend=JAVAC}: what lowers must agree with the JVM at every level.
 */
public class Adr068EqualityRelationPropertyTest {

    public record R(BigInteger x) {
    }

    public record Q(BigInteger x) {
    }

    public sealed interface S permits A, B {
    }

    public record A(BigInteger x) implements S {
    }

    public record B(BigInteger x) implements S {
    }

    /** A static type: how Java spells it, and values of it. */
    private record Model(String type, List<Object> values) {
    }

    private static final BigInteger FIVE = BigInteger.valueOf(5);
    private static final byte[] X = "x".getBytes(StandardCharsets.UTF_8);
    private static final String HERE = Adr068EqualityRelationPropertyTest.class.getCanonicalName();

    private static List<Model> leaves() {
        return List.of(
                new Model("java.math.BigInteger", List.of(FIVE, BigInteger.TWO)),
                new Model("java.lang.Long", List.of(5L)),
                new Model("java.lang.Integer", List.of(5)),
                new Model("java.lang.String", List.of("x")),
                new Model("java.lang.Boolean", List.of(true)),
                new Model("org.julclang.ledger.PubKeyHash", List.of(new PubKeyHash(X))),
                new Model("org.julclang.ledger.TokenName", List.of(new TokenName(X))),
                new Model("org.julclang.core.PlutusData", List.of(new PlutusData.IntData(FIVE), new PlutusData.BytesData(X))),
                new Model("org.julclang.core.PlutusData.IntData", List.of(new PlutusData.IntData(FIVE))),
                new Model(HERE + ".R", List.of(new R(FIVE))),
                new Model(HERE + ".Q", List.of(new Q(FIVE))),
                new Model(HERE + ".S", List.of(new A(FIVE), new B(FIVE))),
                new Model(HERE + ".A", List.of(new A(FIVE))));
    }

    private static List<Model> universe() {
        var models = new ArrayList<>(leaves());
        models.add(new Model("byte[]", List.of((Object) X)));
        for (var leaf : leaves()) {
            var optional = new ArrayList<Object>(List.of(Optional.empty()));
            var list = new ArrayList<Object>(List.of(List.of()));
            var julcList = new ArrayList<Object>(List.of(JulcList.empty()));
            var map = new ArrayList<Object>(List.of(JulcMap.empty()));
            var tuples = new ArrayList<Object>();
            for (var value : leaf.values()) {
                optional.add(Optional.of(value));
                list.add(List.of(value));
                julcList.add(JulcList.of(value));
                map.add(JulcMap.empty().insert(value, FIVE));
                tuples.add(new Tuple2<>(value, FIVE));
            }
            models.add(new Model("java.util.Optional<" + leaf.type() + ">", optional));
            models.add(new Model("java.util.List<" + leaf.type() + ">", list));
            models.add(new Model("org.julclang.core.types.JulcList<" + leaf.type() + ">", julcList));
            models.add(new Model("org.julclang.core.types.JulcMap<" + leaf.type() + ", java.math.BigInteger>", map));
            models.add(new Model("org.julclang.core.types.Tuple2<" + leaf.type() + ", java.math.BigInteger>", tuples));
        }
        models.add(new Model("org.julclang.core.types.JulcList<byte[]>", List.of(JulcList.of((Object) X))));
        return models;
    }

    private static final List<Model> MODELS = universe();
    private static final List<TypeMirror> TYPES = new ArrayList<>();
    private static final EqualityRelation RELATION;
    private static final ArrayIdentityEquality ARRAYS;

    static {
        var source = new StringBuilder("class Types {\n");
        for (int i = 0; i < MODELS.size(); i++) source.append(MODELS.get(i).type()).append(" f").append(i).append(";\n");
        String text = source.append("}\n").toString();
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///Types.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        };
        var task = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, null, null,
                List.of("-proc:none", "-classpath", System.getProperty("java.class.path")), null, List.of(file));
        try {
            task.parse();
            task.analyze();
            var type = task.getElements().getTypeElement("Types");
            for (var field : ElementFilter.fieldsIn(type.getEnclosedElements())) TYPES.add(field.asType());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        if (TYPES.size() != MODELS.size()) throw new IllegalStateException("javac read " + TYPES.size() + " of " + MODELS.size() + " types");
        RELATION = new EqualityRelation(task.getTypes(), task.getElements());
        ARRAYS = new ArrayIdentityEquality(task.getTypes(), task.getElements());
    }

    /** What a script compares: the Data of a value, written from the encodings and not from the compiler. */
    private static PlutusData data(Object value) {
        return switch (value) {
            case PlutusData data -> data;
            case BigInteger i -> new PlutusData.IntData(i);
            case Long i -> new PlutusData.IntData(BigInteger.valueOf(i));
            case Integer i -> new PlutusData.IntData(BigInteger.valueOf(i));
            case String s -> new PlutusData.BytesData(s.getBytes(StandardCharsets.UTF_8));
            case byte[] bytes -> new PlutusData.BytesData(bytes);
            case Boolean b -> new PlutusData.ConstrData(b ? 1 : 0, List.of());
            case PubKeyHash hash -> new PlutusData.BytesData(hash.hash());
            case TokenName name -> new PlutusData.BytesData(name.name());
            case R r -> new PlutusData.ConstrData(0, List.of(data(r.x())));
            case Q q -> new PlutusData.ConstrData(0, List.of(data(q.x())));
            case A a -> new PlutusData.ConstrData(0, List.of(data(a.x())));
            case B b -> new PlutusData.ConstrData(1, List.of(data(b.x())));
            case Optional<?> o -> o.isPresent() ? new PlutusData.ConstrData(0, List.of(data(o.get()))) : new PlutusData.ConstrData(1, List.of());
            case Tuple2<?, ?> t -> new PlutusData.ConstrData(0, List.of(data(t.first()), data(t.second())));
            case List<?> list -> new PlutusData.ListData(list.stream().map(Adr068EqualityRelationPropertyTest::data).toList());
            case JulcList<?> list -> {
                var items = new ArrayList<PlutusData>();
                for (Object item : list) items.add(data(item));
                yield new PlutusData.ListData(items);
            }
            case JulcMap<?, ?> map -> {
                var entries = new ArrayList<PlutusData.Pair>();
                var keys = map.keys();
                var values = map.values();
                for (long i = 0; i < map.size(); i++) entries.add(new PlutusData.Pair(data(keys.get(i)), data(values.get(i))));
                yield new PlutusData.MapData(entries);
            }
            default -> throw new IllegalArgumentException("no model for " + value.getClass());
        };
    }

    /** Whether {@code value} is a container with nothing in it, at any depth. */
    private static boolean holdsNothing(Object value) {
        return value instanceof Optional<?> o && o.isEmpty() || value instanceof List<?> l && l.isEmpty()
                || value instanceof JulcList<?> j && j.isEmpty() || value instanceof JulcMap<?, ?> m && m.isEmpty();
    }

    private static final Map<String, Integer> COUNTS = new TreeMap<>();

    @Provide
    Arbitrary<Integer> indices() {
        return Arbitraries.integers().between(0, MODELS.size() - 1);
    }

    @Property(tries = 20_000, seed = "68", generation = net.jqwik.api.GenerationMode.RANDOMIZED)
    void theVerdictHoldsOnRealValues(@ForAll("indices") int left, @ForAll("indices") int right) {
        var result = RELATION.between(TYPES.get(left), TYPES.get(right));
        var verdict = result.kind();
        // Containers of unrelated arguments are undecided only because two empty ones are equal (M5a review 2, F3).
        boolean empties = RELATION.onlyEmptiesEqual(result);
        String name = empties ? "EMPTIES" : verdict.name();
        COUNTS.merge("pairs " + name, 1, Integer::sum);
        assertEquals(verdict, RELATION.between(TYPES.get(right), TYPES.get(left)).kind(), "the relation has no direction");
        boolean arrays = ARRAYS.check(TYPES.get(left)).rejected();
        for (Object x : MODELS.get(left).values()) {
            for (Object y : MODELS.get(right).values()) {
                boolean java = x.equals(y);
                boolean script = data(x).equals(data(y));
                String pair = MODELS.get(left).type() + " " + x + " / " + MODELS.get(right).type() + " " + y;
                COUNTS.merge("values java=" + java + " script=" + script + " " + name, 1, Integer::sum);
                if (verdict == EqualityRelation.Kind.RELATED && !arrays) assertEquals(java, script, pair);
                if ((verdict == EqualityRelation.Kind.UNRELATED || empties) && java) assertTrue(holdsNothing(x) && holdsNothing(y), pair);
            }
        }
    }

    @AfterProperty
    void theModelMeetsEveryCase() {
        if (COUNTS.isEmpty()) return;
        System.out.println("relation " + COUNTS);
        assertTrue(COUNTS.getOrDefault("values java=true script=true RELATED", 0) > 100, COUNTS::toString);
        assertTrue(COUNTS.getOrDefault("values java=false script=true UNRELATED", 0) > 100, COUNTS::toString);
        assertTrue(COUNTS.getOrDefault("pairs RELATED", 0) > 100, COUNTS::toString);
        assertFalse(COUNTS.containsKey("pairs UNRESOLVED"), COUNTS::toString);
    }

    // --- Through the compiler ---

    private static final DiffRunner RUNNER = new DiffRunner(DiffConfig.allLevels(), 10_000);

    private static final TagTable TAGS = new TagTable(List.of(new TagTable.Entry("Case$S$A", "Case$S", 0), new TagTable.Entry("Case$S$B", "Case$S", 1)));

    private record Spelled(String type, PlutusData sample) {
    }

    private static final PlutusData INT = new PlutusData.IntData(FIVE);
    private static final PlutusData BYTES = new PlutusData.BytesData(X);
    private static final PlutusData CONSTR = new PlutusData.ConstrData(0, List.of(INT));

    private static List<Spelled> spelled() {
        var leaves = List.of(new Spelled("BigInteger", INT), new Spelled("String", BYTES), new Spelled("PubKeyHash", BYTES), new Spelled("TokenName", BYTES),
                new Spelled("PlutusData", INT), new Spelled("R", CONSTR), new Spelled("Q", CONSTR), new Spelled("S", CONSTR), new Spelled("S.A", CONSTR));
        var types = new ArrayList<>(leaves);
        for (var leaf : leaves) {
            types.add(new Spelled("Optional<" + leaf.type() + ">", new PlutusData.ConstrData(0, List.of(leaf.sample()))));
            types.add(new Spelled("List<" + leaf.type() + ">", new PlutusData.ListData(List.of(leaf.sample()))));
            types.add(new Spelled("JulcList<" + leaf.type() + ">", new PlutusData.ListData(List.of(leaf.sample()))));
            types.add(new Spelled("Tuple2<" + leaf.type() + ", BigInteger>", new PlutusData.ConstrData(0, List.of(leaf.sample(), INT))));
        }
        return types;
    }

    private static final List<Spelled> SPELLED = spelled();
    private static final Map<String, Integer> OUTCOMES = new TreeMap<>();

    @Provide
    Arbitrary<Integer> spelledIndices() {
        return Arbitraries.integers().between(0, SPELLED.size() - 1);
    }

    @Provide
    Arbitrary<String> comparisons() {
        return Arbitraries.of("a.equals(b)", "c.contains(b)", "d.containsKey(b)");
    }

    /** Each side holds one Data where it can, so that a representation comparison would say true. */
    @Property(tries = 300, seed = "68", generation = net.jqwik.api.GenerationMode.RANDOMIZED)
    void whatLowersAgreesWithTheJvm(@ForAll("spelledIndices") int left, @ForAll("spelledIndices") int right, @ForAll boolean oneType,
                                    @ForAll("comparisons") String comparison) {
        var a = SPELLED.get(left);
        var b = SPELLED.get(oneType ? left : right);
        String signature = "public static boolean m(%s a, %s b, List<%s> c, Map<%s, BigInteger> d) { return %s; }"
                .formatted(a.type(), b.type(), a.type(), a.type(), comparison);
        var args = List.of(a.sample(), b.sample(), new PlutusData.ListData(List.of(a.sample())),
                new PlutusData.MapData(List.of(new PlutusData.Pair(a.sample(), INT))));
        var report = RUNNER.run(new DiffCase("relation", program(signature), "Case", "m", args, TAGS, null));
        report.results().forEach((config, result) -> {
            var kind = result.verdict().kind();
            OUTCOMES.merge(kind.name(), 1, Integer::sum);
            if (kind == Verdict.Kind.REJECTED) {
                String text = result.uplc().toString();
                assertTrue(text.contains("JULC0078") || text.contains("JULC0087") || text.contains("JULC0084") || text.contains("JULC0055"), report::describe);
            } else {
                assertEquals(Verdict.Kind.AGREE, kind, report::describe);
            }
        });
    }

    @AfterProperty
    void aRejectionIsNoPass() {
        System.out.println("through the compiler " + OUTCOMES);
        if (!OUTCOMES.isEmpty()) assertTrue(OUTCOMES.getOrDefault("AGREE", 0) > 100, OUTCOMES::toString);
    }

    private static String program(String method) {
        return """
                import java.math.BigInteger;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;
                import org.julclang.core.PlutusData;
                import org.julclang.core.types.JulcList;
                import org.julclang.core.types.Tuple2;
                import org.julclang.ledger.PubKeyHash;
                import org.julclang.ledger.TokenName;

                public class Case {
                    public record R(BigInteger x) { }
                    public record Q(BigInteger x) { }
                    public sealed interface S { record A(BigInteger x) implements S { } record B(BigInteger x) implements S { } }
                    %s
                }
                """.formatted(method);
    }
}
