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

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * ADR-069 verification 6, javac-filtered mode: programs under {@link G1.Profile#JAVAC_FILTERED_204} also read pattern
 * variables outside G1's own model of JLS 6.3 ({@code PATTERN_SCOPE_PROBES}: after their statement, in the
 * {@code else} of {@code !(x instanceof V v) || true}). javac decides which are Java: a program javac rejects is skipped
 * and counted. The property is that no program <em>diverges</em>: under every configuration JuLC agrees with the JVM
 * or rejects the program with a coded diagnostic ({@code JULC0139} included). A divergence is written to
 * {@code build/difftest/last-failure.diffcase}.
 */
@AddLifecycleHook(value = CampaignVerifier.RecordSeed.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
class Adr069JavacFilteredPropertyTest {

    /** What one program came to. */
    enum Outcome { AGREED, REJECTED_CODED, JAVAC_REJECTED, NO_ORACLE }

    private static final Pattern CODE = Pattern.compile("JULC\\d{4}");
    /** The smallest share of tries javac must accept, so that the mode is not vacuous. */
    static final double MIN_COMPILED_PERCENT = 25.0;

    private static final Map<Outcome, Integer> outcomes = new TreeMap<>();
    private static final Map<String, Integer> codes = new TreeMap<>();
    private static int tries;
    private static boolean failed;

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
        outcomes.clear();
        codes.clear();
        tries = 0;
        failed = false;
    }

    @AfterProperty
    void report() {
        System.out.println("ADR-069 javac-filtered outcomes " + outcomes + " of " + tries + " tries; JuLC codes " + codes);
        if (failed) return;
        int compiled = tries - outcomes.getOrDefault(Outcome.JAVAC_REJECTED, 0);
        if (compiled * 100.0 < MIN_COMPILED_PERCENT * tries) {
            fail("javac accepted only " + compiled + " of " + tries + " tries: " + outcomes);
        }
        if (outcomes.getOrDefault(Outcome.NO_ORACLE, 0) * 100.0 > Adr069PatternFlowPropertyTest.MAX_SKIPPED_PERCENT * tries) {
            fail("too many tries without an oracle (javac VerifyError): " + outcomes);
        }
    }

    @Property(tries = 300, seed = "20261107")
    void noProgramDiverges(@ForAll("plans") List<Integer> plan, @ForAll("inputs") List<Integer> inputs) {
        tries++;
        Outcome outcome;
        try {
            outcome = check("Adr069JavacFilteredPropertyTest", plan, inputs);
        } catch (AssertionError | RuntimeException e) {
            failed = true;
            throw e;
        }
        outcomes.merge(outcome, 1, Integer::sum);
        Assume.that(outcome == Outcome.AGREED || outcome == Outcome.REJECTED_CODED);
    }

    /**
     * Runs one plan under {@link G1.Profile#JAVAC_FILTERED_204}: skipped when javac rejects it or the JVM rejects javac's
     * class ({@link Adr069PatternFlowPropertyTest#noOracle}); otherwise every configuration must agree or reject with a
     * {@code JULCnnnn} code, or the case is recorded and the check fails.
     */
    static Outcome check(String finder, List<Integer> plan, List<Integer> inputs) {
        var program = G1.decode(plan, G1.Profile.JAVAC_FILTERED_204);
        var diffCase = new DiffCase("g1 " + plan, program.source(), "Probe", "m", program.arguments(inputs), program.tags(),
                program.profile().text());
        DiffRunner.Report report;
        try {
            report = CampaignVerifier.RUNNER.run(diffCase);
        } catch (HarnessException e) {
            if (e.getMessage() != null && e.getMessage().contains(": javac rejected the source")) return Outcome.JAVAC_REJECTED;
            if (Adr069PatternFlowPropertyTest.noOracle(e)) return Outcome.NO_ORACLE;
            throw e;
        }
        boolean rejected = false;
        for (var result : report.results().values()) {
            if (result.verdict().kind() == Verdict.Kind.AGREE) continue;
            if (result.uplc() instanceof Observation.Rejected r && r.code() != null && CODE.matcher(r.code()).matches()) {
                codes.merge(r.code(), 1, Integer::sum);
                rejected = true;
                continue;
            }
            RegressionStore.write(CampaignVerifier.LAST_FAILURE, diffCase, RegressionStore.Expectation.AGREE, null,
                    CampaignVerifier.provenance(finder, G1.VERSION, plan, inputs), report);
            fail("A javac-accepted program diverges:\n" + report.describe() + "\n\n" + program.source());
        }
        return rejected ? Outcome.REJECTED_CODED : Outcome.AGREED;
    }
}
