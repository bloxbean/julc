package org.julclang.benchmark.optimization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR-043 (O9): the costed profile promotes repeatedly indexed lists and matches the manual array form. */
class O9ListIndexPromotionBenchmarkTest {

    private static final String RULE = "pv11.o9.list-to-array";

    @Test
    void costedProfilePromotesTheRequestLoopAndSavesFromTwoRequestsUp() {
        var loop = OptimizationEvidenceMain.o9RequestLoopComparison();
        loop.verifyEquivalent();
        assertTrue(loop.candidateArtifact().appliedRules().contains(RULE));
        assertFalse(loop.baselineArtifact().appliedRules().contains(RULE));
        assertTrue(loop.candidateArtifact().flatBytes() < loop.baselineArtifact().flatBytes());

        for (var after : loop.candidateEvaluations()) {
            var before = loop.baselineEvaluations().stream()
                    .filter(b -> b.backend().equals(after.backend()) && b.caseId().equals(after.caseId()))
                    .findFirst().orElseThrow();
            assertEquals(before.outcome(), after.outcome());
            assertEquals(before.resultTerm(), after.resultTerm());
            assertEquals(before.traces(), after.traces());
            int requests = Integer.parseInt(after.caseId().substring("requests-".length()));
            if (requests >= 2) {
                assertTrue(after.budget().cpuSteps() < before.budget().cpuSteps(), after.caseId());
            }
            System.out.println("O9_LOOP " + after.backend() + " " + after.caseId() + " "
                    + before.budget().cpuSteps() + " -> " + after.budget().cpuSteps());
        }
    }

    @Test
    void costedOutputMatchesTheManualArrayFormByteForByte() {
        var automatic = OptimizationEvidenceMain.o9TwoSitesComparison();
        var manual = OptimizationEvidenceMain.o9ManualArrayControlComparison();
        automatic.verifyEquivalent();
        manual.verifyEquivalent();
        assertTrue(automatic.candidateArtifact().appliedRules().contains(RULE));
        assertFalse(automatic.baselineArtifact().appliedRules().contains(RULE));
        assertFalse(manual.candidateArtifact().appliedRules().contains(RULE));
        assertFalse(manual.baselineArtifact().appliedRules().contains(RULE));

        // The rewritten two-site program is the manual array program; the manual program is
        // unchanged between the safe and costed profiles.
        assertEquals(manual.candidateArtifact().scriptHash(), automatic.candidateArtifact().scriptHash());
        assertEquals(manual.candidateArtifact().flatBytes(), automatic.candidateArtifact().flatBytes());
        assertEquals(manual.baselineArtifact().scriptHash(), manual.candidateArtifact().scriptHash());
        assertTrue(automatic.candidateArtifact().flatBytes() < automatic.baselineArtifact().flatBytes());

        for (var after : automatic.candidateEvaluations()) {
            var before = automatic.baselineEvaluations().stream()
                    .filter(b -> b.backend().equals(after.backend()) && b.caseId().equals(after.caseId()))
                    .findFirst().orElseThrow();
            assertEquals(before.outcome(), after.outcome());
            assertEquals(before.resultTerm(), after.resultTerm());
            assertTrue(after.budget().cpuSteps() < before.budget().cpuSteps(), after.caseId());
        }
    }
}
