package org.julclang.difftest;

import net.jqwik.api.AfterFailureMode;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.RandomDistribution;
import net.jqwik.api.Tag;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.jqwik.api.lifecycle.PropagationMode;
import net.jqwik.api.statistics.Statistics;
import org.julclang.difftest.gen.G1;
import org.julclang.difftest.gen.KnownDivergences;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Positive G1 programs must compile and agree with the JVM at every optimization level (ADR-066 D5, G1-I1). jqwik
 * shrinks the plan and the input sequence together; a failure is written to
 * {@code build/difftest/last-failure.diffcase} with its plan, inputs, profile and tags.
 * <p>
 * The pull-request and nightly campaigns use {@link G1.Profile#DEFAULT}. The rediscovery campaign (D10.1) uses the
 * profile named by {@code -Pdifftest.profile} and is run by {@code ./gradlew :julc-difftest:rediscoveryDiffTest}; it is
 * expected to fail with the gated defect's mismatch.
 */
@AddLifecycleHook(value = CampaignVerifier.RecordSeed.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
class G1PropertyTest {

    /** The share of all tries that must be conclusive and contain each feature (ADR-066 D8). */
    static final double MIN_PERCENT = 3.0;
    static final Set<String> QUOTA_FEATURES = KnownDivergences.names(G1.Feature.QUOTA);

    private static Coverage coverage = new Coverage();
    private static boolean quotasApply;
    private static long started;
    private static int tries;
    private static String firstFailure;

    @Provide
    Arbitrary<List<Integer>> plans() {
        return Arbitraries.integers().between(0, 1000).list().ofMaxSize(200)
                .withSizeDistribution(RandomDistribution.uniform());
    }

    @Provide
    Arbitrary<List<Integer>> inputs() {
        return Arbitraries.integers().between(0, 1000).list().ofMaxSize(40)
                .withSizeDistribution(RandomDistribution.uniform());
    }

    @BeforeProperty
    void startCoverage() {
        coverage = new Coverage();
        quotasApply = false;
        started = System.nanoTime();
        tries = 0;
        firstFailure = null;
    }

    @AfterProperty
    void checkCoverage() {
        System.out.println("G1 coverage\n" + coverage.report());
        if (firstFailure != null) System.out.println(firstFailure);
        if (!quotasApply) return;
        var failures = coverage.quotaFailures(QUOTA_FEATURES, MIN_PERCENT);
        if (!failures.isEmpty()) fail("Coverage quotas not met:\n" + String.join("\n", failures) + "\n" + coverage.report());
    }

    /** The pull-request campaign: a fixed seed and the default profile. */
    @Property(tries = 150, seed = "20261030")
    void generatedProgramsAgree(@ForAll("plans") List<Integer> plan, @ForAll("inputs") List<Integer> inputs) {
        quotasApply = true;
        check("G1PropertyTest", plan, inputs, G1.Profile.DEFAULT);
    }

    /** The scheduled campaign: fresh seeds, 3,000 tries, the default profile. */
    @Property(tries = 3000)
    @Tag("nightly")
    void generatedProgramsAgreeNightly(@ForAll("plans") List<Integer> plan, @ForAll("inputs") List<Integer> inputs) {
        check("G1PropertyTest", plan, inputs, G1.Profile.DEFAULT);
    }

    /**
     * The rediscovery campaign (D10.1): the pull-request seed and bounds under the profile {@code difftest.profile}
     * names, started afresh from the seed each time, so that the time to the first failure is measured anew.
     */
    @Property(tries = 150, seed = "20261030", afterFailure = AfterFailureMode.PREVIOUS_SEED)
    @Tag("rediscovery")
    void generatedProgramsAgreeUnderProfile(@ForAll("plans") List<Integer> plan, @ForAll("inputs") List<Integer> inputs) {
        check("G1PropertyTest rediscovery", plan, inputs, profile(System.getProperty("difftest.profile", "DEFAULT")));
    }

    /** A named profile ({@code DEFAULT}, {@code REDISCOVER_209}, {@code _280}, {@code _146}) or a profile's text. */
    static G1.Profile profile(String name) {
        return switch (name) {
            case "DEFAULT" -> G1.Profile.DEFAULT;
            case "REDISCOVER_209" -> G1.Profile.REDISCOVER_209;
            case "REDISCOVER_280" -> G1.Profile.REDISCOVER_280;
            case "REDISCOVER_146" -> G1.Profile.REDISCOVER_146;
            default -> G1.Profile.parse(name);
        };
    }

    static void check(String finder, List<Integer> plan, List<Integer> inputs, G1.Profile profile) {
        var program = G1.decode(plan, profile);
        tries++;
        CampaignVerifier.Outcome outcome;
        try {
            outcome = verify(finder, plan, inputs, program);
        } catch (AssertionError e) {
            // For the rediscovery record (D10.1): the first failing try, before shrinking, and the time to reach it.
            if (firstFailure == null) {
                firstFailure = "first failure at try " + tries + " after " + (System.nanoTime() - started) / 1_000_000 + " ms";
            }
            throw e;
        }
        Statistics.label("allowed divergence")
                .collect(outcome.explained().isEmpty() ? "none" : String.join(",", outcome.explained()));
        coverage.record(KnownDivergences.names(program.features()), outcome.verdicts());
    }

    /** Runs one decoded program through {@link CampaignVerifier#verify}, with its tags and profile (ADR-066 D5). */
    static CampaignVerifier.Outcome verify(String finder, List<Integer> plan, List<Integer> inputs, G1.Program program) {
        return CampaignVerifier.verify(finder, program.source(), "Probe", "m", program.arguments(inputs), program.tags(),
                KnownDivergences.names(program.features()), G1.VERSION, program.profile().text(), plan, inputs);
    }
}
