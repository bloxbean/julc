package org.julclang.benchmark.optimization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-046 (O10): the safe profile embeds a literal table as one array constant (the runtime
 * access and its failures unchanged) and folds an all-literal access to a single constant.
 */
class O10ArrayLiteralBenchmarkTest {

    private static final String RULE = "pv11.o10.array-literal-fold";

    @Test
    void literalTableIsEmbeddedAndTheRuntimeAccessKeepsItsFailures() {
        var comparison = OptimizationEvidenceMain.o10ArrayTableComparison();
        comparison.verifyEquivalent();
        assertTrue(comparison.candidateArtifact().appliedRules().contains(RULE));
        assertFalse(comparison.baselineArtifact().appliedRules().contains(RULE));
        assertTrue(comparison.candidateArtifact().flatBytes() < comparison.baselineArtifact().flatBytes());
        for (var after : comparison.candidateEvaluations()) {
            var before = before(comparison, after);
            assertEquals(before.outcome(), after.outcome(), after.caseId());
            assertEquals(before.failure(), after.failure(), after.caseId());
            assertEquals(before.traces(), after.traces(), after.caseId());
            // The list construction and its conversion no longer run on any path.
            assertTrue(after.budget().cpuSteps() < before.budget().cpuSteps(), after.caseId());
            assertTrue(after.budget().memoryUnits() < before.budget().memoryUnits(), after.caseId());
        }
    }

    @Test
    void allLiteralAccessFoldsToOneConstant() {
        var comparison = OptimizationEvidenceMain.o10ArrayLiteralOnlyComparison();
        comparison.verifyEquivalent();
        assertTrue(comparison.candidateArtifact().appliedRules().contains(RULE));
        assertTrue(comparison.candidateArtifact().flatBytes() < 12, "a bare integer constant");
        for (var after : comparison.candidateEvaluations()) {
            var before = before(comparison, after);
            assertEquals(OptimizationBenchmarkRunner.Outcome.SUCCESS, after.outcome());
            assertTrue(after.budget().cpuSteps() < before.budget().cpuSteps());
        }
    }

    private static OptimizationBenchmarkRunner.EvaluationMeasurement before(
            OptimizationBenchmarkRunner.Comparison comparison, OptimizationBenchmarkRunner.EvaluationMeasurement after) {
        return comparison.baselineEvaluations().stream()
                .filter(b -> b.backend().equals(after.backend()) && b.caseId().equals(after.caseId()))
                .findFirst().orElseThrow();
    }
}
