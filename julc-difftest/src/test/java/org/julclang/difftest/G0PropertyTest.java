package org.julclang.difftest;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.RandomDistribution;
import net.jqwik.api.Tag;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.AroundPropertyHook;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.jqwik.api.lifecycle.PropagationMode;
import net.jqwik.api.lifecycle.PropertyExecutionResult;
import net.jqwik.api.lifecycle.PropertyExecutor;
import net.jqwik.api.lifecycle.PropertyLifecycleContext;
import net.jqwik.api.statistics.Statistics;
import org.julclang.core.PlutusData;
import org.julclang.difftest.gen.G0;
import org.julclang.difftest.gen.KnownDivergences;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Positive G0 programs must compile and agree with the JVM at every optimization level
 * (ADR-063 D5, H7). jqwik shrinks the choice sequence, so a failure is reported as a small
 * program. The last failing case is also written to {@code build/difftest/last-failure.diffcase},
 * with its provenance and both observations (D8), so that it can be saved as a regression.
 */
@AddLifecycleHook(value = G0PropertyTest.RecordSeed.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
class G0PropertyTest {

    private static final DiffRunner RUNNER = DiffRunner.standard();
    /** The rules, and the saved cases that pin known wrong values (D6). */
    static final KnownDivergences KNOWN = KnownDivergences.withPins(RegressionStore.load(RegressionCasesTest.CASES));
    static final Path LAST_FAILURE = Path.of("build/difftest/last-failure.diffcase");

    /** The share of tries that must be conclusive and exercise each G0 feature (D9). */
    static final double MIN_PERCENT = 3.0;

    /** Every feature but the registry markers, which are known bugs' shapes. */
    static final Set<String> QUOTA_FEATURES = EnumSet.complementOf(EnumSet.copyOf(G0.Feature.REGISTRY_MARKERS))
            .stream().map(Enum::name).collect(Collectors.toCollection(TreeSet::new));

    private static Coverage coverage = new Coverage();
    private static boolean quotasApply;
    private static boolean failureWritten;

    @Provide
    Arbitrary<List<Integer>> plans() {
        // Uniform lengths: jqwik favours short lists, which decode to programs without loops.
        return Arbitraries.integers().between(0, 1000).list().ofMaxSize(150)
                .withSizeDistribution(RandomDistribution.uniform());
    }

    @Provide
    Arbitrary<BigInteger> small() {
        return Arbitraries.integers().between(-6, 6).map(BigInteger::valueOf);
    }

    @Provide
    Arbitrary<List<BigInteger>> lists() {
        return small().list().ofMaxSize(4);
    }

    @BeforeProperty
    void startCoverage() {
        coverage = new Coverage();
        quotasApply = false;
    }

    /**
     * Quotas count conclusive cases only (H5): an inconclusive or allowed-divergence case, such as a
     * T21 budget exhaustion, satisfies no quota, whatever features its program has.
     */
    @AfterProperty
    void checkCoverage() {
        System.out.println("G0 coverage\n" + coverage.report());
        if (!quotasApply) return;
        var failures = coverage.quotaFailures(QUOTA_FEATURES, MIN_PERCENT);
        if (!failures.isEmpty()) fail("Coverage quotas not met:\n" + String.join("\n", failures) + "\n" + coverage.report());
    }

    /** The pull-request campaign: a fixed seed, so a run is reproducible (ADR-063 D12). */
    @Property(tries = 150, seed = "20261023")
    void generatedProgramsAgree(@ForAll("plans") List<Integer> plan, @ForAll("small") BigInteger a,
                                @ForAll("small") BigInteger b, @ForAll("lists") List<BigInteger> xs) {
        quotasApply = true;
        check(plan, a, b, xs);
    }

    /**
     * The scheduled campaign: fresh seeds and many more tries (ADR-063 D12). Excluded from
     * {@code test}; run with {@code ./gradlew :julc-difftest:nightlyDiffTest}.
     */
    @Property(tries = 3000)
    @Tag("nightly")
    void generatedProgramsAgreeNightly(@ForAll("plans") List<Integer> plan, @ForAll("small") BigInteger a,
                                       @ForAll("small") BigInteger b, @ForAll("lists") List<BigInteger> xs) {
        check(plan, a, b, xs);
    }

    static void check(List<Integer> plan, BigInteger a, BigInteger b, List<BigInteger> xs) {
        var program = G0.decode(plan);
        Statistics.label("xs size").collect(xs.size() < 2 ? String.valueOf(xs.size()) : "2+");
        var outcome = verify("G0PropertyTest", plan, program, a, b, xs);
        Statistics.label("allowed divergence")
                .collect(outcome.explained().isEmpty() ? "none" : String.join(",", outcome.explained()));
        coverage.record(program.features().stream().map(Enum::name).collect(Collectors.toSet()), outcome.verdicts());
    }

    /**
     * What {@link #verify} found: the catalogue IDs that explained a mismatch, each configuration's verdict, and
     * the confirmation reruns the registry asked for (D6, r6).
     */
    record Outcome(Set<String> explained, Map<DiffConfig, Verdict.Kind> verdicts, List<Confirmation> confirmations) {
    }

    /**
     * One confirmation rerun: the configuration, the budget factor, what it produced, whether that agrees with the
     * JVM (value and traces), and its time.
     */
    record Confirmation(DiffConfig config, long factor, UplcExecutor.Execution execution, boolean agrees, long millis) {

        String observation() {
            return ObservationText.format(execution.observation());
        }

        String cost() {
            var budget = UplcExecutor.budgetTimes(factor);
            return "factor " + factor + " budget cpu " + budget.cpuSteps() + " mem " + budget.memoryUnits()
                    + (execution.measurement() == null ? "" : " used " + execution.measurement()) + " in " + millis + " ms";
        }

        @Override
        public String toString() {
            return config + " rerun x" + factor + ": " + observation() + (agrees ? ", agrees" : ", disagrees")
                    + " (" + cost() + ")";
        }
    }

    /**
     * Runs one decoded program, shared by the jqwik campaigns and {@link G0FuzzTest} (D13). It fails, after
     * writing the case to {@link #LAST_FAILURE} with {@code finder} as its provenance, unless every configuration
     * agrees or the registry explains each one that disagrees.
     */
    static Outcome verify(String finder, List<Integer> plan, G0.Program program, BigInteger a, BigInteger b,
                          List<BigInteger> xs) {
        var items = new ArrayList<PlutusData>();
        for (BigInteger x : xs) items.add(new PlutusData.IntData(x));
        var diffCase = new DiffCase("g0 " + plan, program.source(), "Probe", "m",
                List.of(new PlutusData.IntData(a), new PlutusData.IntData(b), new PlutusData.ListData(items)));
        var report = RUNNER.run(diffCase);
        // A mismatch passes only if the registry explains every configuration that disagrees: by an exact
        // pin, or by a rule for an inconclusive UPLC side (D6).
        var explained = new TreeSet<String>();
        boolean unexplained = false;
        var verdicts = new LinkedHashMap<DiffConfig, Verdict.Kind>();
        var confirmations = new ArrayList<Confirmation>();
        for (var entry : report.results().entrySet()) {
            var config = entry.getKey();
            var result = entry.getValue();
            verdicts.put(config, result.verdict().kind());
            if (result.verdict().kind() == Verdict.Kind.AGREE) continue;
            KnownDivergences.Rerun rerun = factor -> {
                long start = System.nanoTime();
                var execution = RUNNER.rerun(diffCase, config, factor);
                boolean agrees = Verdict.compare(result.jvm(), execution.observation()).kind() == Verdict.Kind.AGREE;
                confirmations.add(new Confirmation(config, factor, execution, agrees, (System.nanoTime() - start) / 1_000_000));
                return execution;
            };
            var known = KNOWN.explain(diffCase, config, program.features(), result, rerun);
            if (known.isPresent()) explained.add(known.get());
            else unexplained = true;
        }
        if (unexplained) {
            RegressionStore.write(LAST_FAILURE, diffCase, RegressionStore.Expectation.AGREE, null,
                    provenance(finder, plan), report);
            for (var confirmation : confirmations) {
                RegressionStore.addRecorded(LAST_FAILURE, confirmation.config() + ".rerun", confirmation.observation());
                RegressionStore.addRecorded(LAST_FAILURE, confirmation.config() + ".rerun.cost", confirmation.cost());
            }
            failureWritten = true;
            fail(report.describe() + "\n\n" + confirmations.stream().map(Confirmation::toString)
                    .collect(Collectors.joining("\n")) + "\n\n" + program.source());
        }
        return new Outcome(explained, verdicts, List.copyOf(confirmations));
    }

    /** Where a generated case came from (D8); the seed is added when jqwik reports it. */
    static Map<String, String> provenance(String finder, List<Integer> plan) {
        var provenance = new LinkedHashMap<String, String>();
        provenance.put("by", finder);
        provenance.put("generator", G0.VERSION);
        provenance.put("plan", plan.stream().map(String::valueOf).collect(Collectors.joining(",")));
        provenance.put("compiler", System.getProperty("difftest.compiler", "unknown"));
        return provenance;
    }

    /** Adds the seed of a failed property to the failure record it wrote. */
    public static final class RecordSeed implements AroundPropertyHook {
        @Override
        public PropertyExecutionResult aroundProperty(PropertyLifecycleContext context, PropertyExecutor property) {
            failureWritten = false;
            PropertyExecutionResult result = property.execute();
            if (failureWritten && result.status() == PropertyExecutionResult.Status.FAILED && Files.exists(LAST_FAILURE)) {
                RegressionStore.addProvenance(LAST_FAILURE, "seed", result.seed().orElse("unknown"));
            }
            return result;
        }
    }
}
