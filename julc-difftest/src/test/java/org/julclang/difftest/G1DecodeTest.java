package org.julclang.difftest;

import org.julclang.difftest.gen.G1;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * G1's decoding (ADR-066 D1, G1-I5, ADR-063 H6): the complete case is a function of the plan, the inputs and the
 * profile, also for empty and truncated plans, and javac accepts every program, which the JVM side then runs.
 */
class G1DecodeTest {

    private static final List<G1.Profile> PROFILES = List.of(G1.Profile.DEFAULT, G1.Profile.REDISCOVER_209,
            G1.Profile.REDISCOVER_280, G1.Profile.REDISCOVER_146, G1.Profile.REDISCOVER_162, G1.Profile.REDISCOVER_291,
            G1.Profile.REDISCOVER_291_LOOPS,
            G1.Profile.DEFAULT.with(G1.Gate.LOOP_BREAK_SHAPES));

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

    private static List<Integer> numbers(String text) {
        return text == null || text.isBlank() ? List.of() : Arrays.stream(text.split(",")).map(Integer::valueOf).toList();
    }

    /**
     * The profile and the inputs are part of the recorded case (G1-I5; review of M2, F73). A case that fails under a
     * historical profile, with inputs, goes through the real failure-record path ({@link G1PropertyTest#verify},
     * {@link CampaignVerifier}, {@link RegressionStore}); its record names the profile, the plan and the inputs, and
     * regenerating from them gives back its source, tags and arguments.
     */
    @Test
    void aFailureRecordRegeneratesItsCase() {
        var random = new Random(20261038);
        var inputs = List.of(7, 11, 3, 5, 9, 2);
        for (int i = 0; i < 2000; i++) {
            var plan = random(random, 200);
            var program = G1.decode(plan, G1.Profile.REDISCOVER_280);
            if (!program.features().contains(G1.Feature.ZERO_ARG_HELPER)) continue;
            try {
                G1PropertyTest.verify("G1DecodeTest", plan, inputs, program);
                continue;
            } catch (AssertionError expected) {
                // The case diverges (#280) and is written to the failure record.
            }
            var saved = RegressionStore.read(CampaignVerifier.LAST_FAILURE);
            try {
                assertEquals(G1.Profile.REDISCOVER_280.text(), saved.diffCase().profile());
                var profile = G1.Profile.parse(saved.diffCase().profile());
                var again = G1.decode(numbers(saved.provenance().get("plan")), profile);
                assertEquals(saved.diffCase().source(), again.source());
                assertEquals(saved.diffCase().tags(), again.tags());
                assertEquals(saved.diffCase().args(), again.arguments(numbers(saved.provenance().get("inputs"))));
                assertEquals(program.arguments(inputs), saved.diffCase().args());
                assertEquals(G1.VERSION, saved.provenance().get("generator"));
                return;
            } finally {
                // The record is this test's, not a finding: do not leave it for triage.
                CampaignVerifier.LAST_FAILURE.toFile().delete();
            }
        }
        fail("no #280 case found to record");
    }

    /**
     * An inert switch changes no program, but a case under it is still another case: its record keeps its profile
     * (G1-I5, F73).
     */
    @Test
    void equalSourcesUnderDifferentProfilesAreDifferentCases(@TempDir Path dir) {
        var random = new Random(20261034);
        var plan = random(random, 200);
        var inputs = random(random, 40);
        var plain = G1.decode(plan, G1.Profile.DEFAULT);
        var guarded = G1.decode(plan, G1.Profile.DEFAULT.with(G1.Gate.GUARDS));
        assertEquals(plain.source(), guarded.source());
        var cases = new ArrayList<DiffCase>();
        for (var program : List.of(plain, guarded)) {
            var diffCase = new DiffCase("g1", program.source(), "Probe", "m", program.arguments(inputs), program.tags(),
                    program.profile().text());
            Path file = dir.resolve(cases.size() + ".diffcase");
            RegressionStore.write(file, diffCase, RegressionStore.Expectation.AGREE, null, Map.of(), null);
            cases.add(RegressionStore.read(file).diffCase());
        }
        assertNotEquals(cases.get(0), cases.get(1));
        assertEquals(G1.Profile.DEFAULT.with(G1.Gate.GUARDS).text(), cases.get(1).profile());
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
