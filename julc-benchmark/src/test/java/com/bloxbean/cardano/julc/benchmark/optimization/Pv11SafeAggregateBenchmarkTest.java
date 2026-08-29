package com.bloxbean.cardano.julc.benchmark.optimization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Pv11SafeAggregateBenchmarkTest {

    @Test
    void representativeFixtureIsEquivalentAndSmallerOnBothBackends() {
        var comparison = OptimizationEvidenceMain.aggregatePv11SafeComparison();

        comparison.verifyEquivalent();
        assertTrue(comparison.candidateArtifact().appliedRules()
                .contains("pv11.o1.drop-list"));
        assertTrue(comparison.candidateArtifact().appliedRules()
                .contains("pv11.o2.case-bool"));
        assertTrue(comparison.candidateArtifact().appliedRules()
                .contains("pv11.o13.exp-mod-literal-fold"));
        assertTrue(comparison.candidateArtifact().flatBytes()
                < comparison.baselineArtifact().flatBytes());
        for (int i = 0; i < comparison.baselineEvaluations().size(); i++) {
            assertTrue(comparison.candidateEvaluations().get(i).budget().cpuSteps()
                    <= comparison.baselineEvaluations().get(i).budget().cpuSteps());
            assertTrue(comparison.candidateEvaluations().get(i).budget().memoryUnits()
                    <= comparison.baselineEvaluations().get(i).budget().memoryUnits());
        }
    }
}
