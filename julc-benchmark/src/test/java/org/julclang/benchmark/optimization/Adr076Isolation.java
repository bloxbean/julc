package org.julclang.benchmark.optimization;

import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.codegen.SharedBindings;
import org.julclang.compiler.uplc.UplcOptimizer;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-076 in the benchmarks of other rules. Its two rules change a PV11 candidate's bytes and budget, so a benchmark that
 * holds a rule to its own size or budget guarantee against BASELINE measures the candidate with both ADR-076 rules off
 * ({@link #WITHOUT_ADR076}), and holds the real candidate to ADR-076's own bound against that one
 * ({@link #assertWithinAdr076Bound}).
 */
final class Adr076Isolation {

    /** Both ADR-076 rules off: shared handler bindings (D1) and shared closed values (D2). */
    static final Consumer<CompilerOptions> WITHOUT_ADR076 = options -> {
        options.disableOptimizationRule(SharedBindings.RULE);
        options.disableOptimizationRule(UplcOptimizer.SHARED_CLOSED_VALUES_RULE);
    };

    /** One machine step under the PV11 cost model. */
    private static final long STEP_CPU = 16_000;
    private static final long STEP_MEM = 100;

    /** The steps of the costliest binding D2 adds: an application, a lambda, two forces and a builtin. */
    private static final int STEPS_PER_SHARED_VALUE = 5;

    private Adr076Isolation() {
    }

    /**
     * {@code real}'s candidate is no larger than {@code unshared}'s, and each of its runs costs at most the same run of
     * {@code unshared}'s candidate plus five machine steps per value D2 bound at the root (ADR-076 I2).
     */
    static void assertWithinAdr076Bound(OptimizationBenchmarkRunner.Comparison real,
                                        OptimizationBenchmarkRunner.Comparison unshared) {
        assertEquals(real.fixtureId(), unshared.fixtureId());
        assertEquals(0, unshared.candidateArtifact().sharedRootValues(), real.fixtureId());
        assertTrue(real.candidateArtifact().flatBytes() <= unshared.candidateArtifact().flatBytes(), real.fixtureId());
        long steps = (long) STEPS_PER_SHARED_VALUE * real.candidateArtifact().sharedRootValues();
        assertEquals(unshared.candidateEvaluations().size(), real.candidateEvaluations().size(), real.fixtureId());
        for (int i = 0; i < real.candidateEvaluations().size(); i++) {
            var on = real.candidateEvaluations().get(i);
            var off = unshared.candidateEvaluations().get(i);
            String label = real.fixtureId() + "/" + on.backend() + "/" + on.caseId();
            assertEquals(off.caseId(), on.caseId(), label);
            assertTrue(on.budget().cpuSteps() <= off.budget().cpuSteps() + steps * STEP_CPU, label + ": ADR-076 CPU bound");
            assertTrue(on.budget().memoryUnits() <= off.budget().memoryUnits() + steps * STEP_MEM, label + ": ADR-076 memory bound");
        }
    }
}
