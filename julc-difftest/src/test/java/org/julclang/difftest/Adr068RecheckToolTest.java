package org.julclang.difftest;

import org.julclang.core.PlutusData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recheck tool's rules ({@link Adr068BytesRecheckTest}): what it may propose from a row's runs, and how it draws inputs.
 * The M5a confirmation review (F1, F2, F3) found rows moved to Cost change and Representation only on inputs that never
 * reached the program's value, script size left out of the budgets, and both frontends' shared departure from the JVM
 * folded into Cost change.
 */
class Adr068RecheckToolTest {

    private static final Set<String> AGREE = Set.of("agree");

    @Test
    void tooFewJvmValuesProposeNoCostChangeOrRepresentationOnly() {
        int few = Adr068BytesRecheckTest.ENOUGH_VALUES - 1;
        assertEquals("FEW-VALUES", Adr068BytesRecheckTest.classOf(60, AGREE, AGREE, few, false, true));
        assertEquals("FEW-VALUES", Adr068BytesRecheckTest.classOf(60, AGREE, AGREE, 0, false, false));
        assertEquals("Cost change", Adr068BytesRecheckTest.classOf(60, AGREE, AGREE, few + 1, false, true));
        assertEquals("Representation only", Adr068BytesRecheckTest.classOf(60, AGREE, AGREE, few + 1, false, false));
        // A method of booleans alone, or of no parameter, has every input run: its values are all there are.
        assertEquals("Cost change", Adr068BytesRecheckTest.classOf(1, AGREE, AGREE, 1, true, true));
    }

    @Test
    void aReferenceThatDepartsIsASemanticFixWhateverTheValues() {
        assertEquals("Semantic fix", Adr068BytesRecheckTest.classOf(60, AGREE, Set.of("agree", "differs"), 1, false, true));
    }

    /** Both frontends departing from the JVM alike is its own outcome, whatever the budgets say (F3). */
    @Test
    void aSharedDepartureIsAlikeNeverCostChange() {
        assertEquals("ALIKE", Adr068BytesRecheckTest.classOf(60, Set.of("agree", "alike"), Set.of("agree", "alike"), 50, false, true));
        assertEquals("ALIKE", Adr068BytesRecheckTest.classOf(60, Set.of("agree", "alike"), Set.of("agree", "alike"), 50, false, false));
    }

    /** A JAVAC failure where the JVM returns is never a value: its own outcome; a JAVAC value that differs is a finding. */
    @Test
    void aJavacFailureIsFailsClosedAndAJavacValueAFinding() {
        assertEquals("FAILS-CLOSED", Adr068BytesRecheckTest.classOf(60, Set.of("agree", "fails"), AGREE, 50, false, true));
        assertEquals("FINDING", Adr068BytesRecheckTest.classOf(60, Set.of("fails", "differs"), AGREE, 50, false, true));
        assertEquals("FINDING", Adr068BytesRecheckTest.classOf(60, Set.of("lenient"), AGREE, 50, false, true));
        assertEquals("BY-HAND", Adr068BytesRecheckTest.classOf(60, Set.of("unobserved"), AGREE, 50, false, true));
        assertEquals("NO-INPUT", Adr068BytesRecheckTest.classOf(0, Set.of(), Set.of(), 0, false, true));
    }

    @Test
    void randomInputsAreRepeatableAndOfTheParameterTypes() {
        String source = """
                import java.math.BigInteger;
                import org.julclang.core.types.JulcList;
                import org.julclang.core.types.JulcMap;
                class Case {
                    sealed interface S permits A, B { }
                    record A(BigInteger x) implements S { }
                    record B(JulcList<BigInteger> xs, boolean c) implements S { }
                    public static long m(JulcList<BigInteger> xs, JulcMap<BigInteger, BigInteger> a, S s, boolean c) { return 1; }
                }
                """;
        assertEquals(List.of("JulcList<BigInteger>", "JulcMap<BigInteger,BigInteger>", "S", "boolean"),
                Adr068BytesRecheckTest.parameterTypes(source, "m"));
        var generators = Adr068BytesRecheckTest.generators(source, "m");
        assertNotNull(generators);
        long seed = Adr068BytesRecheckTest.seed("05f23f6b3a33cb39c2d362800c50776283e1f5b02f82a58f2d7aba66e6bcaa39");
        assertEquals(draws(generators, seed), draws(generators, seed));
        var tags = new HashSet<Integer>();
        for (var args : draws(generators, seed)) {
            assertInstanceOf(PlutusData.ListData.class, args.get(0));
            var map = assertInstanceOf(PlutusData.MapData.class, args.get(1));
            assertEquals(map.entries().size(), map.entries().stream().map(PlutusData.Pair::key).distinct().count(), "distinct keys");
            var s = assertInstanceOf(PlutusData.ConstrData.class, args.get(2));
            tags.add(s.tag());
            assertEquals(s.tag() == 0 ? 1 : 2, s.fields().size());
        }
        assertEquals(Set.of(0, 1), tags, "both records of the sealed interface, by their tags");
    }

    @Test
    void aTypeWithNoGeneratorDrawsNothing() {
        String source = """
                class Case {
                    @NewType record H(byte[] v) { }
                    static byte[] m(H h) { return h.v(); }
                    static Object n(Object o) { return o; }
                }
                """;
        assertNull(Adr068BytesRecheckTest.generators(source, "m"));
        assertNull(Adr068BytesRecheckTest.generators(source, "n"));
        assertTrue(Adr068BytesRecheckTest.generators("class Case { static long k() { return 1; } }", "k").isEmpty());
    }

    private static List<List<PlutusData>> draws(List<Adr068BytesRecheckTest.Generator> generators, long seed) {
        var random = new Random(seed);
        var all = new ArrayList<List<PlutusData>>();
        for (int i = 0; i < 40; i++) {
            var args = new ArrayList<PlutusData>();
            for (var g : generators) args.add(g.draw(random));
            all.add(args);
        }
        return all;
    }
}
