package com.bloxbean.cardano.julc.benchmark.optimization;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseBuiltinExperimentTest {

    @Test
    void listHeadExperimentIsEquivalentButDoesNotAuthorizeTraversalRewrite() {
        assertSmallerAndEquivalent(OptimizationEvidenceMain.o3CaseListExperiment());
    }

    @Test
    void pairProjectionExperimentIsEquivalentButRequiresTypedUseAnalysis() {
        assertSmallerAndEquivalent(OptimizationEvidenceMain.o4CasePairExperiment());
    }

    @Test
    void denseIntegerExperimentPinsNegativeAndOutOfRangeFailure() {
        var comparison = OptimizationEvidenceMain.o5CaseIntegerExperiment();
        assertTrue(comparison.candidateArtifact().flatBytes()
                < comparison.baselineArtifact().flatBytes());
        assertEquals(4, comparison.candidateEvaluations().stream()
                .filter(result -> result.outcome()
                        == OptimizationBenchmarkRunner.Outcome.FAILURE)
                .count());
        for (int i = 0; i < comparison.baselineEvaluations().size(); i++) {
            var baseline = comparison.baselineEvaluations().get(i);
            var candidate = comparison.candidateEvaluations().get(i);
            if (baseline.outcome() == OptimizationBenchmarkRunner.Outcome.SUCCESS) {
                assertEquals(baseline.resultTerm(), candidate.resultTerm());
                assertEquals(baseline.traces(), candidate.traces());
            } else {
                assertTrue(candidate.failure().startsWith("Case: tag "));
                assertEquals("Error term encountered", baseline.failure());
            }
        }
    }

    @Test
    void unitExperimentPreservesTraceAndIsEquivalentForTypedUnit() {
        var comparison = OptimizationEvidenceMain.o6CaseUnitExperiment();
        assertSmallerAndEquivalent(comparison);
        assertTrue(comparison.candidateEvaluations().stream()
                .allMatch(result -> result.traces().equals(List.of("unit"))));
    }

    private static void assertSmallerAndEquivalent(
            OptimizationBenchmarkRunner.Comparison comparison) {
        comparison.verifyEquivalent();
        assertTrue(comparison.candidateArtifact().flatBytes()
                < comparison.baselineArtifact().flatBytes());
        assertTrue(comparison.candidateArtifact().termMetrics().nodes()
                < comparison.baselineArtifact().termMetrics().nodes());
    }
}
