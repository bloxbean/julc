package org.julclang.difftest;

import org.julclang.difftest.gen.G1;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G1's decoding (ADR-066 D1, G1-I5, ADR-063 H6): the complete case is a function of the plan, the inputs and the
 * profile, also for empty and truncated plans, and javac accepts every program, which the JVM side then runs.
 */
class G1DecodeTest {

    private static final List<G1.Profile> PROFILES = List.of(G1.Profile.DEFAULT, G1.Profile.REDISCOVER_209,
            G1.Profile.REDISCOVER_280, G1.Profile.REDISCOVER_146, G1.Profile.DEFAULT.with(G1.Gate.LOOP_BREAK_SHAPES));

    private static List<Integer> random(Random random, int maxLength) {
        var values = new ArrayList<Integer>();
        for (int j = random.nextInt(maxLength + 1); j > 0; j--) values.add(random.nextInt(1001));
        return values;
    }

    /** Everything a case is made of, as text, for comparing two decodings. */
    private static String complete(G1.Program program, List<Integer> inputs) {
        return program.source() + "\n" + program.tags() + "\n" + program.features() + "\n" + program.profile().text()
                + "\n" + program.arguments(inputs);
    }

    @Test
    void theCompleteCaseIsAFunctionOfPlanInputsAndProfile() {
        var random = new Random(20261033);
        for (int i = 0; i < 500; i++) {
            var plan = random(random, 200);
            var inputs = random(random, 40);
            for (var profile : PROFILES) {
                // Each prefix of the plan, the empty one included: the shapes jqwik's shrinking visits.
                for (int cut : List.of(0, Math.min(1, plan.size()), plan.size() / 3, plan.size() / 2, plan.size())) {
                    var prefix = plan.subList(0, cut);
                    assertEquals(complete(G1.decode(prefix, profile), inputs),
                            complete(G1.decode(List.copyOf(prefix), profile), List.copyOf(inputs)), profile + " " + prefix);
                }
            }
        }
    }

    @Test
    void emptyPlansAndInputsDecode() {
        var program = G1.decode(List.of(), G1.Profile.DEFAULT);
        assertTrue(program.source().contains("public static BigInteger m(BigInteger a, BigInteger b, JulcList<BigInteger> xs)"),
                program.source());
        assertTrue(program.tags().isEmpty());
        assertEquals(3, program.arguments(List.of()).size());
        assertEquals(G1.Profile.DEFAULT, program.profile());
    }

    /** The profile is part of the case: the same plan under another profile is another, recorded case (G1-I5). */
    @Test
    void theProfileIsPartOfTheCase() {
        var random = new Random(20261034);
        int differ = 0;
        for (int i = 0; i < 200; i++) {
            var plan = random(random, 200);
            if (!G1.decode(plan, G1.Profile.DEFAULT).source().equals(G1.decode(plan, G1.Profile.REDISCOVER_280).source())) differ++;
        }
        assertNotEquals(0, differ);
    }

    /**
     * ADR-063 H6 through the JVM side: javac accepts every program, its tag table passes source validation, its
     * arguments map to the declared parameter types, and the entry method runs to a value or a failure.
     */
    @Test
    void javacAcceptsEveryProgramAndTheJvmRunsIt() {
        var random = new Random(20261035);
        var executor = new JvmExecutor(5_000);
        var cases = new ArrayList<DiffCase>();
        for (int i = 0; i < 400; i++) {
            var plan = random(random, 200);
            if (i % 4 == 1) plan = plan.subList(0, plan.size() / 2);
            if (i % 50 == 0) plan = List.of();
            var profile = PROFILES.get(i % PROFILES.size());
            String className = "G" + i;
            var program = G1.decode(plan, profile, className);
            cases.add(new DiffCase("g1 " + i, program.source(), className, "m", program.arguments(random(random, 40)),
                    program.tags(), profile.text()));
        }
        executor.precompile(cases);
        for (var diffCase : cases) {
            var observation = executor.run(diffCase);
            assertTrue(observation instanceof Observation.Value || observation instanceof Observation.Failure,
                    observation + "\n" + diffCase.source());
        }
    }
}
