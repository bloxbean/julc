package org.julclang.difftest;

import org.julclang.compiler.CompilerFrontend;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Saved cases whose answer on {@code frontend=JAVAC} is not the one {@code RegressionCasesTest} replays, on the default
 * frontend. D13 asks a semantic fix and an accepted shape for a {@code .diffcase} on which javac agrees with the JVM: those are
 * the ones named {@code -068-}, and T33's pins (#284, #286), whose values could not run before records. A {@code diverge-068-} case
 * diverges on the default frontend alone, and {@code M5} is what closes it. The default frontend's
 * rejection of each stays in the case's {@code expect}.
 */
class Adr068SavedCasesOnJavacTest {

    private static final DiffRunner RUNNER = new DiffRunner(DiffConfig.allLevels(CompilerFrontend.JAVAC), 10_000);

    private static final Set<String> T33_PINS = Set.of("reject-packageless-pubkeyhash-record.diffcase", "reject-packageless-rational-record.diffcase",
            "reject-packageless-rational-record-wildcard.diffcase", "reject-top-level-user-value-on-local.diffcase",
            "agree-nested-rational.diffcase");

    /** A read of a member on {@code new} itself stays {@code JULC0055}: javac knowing the type does not widen the subset (I2). */
    private static final Set<String> READ_ON_NEW = Set.of("reject-top-level-user-value-on-constructor.diffcase",
            "reject-user-value-accessor-on-constructor.diffcase");

    private static List<RegressionStore.SavedCase> saved() {
        return RegressionStore.load(Path.of("src/test/resources/regressions"));
    }

    private static String name(RegressionStore.SavedCase saved) {
        return saved.file().getFileName().toString();
    }

    @TestFactory
    Stream<DynamicTest> theyAgreeWithTheJvm() {
        var cases = saved().stream().filter(saved -> name(saved).contains("-068-") || T33_PINS.contains(name(saved))).toList();
        assertEquals(25 + T33_PINS.size(), cases.size());
        return cases.stream().map(saved -> DynamicTest.dynamicTest(name(saved), () -> {
            var report = RUNNER.run(saved.diffCase());
            assertTrue(report.all(Verdict.Kind.AGREE), report::describe);
            assertEquals(4, report.results().size());
        }));
    }

    @TestFactory
    Stream<DynamicTest> aReadOnNewStaysRejected() {
        var cases = saved().stream().filter(saved -> READ_ON_NEW.contains(name(saved))).toList();
        assertEquals(READ_ON_NEW.size(), cases.size());
        return cases.stream().map(saved -> DynamicTest.dynamicTest(name(saved), () -> RUNNER.run(saved.diffCase()).results().forEach((config, result) -> {
            assertEquals(Verdict.Kind.REJECTED, result.verdict().kind(), result::toString);
            assertTrue(result.uplc().toString().contains("JULC0055"), result::toString);
        })));
    }
}
