package org.julclang.difftest;

import org.julclang.difftest.gen.G1;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G1's {@code switch} grammar as read from the printed programs by javac's parser ({@link G1Source}), not from the
 * generator's markers (ADR-066 D2, D8; review of M3 part 1, F75, F97): each switch feature is in the source exactly
 * when it is marked, arm orders differ from {@code permits} and declaration orders, and no program has a switch in a
 * method receiver (#290) or in a test (the javac limitation), or an arm that assigns an enclosing variable (ADR-048).
 */
class G1SwitchShapesTest {

    static final int PLANS = 4000;

    private static List<Integer> plan(Random random) {
        var plan = new ArrayList<Integer>();
        for (int j = random.nextInt(201); j > 0; j--) plan.add(random.nextInt(1001));
        return plan;
    }

    @Test
    void theSourceShowsExactlyTheMarkedSwitchFeatures() {
        for (var profile : List.of(G1.Profile.DEFAULT, G1.Profile.REDISCOVER_162, G1.Profile.REDISCOVER_291,
                G1.Profile.REDISCOVER_291_LOOPS)) {
            var random = new Random(20261044);
            var seen = EnumSet.noneOf(G1.Feature.class);
            int permuted = 0, switches = 0;
            for (int i = 0; i < (profile == G1.Profile.DEFAULT ? PLANS : PLANS / 4); i++) {
                var plan = plan(random);
                var program = G1.decode(plan, profile);
                var reading = G1Source.read(program.source());
                var marked = EnumSet.copyOf(G1Source.FEATURES);
                marked.retainAll(program.features());
                assertEquals(marked, reading.features(), profile + ", plan " + plan + "\n" + program.source());
                assertEquals(List.of(), reading.violations(), profile + ", plan " + plan + "\n" + program.source());
                seen.addAll(reading.features());
                if (reading.features().contains(G1.Feature.SWITCH)) switches++;
                if (reading.armsPermuted()) permuted++;
            }
            var expected = EnumSet.copyOf(G1Source.FEATURES);
            // Steered to #162, every arm loop assigns the binding itself instead of a copy.
            if (profile == G1.Profile.REDISCOVER_162) expected.remove(G1.Feature.BINDING_ACCUMULATOR);
            else expected.remove(G1.Feature.ASSIGN_PATTERN_BINDING);
            // #291's symptoms appear only under DEFAULT_BLOCK_SCOPE, and either may then; G1GatesTest checks that
            // each steered profile generates its own.
            var symptoms = Set.of(G1.Feature.DEFAULT_BLOCK_SHADOW, G1.Feature.DEFAULT_BLOCK_LOOPS);
            expected.removeAll(symptoms);
            if (profile.has(G1.Gate.DEFAULT_BLOCK_SCOPE)) seen.removeAll(symptoms);
            assertEquals(expected, seen, profile + ": the switch features the source shows");
            // A share of the switches lists its variants in neither their permits order nor their declaration order.
            if (profile == G1.Profile.DEFAULT) assertTrue(permuted > switches / 20, "permuted arm orders: " + permuted + " of " + switches);
        }
    }

    private static final String FAMILY = """
            import java.math.BigInteger;

            public class Probe {
                sealed interface S permits B, A, C {}
                record A(BigInteger v) implements S {}
                record B() implements S {}
                record C() implements S {}
                static final BigInteger v = BigInteger.ONE;

                public static BigInteger m(BigInteger a, S s) {
                    %s
                }
            }
            """;

    private static G1Source.Reading reading(String body) {
        return G1Source.read(FAMILY.formatted(body));
    }

    /** The reading is not vacuous: each shape it rejects or reports is seen in a hand-written program. */
    @Test
    void theReadingSeesEachShape() {
        String multiLineTest = """
                if (switch (s) {
                            case A q -> true;
                            default -> false;
                        }) { return a; }
                        return a;""";
        assertTrue(reading(multiLineTest).violations().getFirst().startsWith("a switch in a test"));
        assertTrue(reading("return a.compareTo(BigInteger.ONE) > 0 ? a : (switch (s) { default -> a; }).add(a);")
                .violations().getFirst().startsWith("a switch in a method receiver"));
        assertEquals(List.of(), reading("return a.add(switch (s) { default -> a; });").violations());
        assertTrue(reading("BigInteger k = a;\n for (var x : java.util.List.of(a)) { k = switch (s) { case A q -> { k = a; yield a; } "
                + "default -> a; }; }\n return k;").violations().getFirst().contains("assigns k"));
        assertEquals(Set.of(G1.Feature.SWITCH, G1.Feature.TYPE_PATTERN, G1.Feature.DEFAULT_ARM, G1.Feature.YIELD_BLOCK,
                G1.Feature.DEFAULT_BLOCK_SHADOW), reading("""
                BigInteger r = switch (s) { case A q -> a; default -> { BigInteger v = a; yield v; } };
                        return v;""").features());
        String loops = "long i0 = 0; while (i0 < 1) { i0 = i0 + 1; } long i1 = 0; while (i1 < 1) { i1 = i1 + 1; }";
        assertTrue(reading("return a.add(switch (s) { case A q -> a; default -> { " + loops + " yield a; } });")
                .features().contains(G1.Feature.DEFAULT_BLOCK_LOOPS));
        assertFalse(reading("BigInteger r = switch (s) { case A q -> a; default -> { " + loops + " yield a; } }; return a.add(r);")
                .features().contains(G1.Feature.DEFAULT_BLOCK_LOOPS));
        assertFalse(reading("return a.add(switch (s) { case A q -> { " + loops + " yield a; } default -> a; });")
                .features().contains(G1.Feature.DEFAULT_BLOCK_LOOPS));
        var accumulator = reading("""
                return switch (s) {
                            case A q -> {
                                A k = q;
                                long i0 = 0;
                                while (i0 < 2) {
                                    k = new A(a);
                                    i0 = i0 + 1;
                                }
                                yield k.v();
                            }
                            case C q -> switch (s) { case B r -> a; default -> a; };
                            case B q -> a;
                        };""");
        assertEquals(List.of(), accumulator.violations());
        assertTrue(accumulator.features().containsAll(Set.of(G1.Feature.BINDING_ACCUMULATOR, G1.Feature.LOOP_IN_ARM,
                G1.Feature.NESTED_SWITCH)), accumulator.features().toString());
        assertFalse(accumulator.features().contains(G1.Feature.DEFAULT_BLOCK_SHADOW));
        assertTrue(accumulator.armsPermuted(), "A, C, B is neither B, A, C nor A, B, C");
        assertFalse(reading("return switch (s) { case B q -> a; case A q -> a; case C q -> a; };").armsPermuted());
        assertFalse(reading("return switch (s) { case A q -> a; case B q -> a; default -> a; };").armsPermuted());
    }
}
