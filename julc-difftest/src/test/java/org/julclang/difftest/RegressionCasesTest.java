package org.julclang.difftest;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Replays every saved case under {@code src/test/resources/regressions} at every optimization
 * level, and checks each verdict against the case's expectation (ADR-063 D8, M1). A known
 * divergence must keep diverging until its catalogue entry is resolved; then its expectation is
 * changed, never deleted.
 */
class RegressionCasesTest {

    private static final Path CASES = Path.of("src/test/resources/regressions");
    private static final DiffRunner RUNNER = DiffRunner.standard();

    @TestFactory
    Stream<DynamicTest> savedCases() {
        var cases = RegressionStore.load(CASES);
        assertTrue(cases.size() >= 15, "expected the M1 hand-written cases, found " + cases.size());
        return cases.stream().map(saved -> DynamicTest.dynamicTest(saved.file().getFileName().toString(), () -> {
            var report = RUNNER.run(saved.diffCase());
            var problems = new ArrayList<String>();
            report.results().forEach((config, result) -> {
                String problem = saved.expectationFor(config.level()).check(result);
                if (problem != null) problems.add(config + ": " + problem);
            });
            if (!problems.isEmpty()) {
                fail(String.join("\n", problems) + "\n\n" + report.describe());
            }
        }));
    }
}
