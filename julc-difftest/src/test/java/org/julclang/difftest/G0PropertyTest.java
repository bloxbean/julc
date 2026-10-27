package org.julclang.difftest;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.RandomDistribution;
import net.jqwik.api.Tag;
import net.jqwik.api.statistics.Statistics;
import org.julclang.core.PlutusData;
import org.julclang.difftest.gen.G0;
import org.julclang.difftest.gen.KnownDivergences;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Positive G0 programs must compile and agree with the JVM at every optimization level
 * (ADR-063 D5, H7). jqwik shrinks the choice sequence, so a failure is reported as a small
 * program. The last failing case is also written to {@code build/difftest/last-failure.diffcase}
 * so that it can be saved as a regression.
 */
class G0PropertyTest {

    private static final DiffRunner RUNNER = DiffRunner.standard();
    private static final Path LAST_FAILURE = Path.of("build/difftest/last-failure.diffcase");

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

    /** The pull-request campaign: a fixed seed, so a run is reproducible (ADR-063 D12). */
    @Property(tries = 150, seed = "20261023")
    void generatedProgramsAgree(@ForAll("plans") List<Integer> plan, @ForAll("small") BigInteger a,
                                @ForAll("small") BigInteger b, @ForAll("lists") List<BigInteger> xs) {
        check(plan, a, b, xs);
        // Coverage quotas (ADR-063 D9): each interaction must appear in enough conclusive cases.
        for (var feature : G0.Feature.values()) {
            // These mark known bugs' shapes for the registry; they have no quota.
            if (feature == G0.Feature.WHILE_BREAK_WITH_IF_UPDATE || feature == G0.Feature.DEEP_COMPARISON) continue;
            Statistics.label(feature.name()).coverage(c -> c.check(true).percentage(p -> p >= MIN_PERCENT));
        }
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

    /** The share of tries that must exercise each G0 feature. */
    private static final double MIN_PERCENT = 3.0;

    static void check(List<Integer> plan, BigInteger a, BigInteger b, List<BigInteger> xs) {
        var program = G0.decode(plan);
        for (var feature : G0.Feature.values()) {
            Statistics.label(feature.name()).collect(program.features().contains(feature));
        }
        Statistics.label("xs size").collect(xs.size() < 2 ? String.valueOf(xs.size()) : "2+");
        var explained = verify("G0PropertyTest", plan, program, a, b, xs);
        Statistics.label("allowed divergence").collect(explained.isEmpty() ? "none" : String.join(",", explained));
    }

    /**
     * Runs one decoded program and fails unless it agrees everywhere or one registry entry explains
     * each configuration that disagrees (D6). Returns the IDs of the entries that matched.
     */
    static Set<String> verify(String source, List<Integer> plan, G0.Program program, BigInteger a, BigInteger b,
                              List<BigInteger> xs) {
        var items = new ArrayList<PlutusData>();
        for (BigInteger x : xs) items.add(new PlutusData.IntData(x));
        var diffCase = new DiffCase("g0 " + plan, program.source(), "Probe", "m",
                List.of(new PlutusData.IntData(a), new PlutusData.IntData(b), new PlutusData.ListData(items)));
        var report = RUNNER.run(diffCase);
        // A mismatch passes only if one registry entry explains every configuration that disagrees (D6).
        var explained = new TreeSet<String>();
        boolean unexplained = false;
        for (var result : report.results().values()) {
            if (result.verdict().kind() == Verdict.Kind.AGREE) continue;
            var entry = KnownDivergences.explain(program.features(), result.jvm(), result.uplc());
            if (entry.isPresent()) explained.add(entry.get().id());
            else unexplained = true;
        }
        if (unexplained) {
            RegressionStore.write(LAST_FAILURE, diffCase, new RegressionStore.Expectation(Verdict.Kind.AGREE, null, null, null),
                    "Generated by " + source + " from plan " + plan);
            fail(report.describe() + "\n\n" + program.source());
        }
        return explained;
    }
}
