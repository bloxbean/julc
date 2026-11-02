package org.julclang.difftest;

import org.julclang.compiler.CompilerFrontend;
import org.julclang.difftest.gen.G0Enumeration;
import org.julclang.difftest.gen.KnownDivergences;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * M4's exit on {@code frontend=JAVAC}: every saved case with a loop, T21's and T23's among them, and G0's bounded grammar, which
 * is its loops' shapes, each on the reference's expectation. A saved case that {@code JAVAC} cannot answer the same way names
 * why, in {@link #ANSWERED_OTHERWISE}.
 */
class Adr068LoopCasesOnJavacTest {

    private static final DiffRunner RUNNER = new DiffRunner(DiffConfig.allLevels(CompilerFrontend.JAVAC), 10_000);

    /** A saved case with a loop whose verdict here is not the reference's, with the reason. */
    private static final Map<String, String> ANSWERED_OTHERWISE = Map.of(
            "reject-g1-291-local-after-loop.diffcase", "A switch as the argument of equals stops at its type, Object, in JavacStopsTest, before its loop");

    private static boolean holdsALoop(RegressionStore.SavedCase saved) {
        var source = saved.diffCase().source();
        return source.contains("while (") || source.contains("for (");
    }

    @TestFactory
    Stream<DynamicTest> everySavedCaseWithALoop() {
        var cases = RegressionStore.load(Path.of("src/test/resources/regressions")).stream().filter(Adr068LoopCasesOnJavacTest::holdsALoop)
                .filter(saved -> !ANSWERED_OTHERWISE.containsKey(saved.file().getFileName().toString())).toList();
        assertTrue(cases.size() >= 50, "too few saved loops: " + cases.size());
        return cases.stream().map(saved -> DynamicTest.dynamicTest(saved.file().getFileName().toString(), () -> {
            var report = RUNNER.run(saved.diffCase());
            var problems = new ArrayList<String>();
            report.results().forEach((config, result) -> {
                String problem = saved.expectationFor(config.level()).check(result);
                if (problem != null) problems.add(config + ": " + problem);
            });
            if (!problems.isEmpty()) fail(String.join("\n", problems) + "\n\n" + report.describe());
            assertEquals(4, report.results().size());
        }));
    }

    @Test
    void everyProgramOfG0() {
        var programs = G0Enumeration.all();
        var cases = new ArrayList<DiffCase>();
        for (int n = 0; n < programs.size(); n++) {
            cases.add(new DiffCase("g0 enumeration " + n + " " + programs.get(n), programs.get(n).source("E" + n), "E" + n, "m",
                    G0ExhaustiveTest.INPUTS.getFirst()));
        }
        RUNNER.precompile(cases);
        var failures = new ArrayList<String>();
        for (int n = 0; n < programs.size(); n++) {
            var program = programs.get(n);
            for (var input : G0ExhaustiveTest.INPUTS) {
                var base = cases.get(n);
                var diffCase = new DiffCase(base.name(), base.source(), base.className(), "m", input);
                var report = RUNNER.run(diffCase);
                for (var entry : new LinkedHashMap<>(report.results()).entrySet()) {
                    var result = entry.getValue();
                    if (result.verdict().kind() != Verdict.Kind.AGREE && CampaignVerifier.KNOWN.explain(diffCase, entry.getKey(),
                            KnownDivergences.names(program.features()), result, factor -> RUNNER.rerun(diffCase, entry.getKey(), factor)).isEmpty()) {
                        failures.add(report.describe() + "\n" + diffCase.source());
                        break;
                    }
                }
            }
        }
        assertEquals(List.of(), failures);
    }
}
