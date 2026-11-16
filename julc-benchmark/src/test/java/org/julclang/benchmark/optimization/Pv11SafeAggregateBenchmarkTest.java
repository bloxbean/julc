package org.julclang.benchmark.optimization;

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
        // The PV11_SAFE rules' size and budget against BASELINE are measured with ADR-076 off, and the real
        // candidate held to ADR-076's bound against that one.
        var unshared = OptimizationEvidenceMain.aggregatePv11SafeComparison(Adr076Isolation.WITHOUT_ADR076);
        Adr076Isolation.assertWithinAdr076Bound(comparison, unshared);
        assertTrue(unshared.candidateArtifact().flatBytes()
                < comparison.baselineArtifact().flatBytes());
        for (int i = 0; i < comparison.baselineEvaluations().size(); i++) {
            assertTrue(unshared.candidateEvaluations().get(i).budget().cpuSteps()
                    <= comparison.baselineEvaluations().get(i).budget().cpuSteps());
            assertTrue(unshared.candidateEvaluations().get(i).budget().memoryUnits()
                    <= comparison.baselineEvaluations().get(i).budget().memoryUnits());
        }
    }
}
