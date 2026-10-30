package org.julclang.difftest;

import org.julclang.core.PlutusData;
import org.julclang.difftest.gen.G1;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Constructs whose defect was fixed by a rejection (ADR-066 D6): each must be rejected at every level, with the
 * expected diagnostic. Until ADR-067 lands, this is #162's reassignment of a {@code switch} case-pattern binding
 * inside a loop in its arm ({@code LoopBodyGenerator}), both in fixed programs and in every program the historical
 * profile {@link G1.Profile#REDISCOVER_162} generates with the construct.
 */
class G1MustRejectTest {

    /** #162's diagnostic has no code; its message names the construct. */
    static final String MESSAGE = "Reassignment of switch case-pattern variable";

    private static final DiffRunner RUNNER = DiffRunner.standard();

    private static final String FAMILY = """
            import java.math.BigInteger;
            import org.julclang.core.types.JulcList;

            public class Probe {
                sealed interface S permits A, B {}
                record A(BigInteger v) implements S {}
                record B() implements S {}

                public static BigInteger m(JulcList<BigInteger> xs, S s) {
                    return %s;
                }
            }
            """;

    /** The fixed programs: the binding reassigned in a for-each and in a while loop, read after the loop or not. */
    static final List<String> FIXED = List.of(
            """
            switch (s) {
                        case A q -> {
                            for (var x : xs) {
                                q = new A(x);
                            }
                            yield q.v();
                        }
                        case B q -> BigInteger.ZERO;
                    }""",
            """
            switch (s) {
                        case A q -> {
                            long i = 0;
                            while (i < 2) {
                                q = new A(q.v().add(BigInteger.ONE));
                                i = i + 1;
                            }
                            yield BigInteger.ONE;
                        }
                        default -> BigInteger.TWO;
                    }""");

    private static void assertRejected(DiffCase diffCase) {
        var report = RUNNER.run(diffCase);
        report.results().forEach((config, result) -> {
            var rejected = result.uplc() instanceof Observation.Rejected r ? r : null;
            assertTrue(rejected != null, config + ": " + report.describe());
            assertEquals(null, rejected.code(), config + ": " + rejected);
            assertTrue(rejected.message().contains(MESSAGE), config + ": " + rejected.message());
        });
    }

    @Test
    void theFixedProgramsAreRejectedAtEveryLevel() {
        var tags = TagTable.of(new TagTable.Entry("Probe$A", "Probe$S", 0), new TagTable.Entry("Probe$B", "Probe$S", 1));
        for (String expression : FIXED) {
            var args = List.<PlutusData>of(new PlutusData.ListData(List.of(new PlutusData.IntData(BigInteger.TWO))),
                    new PlutusData.ConstrData(0, List.of(new PlutusData.IntData(BigInteger.ONE))));
            assertRejected(new DiffCase("#162", FAMILY.formatted(expression), "Probe", "m", args, tags, null));
        }
    }

    /**
     * A run under the historical profile on the fixed compiler ends in the expected rejection (D6): every generated
     * program that contains the construct is rejected with #162's diagnostic at every level.
     */
    @Test
    void theHistoricalProfileEndsInTheRejection() {
        var random = new Random(20261043);
        int checked = 0;
        for (int i = 0; i < 4000 && checked < 10; i++) {
            var plan = new ArrayList<Integer>();
            for (int j = random.nextInt(201); j > 0; j--) plan.add(random.nextInt(1001));
            var program = G1.decode(plan, G1.Profile.REDISCOVER_162);
            if (!program.features().contains(G1.Feature.ASSIGN_PATTERN_BINDING)) continue;
            var inputs = new ArrayList<Integer>();
            for (int j = 0; j < 40; j++) inputs.add(random.nextInt(1001));
            assertRejected(new DiffCase("#162 profile", program.source(), "Probe", "m", program.arguments(inputs),
                    program.tags(), program.profile().text()));
            checked++;
        }
        assertEquals(10, checked, "programs with #162's construct under its historical profile");
    }
}
