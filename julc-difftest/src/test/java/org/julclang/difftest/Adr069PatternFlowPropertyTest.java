package org.julclang.difftest;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Assume;
import net.jqwik.api.Arbitrary;
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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * ADR-069's generated shapes (verification 6, accepted shapes): programs under {@link G1.Profile#PATTERN_FLOW_204}
 * (M1's bare type tests and pattern {@code if}s in loop bodies with accumulators, and M2's pattern conditions over
 * {@code &&}, {@code ||}, {@code !}, {@code ?:} and lambdas, in every position) must compile and <em>agree</em> with
 * the JVM at every optimization level. Unlike {@link G1PropertyTest}, nothing may stand in for agreement: a
 * rejection is a failure whatever its code ({@code JULC0139} and {@code JULC0011} included, and {@code JULC0140}
 * since the generator never assigns a pattern variable), and so is a mismatch the registry would explain. A
 * divergence {@link CampaignVerifier} does not explain is written to {@code build/difftest/last-failure.diffcase}.
 */
@AddLifecycleHook(value = CampaignVerifier.RecordSeed.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
class Adr069PatternFlowPropertyTest {

    /** The share of all tries that must contain each of the gate's constructs. */
    static final double MIN_PERCENT = 3.0;
    static final Set<String> QUOTA_FEATURES = KnownDivergences.names(EnumSet.of(G1.Feature.BARE_TYPE_TEST,
            G1.Feature.LOOP_PATTERN_IF, G1.Feature.LOOP_PATTERN_ELSE_IF, G1.Feature.PATTERN_CONDITION,
            G1.Feature.PATTERN_IN_WHILE_CONDITION, G1.Feature.PATTERN_IN_LAMBDA, G1.Feature.BREAK, G1.Feature.HELPER_CALL));

    /** The largest share of tries that may be skipped for want of an oracle ({@link #noOracle}). */
    static final double MAX_SKIPPED_PERCENT = 2.0;

    private static Coverage coverage = new Coverage();
    private static boolean failed;
    private static int tries;
    private static int skipped;

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
        failed = false;
        tries = 0;
        skipped = 0;
    }

    @AfterProperty
    void checkCoverage() {
        System.out.println("ADR-069 pattern-flow coverage\n" + coverage.report());
        System.out.println("skipped for want of an oracle (javac VerifyError): " + skipped + " of " + tries + " tries");
        if (failed) return;
        // A generator change that makes many programs unverifiable must not pass unnoticed.
        if (skipped * 100.0 > MAX_SKIPPED_PERCENT * tries) {
            fail(skipped + " of " + tries + " tries had no oracle (javac VerifyError), more than " + MAX_SKIPPED_PERCENT + "%");
        }
        var failures = coverage.quotaFailures(QUOTA_FEATURES, MIN_PERCENT);
        if (!failures.isEmpty()) fail("Coverage quotas not met:\n" + String.join("\n", failures) + "\n" + coverage.report());
    }

    @Property(tries = 400, seed = "20261106")
    void generatedProgramsAgree(@ForAll("plans") List<Integer> plan, @ForAll("inputs") List<Integer> inputs) {
        tries++;
        CampaignVerifier.Outcome outcome;
        try {
            outcome = check("Adr069PatternFlowPropertyTest", plan, inputs);
        } catch (AssertionError | RuntimeException e) {
            failed = true;
            throw e;
        }
        if (outcome == null) {
            // No oracle (noOracle): the try is skipped and counted.
            skipped++;
            Assume.that(false);
            return;
        }
        coverage.record(KnownDivergences.names(G1.decode(plan, G1.Profile.PATTERN_FLOW_204).features()), outcome.verdicts());
    }

    /**
     * Decodes a plan under {@link G1.Profile#PATTERN_FLOW_204}, runs it through {@link CampaignVerifier#verify} and
     * requires every configuration to agree, explained divergences and policy rejections included. Returns null, and
     * checks nothing, for a program that has no oracle ({@link #noOracle}).
     */
    static CampaignVerifier.Outcome check(String finder, List<Integer> plan, List<Integer> inputs) {
        var program = G1.decode(plan, G1.Profile.PATTERN_FLOW_204);
        CampaignVerifier.Outcome outcome;
        try {
            outcome = G1PropertyTest.verify(finder, plan, inputs, program);
        } catch (HarnessException e) {
            if (noOracle(e)) return null;
            throw e;
        }
        Map<DiffConfig, Verdict.Kind> disagreeing = outcome.verdicts().entrySet().stream()
                .filter(e -> e.getValue() != Verdict.Kind.AGREE)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        if (!disagreeing.isEmpty()) {
            fail("Under " + program.profile() + " every configuration must agree, but " + disagreeing + " (explained as "
                    + outcome.explained() + ")\nplan " + plan + "\ninputs " + inputs + "\n\n" + program.source());
        }
        return outcome;
    }

    /**
     * Whether the JVM side has no oracle: the JVM rejects the class javac compiled from the program with a
     * {@code VerifyError} ({@code JvmExecutor}). javac 25.0.2 emits inconsistent stackmap frames for an {@code if} whose
     * condition introduces two pattern variables (joined by {@code &&}, {@code ||} or {@code ?:}) followed by a pattern
     * {@code switch} whose arm copies its binding and loops over a pattern {@code if} (ADR-069 R4). Such a program is
     * skipped, in these two ADR-069 targets only; any other harness fault still fails.
     */
    static boolean noOracle(HarnessException e) {
        return e.getCause() instanceof VerifyError && e.getMessage() != null
                && e.getMessage().contains("the JVM rejects javac's class");
    }
}
