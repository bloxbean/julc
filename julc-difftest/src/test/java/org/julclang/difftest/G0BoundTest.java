package org.julclang.difftest;

import org.julclang.difftest.gen.G0;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G0 r2 bounds comparison nesting (ADR-063 D13, open question 8): a ternary appears inside fewer than
 * {@link G0#MAX_COMPARISON_NESTING} {@code compareTo} operands, and {@link G0#UNBOUNDED} still decodes G0 r1.
 */
class G0BoundTest {

    /**
     * The plan of a G0 r1 program that Jazzer found and that runs the compiler out of memory at
     * {@code BASELINE} in a fresh JVM, by #146's growth; its ternaries sit 17 operands deep.
     */
    static final List<Integer> R1_OUT_OF_MEMORY = plan("78,111,16,127,243,213,0,0,47,21,124,142,7,96,20,1,25,78,162,"
            + "94,75,47,155,163,167,139,0,0,0,2,59,173,239,96,226," + "203,".repeat(67) + "70,173,251,183,36");

    private static List<Integer> plan(String text) {
        return Arrays.stream(text.split(",")).map(Integer::valueOf).toList();
    }

    @Test
    void theVersionNamesTheBound() {
        assertEquals("G0 r2", G0.VERSION);
        assertTrue(G0.MAX_COMPARISON_NESTING > G0.DEEP_COMPARISON_NESTING, "T13's marker must stay reachable");
    }

    @Test
    void anR1OutOfMemoryPlanIsBoundedInR2() {
        assertEquals(107, R1_OUT_OF_MEMORY.size());
        var r1 = G0.decode(R1_OUT_OF_MEMORY, G0.UNBOUNDED);
        var r2 = G0.decode(R1_OUT_OF_MEMORY);
        assertEquals(17, r1.comparisonNesting());
        assertEquals(G0.MAX_COMPARISON_NESTING - 1, r2.comparisonNesting());
        assertNotEquals(r1.source(), r2.source());
        assertTrue(r2.features().contains(G0.Feature.DEEP_COMPARISON));
    }

    /** The bound holds for every plan, and changes only the programs that exceed it. */
    @Test
    void r2DiffersFromR1OnlyBeyondTheBound() {
        var random = new Random(20261029);
        int beyond = 0;
        for (int i = 0; i < 20_000; i++) {
            var plan = new ArrayList<Integer>();
            for (int j = 0; j < 150; j++) plan.add(random.nextInt(256));
            if (i % 2 == 0) for (int j = 40; j < 120; j++) plan.set(j, 203);  // the fuzzer's deep pattern
            var r1 = G0.decode(plan, G0.UNBOUNDED);
            var r2 = G0.decode(plan);
            assertTrue(r2.comparisonNesting() < G0.MAX_COMPARISON_NESTING, "plan " + plan);
            if (r1.comparisonNesting() < G0.MAX_COMPARISON_NESTING) {
                assertEquals(r1, r2, "plan " + plan);
            } else {
                beyond++;
            }
        }
        assertTrue(beyond > 0, "the sample must include programs beyond the bound");
    }

    @Test
    void theBoundIsPositive() {
        assertThrows(IllegalArgumentException.class, () -> G0.decode(List.of(), 0));
    }
}
