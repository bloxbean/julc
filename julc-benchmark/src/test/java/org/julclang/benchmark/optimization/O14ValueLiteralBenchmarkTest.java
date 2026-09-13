package org.julclang.benchmark.optimization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-045 (O14): the safe profile folds a literal requirement built from the typed native
 * Value producers into one constant, keeps the runtime containment check and its failure
 * behaviour, and folds an all-literal method to a single constant.
 */
class O14ValueLiteralBenchmarkTest {

    private static final String RULE = "pv11.o14.value-literal-fold";

    @Test
    void literalRequirementFoldsAndKeepsTheRuntimeCheck() {
        var comparison = OptimizationEvidenceMain.o14LiteralRequirementComparison();
        comparison.verifyEquivalent();
        assertTrue(comparison.candidateArtifact().appliedRules().contains(RULE));
        assertFalse(comparison.baselineArtifact().appliedRules().contains(RULE));
        assertTrue(comparison.candidateArtifact().flatBytes() < comparison.baselineArtifact().flatBytes());
        for (var after : comparison.candidateEvaluations()) {
            var before = before(comparison, after);
            assertEquals(before.outcome(), after.outcome(), after.caseId());
            assertEquals(before.failure(), after.failure(), after.caseId());
            assertEquals(before.traces(), after.traces(), after.caseId());
            // The literal side no longer runs two inserts and a union on any path.
            assertTrue(after.budget().cpuSteps() < before.budget().cpuSteps(), after.caseId());
            assertTrue(after.budget().memoryUnits() < before.budget().memoryUnits(), after.caseId());
        }
    }

    @Test
    void allLiteralMethodFoldsToOneConstant() {
        var comparison = OptimizationEvidenceMain.o14LiteralOnlyComparison();
        comparison.verifyEquivalent();
        assertTrue(comparison.candidateArtifact().appliedRules().contains(RULE));
        assertTrue(comparison.candidateArtifact().flatBytes() < 10, "a bare integer constant");
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
