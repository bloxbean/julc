package org.julclang.difftest;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.RandomDistribution;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.jqwik.api.lifecycle.PropagationMode;
import org.julclang.difftest.gen.G1;
import org.julclang.difftest.gen.KnownDivergences;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * ADR-070 verification 5: programs under {@link G1.Profile#DOWNCASTS_309} ({@code V v = (V) s;} in method bodies, loop
 * bodies and switch-arm blocks, read or never read, steered to the wrong variant) must agree with the JVM at every level,
 * as {@link Adr069PatternFlowPropertyTest#check} requires: nothing explained, no rejection. A cast to the wrong variant
 * is a JVM {@code ClassCastException}, which the harness observes as a failure; it must meet a UPLC failure with the same
 * traces. The property counts the cases whose JVM run ends in a {@code ClassCastException}, and requires both outcomes.
 */
@AddLifecycleHook(value = CampaignVerifier.RecordSeed.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
class Adr070DowncastsPropertyTest {

    static final double MIN_PERCENT = 3.0;
    static final Set<String> QUOTA_FEATURES = KnownDivergences.names(EnumSet.of(G1.Feature.DOWNCAST,
            G1.Feature.DOWNCAST_UNUSED));
    private static final JvmExecutor JVM = new JvmExecutor(10_000);

    private static Coverage coverage = new Coverage();
    private static boolean failed;
    private static int tries;
    private static int skipped;
    private static int classCasts;

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
    void start() {
        coverage = new Coverage();
        failed = false;
        tries = 0;
        skipped = 0;
        classCasts = 0;
    }

    @AfterProperty
    void checkCoverage() {
        System.out.println("ADR-070 downcast coverage\n" + coverage.report());
        System.out.println("JVM ClassCastException in " + classCasts + " of " + tries + " tries; skipped for want of an "
                + "oracle: " + skipped);
        if (failed) return;
        var failures = coverage.quotaFailures(QUOTA_FEATURES, MIN_PERCENT);
        if (!failures.isEmpty()) fail("Coverage quotas not met:\n" + String.join("\n", failures) + "\n" + coverage.report());
        // Both outcomes of a cast: a share of the cases fail on the wrong variant, and most do not.
        if (classCasts * 100.0 < 5.0 * tries || classCasts * 100.0 > 80.0 * tries) {
            fail("ClassCastException in " + classCasts + " of " + tries + " tries: the steer no longer gives both outcomes");
        }
        if (skipped * 100.0 > Adr069PatternFlowPropertyTest.MAX_SKIPPED_PERCENT * tries) {
            fail(skipped + " of " + tries + " tries had no oracle");
        }
    }

    @Property(tries = 300, seed = "20261108")
    void generatedProgramsAgree(@ForAll("plans") List<Integer> plan, @ForAll("inputs") List<Integer> inputs) {
        tries++;
        CampaignVerifier.Outcome outcome;
        try {
            outcome = check("Adr070DowncastsPropertyTest", plan, inputs);
        } catch (AssertionError | RuntimeException e) {
            failed = true;
            throw e;
        }
        if (outcome == null) {
            skipped++;
            Assume.that(false);
            return;
        }
        var program = G1.decode(plan, G1.Profile.DOWNCASTS_309);
        coverage.record(KnownDivergences.names(program.features()), outcome.verdicts());
        var observed = JVM.run(new DiffCase("g1 " + plan, program.source(), "Probe", "m", program.arguments(inputs),
                program.tags(), program.profile().text()));
        if (observed instanceof Observation.Failure failure && failure.detail().contains("ClassCastException")) classCasts++;
    }

    /** {@link Adr069PatternFlowPropertyTest#check} under {@link G1.Profile#DOWNCASTS_309}: every verdict AGREE. */
    static CampaignVerifier.Outcome check(String finder, List<Integer> plan, List<Integer> inputs) {
        return Adr069PatternFlowPropertyTest.check(finder, plan, inputs, G1.Profile.DOWNCASTS_309);
    }
}
