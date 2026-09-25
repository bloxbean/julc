package org.julclang.testkit.property;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.CompilationContext;
import org.julclang.compiler.backend.PirBackend;
import org.julclang.compiler.codegen.StrictBoundaryGenerator;
import org.julclang.compiler.pir.PirTerm;
import org.julclang.compiler.pir.PirType;
import org.julclang.core.Constant;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.Term;
import org.julclang.testkit.property.PropertyCheck.Settings;
import org.julclang.testkit.property.PropertyCheck.Verdict;
import org.julclang.vm.EvalResult;
import org.julclang.vm.JulcVm;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DataGeneratorsTest {
    static final PirType INT = new PirType.IntegerType();
    static final PirType BYTES = new PirType.ByteStringType();
    static final PirType STRING = new PirType.StringType();
    static final PirType BOOL = new PirType.BoolType();
    static final PirType DATA = new PirType.DataType();

    /** {@code IntList = Nil | Cons Int IntList}, by reference. */
    static final PirType.NamedTypeRef INT_LIST = new PirType.NamedTypeRef("test.IntList", "IntList", PirType.NamedKind.SUM);
    /** {@code Tree = Leaf Int | Node Tree Tree}: no nullary constructor, and the leaf comes first. */
    static final PirType.NamedTypeRef TREE = new PirType.NamedTypeRef("test.Tree", "Tree", PirType.NamedKind.SUM);
    static final Map<String, PirType> NAMED = Map.of(
            INT_LIST.stableId(), new PirType.SumType("IntList", List.of(
                    new PirType.Constructor("Nil", 0, List.of()),
                    new PirType.Constructor("Cons", 1, List.of(field("head", INT), field("tail", INT_LIST))))),
            TREE.stableId(), new PirType.SumType("Tree", List.of(
                    new PirType.Constructor("Leaf", 0, List.of(field("value", INT))),
                    new PirType.Constructor("Node", 1, List.of(field("left", TREE), field("right", TREE))))));

    static PirType.Field field(String name, PirType type) {
        return new PirType.Field(name, type);
    }

    static final PirType ACTION = new PirType.SumType("Action", List.of(
            new PirType.Constructor("Close", 0, List.of()),
            new PirType.Constructor("Deposit", 1, List.of(field("amount", INT))),
            new PirType.Constructor("Pay", 3, List.of(field("to", BYTES), field("memo", new PirType.OptionalType(STRING))))));
    static final PirType ORDER = new PirType.RecordType("Order", List.of(
            field("owner", BYTES), field("amounts", new PirType.ListType(INT)), field("open", BOOL),
            field("action", ACTION), field("tags", new PirType.MapType(BYTES, new PirType.ListType(new PirType.OptionalType(INT)))),
            field("extra", DATA), field("unit", new PirType.UnitType())));

    /**
     * The backend's own strict Data check for {@code type} (the guard validators run on their
     * datum and redeemer), lowered as a function {@code Data -> Bool}.
     */
    static Program strictCheck(PirType type) {
        var data = new PirTerm.Var("candidate", DATA);
        var check = new StrictBoundaryGenerator(NAMED).check(data, type);
        return PirBackend.lower(new PirTerm.Lam("candidate", DATA, check),
                CompilationContext.resolve(new CompilerOptions()), null, true).program();
    }

    static boolean conforms(Program check, PlutusData value) {
        var result = JulcVm.create().evaluate(check.applyParams(value), CompilerTarget.PLUTUS_V3_PV11.ledgerTarget());
        var success = assertInstanceOf(EvalResult.Success.class, result, result::toString);
        return success.resultTerm() instanceof Term.Const(Constant.BoolConst(boolean ok)) && ok;
    }

    @Test
    void generatedValuesPassTheStrictBoundaryCheck() {
        for (var type : List.of(INT, BYTES, STRING, BOOL, new PirType.UnitType(), DATA, ACTION, ORDER, INT_LIST, TREE,
                new PirType.ListType(TREE), new PirType.MapType(ORDER, INT_LIST))) {
            var generator = DataGenerators.forType(type, NAMED);
            var check = strictCheck(type);
            var random = new SplittableRandom(7);
            for (int i = 0; i < 60; i++) {
                var value = generator.generate(random, i / 2);
                assertTrue(conforms(check, value), () -> type + " generated " + value);
                // Every shrink candidate is still a value of the type.
                generator.shrink(value).limit(15).forEach(simpler ->
                        assertTrue(conforms(check, simpler), () -> type + " shrank " + value + " to " + simpler));
            }
        }
    }

    @Test
    void aSeedReproducesTheValues() {
        var generator = DataGenerators.forType(ORDER, NAMED);
        var first = new SplittableRandom(99);
        var second = new SplittableRandom(99);
        for (int size = 0; size < 30; size++) assertEquals(generator.generate(first, size), generator.generate(second, size));
    }

    @Test
    void sizeZeroValuesAreTheShallowest() {
        var random = new SplittableRandom(1);
        var nil = new PlutusData.ConstrData(0, List.of());
        for (int i = 0; i < 20; i++) {
            assertEquals(nil, DataGenerators.forType(INT_LIST, NAMED).generate(random, 0));
            var leaf = (PlutusData.ConstrData) DataGenerators.forType(TREE, NAMED).generate(random, 0);
            assertEquals(0, leaf.tag(), "a Tree at size 0 is a leaf");
            assertEquals(new PlutusData.ListData(List.of()),
                    DataGenerators.forType(new PirType.ListType(INT)).generate(random, 0));
            assertEquals(new PlutusData.ConstrData(1, List.of()),
                    DataGenerators.forType(new PirType.OptionalType(INT)).generate(random, 0), "None");
        }
    }

    @Test
    void recursiveValuesStayFinite() {
        var random = new SplittableRandom(3);
        var generator = DataGenerators.forType(TREE, NAMED);
        for (int i = 0; i < 200; i++) assertTrue(depth(generator.generate(random, 1000)) <= 12);
    }

    static int depth(PlutusData value) {
        return value instanceof PlutusData.ConstrData constr
                ? 1 + constr.fields().stream().mapToInt(DataGeneratorsTest::depth).max().orElse(0) : 0;
    }

    @Test
    void mapKeysAreDistinct() {
        var generator = DataGenerators.forType(new PirType.MapType(BOOL, INT));
        var random = new SplittableRandom(5);
        for (int i = 0; i < 100; i++) {
            var map = (PlutusData.MapData) generator.generate(random, 30);
            assertTrue(map.entries().size() <= 2);
            assertEquals(map.entries().size(), map.entries().stream().map(PlutusData.Pair::key).distinct().count());
            generator.shrink(map).forEach(simpler -> {
                var entries = ((PlutusData.MapData) simpler).entries();
                assertEquals(entries.size(), entries.stream().map(PlutusData.Pair::key).distinct().count());
            });
        }
    }

    @Test
    void rangesAndLengthsHold() {
        var random = new SplittableRandom(11);
        var amounts = DataGenerators.integers(10, 20);
        var hashes = DataGenerators.bytes(28);
        var naturals = DataGenerators.integers(BigInteger.ZERO, null);
        for (int i = 0; i < 300; i++) {
            var amount = ((PlutusData.IntData) amounts.generate(random, i % 40)).value().intValueExact();
            assertTrue(amount >= 10 && amount <= 20, "amount " + amount);
            assertEquals(28, ((PlutusData.BytesData) hashes.generate(random, i % 40)).size());
            assertTrue(((PlutusData.IntData) naturals.generate(random, i % 40)).value().signum() >= 0);
        }
        amounts.shrink(new PlutusData.IntData(17)).forEach(v -> {
            int value = ((PlutusData.IntData) v).value().intValueExact();
            assertTrue(value >= 10 && value < 17, "shrank to " + value);
        });
        var hash = hashes.generate(random, 5);
        hashes.shrink(hash).forEach(v -> assertEquals(28, ((PlutusData.BytesData) v).size()));
        assertEquals(new PlutusData.BytesData(new byte[28]), hashes.shrink(hash).findFirst().orElseThrow());
    }

    @Test
    void failuresShrinkToMinimalCounterexamples() {
        var ints = DataGenerators.integers();
        var result = PropertyCheck.check(List.of(ints), args -> Verdict.of(integer(args.getFirst()) < 100, "too big"),
                Settings.seeded(42).withTries(500));
        assertFalse(result.passed());
        assertEquals(List.of(new PlutusData.IntData(100)), result.counterexample(), result.describe());
        assertTrue(result.describe().contains("seed 42"));

        // A list whose sum reaches 10 shrinks until removing or decrementing any element passes.
        var lists = DataGenerators.listOf(DataGenerators.integers(0, 1000));
        var sum = PropertyCheck.check(List.of(lists), args -> Verdict.of(sum(items(args.getFirst())) < 10, "sum too big"),
                Settings.seeded(1));
        assertFalse(sum.passed());
        var shrunk = items(sum.counterexample().getFirst());
        assertEquals(10, sum(shrunk), sum.describe());
        assertTrue(shrunk.stream().noneMatch(new PlutusData.IntData(0)::equals), sum.describe());

        // Recursive values shrink through their subterms: a tree with a leaf of 5 or more becomes that leaf.
        var trees = DataGenerators.forType(TREE, NAMED);
        var tree = PropertyCheck.check(List.of(trees), args -> Verdict.of(maxLeaf(args.getFirst()) < 5, "big leaf"),
                Settings.seeded(9).withTries(300));
        assertEquals(List.of(new PlutusData.ConstrData(0, List.of(new PlutusData.IntData(5)))), tree.counterexample(),
                tree.describe());

        // Two arguments shrink independently.
        var pair = PropertyCheck.check(List.of(ints, ints), args -> Verdict.of(
                integer(args.get(0)) <= 3 || integer(args.get(1)) <= 7, "both big"), Settings.seeded(4).withTries(500));
        assertEquals(List.of(new PlutusData.IntData(4), new PlutusData.IntData(8)), pair.counterexample(), pair.describe());
    }

    static long integer(PlutusData value) {
        return ((PlutusData.IntData) value).value().longValueExact();
    }

    static List<PlutusData> items(PlutusData value) {
        return ((PlutusData.ListData) value).items();
    }

    static long sum(List<PlutusData> values) {
        return values.stream().mapToLong(DataGeneratorsTest::integer).sum();
    }

    static long maxLeaf(PlutusData tree) {
        var constr = (PlutusData.ConstrData) tree;
        return constr.tag() == 0 ? integer(constr.fields().getFirst())
                : Math.max(maxLeaf(constr.fields().get(0)), maxLeaf(constr.fields().get(1)));
    }

    @Test
    void runsAreReproducibleAndExceptionsFail() {
        var generators = List.of(DataGenerators.forType(ORDER, NAMED));
        var seen = new ArrayList<PlutusData>();
        var passed = PropertyCheck.check(generators, args -> {
            seen.add(args.getFirst());
            return Verdict.pass();
        }, Settings.seeded(77).withTries(25));
        assertTrue(passed.passed());
        assertEquals(25, passed.tries());
        var again = new ArrayList<PlutusData>();
        PropertyCheck.check(generators, args -> {
            again.add(args.getFirst());
            return Verdict.pass();
        }, Settings.seeded(77).withTries(25));
        assertEquals(seen, again);

        var thrown = PropertyCheck.check(List.of(DataGenerators.integers()), args -> {
            throw new IllegalStateException("boom " + integer(args.getFirst()));
        }, Settings.seeded(1));
        assertFalse(thrown.passed());
        assertEquals(1, thrown.tries());
        assertTrue(thrown.message().startsWith("boom"), thrown.message());
    }

    @Test
    void overridesReplaceNamedTypes() {
        var override = DataGenerators.constant(new PlutusData.IntData(42));
        var byName = DataGenerators.forType(new PirType.ListType(TREE), NAMED, Map.of("Tree", override));
        var byId = DataGenerators.forType(new PirType.ListType(TREE), NAMED, Map.of(TREE.stableId(), override));
        var random = new SplittableRandom(2);
        for (int i = 0; i < 20; i++) {
            items(byName.generate(random, 10)).forEach(v -> assertEquals(new PlutusData.IntData(42), v));
            items(byId.generate(random, 10)).forEach(v -> assertEquals(new PlutusData.IntData(42), v));
        }
    }

    @Test
    void typesWithoutADataEncodingAreRejected() {
        for (var type : List.of(new PirType.PairType(INT, INT), new PirType.ArrayType(INT), new PirType.FunType(INT, INT),
                new PirType.ListType(new PirType.PairType(INT, INT)), new PirType.NativeValueType()))
            assertThrows(IllegalArgumentException.class, () -> DataGenerators.forType(type), type::toString);
        var loop = new PirType.NamedTypeRef("test.Loop", "Loop", PirType.NamedKind.RECORD);
        var endless = Map.<String, PirType>of(loop.stableId(), new PirType.RecordType("Loop", List.of(field("next", loop))));
        var error = assertThrows(IllegalArgumentException.class, () -> DataGenerators.forType(loop, endless));
        assertTrue(error.getMessage().contains("no finite value"), error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> DataGenerators.forType(TREE));
    }

    @Test
    void stringsAreUtf8() {
        var random = new SplittableRandom(8);
        var strings = DataGenerators.strings();
        var decoded = new HashSet<String>();
        for (int i = 0; i < 200; i++) {
            var bytes = ((PlutusData.BytesData) strings.generate(random, 30)).value();
            var text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            assertArrayEquals(bytes, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            decoded.add(text);
        }
        assertTrue(decoded.size() > 150, decoded.stream().limit(5).collect(Collectors.joining(", ")));
    }
}
