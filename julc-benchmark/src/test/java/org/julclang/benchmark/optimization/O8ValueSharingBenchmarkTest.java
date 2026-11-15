package org.julclang.benchmark.optimization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR-042 (O8): the safe profile shares a leading repeated conversion and matches manual sharing. */
class O8ValueSharingBenchmarkTest {

    private static final String RULE = "pv11.o8.value-sharing";

    @Test
    void safeProfileSharesTheConversionAndMatchesManualSharingByteForByte() {
        var automatic = OptimizationEvidenceMain.o8ValueSharingComparison();
        var manual = OptimizationEvidenceMain.o8ManualSharingControlComparison();

        automatic.verifyEquivalent();
        manual.verifyEquivalent();
        assertTrue(automatic.candidateArtifact().appliedRules().contains(RULE));
        assertFalse(automatic.baselineArtifact().appliedRules().contains(RULE));
        assertFalse(manual.candidateArtifact().appliedRules().contains(RULE));

        // The rewritten "repeated" program is the "shared" program.
        assertEquals(manual.candidateArtifact().scriptHash(), automatic.candidateArtifact().scriptHash());
        assertEquals(manual.candidateArtifact().flatBytes(), automatic.candidateArtifact().flatBytes());
        // O8's size and budget against BASELINE are measured with ADR-076 off, and the real candidate held to
        // ADR-076's bound against that one.
        var unshared = OptimizationEvidenceMain.o8ValueSharingComparison(Adr076Isolation.WITHOUT_ADR076);
        Adr076Isolation.assertWithinAdr076Bound(automatic, unshared);
        assertTrue(unshared.candidateArtifact().flatBytes() < automatic.baselineArtifact().flatBytes());

        for (var after : automatic.candidateEvaluations()) {
            var before = automatic.baselineEvaluations().stream()
                    .filter(b -> b.backend().equals(after.backend()) && b.caseId().equals(after.caseId()))
                    .findFirst().orElseThrow();
            var plain = unshared.candidateEvaluations().stream()
                    .filter(p -> p.backend().equals(after.backend()) && p.caseId().equals(after.caseId()))
                    .findFirst().orElseThrow();
            assertEquals(before.outcome(), after.outcome());
            assertEquals(before.failure(), after.failure());
            assertEquals(before.traces(), after.traces());
            assertTrue(plain.budget().cpuSteps() < before.budget().cpuSteps(), after.caseId());
            assertTrue(plain.budget().memoryUnits() <= before.budget().memoryUnits(), after.caseId());
        }
    }
}
