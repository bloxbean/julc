package org.julclang.benchmark.optimization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-044 (O15): the safe profile shares a leading repeated record projection, matches the
 * manual binding byte for byte, and on the ledger shape shares the fields prefix across
 * branches at the documented cost of one binding on a path that reaches no later occurrence.
 */
class O15ProjectionSharingBenchmarkTest {

    private static final String RULE = "pv11.o15.projection-sharing";
    /** One lambda, one application and one variable lookup under cardano-node-11.0.1 PV11 costs. */
    private static final long BINDING_CPU = 48_000;
    private static final long BINDING_MEM = 300;

    @Test
    void safeProfileSharesTheProjectionAndMatchesManualBindingByteForByte() {
        var automatic = OptimizationEvidenceMain.o15ProjectionSharingComparison();
        var manual = OptimizationEvidenceMain.o15ManualBindingControlComparison();

        automatic.verifyEquivalent();
        manual.verifyEquivalent();
        assertTrue(automatic.candidateArtifact().appliedRules().contains(RULE));
        assertFalse(automatic.baselineArtifact().appliedRules().contains(RULE));
        assertFalse(manual.candidateArtifact().appliedRules().contains(RULE));

        // The rewritten "repeated" program is the "manual" program.
        assertEquals(manual.candidateArtifact().scriptHash(), automatic.candidateArtifact().scriptHash());
        assertEquals(manual.candidateArtifact().flatBytes(), automatic.candidateArtifact().flatBytes());
        assertTrue(automatic.candidateArtifact().flatBytes() < automatic.baselineArtifact().flatBytes());

        for (var after : automatic.candidateEvaluations()) {
            var before = before(automatic, after);
            assertEquals(before.outcome(), after.outcome());
            assertEquals(before.failure(), after.failure());
            assertEquals(before.traces(), after.traces());
            if (after.outcome() == OptimizationBenchmarkRunner.Outcome.SUCCESS) {
                // Every successful path projects the field at least twice more after the binding.
                assertTrue(after.budget().cpuSteps() < before.budget().cpuSteps(), after.caseId());
                assertTrue(after.budget().memoryUnits() < before.budget().memoryUnits(), after.caseId());
            }
        }
    }

    @Test
    void ledgerShapeSharesTheChainAndThePrefixWithinTheBindingBound() {
        var ledger = OptimizationEvidenceMain.o15LedgerProjectionComparison();
        ledger.verifyEquivalent();
        assertTrue(ledger.candidateArtifact().appliedRules().contains(RULE));
        assertTrue(ledger.candidateArtifact().flatBytes() < ledger.baselineArtifact().flatBytes());
        for (var after : ledger.candidateEvaluations()) {
            var before = before(ledger, after);
            assertEquals(before.outcome(), after.outcome());
            assertEquals(before.failure(), after.failure());
            if (after.caseId().equals("two-outputs")) {
                assertTrue(after.budget().cpuSteps() < before.budget().cpuSteps(), after.caseId());
            } else if (after.caseId().equals("no-outputs")) {
                // The empty branch reaches neither later occurrence: it pays the two bindings.
                assertTrue(after.budget().cpuSteps() <= before.budget().cpuSteps() + 2 * BINDING_CPU, after.caseId());
                assertTrue(after.budget().memoryUnits() <= before.budget().memoryUnits() + 2 * BINDING_MEM, after.caseId());
            }
        }
    }

    private static OptimizationBenchmarkRunner.EvaluationMeasurement before(
            OptimizationBenchmarkRunner.Comparison comparison, OptimizationBenchmarkRunner.EvaluationMeasurement after) {
        return comparison.baselineEvaluations().stream()
                .filter(b -> b.backend().equals(after.backend()) && b.caseId().equals(after.caseId()))
                .findFirst().orElseThrow();
    }
}
