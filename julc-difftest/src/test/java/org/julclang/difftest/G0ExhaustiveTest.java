package org.julclang.difftest;

import org.julclang.core.PlutusData;
import org.julclang.difftest.gen.G0;
import org.julclang.difftest.gen.G0Enumeration;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every program of the bounded grammar {@link G0Enumeration}, run on fixed inputs (ADR-063 D7). It
 * complements random generation, which can miss small combinations. The corpus is checked for what it
 * exercises, not only for its size: removing all loops, or any one statement, changes the JVM result
 * on some input, so a lowering that drops a loop or a statement cannot pass. Every failure is written
 * to {@code build/difftest/exhaustive/}.
 */
class G0ExhaustiveTest {

    /**
     * {@code a}, {@code b} and {@code xs}. Under the break condition {@code X > b}, the three-iteration
     * while never breaks, breaks at its second iteration or at its first; a for-each runs over no element,
     * breaks at its second element, never breaks, or breaks at its second of four. The last three were
     * chosen by a greedy search over 210 candidates as the fewest inputs under which removing any loop or
     * statement changes some result; the first adds zero iterations.
     */
    static final List<List<PlutusData>> INPUTS = List.of(
            inputs(0, 3),
            inputs(-2, 1, -1, 2, 4),
            inputs(2, 1, 1),
            inputs(-2, -3, -4, 3, 1, 2));

    /** 8 bodies of one statement and 80 of two, then the shapes of {@link G0Enumeration#all}. */
    static final int PROGRAMS = 8 + 88 + 88 + 3 * 64;

    /** The fewest programs that must have each G0 feature. */
    static final int MIN_PER_FEATURE = 20;

    private static List<PlutusData> inputs(long a, long b, long... xs) {
        var items = new ArrayList<PlutusData>();
        for (long x : xs) items.add(new PlutusData.IntData(BigInteger.valueOf(x)));
        return List.of(new PlutusData.IntData(BigInteger.valueOf(a)), new PlutusData.IntData(BigInteger.valueOf(b)),
                new PlutusData.ListData(items));
    }

    private static String className(int program) {
        return "E" + program;
    }

    @Test
    void theCorpusHasItsBoundAndItsFeatures() {
        var programs = G0Enumeration.all();
        assertEquals(PROGRAMS, programs.size());
        var sources = new HashSet<String>();
        programs.forEach(p -> sources.add(p.source("E")));
        assertEquals(PROGRAMS, sources.size(), "every program is distinct");
        var counts = new EnumMap<G0.Feature, Integer>(G0.Feature.class);
        programs.forEach(p -> p.features().forEach(f -> counts.merge(f, 1, Integer::sum)));
        for (var feature : G0.Feature.values()) {
            if (G0.Feature.REGISTRY_MARKERS.contains(feature)) continue;
            assertTrue(counts.getOrDefault(feature, 0) >= MIN_PER_FEATURE, feature + ": " + counts);
        }
    }

    /** A loop, or a statement, that no input can observe would let a lowering drop it unnoticed. */
    @Test
    void everyLoopAndEveryStatementChangesTheResult() {
        var programs = G0Enumeration.all();
        var executor = new JvmExecutor(5_000);
        var variants = new LinkedHashMap<String, List<String>>();
        var cases = new ArrayList<DiffCase>();
        for (int n = 0; n < programs.size(); n++) {
            var program = programs.get(n);
            var erasures = new ArrayList<G0Enumeration.Erasure>();
            erasures.add(new G0Enumeration.Erasure.None());
            erasures.add(new G0Enumeration.Erasure.Loops());
            for (int i = 0; i < program.first().size(); i++) erasures.add(new G0Enumeration.Erasure.OneStatement(false, i));
            for (int i = 0; i < program.second().size(); i++) erasures.add(new G0Enumeration.Erasure.OneStatement(true, i));
            var names = new ArrayList<String>();
            for (int k = 0; k < erasures.size(); k++) {
                String name = className(n) + "_" + k;
                cases.add(new DiffCase(program + " without " + erasures.get(k), program.source(name, erasures.get(k)),
                        name, "m", INPUTS.getFirst()));
                names.add(name);
            }
            variants.put(program.toString(), names);
        }
        executor.precompile(cases);
        var byName = new LinkedHashMap<String, DiffCase>();
        cases.forEach(c -> byName.put(c.className(), c));
        var unobserved = new ArrayList<String>();
        variants.forEach((program, names) -> {
            List<Observation> original = observe(executor, byName.get(names.getFirst()));
            for (String name : names.subList(1, names.size())) {
                if (observe(executor, byName.get(name)).equals(original)) unobserved.add(byName.get(name).name());
            }
        });
        if (!unobserved.isEmpty()) fail(unobserved.size() + " erasures change no result:\n" + String.join("\n", unobserved));
    }

    private static List<Observation> observe(JvmExecutor executor, DiffCase diffCase) {
        var observations = new ArrayList<Observation>();
        for (var args : INPUTS) {
            observations.add(executor.run(new DiffCase(diffCase.name(), diffCase.source(), diffCase.className(), "m", args)));
        }
        return observations;
    }

    @Test
    void everyProgramAgrees() {
        var programs = G0Enumeration.all();
        var runner = DiffRunner.standard();
        var cases = new ArrayList<DiffCase>();
        for (int n = 0; n < programs.size(); n++) {
            cases.add(new DiffCase("g0 enumeration " + n + " " + programs.get(n), programs.get(n).source(className(n)),
                    className(n), "m", INPUTS.getFirst()));
        }
        runner.precompile(cases);
        var coverage = new Coverage();
        var failures = new ArrayList<String>();
        for (int n = 0; n < programs.size(); n++) {
            var program = programs.get(n);
            for (int i = 0; i < INPUTS.size(); i++) {
                var base = cases.get(n);
                var diffCase = new DiffCase(base.name() + " input " + i, base.source(), base.className(), "m", INPUTS.get(i));
                var report = runner.run(diffCase);
                Map<DiffConfig, Verdict.Kind> verdicts = new LinkedHashMap<>();
                boolean unexplained = false;
                for (var entry : report.results().entrySet()) {
                    var result = entry.getValue();
                    verdicts.put(entry.getKey(), result.verdict().kind());
                    var config = entry.getKey();
                    if (result.verdict().kind() != Verdict.Kind.AGREE && G0PropertyTest.KNOWN.explain(diffCase, config,
                            program.features(), result, factor -> runner.rerun(diffCase, config, factor)).isEmpty()) {
                        unexplained = true;
                    }
                }
                if (unexplained) {
                    var provenance = new LinkedHashMap<String, String>();
                    provenance.put("by", "G0ExhaustiveTest");
                    provenance.put("generator", G0Enumeration.VERSION);
                    provenance.put("program", program.toString());
                    provenance.put("compiler", System.getProperty("difftest.compiler", "unknown"));
                    RegressionStore.write(Path.of("build/difftest/exhaustive/case-" + n + "-" + i + ".diffcase"), diffCase,
                            RegressionStore.Expectation.AGREE, null, provenance, report);
                    failures.add(report.describe() + "\n" + diffCase.source());
                } else {
                    coverage.record(new HashSet<>(program.features().stream().map(Enum::name).toList()), verdicts);
                }
            }
        }
        System.out.println("G0 enumeration coverage\n" + coverage.report());
        if (!failures.isEmpty()) {
            fail(failures.size() + " of " + programs.size() * INPUTS.size() + " runs disagree:\n\n" + String.join("\n\n", failures));
        }
    }
}
