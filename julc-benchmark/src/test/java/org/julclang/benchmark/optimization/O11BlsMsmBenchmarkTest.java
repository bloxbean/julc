package org.julclang.benchmark.optimization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-047 (O11): the explicit G1 multi-scalar multiplication against the manual
 * {@code g1ScalarMul}/{@code g1Add} chain for one to eight points on the pinned profile. The
 * two programs agree on every backend; the builtin is smaller from three points and cheaper in
 * CPU from seven, so the crossover is a documented fact of the profile, not a compiler rule.
 */
class O11BlsMsmBenchmarkTest {

    @Test
    void explicitMsmAgreesWithTheChainAndOvertakesItAtSevenPoints() {
        for (int n = 1; n <= 8; n++) {
            var comparison = OptimizationEvidenceMain.o11MsmCrossoverComparison(n);
            comparison.verifyEquivalent();
            var chain = comparison.baselineArtifact();
            var msm = comparison.candidateArtifact();
            assertEquals(n >= 3, msm.flatBytes() < chain.flatBytes(), "bytes at n=" + n + ": " + msm.flatBytes() + " vs " + chain.flatBytes());
            for (var after : comparison.candidateEvaluations()) {
                var before = comparison.baselineEvaluations().stream()
                        .filter(b -> b.backend().equals(after.backend()) && b.caseId().equals(after.caseId()))
                        .findFirst().orElseThrow();
                assertEquals(OptimizationBenchmarkRunner.Outcome.SUCCESS, after.outcome(), "n=" + n);
                assertEquals(before.resultTerm(), after.resultTerm(), "n=" + n + " " + after.backend());
                assertEquals(n >= 7, after.budget().cpuSteps() < before.budget().cpuSteps(),
                        "cpu at n=" + n + " " + after.backend() + ": " + after.budget() + " vs " + before.budget());
                assertEquals(n >= 3, after.budget().memoryUnits() < before.budget().memoryUnits(),
                        "mem at n=" + n + " " + after.backend() + ": " + after.budget() + " vs " + before.budget());
                if (after.backend().equals(OptimizationBenchmarkRunner.Backend.javaVm().id())) {
                    System.out.println("BLS_MSM_CROSSOVER n=" + n + " chain=" + before.budget() + "/" + chain.flatBytes() + "B"
                            + " msm=" + after.budget() + "/" + msm.flatBytes() + "B");
                }
            }
        }
    }
}
