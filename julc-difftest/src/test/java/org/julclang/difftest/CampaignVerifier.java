package org.julclang.difftest;

import net.jqwik.api.lifecycle.AroundPropertyHook;
import net.jqwik.api.lifecycle.PropertyExecutionResult;
import net.jqwik.api.lifecycle.PropertyExecutor;
import net.jqwik.api.lifecycle.PropertyLifecycleContext;
import org.julclang.core.PlutusData;
import org.julclang.difftest.gen.KnownDivergences;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks one generated case, for any generator (ADR-066 D5): the jqwik campaigns and the Jazzer targets of
 * each generator run their decoded programs through {@link #verify}. A mismatch passes only if the registry
 * explains every configuration that disagrees; otherwise the case is written to {@link #LAST_FAILURE} with its
 * provenance and both observations (ADR-063 D8), and the check fails.
 */
final class CampaignVerifier {

    static final DiffRunner RUNNER = DiffRunner.standard();
    /** The rules, and the saved cases that pin known wrong values (ADR-063 D6). */
    static final KnownDivergences KNOWN = KnownDivergences.withPins(RegressionStore.load(RegressionCasesTest.CASES));
    static final Path LAST_FAILURE = Path.of("build/difftest/last-failure.diffcase");

    private static boolean failureWritten;

    private CampaignVerifier() {
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

        /** The budget and what the rerun used: deterministic, so it goes into the D8 record (H4). */
        String cost() {
            var budget = UplcExecutor.budgetTimes(factor);
            return "factor " + factor + " budget cpu " + budget.cpuSteps() + " mem " + budget.memoryUnits()
                    + (execution.measurement() == null ? "" : " used " + execution.measurement());
        }

        /** The line reported with an input, with the rerun's wall-clock time, which no record keeps. */
        @Override
        public String toString() {
            return config + " rerun x" + factor + ": " + observation() + (agrees ? ", agrees" : ", disagrees")
                    + " (" + cost() + " in " + millis + " ms)";
        }
    }

    /**
     * Runs one generated case. {@code finder} names the test for the provenance; {@code generator} is the
     * generator's version, such as {@code G0 r2}, whose first word, in lower case, starts the case's name;
     * {@code tags} and {@code profile} are carried into the case and its record (ADR-066 D3, D6). It fails, after
     * writing the case to {@link #LAST_FAILURE}, unless every configuration agrees or the registry explains each
     * one that disagrees.
     */
    static Outcome verify(String finder, String source, String className, String method, List<PlutusData> args,
                          TagTable tags, Set<String> features, String generator, String profile, List<Integer> plan) {
        return verify(finder, source, className, method, args, tags, features, generator, profile, plan, null);
    }

    /**
     * As {@link #verify(String, String, String, String, List, TagTable, Set, String, String, List)}, for a generator
     * whose arguments are decoded from a second choice sequence, {@code inputs}, saved as {@code found.inputs}
     * (ADR-066 D4).
     */
    static Outcome verify(String finder, String source, String className, String method, List<PlutusData> args,
                          TagTable tags, Set<String> features, String generator, String profile, List<Integer> plan,
                          List<Integer> inputs) {
        String name = generator.split(" ")[0].toLowerCase(Locale.ROOT) + " " + plan;
        var diffCase = new DiffCase(name, source, className, method, args, tags, profile);
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
            var known = KNOWN.explain(diffCase, config, features, result, rerun);
            if (known.isPresent()) explained.add(known.get());
            else unexplained = true;
        }
        if (unexplained) {
            RegressionStore.write(LAST_FAILURE, diffCase, RegressionStore.Expectation.AGREE, null,
                    provenance(finder, generator, plan, inputs), report);
            for (var confirmation : confirmations) {
                // One pair of lines per rung, for example recorded.Java/NONE.rerun.x1000 (D6, r6).
                String key = confirmation.config() + ".rerun.x" + confirmation.factor();
                RegressionStore.addRecorded(LAST_FAILURE, key, confirmation.observation());
                RegressionStore.addRecorded(LAST_FAILURE, key + ".cost", confirmation.cost());
            }
            failureWritten = true;
            fail(report.describe() + "\n\n" + confirmations.stream().map(Confirmation::toString)
                    .collect(Collectors.joining("\n")) + "\n\n" + source);
        }
        return new Outcome(explained, verdicts, List.copyOf(confirmations));
    }

    /** Where a generated case came from (D8); the seed is added when jqwik reports it. */
    static Map<String, String> provenance(String finder, String generator, List<Integer> plan, List<Integer> inputs) {
        var provenance = new LinkedHashMap<String, String>();
        provenance.put("by", finder);
        provenance.put("generator", generator);
        provenance.put("plan", plan.stream().map(String::valueOf).collect(Collectors.joining(",")));
        if (inputs != null) provenance.put("inputs", inputs.stream().map(String::valueOf).collect(Collectors.joining(",")));
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
